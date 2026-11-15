package com.example.oreshik.agent.tools

import com.example.oreshik.ai.DeviceModelAdvisor
import com.example.oreshik.ai.GgufQuantizer
import com.example.oreshik.ai.OfflineModelRuntime
import com.example.oreshik.ai.OfflineModelStore
import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/** Quantizes a single GGUF model using the llama.cpp engine bundled for local inference. */
class GgufQuantizeTool : AgentTool {
    override val definition = ToolDefinition(
        name = "gguf.quantize",
        category = ToolCategory.RUNTIME,
        description = "Quantize an existing, single-file GGUF model to a supported llama.cpp format. " +
            "Keeps the source unchanged, writes a new model beside it, and defaults to device-friendly Q4_K_M. " +
            "Requantization of an already-quantized model is disabled unless explicitly requested.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "source_path" to mapOf(
                    "type" to "string",
                    "description" to "Optional GGUF path inside the app model folder or current workspace; defaults to the selected offline model.",
                ),
                "output_name" to mapOf(
                    "type" to "string",
                    "description" to "Optional output filename in the same directory; defaults to <source>-<type>.gguf.",
                ),
                "quantization_type" to mapOf(
                    "type" to "string",
                    "enum" to SUPPORTED_TYPES.toList(),
                    "description" to "Target llama.cpp format. Defaults to Q4_K_M for a mobile quality/size balance.",
                ),
                "threads" to mapOf(
                    "type" to "integer",
                    "description" to "Optional CPU thread count, clamped to this device's detected CPU recommendation (up to four).",
                ),
                "allow_requantize" to mapOf(
                    "type" to "boolean",
                    "description" to "Defaults false. Set true only when explicitly requantizing an already-quantized GGUF; repeated quantization can reduce quality.",
                ),
            ),
            "required" to emptyList<String>(),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: com.google.gson.JsonElement): ToolResult =
        withContext(Dispatchers.IO) {
            val args = arguments.asJsonObject
            val profile = DeviceModelAdvisor.detect(context.app)
            if (!profile.supportsRuntime) {
                return@withContext ToolResult.failure("GGUF quantization requires the bundled arm64-v8a llama.cpp runtime.")
            }

            val inputArg = args.get("source_path")?.takeUnless { it.isJsonNull }?.asString.orEmpty().trim()
            val source = if (inputArg.isBlank()) {
                OfflineModelStore.selectedFile(context.app)
                    ?: return@withContext ToolResult.failure("No input GGUF was supplied and no offline model is selected. Import or select a model first.")
            } else {
                val requested = File(inputArg)
                if (requested.isAbsolute) requested else File(context.workspace, inputArg)
            }
            val sourceFile = runCatching { source.canonicalFile }.getOrElse {
                return@withContext ToolResult.failure("Could not resolve source GGUF path: ${it.message}")
            }
            if (!isAllowedLocation(context.workspace, OfflineModelStore.directory(context.app), sourceFile)) {
                return@withContext ToolResult.failure("Source must be inside this app's model folder or the current workspace.")
            }
            if (!sourceFile.isFile || !sourceFile.canRead() || !sourceFile.extension.equals("gguf", ignoreCase = true)) {
                return@withContext ToolResult.failure("Source must be a readable .gguf file.")
            }
            if (isSplitShard(sourceFile.name)) {
                return@withContext ToolResult.failure("Split GGUF shards are not supported by the in-app quantizer yet. Use the host llama-quantize tool with --keep-split.")
            }
            if (!hasGgufMagic(sourceFile)) {
                return@withContext ToolResult.failure("The selected file does not have a GGUF header.")
            }

            val type = args.get("quantization_type")?.takeUnless { it.isJsonNull }?.asString
                ?.trim()?.uppercase(Locale.ROOT)?.ifBlank { "Q4_K_M" } ?: "Q4_K_M"
            if (type !in SUPPORTED_TYPES) {
                return@withContext ToolResult.failure("Unsupported quantization type '$type'. Choose one of: ${SUPPORTED_TYPES.joinToString()}.")
            }
            val allowRequantize = args.get("allow_requantize")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            val requestedThreads = args.get("threads")?.takeUnless { it.isJsonNull }?.asInt ?: profile.cpuThreads
            val threads = requestedThreads.coerceIn(1, profile.cpuThreads.coerceIn(1, 4))

            val suppliedName = args.get("output_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty().trim()
            val outputName = (suppliedName.ifBlank { "${sourceFile.nameWithoutExtension}-$type.gguf" })
                .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }
            if (outputName == ".gguf" || outputName == "." || outputName == ".." ||
                outputName.contains('/') || outputName.contains('\\') || outputName.any { it == '\u0000' }
            ) {
                return@withContext ToolResult.failure("output_name must be a filename, not a path.")
            }
            val outputFile = runCatching { File(sourceFile.parentFile, outputName).canonicalFile }.getOrElse {
                return@withContext ToolResult.failure("Could not resolve output GGUF path: ${it.message}")
            }
            if (outputFile == sourceFile) return@withContext ToolResult.failure("Output must be a new file; the source GGUF is never overwritten.")
            if (outputFile.exists()) return@withContext ToolResult.failure("Output already exists: ${outputFile.name}. Choose a different output_name.")
            val parent = outputFile.parentFile
                ?: return@withContext ToolResult.failure("Output directory is invalid.")
            if (parent != sourceFile.parentFile || !parent.isDirectory || !parent.canWrite()) {
                return@withContext ToolResult.failure("Output must be writable and in the same folder as the source model.")
            }
            val minimumFreeBytes = max(MIN_FREE_BYTES, if (allowRequantize) sourceFile.length() * 2L else sourceFile.length() / 8L)
            val freeBytes = runCatching { android.os.StatFs(parent.absolutePath).availableBytes }.getOrDefault(0L)
            if (freeBytes < minimumFreeBytes) {
                return@withContext ToolResult.failure(
                    "Not enough free storage for a safe quantization attempt. Need at least ${OfflineModelStore.formatBytes(minimumFreeBytes)} free; currently ${OfflineModelStore.formatBytes(freeBytes)}.",
                )
            }

            val temporaryFile = File(parent, "${outputFile.nameWithoutExtension}.partial-${UUID.randomUUID()}.gguf")
            if (temporaryFile.exists()) return@withContext ToolResult.failure("Could not reserve a temporary output filename.")
            return@withContext try {
                context.onProgress?.invoke(
                    "Quantizing ${sourceFile.name} to $type on ${profile.deviceLabel} using $threads CPU thread${if (threads == 1) "" else "s"}…",
                )
                val result = quantizeWithProgress(context, sourceFile, temporaryFile, type, threads, allowRequantize)
                if (result != 0) {
                    temporaryFile.delete()
                    ToolResult.failure("llama.cpp quantization failed with status $result. The original GGUF was kept unchanged.")
                } else if (!temporaryFile.isFile || temporaryFile.length() <= 0 || !hasGgufMagic(temporaryFile)) {
                    temporaryFile.delete()
                    ToolResult.failure("llama.cpp returned success but did not produce a valid GGUF output. The original was kept unchanged.")
                } else if (outputFile.exists() || !temporaryFile.renameTo(outputFile)) {
                    temporaryFile.delete()
                    ToolResult.failure("Could not safely finalize the quantized file. The original GGUF was kept unchanged.")
                } else {
                    ToolResult.success(
                        "Quantization complete: ${sourceFile.name} → ${outputFile.name} ($type, ${OfflineModelStore.formatBytes(outputFile.length())}). " +
                            "The source was preserved and the new model was not selected automatically. Review it in Models & thinking before switching.",
                        metadata = mapOf(
                            "source_path" to sourceFile.absolutePath,
                            "output_path" to outputFile.absolutePath,
                            "quantization_type" to type,
                            "output_bytes" to outputFile.length(),
                            "threads" to threads,
                            "requantized" to allowRequantize,
                        ),
                    )
                }
            } catch (failure: Exception) {
                temporaryFile.delete()
                ToolResult.failure("GGUF quantization failed: ${failure.message ?: failure.javaClass.simpleName}. The original file was kept unchanged.")
            }
        }

    private suspend fun quantizeWithProgress(
        context: ToolContext,
        source: File,
        temporary: File,
        type: String,
        threads: Int,
        allowRequantize: Boolean,
    ): Int = coroutineScope {
        val nativeJob = async(Dispatchers.IO) {
            OfflineModelRuntime.withExclusiveMaintenance {
                GgufQuantizer.quantizeNative(
                    source.absolutePath,
                    temporary.absolutePath,
                    type,
                    threads,
                    allowRequantize,
                )
            }
        }
        var elapsedSeconds = 0
        while (!nativeJob.isCompleted) {
            delay(PROGRESS_INTERVAL_MS)
            elapsedSeconds += (PROGRESS_INTERVAL_MS / 1000L).toInt()
            if (!nativeJob.isCompleted) {
                val elapsed = if (elapsedSeconds >= 60) "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s" else "${elapsedSeconds}s"
                context.onProgress?.invoke("Quantizing ${source.name} to $type… still running ($elapsed elapsed). Keep the app open while the phone processes the model.")
            }
        }
        nativeJob.await()
    }

    private fun isAllowedLocation(workspace: File, modelDirectory: File, file: File): Boolean {
        val roots = listOf(workspace, modelDirectory).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return roots.any { root -> file.path.startsWith(root.path + File.separator) }
    }

    private fun isSplitShard(name: String): Boolean =
        Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    private fun hasGgufMagic(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
    }.getOrDefault(false)

    private companion object {
        val SUPPORTED_TYPES = setOf("Q4_K_M", "Q5_K_M", "Q6_K", "Q8_0", "Q4_0", "Q3_K_M", "Q4_K_S", "F16")
        const val MIN_FREE_BYTES = 256L * 1024L * 1024L
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
