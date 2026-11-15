package com.example.oreshik.agent.core

import com.example.oreshik.ai.ProviderEndpointPolicy
import com.example.oreshik.agent.net.ProxyAutoTuner
import com.example.oreshik.agent.net.ProxyRotator
import com.example.oreshik.logging.AppLogger
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/** OpenAI-compatible NVIDIA/NIM client with model-capability fallbacks. */
class AgentApiClient(
    /** Optional key-rotation manager. Null = single-key mode (no rotation). */
    private val keyManager: NvidiaKeyManager? = null,
) {

    companion object {
        private const val TAG = "AgentApi"
    }
    /** Proxy-aware inference client; rebuilt when ProxyRotator changes the route. */
    @Volatile private var http: OkHttpClient = newHttp(null)

    init {
        ProxyRotator.register("agent_api") { proxy -> newHttp(proxy) }
        ProxyRotator.onProxyChanged("agent_api") { entry -> http = newHttp(ProxyRotator.toJavaProxy(entry)) }
        AppLogger.i(TAG, "AgentApiClient initialized (proxy-aware client ready)")
    }

    private fun newHttp(proxy: java.net.Proxy?): OkHttpClient {
        val retryOnConnection = true
        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(retryOnConnection)
            .build()
    }

    private val gson = Gson()
    private val toolSupport = ConcurrentHashMap<String, Boolean>()
    private val reasoningSupport = ConcurrentHashMap<String, Boolean>()

    /**
     * Resolves the API key for a request: the caller's key when rotation is off
     * (single-key mode), or the next eligible key from the manager.
     */
    @Suppress("unused") // Public API kept for key-rotation flows outside the current call graph.
    private suspend fun resolveKey(callerKey: String): Pair<String, String?> {
        if ((keyManager == null) || (callerKey.isNotBlank())) return callerKey to null
        val selected = keyManager.selectKey() ?: return "" to null
        return selected to selected
    }

    suspend fun completeText(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        reasoningEffort: String = "off",
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val key = capabilityKey(baseUrl, model)
        val requestedReasoning = if (reasoningSupport[key] == false) "off" else reasoningEffort
        val first = requestCompletion(baseUrl, apiKey, model, messages, null, requestedReasoning)
        if (first.isSuccess) {
            reportKeyOutcome(apiKey, first)
            if (requestedReasoning != "off") reasoningSupport[key] = true
            return@withContext first
        }
        // Auth failures are never fixed by any fallback — surface them immediately.
        if (NvidiaAuthErrors.isAuthFailure(first.exceptionOrNull()?.message.orEmpty())) {
            reportKeyOutcome(apiKey, first)
            return@withContext first
        }
        if ((requestedReasoning != "off") && looksLikeReasoningFailure(first.exceptionOrNull()?.message.orEmpty())) {
            val fallback = requestCompletion(baseUrl, apiKey, model, messages, null, "off")
            if (fallback.isSuccess) { reasoningSupport[key] = false; reportKeyOutcome(apiKey, fallback) }
            return@withContext fallback
        }
        first
    }

    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray,
        reasoningEffort: String = "high",
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val key = capabilityKey(baseUrl, model)
        val requestedReasoning = if (reasoningSupport[key] == false) "off" else reasoningEffort

        // If this model has already failed tool calling, don't keep sending a request
        // that NVIDIA/NIM may route into a missing server-side Function UUID.
        if (toolSupport[key] == false) {
            return@withContext completeWithoutTools(baseUrl, apiKey, model, messages, requestedReasoning, key)
        }

        val first = requestCompletion(baseUrl, apiKey, model, messages, tools, requestedReasoning)
        if (first.isSuccess) {
            toolSupport[key] = true
            if (requestedReasoning != "off") reasoningSupport[key] = true
            return@withContext first
        }

        val firstMessage = first.exceptionOrNull()?.message.orEmpty()

        // Auth failures are never fixed by any fallback — surface them immediately.
        if (NvidiaAuthErrors.isAuthFailure(firstMessage)) {
            reportKeyOutcome(apiKey, first)
            return@withContext first
        }

        // Some deployments reject reasoning_effort independently. Check reasoning
        // BEFORE blaming tools: a reasoning_effort schema error (e.g. "unknown
        // variant `ultra`") must disable only reasoning, never tool support.
        if ((requestedReasoning != "off") && looksLikeReasoningFailure(firstMessage)) {
            val noReasoning = requestCompletion(baseUrl, apiKey, model, messages, tools, "off")
            if (noReasoning.isSuccess) {
                reasoningSupport[key] = false
                return@withContext noReasoning
            }
        }

        // NVIDIA's hosted catalog is model-specific: tool use is an advertised
        // capability, not a universal property of every chat model. Retry without
        // tools first, then without reasoning. This fixes both Function-404 routing
        // failures and NIM 500s caused by unsupported tool/reasoning combinations.
        if (isToolCapabilityFailure(firstMessage)) {
            val noTools = requestCompletion(baseUrl, apiKey, model, messages, null, requestedReasoning)
            if (noTools.isSuccess) {
                toolSupport[key] = false
                if (requestedReasoning != "off") reasoningSupport[key] = true
                return@withContext noTools.map { it.apply { addProperty("_oreshik_tool_fallback", true) } }
            }
            if ((requestedReasoning != "off") && looksLikeReasoningFailure(noTools.exceptionOrNull()?.message.orEmpty())) {
                val plain = requestCompletion(baseUrl, apiKey, model, messages, null, "off")
                if (plain.isSuccess) {
                    toolSupport[key] = false
                    reasoningSupport[key] = false
                    return@withContext plain.map { it.apply { addProperty("_oreshik_tool_fallback", true) } }
                }
            }
        }

        // Last safe compatibility attempt: ordinary chat, no tools, no reasoning.
        // Keep it NON-sticky for tools: the earlier failure may have been transient,
        // and marking toolSupport=false here poisoned every future chat with no
        // tools (the model then fake-calls tools in plain text).
        val plain = requestCompletion(baseUrl, apiKey, model, messages, null, "off")
        if (plain.isSuccess) {
            reasoningSupport[key] = false
            reportKeyOutcome(apiKey, plain)
            return@withContext plain.map { it.apply { addProperty("_oreshik_compat_fallback", true) } }
        }
        first
    }

    /**
     * Reports the outcome to the key manager (when rotation is active) so key
     * health state stays accurate. Success resets failures; retryable failures
     * (429/401/403/5xx) cool the key down and may trigger rotation upstream.
     */
    private suspend fun reportKeyOutcome(callerKey: String, result: Result<JsonObject>) {
        if (keyManager == null) return
        val km = keyManager
        if (result.isSuccess) {
            if (callerKey.isNotBlank()) km.reportSuccess(callerKey)
            return
        }
        val msg = result.exceptionOrNull()?.message.orEmpty()
        val code = km.httpCodeFromMessage(msg)
        if (km.shouldRotate(code, msg) && callerKey.isNotBlank()) {
            km.reportFailure(callerKey, code, msg)
        }
    }

    private suspend fun completeWithoutTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        reasoningEffort: String,
        key: String,
    ): Result<JsonObject> {
        val result = requestCompletion(baseUrl, apiKey, model, messages, null, reasoningEffort)
        if (result.isSuccess) return result
        if ((reasoningEffort != "off") && looksLikeReasoningFailure(result.exceptionOrNull()?.message.orEmpty())) {
            val plain = requestCompletion(baseUrl, apiKey, model, messages, null, "off")
            if (plain.isSuccess) {
                reasoningSupport[key] = false
                return plain
            }
        }
        return result
    }

    private suspend fun requestCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray?,
        reasoningEffort: String,
    ): Result<JsonObject> {
        ProviderEndpointPolicy.error(baseUrl)?.let { error ->
            return Result.failure(IllegalArgumentException(error))
        }
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
                    add("messages", com.example.oreshik.ai.NvidiaSanitizer.sanitizeMessages(messages))
                    addProperty("temperature", 0.1)
                    if ((tools != null) && (tools.size() > 0)) {
                        add("tools", tools)
                        addProperty("tool_choice", "auto")
                    }
                    if ((reasoningEffort.isNotBlank()) && (reasoningEffort != "off")) {
                        // Defensive normalization: NIM accepts only none/minimal/low/
                        // medium/high/xhigh/max. Legacy values like "ultra" are downgraded
                        // to "max" instead of triggering HTTP 400 unknown-variant.
                        addProperty("reasoning_effort", normalizeReasoningEffort(reasoningEffort))
                    }
                }
                // FIX (double-/v1): providers store base URLs WITH /v1
                // ("https:" + "//integrate.api.nvidia.com/v1", "https:" + "//openrouter.ai/api/v1…).
                // Appending another "/v1/chat/completions" produced
                // "/v1/v1/chat/completions" → 404 on every request. Append the
                // path only when the base URL doesn't already end with /v1.
                val base = baseUrl.trimEnd('/')
                val url = if (base.endsWith("/v1") || base.endsWith("/v1/")) "$base/chat/completions"
                          else "$base/v1/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .post(gson.toJson(root).toRequestBody("application/json".toMediaType()))
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.w("AgentApi", "chat $model attempt $attempt → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms: ${body.take(250)}")
                        Result.failure(IllegalStateException(NvidiaAuthErrors.describe(response.code, body)))
                    } else {
                        AppLogger.i("AgentApi", "chat $model → HTTP ${response.code} in ${System.currentTimeMillis() - attemptStart}ms")
                        val parsed = gson.fromJson(body, JsonObject::class.java)
                        if (parsed == null) {
                            AppLogger.w("AgentApi", "chat $model → empty JSON response from inference server")
                            Result.failure(IllegalStateException("Empty JSON response from inference server"))
                        } else {
                            Result.success(parsed)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e("AgentApi", "chat $model attempt $attempt → ${t.javaClass.simpleName}: ${t.message}", t)
                lastException = t
                Result.failure(t)
            }
            
            val usage = result.getOrNull()?.getAsJsonObject("usage")
            val promptTokens = usage?.get("prompt_tokens")?.asLong
                ?: usage?.get("input_tokens")?.asLong ?: 0L
            val completionTokens = usage?.get("completion_tokens")?.asLong
                ?: usage?.get("output_tokens")?.asLong ?: 0L
            ProviderMetrics.record(
                baseUrl = baseUrl,
                model = model,
                latencyMs = System.currentTimeMillis() - attemptStart,
                success = result.isSuccess,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
            if (result.isSuccess) {
                return result
            }
            
            // Check if we should retry (network errors, timeouts, 5xx errors)
            // NOTE: SocketTimeoutException and ConnectException are subclasses of
            // IOException, so the IOException check already covers them; kept as
            // named locals for documentation only.
            val isIoFailure = lastException is java.io.IOException
            @Suppress("USELESS_ELVIS")
            val shouldRetry = (attempt < maxRetries) && (
                isIoFailure ||
                    (result.exceptionOrNull()?.message?.contains("HTTP 5") == true)
                )
            
            if (shouldRetry) {
                // Auto-VPN: on the FIRST retryable failure, if the feature is on,
                // diagnose the network and (when slow/DNS-broken) find+apply a fast
                // free proxy before the next attempt. Single-flight; bounded time.
                if ((attempt == 0) && AgentConfig.autoVpnEnabled) {
                    AppLogger.d("AgentApi", "chat $model → auto-VPN tuning before retry")
                    runCatching { ProxyAutoTuner.ensureTunnel() }
                }
                // Exponential backoff: 1s, 2s, 4s - use delay() which is cancellation-aware
                val delayMs = (1000 * 2.0.pow(attempt.toDouble())).toLong()
                AppLogger.d(TAG, "chat $model attempt $attempt → retrying in ${delayMs}ms")
                try {
                    delay(delayMs.milliseconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    AppLogger.d("AgentApi", "chat $model attempt $attempt cancelled during backoff")
                    return Result.failure(e)
                }
                // Check cancellation after delay
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                continue
            } else {
                return result
            }
        }
        
        AppLogger.e("AgentApi", "chat $model → exhausted $maxRetries retries: ${lastException?.javaClass?.simpleName}: ${lastException?.message?.take(200)}")
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

    /** Clamps reasoning_effort to values NVIDIA NIM accepts on the wire. */
    private fun normalizeReasoningEffort(effort: String): String = when (effort.trim().lowercase()) {
        "none", "minimal", "low", "medium", "high", "xhigh", "max" -> effort.trim().lowercase()
        "ultra" -> "max"
        else -> "high"
    }
}
