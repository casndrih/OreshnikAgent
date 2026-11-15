package com.example.oreshik.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.oreshik.Model
import com.example.oreshik.R
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** RAM and CPU-aware defaults for local GGUF inference on this device. */
object DeviceModelAdvisor {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    data class Profile(
        val deviceLabel: String,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val availableStorageBytes: Long,
        val cpuThreads: Int,
        val contextSize: Int,
        val recommendedModelBytes: Long,
        val recommendation: String,
        val supportsRuntime: Boolean,
    ) {
        // Keep prompts and replies short on budget phones: llama.cpp's JNI API
        // returns only after the complete native generation has finished.
        val maxCompletionTokens: Int
            get() = when {
                totalRamBytes < 4L * 1024L * 1024L * 1024L -> 160
                totalRamBytes < 6L * 1024L * 1024L * 1024L -> 256
                else -> 384
            }
        val maxPromptChars: Int
            get() = when {
                totalRamBytes < 4L * 1024L * 1024L * 1024L -> 1200
                totalRamBytes < 6L * 1024L * 1024L * 1024L -> 2800
                else -> 5000
            }
        val maxSystemContextChars: Int
            get() = when {
                totalRamBytes < 4L * 1024L * 1024L * 1024L -> 900
                totalRamBytes < 6L * 1024L * 1024L * 1024L -> 1800
                else -> 3200
            }
        val inferenceMemoryReserveBytes: Long
            get() = 384L * 1024L * 1024L

        val runtimeConfig: LlamaConfig
            get() = LlamaConfig(
                contextSize = contextSize,
                threads = cpuThreads,
                gpuLayers = 0,
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
            )
    }

    fun detect(context: Context): Profile {
        val app = context.applicationContext
        val memory = ActivityManager.MemoryInfo().also {
            app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val totalRam = memory.totalMem.coerceAtLeast(1L)
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val (maxModel, contextSize, modelClass) = when {
            totalRam < 4L * GIB -> Triple(650L * MIB, 1024, "about 0.5B–1B")
            totalRam < 6L * GIB -> Triple(1100L * MIB, 2048, "about 1B–2B")
            totalRam < 8L * GIB -> Triple(1800L * MIB, 3072, "about 2B–3B")
            else -> Triple(4L * GIB, 4096, "up to about 7B")
        }
        val modelsDir = OfflineModelStore.directory(app)
        val availableStorage = runCatching { StatFs(modelsDir.absolutePath).availableBytes }.getOrDefault(0L)
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.ROOT) }
        val device = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }
        return Profile(
            deviceLabel = device,
            totalRamBytes = totalRam,
            availableRamBytes = memory.availMem.coerceAtLeast(0L),
            availableStorageBytes = availableStorage,
            cpuThreads = cpuCount.coerceAtMost(4),
            contextSize = contextSize,
            recommendedModelBytes = maxModel,
            recommendation = "$modelClass GGUF, Q4_K_M quantization, up to ${OfflineModelStore.formatBytes(maxModel)}",
            supportsRuntime = "arm64-v8a" in abis,
        )
    }
}

/** App-owned model-file location and listing. The SAF import is copied here before loading. */
object OfflineModelStore {
    fun directory(context: Context): File =
        (context.applicationContext.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
            .apply { mkdirs() }

    fun models(context: Context): List<File> = directory(context)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun selectedFile(context: Context): File? {
        val configured = AgentConfig.offlineModelPath.takeIf { it.isNotBlank() }?.let(::File)
        if (configured?.isFile == true && configured.extension.equals("gguf", ignoreCase = true)) return configured
        val byName = AgentConfig.offlineModelName.takeIf { it.isNotBlank() }
            ?.let { name -> models(context).firstOrNull { it.name.equals(name, ignoreCase = true) } }
        return byName
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
        else -> "$bytes B"
    }
}

/** Serializes local native inference and keeps one model loaded for responsive follow-up turns. */
object OfflineModelRuntime {
    private val mutex = Mutex()
    @Volatile private var loadedPath: String? = null
    private var loadedConfig: LlamaConfig? = null
    @Volatile private var loadedModel: LlamaModel? = null
    private val inferenceActive = AtomicBoolean(false)

    fun isLoaded(path: String): Boolean =
        (loadedPath == File(path).absolutePath) && (loadedModel?.isLoaded == true)

    fun loadedModelPath(): String? = loadedPath?.takeIf { loadedModel?.isLoaded == true }

    /** True while llama.cpp is inside a non-interruptible native inference call. */
    fun isInferenceActive(): Boolean = inferenceActive.get()

    suspend fun load(path: String, profile: DeviceModelAdvisor.Profile) = mutex.withLock {
        ensureLoaded(path, profile)
    }

    /** Releases the native model so its memory can be reclaimed without deleting its GGUF file. */
    suspend fun unload(path: String? = null): Boolean = mutex.withLock {
        val model = loadedModel ?: return@withLock false
        if (path != null && loadedPath != File(path).absolutePath) return@withLock false
        val released = runCatching { Llama.releaseModel(model) }.isSuccess
        loadedModel = null
        loadedPath = null
        loadedConfig = null
        released
    }

    /** Excludes local inference while a memory-intensive native model operation runs. */
    suspend fun <T> withExclusiveMaintenance(block: () -> T): T = mutex.withLock {
        loadedModel?.let { model -> runCatching { Llama.releaseModel(model) } }
        loadedModel = null
        loadedPath = null
        loadedConfig = null
        block()
    }

    suspend fun complete(
        path: String,
        profile: DeviceModelAdvisor.Profile,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
    ) = mutex.withLock {
        val model = ensureLoaded(path, profile)
        inferenceActive.set(true)
        try {
            Llama.complete(
                model = model,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens.coerceIn(64, 512),
            )
        } finally {
            inferenceActive.set(false)
        }
    }

    private suspend fun ensureLoaded(path: String, profile: DeviceModelAdvisor.Profile): LlamaModel {
        check(profile.supportsRuntime) { "On-device GGUF inference currently requires an arm64-v8a device." }
        val file = File(path)
        check(file.isFile && file.canRead()) { "The selected GGUF file is missing or unreadable. Import it again." }
        val config = profile.runtimeConfig
        if ((loadedPath == file.absolutePath) && (loadedConfig == config) && (loadedModel?.isLoaded == true)) {
            return loadedModel!!
        }

        val usableForWeights = (profile.availableRamBytes - profile.inferenceMemoryReserveBytes).coerceAtLeast(0L)
        check(file.length() <= usableForWeights) {
            "${file.name} is ${OfflineModelStore.formatBytes(file.length())}, but Android currently has only " +
                "${OfflineModelStore.formatBytes(profile.availableRamBytes)} RAM available. Close other apps or use a smaller Q4_K_M/Q3_K_M model, then try again."
        }

        loadedModel?.let { old -> runCatching { Llama.releaseModel(old) } }
        loadedModel = null
        loadedPath = null
        loadedConfig = null
        AppLogger.i("OfflineModel", "loading ${file.name} (${OfflineModelStore.formatBytes(file.length())}), RAM=${OfflineModelStore.formatBytes(profile.totalRamBytes)}, ctx=${config.contextSize}, threads=${config.threads}, CPU backend")
        val next = Llama.loadModel(file.absolutePath, config)
        loadedModel = next
        loadedPath = file.absolutePath
        loadedConfig = config
        AppLogger.i("OfflineModel", "loaded ${file.name} using llama.cpp CPU inference")
        return next
    }
}

/** Provider-shaped adapter so the existing agent runner can use local completions. */
class OfflineAiProvider(private val context: Context) : AiProvider {
    override val id: String = ID
    override val displayName: String = "Offline GGUF"
    override val baseUrl: String = "http://localhost/offline"
    override val supportsModelCatalog: Boolean = false

    override fun getApiKeys(): List<String> = emptyList()
    override fun addApiKey(key: String) = Unit
    override fun removeApiKey(key: String) = Unit
    override suspend fun validateKey(key: String) = ValidationResult(isValid = true)
    override suspend fun validateAllKeys(): List<KeyValidationResult> = emptyList()
    override suspend fun fetchModels(): Result<List<Model>> = Result.success(
        listOf(Model(id = AgentConfig.activeModelName.ifBlank { "offline-gguf" }, description = "On-device GGUF model • CPU inference")),
    )
    override fun createApiClient(): AiApiClient = OfflineModelApiClient(context)
    override fun getActiveKey(): String? = null
    override fun setActiveKey(key: String?) = Unit
    override val iconRes: Int = R.drawable.ic_memory

    companion object {
        const val ID = "offline_gguf"
    }
}

/** Converts app chat history to a bounded local prompt and returns OpenAI-shaped data to the agent. */
class OfflineModelApiClient(
    context: Context,
    private val pathOverride: String? = null,
) : AiApiClient {
    private val app = context.applicationContext

    override suspend fun complete(
        model: String,
        messages: JsonObject,
        tools: JsonObject?,
        reasoningEffort: String,
    ): Result<JsonObject> = generate(model, messages)

    override suspend fun completeText(
        model: String,
        messages: JsonObject,
        reasoningEffort: String,
    ): Result<JsonObject> = generate(model, messages)

    private suspend fun generate(model: String, request: JsonObject): Result<JsonObject> = runCatching {
        val file = if (pathOverride != null) {
            pathOverride.takeIf { it.isNotBlank() }?.let(::File)
                ?: error("This chat's GGUF model path is missing. Choose another model in Models & thinking.")
        } else {
            OfflineModelStore.selectedFile(app)
                ?: error("No GGUF model selected. Open Models & thinking, choose Offline GGUF, then import a model.")
        }
        val profile = DeviceModelAdvisor.detect(app)
        check(profile.supportsRuntime) { "Offline GGUF inference is available on arm64-v8a Android devices only." }
        val inputMessages = request.get("messages")?.takeUnless { it.isJsonNull }?.asJsonArray ?: JsonArray()
        val contextualInstructions = inputMessages.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val message = element.asJsonObject
            if (message.get("role")?.takeUnless { it.isJsonNull }?.asString != "system") return@mapNotNull null
            message.get("content")?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                ?.takeIf { it.isNotBlank() && !it.startsWith("You are a serious autonomous coding agent") }
        }.joinToString("\n\n").takeLast(profile.maxSystemContextChars)
        val systemPrompt = buildString {
            append("You are a helpful assistant running as a local GGUF model on this phone. ")
            append("Answer the user directly and clearly. This offline mode cannot browse the web, call app tools, inspect files, or control the device. ")
            append("Never claim to have performed actions or accessed data that were not provided in this conversation.")
            if (contextualInstructions.isNotBlank()) append("\n\nRelevant conversation instructions and saved context:\n").append(contextualInstructions)
        }
        val maxPromptChars = profile.maxPromptChars
        val transcript = buildString {
            inputMessages.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val message = element.asJsonObject
                val role = message.get("role")?.takeUnless { it.isJsonNull }?.asString.orEmpty().lowercase(Locale.ROOT)
                if (role !in setOf("user", "assistant", "tool")) return@forEach
                val content = message.get("content")?.takeUnless { it.isJsonNull }?.let { value ->
                    if (value.isJsonPrimitive) value.asString else value.toString()
                }.orEmpty().trim()
                if (content.isNotBlank()) {
                    val label = if (role == "tool") "Previous tool result" else role.replaceFirstChar { it.uppercase() }
                    append(label).append(": ").append(content).append("\n\n")
                }
            }
        }.takeLast(maxPromptChars).ifBlank { "User: Hello\n\n" }

        val localResult = OfflineModelRuntime.complete(
            path = file.absolutePath,
            profile = profile,
            prompt = transcript,
            systemPrompt = systemPrompt,
            maxTokens = profile.maxCompletionTokens,
        )
        AppLogger.i(
            "OfflineModel",
            "generation complete: ${localResult.tokensGenerated} tokens at ${"%.2f".format(Locale.ROOT, localResult.tokensPerSecond)} tok/s; " +
                "prompt=${localResult.promptEvalTimeMs}ms generation=${localResult.generateTimeMs}ms",
        )
        val answer = localResult.text.trim()
        check(answer.isNotBlank()) { "The local model returned an empty response." }
        JsonObject().apply {
            addProperty("id", "offline-${System.currentTimeMillis()}")
            addProperty("object", "chat.completion")
            addProperty("model", model.ifBlank { file.nameWithoutExtension })
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", answer)
                    })
                    addProperty("finish_reason", "stop")
                })
            })
            add("usage", JsonObject().apply {
                addProperty("prompt_tokens", 0)
                addProperty("completion_tokens", localResult.tokensGenerated)
                addProperty("total_tokens", localResult.tokensGenerated)
            })
        }
    }.onFailure {
        // Do not turn a user stop into an ordinary provider error. Cancellation
        // cannot interrupt the current JNI inference, but it must propagate as
        // soon as llama.cpp returns so the service retires the chat run.
        if (it is kotlinx.coroutines.CancellationException) throw it
        AppLogger.e("OfflineModel", "local completion failed: ${it.javaClass.simpleName}: ${it.message}", it)
    }
}
