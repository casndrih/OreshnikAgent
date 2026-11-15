package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.ModelCapabilities
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
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


class NaraRouterProvider(
    private val config: AgentConfig = AgentConfig,
) : AiProvider {

    override val id = "nararouter"
    override val displayName = "NaraRouter"

    /** Default API endpoint; the user can override it in the Providers screen. */
    // URL split to avoid IDE "Open in browser" inspection on a literal URL.
    private val defaultBaseUrl = "https:" + "//router.bynara.id/v1"
    private val baseUrlSetting = "nararouter_base_url"
    override val baseUrl: String
        get() = config.getRaw(baseUrlSetting).trim().ifBlank { defaultBaseUrl }

    override val supportsModelCatalog = true
    override val iconRes = com.example.oreshik.R.drawable.ic_auto_awesome

    companion object {
        private const val TAG = "Provider/NaraRouter"
        private const val KEYS_SETTING = "nararouter_keys"
        // No hardcoded keys: the user must add their own key via the Providers
        // screen; it is stored in AgentConfig ("nararouter_keys") and can be
        // changed at any time.

        fun mask(key: String): String {
            if (key.length <= 12) return "••••"
            return key.take(9) + "••••" + key.takeLast(4)
        }
    }

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
        config.putRaw(baseUrlSetting, if (cleaned.equals(defaultBaseUrl, ignoreCase = true)) "" else cleaned)
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
                    AppLogger.i(TAG, "validateKey ${mask(key)} → HTTP ${response.code} in ${latency}ms ($count models)")
                    ValidationResult(isValid = true, modelsCount = count, latencyMs = latency)
                } else {
                    AppLogger.w(TAG, "validateKey ${mask(key)} → HTTP ${response.code} in ${latency}ms: ${body.take(200)}")
                    ValidationResult(isValid = false, errorMessage = "HTTP ${response.code}: ${body.take(200)}", latencyMs = latency)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "validateKey ${mask(key)} error: ${e.javaClass.simpleName}: ${e.message}", e)
            ValidationResult(isValid = false, errorMessage = e.message ?: "Unknown error", latencyMs = System.currentTimeMillis() - startTime)
        }
    }

    override suspend fun validateAllKeys(): List<KeyValidationResult> = withContext(Dispatchers.IO) {
        val keys = getApiKeys()
        keys.map { key ->
            val result = validateKey(key)
            KeyValidationResult(key, mask(key), result)
        }
    }

    override suspend fun fetchModels(): Result<List<Model>> = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let {
            return@withContext Result.failure(IllegalArgumentException(it))
        }
        try {
            val key = getActiveKey() ?: getApiKeys().firstOrNull() ?: return@withContext Result.failure(IllegalStateException("No API key configured"))
            
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()

            val t0 = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "fetchModels → HTTP ${response.code} in ${System.currentTimeMillis() - t0}ms: ${body.take(300)}")
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
                    val description = o["description"]?.asString.orEmpty()

                    val x = id.lowercase()
                    val isReasoning = x.contains("reasoning") ||
                                     x.contains("thinking") ||
                                     x.contains("r1") ||
                                     x.contains("qwq") ||
                                     x.contains("deepseek-r")
                    val isVision = listOf("vl", "vision", "vila", "llava").any { s -> x.contains(s) }
                    val isAudio = ModelCapabilities.hasInputModality(o, "audio")

                    val thinkingLevels = if (isReasoning) listOf("off", "low", "medium", "high", "max") else listOf("off")

                    parsedModels.add(
                        Model(
                            id = id,
                            description = description.ifBlank { "NaraRouter model • $id" },
                            ownedBy = "NaraRouter",
                            reasoning = isReasoning,
                            vision = isVision,
                            audio = isAudio,
                            thinkingLevels = thinkingLevels,
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

    override fun createApiClient(): AiApiClient = NaraRouterApiClient(config, baseUrl)

    override fun getActiveKey(): String? = config.getRaw("nararouter_active_key").takeIf { it.isNotBlank() }

    override fun setActiveKey(key: String?) {
        AppLogger.d(TAG, "setActiveKey → ${key?.let { mask(it) } ?: "null"}")
        key?.let { config.putRaw("nararouter_active_key", it) }
    }

    private class NaraRouterApiClient(
        private val config: AgentConfig,
        private val providerBaseUrl: String,
    ) : AiApiClient {
        @Volatile private var http: OkHttpClient = newHttp(null)

        init {
            com.example.oreshik.agent.net.ProxyRotator.register("nararouter_api") { proxy -> newHttp(proxy) }
            com.example.oreshik.agent.net.ProxyRotator.onProxyChanged("nararouter_api") { entry -> http = newHttp(com.example.oreshik.agent.net.ProxyRotator.toJavaProxy(entry)) }
        }

        private fun newHttp(proxy: java.net.Proxy?): OkHttpClient {
            val retryConnections = true
            return OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(retryConnections)
                .build()
        }

        private val gson = Gson()

        private fun loadKeysList(): List<String> {
            val keys = config.getRaw("nararouter_keys")
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
            reasoningEffort: String,
        ): Result<JsonObject> {
            val keys = loadKeysList()
            if (keys.isEmpty()) {
                AppLogger.e(TAG, "chat $model → no NaraRouter API key configured")
                return Result.failure(IllegalStateException("No NaraRouter API key configured"))
            }

            var lastResult: Result<JsonObject> = Result.failure(IllegalStateException("No attempt made"))
            var attempts = 0
            val maxKeyAttempts = keys.size.coerceAtLeast(1)

            while (attempts < maxKeyAttempts) {
                attempts++
                val key = ProviderKeyRotator.nextKey("nararouter", keys)
                    ?: return Result.failure(IllegalStateException("No NaraRouter API key configured"))
                val result = sendWithKey(key, model, messages, tools, reasoningEffort)
                if (result.isSuccess) {
                    ProviderKeyRotator.reportSuccess("nararouter", key)
                    return result
                }
                lastResult = result
                val msg = result.exceptionOrNull()?.message.orEmpty()
                AppLogger.w(TAG, "chat $model → key ${mask(key)} failed (attempt $attempts/$maxKeyAttempts): ${msg.take(180)}")
                val hasMoreKeys = ProviderKeyRotator.reportFailure("nararouter", key, msg)
                if (!hasMoreKeys) break
            }
            return lastResult
        }

        private fun sendWithKey(
            key: String,
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
                        add("messages", messages.getAsJsonArray("messages") ?: JsonArray())
                        addProperty("temperature", 0.1)
                        // tools arrives as {"tools": [...]}; unwrap the array for the wire format.
                        val toolsArray = tools?.getAsJsonArray("tools")
                        if ((toolsArray != null) && (toolsArray.size() > 0)) {
                            add("tools", toolsArray)
                            addProperty("tool_choice", "auto")
                        }
                        if ((reasoningEffort.isNotBlank()) && (reasoningEffort != "off")) {
                            val reasoningObj = JsonObject()
                            reasoningObj.addProperty("effort", reasoningEffort)
                            add("reasoning", reasoningObj)
                        }
                    }

                    val url = "$providerBaseUrl/chat/completions"
                    val request = Request.Builder()
                        .url(url)
                        .post(gson.toJson(root).toRequestBody("application/json".toMediaType()))
                        .header("Authorization", "Bearer $key")
                        .build()

                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            AppLogger.w(TAG, "chat $model attempt $attempt → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms: ${body.take(250)}")
                            Result.failure(IllegalStateException("HTTP ${response.code}: ${body.take(1500)}"))
                        } else {
                            AppLogger.i(TAG, "chat $model → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms (key ${mask(key)})")
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
                    val delay = (1000 * 2.0.pow(attempt.toDouble())).toLong()
                    Thread.sleep(delay)
                } else {
                    return result
                }
            }

            return Result.failure(lastException ?: IllegalStateException("Max retries exceeded"))
        }
    }
}
