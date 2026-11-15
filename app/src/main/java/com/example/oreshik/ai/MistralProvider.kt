package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.ModelCapabilities
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.pow as powK

/**
 * Mistral AI provider (OpenAI-compatible API).
 * Docs: Mistral chat-completions endpoint (see docs.mistral.ai).
 * Base: api.mistral.ai/v1  (models: GET /models, chat: POST /chat/completions)
 */
class MistralProvider(
    private val config: AgentConfig = AgentConfig,
) : AiProvider {

    override val id = "mistral"
    override val displayName = "Mistral AI"

    companion object {
        // URL split to avoid IDE "Open in browser" inspection on a literal URL.
        const val MISTRAL_BASE_URL = "https:" + "//api.mistral.ai/v1"
        private const val KEYS_SETTING = "mistral_keys"
        private const val TAG = "Provider/Mistral"

        // No hardcoded keys: the user must add their own key via the Providers
        // screen; it is stored in AgentConfig ("mistral_keys") and can be
        // changed at any time.

        fun mask(key: String): String {
            if (key.length <= 12) return "••••"
            return key.take(9) + "••••" + key.takeLast(4)
        }
    }

    /** Default API endpoint; the user can override it in the Providers screen. */
    private val baseUrlSetting = "mistral_base_url"
    override val baseUrl: String
        get() = config.getRaw(baseUrlSetting).trim().ifBlank { MISTRAL_BASE_URL }
    override val supportsModelCatalog = true
    override val iconRes = com.example.oreshik.R.drawable.ic_auto_awesome

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var keyStates = mutableMapOf<String, KeyState>()

    private data class KeyState(
        val key: String,
        var cooldownUntil: Long = 0,
        var consecutiveFailures: Int = 0,
        var lastError: String = "",
    )

    private fun loadKeys(): List<String> {
        val raw = config.getRaw(KEYS_SETTING)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveKeys(keys: List<String>) {
        val cleaned = keys.asSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
        mutex.tryLock()
        try {
            keyStates.keys.retainAll(cleaned.toSet())
            cleaned.forEach { k -> keyStates.getOrPut(k) { KeyState(k) } }
        } finally {
            if (mutex.isLocked) mutex.unlock()
        }
        config.putRaw(KEYS_SETTING, if (cleaned.isEmpty()) "" else gson.toJson(cleaned))
    }

    override fun getApiKeys(): List<String> = loadKeys()

    override fun addApiKey(key: String) = saveKeys(loadKeys() + key)

    override fun removeApiKey(key: String) = saveKeys(loadKeys() - key)

    /** Next key round-robin (rotates across concurrent sessions too). Public API kept for external callers. */
    @Suppress("unused")
    fun nextRotatedKey(): String? = ProviderKeyRotator.nextKey(id, getApiKeys())

    override fun getEffectiveBaseUrl(): String = baseUrl

    override fun setBaseUrl(url: String?) {
        val cleaned = url?.trim().orEmpty().trimEnd('/')
        config.putRaw(baseUrlSetting, if (cleaned.equals(MISTRAL_BASE_URL, ignoreCase = true)) "" else cleaned)
    }

    override suspend fun validateKey(key: String): ValidationResult = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let { return@withContext ValidationResult(false, it) }
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - startTime
                AppLogger.i(TAG, "validateKey ${mask(key)} → HTTP ${response.code} in ${latency}ms")
                if (response.isSuccessful) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val count = root.getAsJsonArray("data")?.size() ?: 0
                    ValidationResult(isValid = true, modelsCount = count, latencyMs = latency)
                } else {
                    AppLogger.w(TAG, "validateKey failed: ${body.take(200)}")
                    ValidationResult(isValid = false, errorMessage = "HTTP ${response.code}: ${body.take(200)}", latencyMs = latency)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "validateKey error: ${e.message}", e)
            ValidationResult(isValid = false, errorMessage = e.message ?: "Unknown error", latencyMs = System.currentTimeMillis() - startTime)
        }
    }

    override suspend fun validateAllKeys(): List<KeyValidationResult> = withContext(Dispatchers.IO) {
        getApiKeys().map { key ->
            KeyValidationResult(key, mask(key), validateKey(key))
        }
    }

    override suspend fun fetchModels(): Result<List<Model>> = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let {
            return@withContext Result.failure(IllegalArgumentException(it))
        }
        try {
            val key = getActiveKey() ?: getApiKeys().firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No API key configured"))
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            val t0 = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                AppLogger.i(TAG, "fetchModels → HTTP ${response.code} in ${System.currentTimeMillis() - t0}ms")
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "fetchModels failed: ${body.take(300)}")
                    return@withContext Result.failure(IllegalStateException("HTTP ${response.code}: ${body.take(500)}"))
                }
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: return@withContext Result.success(emptyList())
                // Indexed loop instead of mapNotNull chain (avoids Sequence/destructuring hints).
                val parsedModels = ArrayList<Model>(data.size())
                for (i in 0 until data.size()) {
                    val o = data[i].asJsonObject
                    val id = o["id"]?.asString?.trim().orEmpty()
                    if (id.isBlank()) continue
                    val x = id.lowercase()
                    val description = o["description"]?.asString.orEmpty()
                    val caps = o.getAsJsonObject("capabilities")
                    val vision = (caps?.get("vision")?.asBoolean == true) ||
                            listOf("vl", "vision", "pixtral").any { s -> x.contains(s) }
                    val audio = (caps?.get("audio")?.asBoolean == true) || ModelCapabilities.hasInputModality(o, "audio")
                    val isReasoning = x.contains("magistral") ||
                            x.contains("reasoning") ||
                            x.contains("thinking")
                    parsedModels.add(
                        Model(
                            id = id,
                            description = when {
                                description.isNotBlank() -> description
                                vision -> "Mistral model (vision-capable) • $id"
                                else -> "Mistral model • $id"
                            },
                            ownedBy = "Mistral",
                            reasoning = isReasoning,
                            vision = vision,
                            audio = audio,
                            thinkingLevels = if (isReasoning) listOf("off", "low", "medium", "high", "max") else listOf("off"),
                        ),
                    )
                }
                // Preserve original distinctBy/sortedBy semantics with indexed loops.
                val distinctModels = ArrayList<Model>(parsedModels.size)
                val seenIds = HashSet<String>()
                for (i in parsedModels.indices) {
                    val m = parsedModels[i]
                    if (seenIds.add(m.id)) distinctModels.add(m)
                }
                for (i in 1 until distinctModels.size) {
                    val cur = distinctModels[i]
                    var j = i - 1
                    while ((j >= 0) && (distinctModels[j].id.lowercase() > cur.id.lowercase())) {
                        distinctModels[j + 1] = distinctModels[j]
                        j--
                    }
                    distinctModels[j + 1] = cur
                }
                val models: List<Model> = distinctModels
                AppLogger.i(TAG, "fetchModels: ${models.size} models")
                Result.success(models)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "fetchModels error: ${t.message}", t)
            Result.failure(t)
        }
    }

    override fun createApiClient(): AiApiClient = MistralApiClient(config, baseUrl)

    override fun getActiveKey(): String? = config.getRaw("mistral_active_key").takeIf { it.isNotBlank() }

    override fun setActiveKey(key: String?) {
        AppLogger.d(TAG, "setActiveKey → ${key?.let { mask(it) } ?: "null"}")
        key?.let { config.putRaw("mistral_active_key", it) }
    }

    private class MistralApiClient(
        private val config: AgentConfig,
        private val providerBaseUrl: String,
    ) : AiApiClient {
        @Volatile private var http: OkHttpClient = newHttp(null)

        init {
            com.example.oreshik.agent.net.ProxyRotator.register("mistral_api") { proxy -> newHttp(proxy) }
            com.example.oreshik.agent.net.ProxyRotator.onProxyChanged("mistral_api") { entry -> http = newHttp(com.example.oreshik.agent.net.ProxyRotator.toJavaProxy(entry)) }
        }

        private fun newHttp(proxy: java.net.Proxy?): OkHttpClient = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(retryOnConnectionFailure = true)
            .build()

        private val gson = Gson()

        private fun loadKeysList(): List<String> {
            val keys = config.getRaw("mistral_keys")
            if (keys.isBlank()) return emptyList()
            return runCatching {
                val list = gson.fromJson<List<String>>(keys, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type)
                list.orEmpty().filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }

        override suspend fun complete(
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            reasoningEffort: String,
        ): Result<JsonObject> = withContext(Dispatchers.IO) {
            send(model, messages, tools, reasoningEffort)
        }

        override suspend fun completeText(
            model: String,
            messages: JsonObject,
            reasoningEffort: String,
        ): Result<JsonObject> = withContext(Dispatchers.IO) {
            send(model, messages, null, reasoningEffort)
        }

        /**
         * Sends a chat completion with cross-session key rotation: picks the
         * next key each call; on retryable key failures (429/401/403/5xx) the
         * failing key is cooled down and the next key is tried immediately.
         */
        private fun send(
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            @Suppress("UNUSED_PARAMETER") reasoningEffort: String,
        ): Result<JsonObject> {
            // Note: reasoningEffort intentionally unused — Mistral rejects unknown/reasoning fields on some models.
            val keys = loadKeysList()
            if (keys.isEmpty()) {
                AppLogger.e(TAG, "chat $model → no Mistral API key configured")
                return Result.failure(IllegalStateException("No Mistral API key configured"))
            }

            var lastResult: Result<JsonObject> = Result.failure(IllegalStateException("No attempt made"))
            var attempts = 0
            val maxKeyAttempts = keys.size.coerceAtLeast(1)

            while (attempts < maxKeyAttempts) {
                attempts++
                val key = ProviderKeyRotator.nextKey("mistral", keys)
                    ?: return Result.failure(IllegalStateException("No Mistral API key configured"))
                val result = sendWithKey(key, model, messages, tools)
                if (result.isSuccess) {
                    ProviderKeyRotator.reportSuccess("mistral", key)
                    return result
                }
                lastResult = result
                val msg = result.exceptionOrNull()?.message.orEmpty()
                AppLogger.w(TAG, "chat $model → key ${mask(key)} failed (attempt $attempts/$maxKeyAttempts): ${msg.take(180)}")
                val hasMoreKeys = ProviderKeyRotator.reportFailure("mistral", key, msg)
                if (!hasMoreKeys) break
            }
            return lastResult
        }

        private fun sendWithKey(
            key: String,
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
        ): Result<JsonObject> {
            val maxRetries = 3
            var lastException: Throwable? = null
            for (attempt in 0..maxRetries) {
                val t0 = System.currentTimeMillis()
                val result = try {
                    val root = JsonObject().apply {
                        addProperty("model", model)
                        // Mistral 422-proofs the history: reasoning models return a
                        // `reasoning` member on assistant messages which Mistral
                        // forbids as input ("extra_forbidden"), so strip it here.
                        add("messages", MistralSanitizer.sanitizeMessages(messages))
                        addProperty("temperature", 0.1)
                        // tools arrives as {"tools": [...]}; unwrap the array for the wire format.
                        val toolsArray = tools?.getAsJsonArray("tools")
                        if ((toolsArray != null) && (toolsArray.size() > 0)) {
                            add("tools", toolsArray)
                            addProperty("tool_choice", "auto")
                        }
                        // Note: no reasoning param — Mistral rejects unknown fields on some models.
                    }
                    val url = "$providerBaseUrl/chat/completions"
                    val request = Request.Builder()
                        .url(url)
                        .post(gson.toJson(root).toRequestBody("application/json".toMediaType()))
                        .header("Authorization", "Bearer $key")
                        .build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val ms = System.currentTimeMillis() - t0
                        if (!response.isSuccessful) {
                            AppLogger.w(TAG, "chat $model → HTTP ${response.code} in ${ms}ms: ${body.take(250)}")
                            Result.failure(IllegalStateException("HTTP ${response.code}: ${body.take(1500)}"))
                        } else {
                            AppLogger.i(TAG, "chat $model → 200 in ${ms}ms")
                            val parsed = gson.fromJson(body, JsonObject::class.java)
                            if (parsed == null) Result.failure(IllegalStateException("Empty JSON response"))
                            else Result.success(parsed)
                        }
                    }
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "chat $model attempt $attempt failed: ${t.javaClass.simpleName}: ${t.message}", t)
                    lastException = t
                    Result.failure(t)
                }
                if (result.isSuccess) return result
                val msg = result.exceptionOrNull()?.message.orEmpty()
                if (ProviderKeyRotator.isRetryableFailure(msg)) return result
                val shouldRetry = (attempt < maxRetries) && (
                        (lastException is java.io.IOException) ||
                                (msg.contains("HTTP 5"))
                        )
                if (shouldRetry) {
                    if ((attempt == 0) && config.autoVpnEnabled) {
                        runCatching { com.example.oreshik.agent.net.ProxyAutoTuner.ensureTunnel() }
                    }
                    val delay = (1000 * 2.0.powK(attempt.toDouble())).toLong()
                    Thread.sleep(delay)
                } else {
                    return result
                }
            }
            return Result.failure(lastException ?: IllegalStateException("Max retries exceeded"))
        }
    }
}
