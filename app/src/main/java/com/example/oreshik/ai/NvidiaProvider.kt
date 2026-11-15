package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.ModelCapabilities
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.NvidiaAuthErrors
import com.example.oreshik.agent.core.NvidiaKeyManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class NvidiaProvider(
    private val config: AgentConfig = AgentConfig,
    private val keyManager: NvidiaKeyManager = NvidiaKeyManager(),
) : AiProvider {

    override val id = "nvidia"
    override val displayName = "NVIDIA NIM"

    companion object {
        /** Fixed NVIDIA NIM endpoint — never follows AgentConfig.baseUrl, which can
         *  be repointed to another provider (OpenRouter/BlueSmind/NaraRouter). */
        // URL split to avoid IDE "Open in browser" inspection on a literal URL.
        const val NVIDIA_DEFAULT_BASE_URL = "https:" + "//integrate.api.nvidia.com/v1"
        private const val TAG = "Provider/NVIDIA"
    }

    override val baseUrl: String
        get() = config.getRaw("nvidia_base_url").trim().ifBlank { NVIDIA_DEFAULT_BASE_URL }

    override val supportsModelCatalog = true
    override val iconRes = com.example.oreshik.R.drawable.ic_auto_awesome

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun getApiKeys(): List<String> = keyManager.loadKeys()

    override fun addApiKey(key: String) = keyManager.addKey(key)

    override fun removeApiKey(key: String) = keyManager.removeKey(key)

    /** Next key round-robin (rotates across concurrent sessions too). Public API kept for external callers. */
    @Suppress("unused")
    fun nextRotatedKeyBlocking(): String? = kotlinx.coroutines.runBlocking { keyManager.selectKey() }

    override fun getEffectiveBaseUrl(): String = baseUrl

    override fun setBaseUrl(url: String?) {
        val cleaned = url?.trim().orEmpty().trimEnd('/')
        config.putRaw("nvidia_base_url", if (cleaned.equals(NVIDIA_DEFAULT_BASE_URL, ignoreCase = true)) "" else cleaned)
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
                if (response.isSuccessful) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val data = root.getAsJsonArray("data")
                    val count = data?.size() ?: 0
                    AppLogger.i(TAG, "validateKey ${NvidiaKeyManager.mask(key)} → HTTP ${response.code} in ${latency}ms ($count models)")
                    ValidationResult(isValid = true, modelsCount = count, latencyMs = latency)
                } else {
                    AppLogger.w(TAG, "validateKey ${NvidiaKeyManager.mask(key)} → HTTP ${response.code} in ${latency}ms: ${body.take(200)}")
                    ValidationResult(isValid = false, errorMessage = NvidiaAuthErrors.describe(response.code, body, bodyLimit = 200), latencyMs = latency)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "validateKey ${NvidiaKeyManager.mask(key)} error: ${e.javaClass.simpleName}: ${e.message}", e)
            ValidationResult(isValid = false, errorMessage = e.message ?: "Unknown error", latencyMs = System.currentTimeMillis() - startTime)
        }
    }

    override suspend fun validateAllKeys(): List<KeyValidationResult> = withContext(Dispatchers.IO) {
        val keys = getApiKeys()
        keys.map { key ->
            val result = validateKey(key)
            KeyValidationResult(key, NvidiaKeyManager.mask(key), result)
        }
    }

    override suspend fun fetchModels(): Result<List<Model>> = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let {
            return@withContext Result.failure(IllegalArgumentException(it))
        }
        // Use this provider's own (editable) base URL and a rotated key instead
        // of the legacy global AgentConfig path, so testing a non-active provider
        // still works and multi-key users spread load across keys.
        val key = keyManager.selectKey() ?: config.apiKey
        val t0 = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "fetchModels → HTTP ${response.code} in ${System.currentTimeMillis() - t0}ms: ${body.take(300)}")
                    return@withContext Result.failure(IllegalStateException("NVIDIA models " + NvidiaAuthErrors.describe(response.code, body, bodyLimit = 500)))
                }
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: return@withContext Result.success(emptyList())
                // Indexed loop instead of mapNotNull chain (avoids Sequence/destructuring hints).
                val parsedModels = ArrayList<Model>(data.size())
                for (i in 0 until data.size()) {
                    val o = data[i].asJsonObject
                    val id = o["id"]?.asString?.trim().orEmpty()
                    if (id.isBlank()) continue
                    val owned = o["owned_by"]?.asString.orEmpty()
                    val desc = o["description"]?.asString.orEmpty()
                    val x = "$id $desc".lowercase()
                    val reasoningCapability = ModelCapabilities.reasoningCapability(o, id, desc)
                    val reasoning = reasoningCapability.supported
                    val vision = listOf("vl", "vision", "vila", "llava").any { s -> id.lowercase().contains(s) }
                    val audio = ModelCapabilities.hasInputModality(o, "audio")
                    val thinkingLevels = reasoningCapability.levels
                    parsedModels.add(
                        Model(
                            id = id,
                            description = desc.ifBlank { when {
                                listOf("coder", "code", "devstral", "starcoder").any { it in x } -> "Coding model"
                                reasoning -> "Reasoning model"
                                vision -> "Vision model"
                                else -> "General model"
                            } },
                            ownedBy = owned,
                            reasoning = reasoning,
                            vision = vision,
                            audio = audio,
                            thinkingLevels = thinkingLevels,
                            reasoningVerified = reasoningCapability.verified,
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
                AppLogger.i(TAG, "fetchModels → ${models.size} models in ${System.currentTimeMillis() - t0}ms")
                Result.success(models)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "fetchModels error: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        }
    }

    override fun createApiClient(): AiApiClient = NvidiaApiClient(keyManager, config, baseUrl)

    override fun getActiveKey(): String? = config.apiKey.takeIf { it.isNotBlank() }

    override fun setActiveKey(key: String?) {
        AppLogger.d(TAG, "setActiveKey → ${key?.let { NvidiaKeyManager.mask(it) } ?: "null"}")
        key?.let { config.apiKey = it }
    }

    private class NvidiaApiClient(
        private val keyManager: NvidiaKeyManager?,
        private val config: AgentConfig,
        private val providerBaseUrl: String,
    ) : AiApiClient {
        @Volatile private var http: OkHttpClient = newHttp(null)

        init {
            com.example.oreshik.agent.net.ProxyRotator.register("nvidia_api") { proxy -> newHttp(proxy) }
            com.example.oreshik.agent.net.ProxyRotator.onProxyChanged("nvidia_api") { entry -> http = newHttp(com.example.oreshik.agent.net.ProxyRotator.toJavaProxy(entry)) }
        }

        private fun newHttp(proxy: java.net.Proxy?): OkHttpClient = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(retryOnConnectionFailure = true)
            .build()

        private val gson = com.google.gson.Gson()
        private val toolSupport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val reasoningSupport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private suspend fun resolveKey(callerKey: String): Pair<String, String?> {
            if (keyManager == null) return callerKey to null
            val configuredKeys = keyManager.loadKeys()
            if (configuredKeys.isEmpty()) return callerKey to null
            // Always select through the key manager. ProviderManager keeps the
            // first configured key in AgentConfig for legacy features, but using
            // that value directly here bypassed round-robin and cooldown handling.
            val selected = keyManager.selectKey() ?: return "" to null
            return selected to selected
        }

        private data class KeyedCompletion(
            val result: Result<JsonObject>,
            val apiKey: String,
            val managedKey: String?,
        )

        /** Makes one request per eligible saved key when a key-specific/transient
         * HTTP failure occurs. Each key is attempted at most once in this call. */
        private suspend fun requestWithKeyRotation(
            initialKey: String,
            initialManagedKey: String?,
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            reasoningEffort: String,
        ): KeyedCompletion {
            var currentKey = initialKey
            var currentManagedKey = initialManagedKey
            val attempted = mutableSetOf<String>()
            while (true) {
                attempted += currentKey
                val result = requestCompletion(currentKey, model, messages, tools, reasoningEffort)
                reportKeyOutcome(currentManagedKey, result)
                if (result.isSuccess || currentManagedKey == null || keyManager == null) {
                    return KeyedCompletion(result, currentKey, currentManagedKey)
                }

                val message = result.exceptionOrNull()?.message.orEmpty()
                val code = keyManager.httpCodeFromMessage(message)
                if (!keyManager.shouldRotate(code, message)) {
                    return KeyedCompletion(result, currentKey, currentManagedKey)
                }
                val nextKey = keyManager.selectKey()
                    ?: return KeyedCompletion(result, currentKey, currentManagedKey)
                if (nextKey in attempted) return KeyedCompletion(result, currentKey, currentManagedKey)
                currentKey = nextKey
                currentManagedKey = nextKey
            }
        }

        private fun missingKeyFailure(): Throwable = IllegalStateException(
            if (keyManager?.loadKeys()?.isNotEmpty() == true) {
                "All configured NVIDIA API keys are temporarily unavailable or cooling down. Wait briefly, then try again."
            } else {
                "No API key available"
            },
        )

        override suspend fun complete(
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            reasoningEffort: String,
        ): Result<JsonObject> = withContext(Dispatchers.IO) {
            val callerKey = config.apiKey
            val (key, managedKey) = resolveKey(callerKey)
            if (key.isBlank()) return@withContext Result.failure(missingKeyFailure())

            val baseUrl = providerBaseUrl
            val keyForCapability = capabilityKey(baseUrl, model)
            val requestedReasoning = if (reasoningSupport[keyForCapability] == false) "off" else reasoningEffort

            if (toolSupport[keyForCapability] == false) {
                AppLogger.d(TAG, "chat $model → tools previously unsupported; using no-tools path")
                return@withContext completeWithoutTools(key, managedKey, model, messages, requestedReasoning, keyForCapability)
            }

            val firstAttempt = requestWithKeyRotation(key, managedKey, model, messages, tools, requestedReasoning)
            val requestKey = firstAttempt.apiKey
            val requestManagedKey = firstAttempt.managedKey
            val first = firstAttempt.result
            if (first.isSuccess) {
                toolSupport[keyForCapability] = true
                if (requestedReasoning != "off") reasoningSupport[keyForCapability] = true
                return@withContext first
            }

            val firstMessage = first.exceptionOrNull()?.message.orEmpty()
            AppLogger.w(TAG, "chat $model (tools+reasoning) → ${first.exceptionOrNull()?.javaClass?.simpleName}: ${firstMessage.take(200)}")

            // Auth failures are never fixed by any fallback — surface them immediately.
            if (NvidiaAuthErrors.isAuthFailure(firstMessage)) {
                return@withContext first
            }

            // A quota response cannot be repaired by changing the request shape.
            // requestWithKeyRotation has already tried each eligible saved key.
            if (isRateLimitFailure(firstMessage)) return@withContext first

            if ((requestedReasoning != "off") && looksLikeReasoningFailure(firstMessage)) {
                // Reorder: check reasoning_effort rejection BEFORE blaming tools.
                // A reasoning_effort schema error (e.g. "unknown variant `ultra`")
                // must disable only reasoning, never tool support.
                val noReasoning = requestCompletion(requestKey, model, messages, tools, "off")
                reportKeyOutcome(requestManagedKey, noReasoning)
                if (noReasoning.isSuccess) {
                    reasoningSupport[keyForCapability] = false
                    return@withContext noReasoning
                }
                val noReasoningMessage = noReasoning.exceptionOrNull()?.message.orEmpty()
                if (NvidiaAuthErrors.isAuthFailure(noReasoningMessage) || isRateLimitFailure(noReasoningMessage)) {
                    return@withContext noReasoning
                }
            }

            if (isToolCapabilityFailure(firstMessage)) {
                AppLogger.d(TAG, "chat $model → tool-capability failure detected; retrying without tools")
                val noTools = requestCompletion(requestKey, model, messages, null, requestedReasoning)
                reportKeyOutcome(requestManagedKey, noTools)
                if (noTools.isSuccess) {
                    toolSupport[keyForCapability] = false
                    if (requestedReasoning != "off") reasoningSupport[keyForCapability] = true
                    return@withContext noTools.map { it.apply { addProperty("_oreshik_tool_fallback", true) } }
                }
                val noToolsMessage = noTools.exceptionOrNull()?.message.orEmpty()
                if (NvidiaAuthErrors.isAuthFailure(noToolsMessage) || isRateLimitFailure(noToolsMessage)) return@withContext noTools
                if ((requestedReasoning != "off") && looksLikeReasoningFailure(noTools.exceptionOrNull()?.message.orEmpty())) {
                    val plain = requestCompletion(requestKey, model, messages, null, "off")
                    reportKeyOutcome(requestManagedKey, plain)
                    if (plain.isSuccess) {
                        toolSupport[keyForCapability] = false
                        reasoningSupport[keyForCapability] = false
                        return@withContext plain.map { it.apply { addProperty("_oreshik_tool_fallback", true) } }
                    }
                    val plainMessage = plain.exceptionOrNull()?.message.orEmpty()
                    if (NvidiaAuthErrors.isAuthFailure(plainMessage) || isRateLimitFailure(plainMessage)) return@withContext plain
                }
            }

            // Last-resort compatibility attempt. Keep it NON-sticky for tools: the
            // earlier failure may have been a transient server hiccup, and marking
            // toolSupport=false here poisoned every future chat with no tools (the
            // model then fake-calls tools in plain text).
            val plain = requestCompletion(requestKey, model, messages, null, "off")
            reportKeyOutcome(requestManagedKey, plain)
            if (plain.isSuccess) {
                reasoningSupport[keyForCapability] = false
                AppLogger.w(TAG, "chat $model → succeeded with no-tools/no-reasoning fallback for this request (tool support NOT permanently disabled)")
                return@withContext plain.map { it.apply { addProperty("_oreshik_compat_fallback", true) } }
            }
            val plainMessage = plain.exceptionOrNull()?.message.orEmpty()
            if (NvidiaAuthErrors.isAuthFailure(plainMessage) || isRateLimitFailure(plainMessage)) return@withContext plain
            AppLogger.e(TAG, "chat $model → ALL attempts failed (tools/reasoning/no-tools/no-reasoning)")
            first
        }

        override suspend fun completeText(
            model: String,
            messages: JsonObject,
            reasoningEffort: String,
        ): Result<JsonObject> = withContext(Dispatchers.IO) {
            val callerKey = config.apiKey
            val (key, managedKey) = resolveKey(callerKey)
            if (key.isBlank()) return@withContext Result.failure(missingKeyFailure())

            val baseUrl = providerBaseUrl
            val keyForCapability = capabilityKey(baseUrl, model)
            val requestedReasoning = if (reasoningSupport[keyForCapability] == false) "off" else reasoningEffort
            
            val firstAttempt = requestWithKeyRotation(key, managedKey, model, messages, null, requestedReasoning)
            val first = firstAttempt.result
            if (first.isSuccess) {
                if (requestedReasoning != "off") reasoningSupport[keyForCapability] = true
                return@withContext first
            }
            AppLogger.w(TAG, "completeText $model → ${first.exceptionOrNull()?.javaClass?.simpleName}: ${first.exceptionOrNull()?.message?.take(200)}")
            // Auth failures are never fixed by any fallback — surface them immediately.
            if (NvidiaAuthErrors.isAuthFailure(first.exceptionOrNull()?.message.orEmpty())) {
                return@withContext first
            }
            if (isRateLimitFailure(first.exceptionOrNull()?.message.orEmpty())) return@withContext first
            if ((requestedReasoning != "off") && looksLikeReasoningFailure(first.exceptionOrNull()?.message.orEmpty())) {
                val fallback = requestCompletion(firstAttempt.apiKey, model, messages, null, "off")
                reportKeyOutcome(firstAttempt.managedKey, fallback)
                if (fallback.isSuccess) {
                    reasoningSupport[keyForCapability] = false
                    AppLogger.d(TAG, "completeText $model → succeeded after disabling reasoning_effort")
                    return@withContext fallback
                }
                val fallbackMessage = fallback.exceptionOrNull()?.message.orEmpty()
                if (NvidiaAuthErrors.isAuthFailure(fallbackMessage) || isRateLimitFailure(fallbackMessage)) return@withContext fallback
            }
            first
        }

        private suspend fun completeWithoutTools(
            key: String,
            managedKey: String?,
            model: String,
            messages: JsonObject,
            reasoningEffort: String,
            capabilityKey: String,
        ): Result<JsonObject> {
            val first = requestWithKeyRotation(key, managedKey, model, messages, null, reasoningEffort)
            val result = first.result
            if (result.isSuccess) return result
            val firstMessage = result.exceptionOrNull()?.message.orEmpty()
            if (NvidiaAuthErrors.isAuthFailure(firstMessage) || isRateLimitFailure(firstMessage)) return result
            if ((reasoningEffort != "off") && looksLikeReasoningFailure(result.exceptionOrNull()?.message.orEmpty())) {
                val plain = requestCompletion(first.apiKey, model, messages, null, "off")
                reportKeyOutcome(first.managedKey, plain)
                if (plain.isSuccess) {
                    reasoningSupport[capabilityKey] = false
                    AppLogger.d(TAG, "chat $model → succeeded after disabling reasoning_effort (no-tools path)")
                    return plain
                }
                val plainMessage = plain.exceptionOrNull()?.message.orEmpty()
                if (NvidiaAuthErrors.isAuthFailure(plainMessage) || isRateLimitFailure(plainMessage)) return plain
            }
            return result
        }

        private fun requestCompletion(
            apiKey: String,
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            reasoningEffort: String,
        ): Result<JsonObject> {
            val maxRetries = 3
            var lastException: Throwable? = null
            
            for (attempt in 0..maxRetries) {
                val attemptStart = System.currentTimeMillis()
                val result = try {
                    val root = JsonObject().apply {
                        addProperty("model", model)
                        // NVIDIA NIM (vLLM-backed) rejects the whole request with HTTP 400
                        // "This model only supports single tool-calls at once!" when ANY
                        // replayed assistant message carries more than one tool_call. Split
                        // parallel tool-call turns into sequential assistant/tool pairs on
                        // the wire copy (session history itself stays untouched).
                        add("messages", NvidiaSanitizer.sanitizeMessages(messages))
                        addProperty("temperature", 0.1)
                        // tools arrives as {"tools": [...]}; unwrap the array for the wire format.
                        val toolsArray = tools?.getAsJsonArray("tools")
                        if ((toolsArray != null) && (toolsArray.size() > 0)) {
                            add("tools", toolsArray)
                            addProperty("tool_choice", "auto")
                        }
                        if ((reasoningEffort.isNotBlank()) && (reasoningEffort != "off")) {
                            // Defensive normalization: NIM accepts only none/minimal/low/
                            // medium/high/xhigh/max. Values like the legacy "ultra" (or a
                            // stale value saved by an older build) are downgraded to "high"
                            // instead of triggering HTTP 400 unknown-variant.
                            addProperty("reasoning_effort", normalizeReasoningEffort(reasoningEffort))
                        }
                    }
                    val url = "$providerBaseUrl/chat/completions"
                    val request = Request.Builder()
                        .url(url)
                        .post(gson.toJson(root).toRequestBody("application/json".toMediaType()))
                        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                        .build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            AppLogger.w(TAG, "chat $model attempt $attempt → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms: ${body.take(250)}")
                            Result.failure(IllegalStateException(NvidiaAuthErrors.describe(response.code, body)))
                        } else {
                            AppLogger.i(TAG, "chat $model → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms (key ${NvidiaKeyManager.mask(apiKey)})")
                            val parsed = gson.fromJson(body, JsonObject::class.java)
                            if (parsed == null) {
                                AppLogger.w(TAG, "chat $model → empty JSON response")
                                Result.failure(IllegalStateException("Empty JSON response"))
                            } else {
                                Result.success(parsed)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "chat $model attempt $attempt → ${t.javaClass.simpleName}: ${t.message}", t)
                    lastException = t
                    Result.failure(t)
                }
                
                if (result.isSuccess) return result
                
                val shouldRetry = (attempt < maxRetries) && (
                    (lastException is java.io.IOException) ||
                    (result.exceptionOrNull()?.message?.contains("HTTP 5") == true)
                )
                
                if (shouldRetry) {
                    if ((attempt == 0) && config.autoVpnEnabled) {
                        AppLogger.d(TAG, "chat $model → auto-VPN tuning before retry")
                        runCatching { com.example.oreshik.agent.net.ProxyAutoTuner.ensureTunnel() }
                    }
                    val delayMs = (1000 * 2.0.pow(attempt.toDouble())).toLong()
                    AppLogger.d(TAG, "chat $model attempt $attempt → retrying in ${delayMs}ms")
                    Thread.sleep(delayMs)
                } else {
                    return result
                }
            }
            
            AppLogger.e(TAG, "chat $model → exhausted $maxRetries retries: ${lastException?.javaClass?.simpleName}: ${lastException?.message?.take(200)}")
            return Result.failure(lastException ?: IllegalStateException("Max retries exceeded"))
        }

        private fun capabilityKey(baseUrl: String, model: String) = "${baseUrl.trimEnd('/')}::$model"

        private fun isToolCapabilityFailure(message: String): Boolean {
            val x = message.lowercase()
            val function404 = ("http 404" in x) && (("function" in x) || ("tool" in x)) && (("not found" in x) || ("notfound" in x))
            val inference500 = ("http 500" in x) && (("internal error while making inference" in x) || ("function" in x) || ("tool" in x))
            val unsupported = (("http 400" in x) || ("http 422" in x)) && (("tool_choice" in x) || ("tool calling" in x) || ("tools" in x) || ("function" in x))
            return function404 || inference500 || unsupported
        }

        private fun looksLikeReasoningFailure(message: String): Boolean {
            val x = message.lowercase()
            // "unknown variant `ultra`" / "expected one of `none`..." are reasoning_effort
            // schema errors — they must be retried without reasoning, NOT without tools
            // (misdetection previously disabled tool calling for the whole model).
            val unknownVariant = ("unknown variant" in x) && (("reasoning" in x) || ("effort" in x) || ("ultra" in x) || ("minimal" in x))
            return (unknownVariant) || ("reasoning_effort" in x) || ("reasoning effort" in x) || ("unknown parameter" in x) || (("unsupported" in x) && ("reason" in x))
        }

        private fun isRateLimitFailure(message: String): Boolean =
            keyManager?.httpCodeFromMessage(message) == 429 || message.contains("HTTP 429")

        /** Clamps reasoning_effort to values NVIDIA NIM accepts on the wire. */
        private fun normalizeReasoningEffort(effort: String): String = when (effort.trim().lowercase()) {
            "none", "minimal", "low", "medium", "high", "xhigh", "max" -> effort.trim().lowercase()
            "ultra" -> "max"
            else -> "high"
        }

        private suspend fun reportKeyOutcome(callerKey: String?, result: Result<JsonObject>) {
            if ((keyManager == null) || callerKey.isNullOrBlank()) return
            if (result.isSuccess) {
                keyManager.reportSuccess(callerKey)
                return
            }
            val msg = result.exceptionOrNull()?.message.orEmpty()
            val code = keyManager.httpCodeFromMessage(msg)
            if (keyManager.shouldRotate(code, msg)) {
                keyManager.reportFailure(callerKey, code, msg)
            }
        }
    }
}
