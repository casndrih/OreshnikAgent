package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Live "does this model actually respond?" testing.
 *
 * Catalog listings routinely include models that a given account cannot use:
 * paid-only models (HTTP 402/403), rate-limited or restricted endpoints
 * (429), deprecated/unavailable models (404/500)… This class sends a tiny,
 * cheap probe request to each model and records whether it truly responds.
 *
 * The probe is kind-aware (from [Model.kind]):
 *  - vision     → asks for a 1×1 PNG description (exercises image input)
 *  - reasoning  → a tiny reasoning-style question (higher max_tokens)
 *  - coding     → a tiny code answer
 *  - general    → a trivial factual question
 *
 * Results are cached per provider+model so repeated UI refreshes are cheap.
 */
object ModelTester {

    /**
     * Live availability status for the model-picker dots:
     *  - ONLINE  → probe responded (green)
     *  - OFFLINE → probe failed (red)
     *  - TIMEOUT → timed out / rate-limited / transient provider error (orange)
     *  - PROBING → not probed yet, still being checked (gray, pulsing)
     */
    enum class ModelStatus { ONLINE, OFFLINE, TIMEOUT, PROBING, RESTRICTED, UNAVAILABLE }

    private fun noopProgress(@Suppress("UNUSED_PARAMETER") done: Int, @Suppress("UNUSED_PARAMETER") total: Int, @Suppress("UNUSED_PARAMETER") latest: Model) { /* default no-op */ }

    private val NOOP_PROGRESS: (done: Int, total: Int, latest: Model) -> Unit = ::noopProgress

    /** "providerId::modelId" → probe outcome. */
    private val results = ConcurrentHashMap<String, ProbeResult>()

    data class ProbeResult(
        val ok: Boolean,
        val note: String,
        val latencyMs: Long,
        val atMs: Long = System.currentTimeMillis(),
        val reasoningVerified: Boolean? = null,
        val thinkingLevels: List<String> = emptyList(),
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(retryOnConnectionFailure = true)
        .build()

    private val gson = com.google.gson.Gson()

    /** Builds the cheapest valid message array for the model's kind. */
    internal fun probeMessages(kind: String): JsonArray {
        val userText = when (kind) {
            "reasoning" -> "If all roses are flowers and some flowers fade quickly, can some roses fade quickly? Answer yes or no in one word."
            "coding" -> "Reply with one line of Python that prints hello."
            else -> "What is 2+2? Answer with the number only."
        }
        val arr = JsonArray()
        if (kind != "vision") {
            val systemMessage = JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", "You answer briefly.")
            }
            arr.add(systemMessage)
        }
        arr.add(
            JsonObject().apply {
                addProperty("role", "user")
                if (kind == "vision") add("content", visionContent()) else addProperty("content", userText)
            },
        )
        return arr
    }

    /** 1×1 transparent PNG as OpenAI-style multimodal content — smallest valid image payload. */
    private fun visionContent(): JsonArray {
        val png1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val arr = JsonArray()
        arr.add(
            JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "What does this image show? Answer in one short sentence.")
            },
        )
        arr.add(
            JsonObject().apply {
                addProperty("type", "image_url")
                add(
                    "image_url",
                    JsonObject().apply {
                        addProperty("url", "data:image/png;base64,$png1x1")
                    },
                )
            },
        )
        return arr
    }

    /**
     * Maps a model's last probe outcome to a UI status for the status dots.
     * Failed probes whose note looks transient (timeout, rate-limit, 4xx/5xx
     * gateway codes, network hiccups) map to TIMEOUT instead of OFFLINE.
     */
    fun statusFor(m: Model): ModelStatus = when {
        m.tested == true -> ModelStatus.ONLINE
        m.tested == null -> ModelStatus.PROBING
        isRestrictedNote(m.testNote) -> ModelStatus.RESTRICTED
        isUnavailableNote(m.testNote) -> ModelStatus.UNAVAILABLE
        isTransientNote(m.testNote) -> ModelStatus.TIMEOUT
        else -> ModelStatus.OFFLINE
    }

    internal fun isRestrictedNote(note: String): Boolean {
        val n = note.lowercase()
        return listOf("http 401", "http 402", "http 403", "invalid key", "paid account",
            "not allowed for this key", "unauthorized", "forbidden", "insufficient credits").any(n::contains)
    }

    internal fun isUnavailableNote(note: String): Boolean {
        val n = note.lowercase()
        return listOf("http 404", "http 410", "model not found", "not found on provider",
            "unknown model", "model is deprecated", "model unavailable").any(n::contains)
    }

    internal fun isTransientNote(note: String): Boolean {
        val n = note.lowercase()
        return n.contains("timed out") || n.contains("timeout") ||
                n.contains("rate limited") || n.contains("rate-limit") ||
                n.contains("http 408") || n.contains("http 429") ||
                Regex("http 5[0-9]{2}").containsMatchIn(n) ||
                n.contains("network error") || n.contains("connection reset") ||
                n.contains("temporarily unavailable")
    }

    /** Probe with a cache short-circuit: returns the cached outcome when present. */
    private fun probeCached(providerId: String, m: Model, baseUrl: String, key: String): Model {
        val cacheKey = "$providerId::${baseUrl.trimEnd('/')}::${m.id}::${key.hashCode()}"
        val cached = results[cacheKey]
        if (cached != null) {
            val ttl = if (cached.ok) ONLINE_CACHE_TTL_MS else FAILURE_CACHE_TTL_MS
            if (System.currentTimeMillis() - cached.atMs < ttl) {
                AppLogger.d("ModelTester", "probe cache hit for $providerId/${m.id}")
                return m.copy(
                    tested = cached.ok,
                    testNote = cached.note,
                    reasoningVerified = cached.reasoningVerified ?: m.reasoningVerified,
                    thinkingLevels = cached.thinkingLevels.takeIf { it.isNotEmpty() } ?: m.thinkingLevels,
                )
            }
            results.remove(cacheKey, cached)
        }
        val out = runProbe(providerId, m, baseUrl, key)
        results[cacheKey] = ProbeResult(
            ok = out.tested == true,
            note = out.testNote,
            latencyMs = 0,
            atMs = System.currentTimeMillis(),
            reasoningVerified = out.reasoningVerified,
            thinkingLevels = out.thinkingLevels,
        )
        return out
    }

    /** Probes one model; returns a copy with [Model.tested]/[Model.testNote] set. */
    @Suppress("unused") // Public API used by future provider-test UI flows.
    suspend fun probe(
        providerId: String,
        m: Model,
        baseUrl: String,
        key: String,
    ): Model = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let { error ->
            return@withContext m.copy(tested = false, testNote = error)
        }
        probeCached(providerId, m, baseUrl, key)
    }

    private fun runProbe(providerId: String, m: Model, baseUrl: String, key: String): Model {
        val t0 = System.currentTimeMillis()
        val probeEffort = m.thinkingLevels.firstOrNull { !it.equals("off", true) } ?: "low"
        try {
            val openAiReasoner = Regex("(^|[/_-])o[134]([_-]|$)").containsMatchIn(m.id.lowercase())
            val root = JsonObject().apply {
                addProperty("model", m.id)
                add("messages", probeMessages(m.kind))
                if (openAiReasoner) addProperty("max_completion_tokens", 96)
                else addProperty("max_tokens", if (m.kind == "reasoning") 96 else 16)
                // OpenAI o-series models reject temperature; NVIDIA/other reasoners accept it.
                if (!openAiReasoner) addProperty("temperature", 0.0)
                // Test the actual reasoning parameter, not just ordinary chat availability.
                if (m.reasoning) addProperty("reasoning_effort", probeEffort)
            }
            val body = gson.toJson(root).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(completionUrl(baseUrl))
                .post(body)
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - t0
                if (!response.isSuccessful) {
                    val lowerBody = text.lowercase()
                    val reasoningRejected = m.reasoning && response.code in 400..499 &&
                        (lowerBody.contains("reasoning_effort") || lowerBody.contains("reasoning effort") ||
                         lowerBody.contains("unknown parameter") || lowerBody.contains("unsupported parameter") ||
                         lowerBody.contains("unrecognized request argument") ||
                         (lowerBody.contains("unknown variant") && (lowerBody.contains("effort") || lowerBody.contains("low") || lowerBody.contains("high"))))
                    if (reasoningRejected) {
                        val plainRoot = root.deepCopy().apply { remove("reasoning_effort") }
                        val plainRequest = Request.Builder()
                            .url(completionUrl(baseUrl))
                            .post(gson.toJson(plainRoot).toRequestBody("application/json".toMediaType()))
                            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                            .build()
                        http.newCall(plainRequest).execute().use { plainResponse ->
                            val plainText = plainResponse.body?.string().orEmpty()
                            val plainHasAnswer = runCatching {
                                val parsed = gson.fromJson(plainText, JsonObject::class.java)
                                val msg = parsed?.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("message")
                                val content = msg?.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                                val reasoning = msg?.get("reasoning_content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                                content.isNotBlank() || reasoning.isNotBlank()
                            }.getOrDefault(false)
                            if (plainResponse.isSuccessful && plainHasAnswer) {
                                return m.copy(
                                    tested = true,
                                    testNote = "online; reasoning effort unsupported",
                                    reasoningVerified = false,
                                    thinkingLevels = listOf("off"),
                                )
                            }
                        }
                    }
                    val note = prettyError(response.code, text)
                    AppLogger.i("ModelTester", "$providerId ${m.id} → HTTP ${response.code} in ${ms}ms ($note)")
                    return m.copy(tested = false, testNote = note)
                }
                val parsed = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
                val hasContent = runCatching {
                    val choice = parsed?.getAsJsonArray("choices")?.get(0)?.asJsonObject
                    val msg = choice?.getAsJsonObject("message")
                    val content = msg?.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    val reasoning = msg?.get("reasoning_content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    content.isNotBlank() || reasoning.isNotBlank()
                }.getOrDefault(defaultValue = false)
                if (!hasContent) {
                    return m.copy(tested = false, testNote = "empty response")
                }
                AppLogger.i("ModelTester", "$providerId ${m.id} → OK in ${ms}ms")
                return m.copy(
                    tested = true,
                    testNote = "",
                    reasoningVerified = if (m.reasoning) true else m.reasoningVerified,
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLogger.w("ModelTester", "$providerId ${m.id} probe failed: ${t.message}")
            return m.copy(tested = false, testNote = t.message?.take(80) ?: "network error")
        }
    }

    private const val ONLINE_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val FAILURE_CACHE_TTL_MS = 45 * 1000L

    private fun completionUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    /**
     * Probes models with bounded parallelism, reporting progress via [onProgress].
     * API keys rotate round-robin across probes so multi-key accounts spread load.
     */
    suspend fun probeAll(
        providerId: String,
        models: List<Model>,
        baseUrl: String,
        keys: List<String>,
        /** When true, a cached probe outcome short-circuits the network call. */
        useCache: Boolean = false,
        onProgress: (done: Int, total: Int, latest: Model) -> Unit = NOOP_PROGRESS,
    ): List<Model> = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let { error ->
            val rejected = models.map { it.copy(tested = false, testNote = error) }
            rejected.forEachIndexed { index, model -> onProgress(index + 1, rejected.size, model) }
            return@withContext rejected
        }
        val total = models.size
        var done = 0
        val usableKeys = keys.filter { it.isNotBlank() }
        val out = java.util.Collections.synchronizedList(ArrayList<Model>(total))
        val gate = Semaphore(6) // bounded parallelism to avoid tripping provider rate limits
        coroutineScope {
            models.mapIndexed { index, m ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val key = if (usableKeys.isEmpty()) "" else usableKeys[index % usableKeys.size]
                        val probed = if (useCache) probeCached(providerId, m, baseUrl, key)
                                     else runProbe(providerId, m, baseUrl, key)
                        out.add(probed)
                        synchronized(out) { done++ }
                        onProgress(done, total, probed)
                    }
                }
            }.awaitAll()
        }
        // Sort via indexed loop (avoids Sequence/destructuring hints).
        val sorted = ArrayList<Model>(out)
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            var j = i - 1
            while ((j >= 0) && (sorted[j].id.lowercase() > cur.id.lowercase())) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = cur
        }
        sorted
    }

    /** True when the model list contains live-test results (all tested != null). Public API kept for future UI use. */
    @Suppress("unused")
    fun hasTestResults(models: List<Model>): Boolean {
        if (models.isEmpty()) return false
        for (i in models.indices) {
            if (models[i].tested == null) return false
        }
        return true
    }

    internal fun prettyError(code: Int, body: String): String = when (code) {
        402 -> "paid account required"
        403 -> "not allowed for this key"
        429 -> "rate limited / quota"
        404 -> "model not found on provider"
        401 -> "invalid key"
        in 500..599 -> "provider error (HTTP $code)"
        else -> "HTTP $code" + (body.take(60).takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }

    /** Public API kept for settings/debug screens that refresh probe results. */
    @Suppress("unused")
    fun clearCache(providerId: String? = null) {
        if (providerId == null) results.clear()
        else results.keys.removeAll { it.startsWith("$providerId::") }
    }

    /**
     * Persists per-model probe outcomes (tested + note) for a provider, shared by
     * every model picker so dots survive process restarts.
     */
    fun persistResults(providerId: String, models: List<Model>) {
        val gson = com.google.gson.Gson()
        val payload = models.mapNotNull { m ->
            if (m.tested == null) return@mapNotNull null
            gson.toJsonTree(
                mapOf(
                    "id" to m.id,
                    "tested" to m.tested,
                    "note" to m.testNote,
                    "reasoningVerified" to m.reasoningVerified,
                    "thinkingLevels" to m.thinkingLevels,
                    "atMs" to System.currentTimeMillis(),
                ),
            )
        }
        AgentConfig.putRaw("modeltest_$providerId", gson.toJson(payload))
    }

    /** Overlays previously persisted probe outcomes onto a freshly fetched catalog. */
    fun overlaySavedResults(providerId: String, models: List<Model>): List<Model> {
        val raw = AgentConfig.getRaw("modeltest_$providerId")
        if (raw.isBlank()) return models
        val gson = com.google.gson.Gson()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = gson.fromJson(raw, type) ?: return models
            val now = System.currentTimeMillis()
            val fresh = list.filter { item ->
                val at = (item["atMs"] as? Number)?.toLong() ?: 0L
                val ok = item["tested"] as? Boolean ?: false
                val ttl = if (ok) ONLINE_CACHE_TTL_MS else FAILURE_CACHE_TTL_MS
                at > 0L && now - at in 0..ttl
            }
            val byId = fresh.associate { (it["id"] as? String ?: "") to (it["tested"] as? Boolean) }
            val notes = fresh.associate { (it["id"] as? String ?: "") to (it["note"] as? String ?: "") }
            val reasoning = fresh.associate { (it["id"] as? String ?: "") to (it["reasoningVerified"] as? Boolean) }
            val levels = fresh.associate { (it["id"] as? String ?: "") to (it["thinkingLevels"] as? List<*>)?.mapNotNull { value -> value as? String } }
            models.map { m ->
                val tested = byId[m.id]
                if (tested != null) m.copy(
                    tested = tested,
                    testNote = notes[m.id].orEmpty(),
                    reasoningVerified = reasoning[m.id] ?: m.reasoningVerified,
                    thinkingLevels = levels[m.id]?.takeIf { it.isNotEmpty() } ?: m.thinkingLevels,
                ) else m
            }
        } catch (_: Exception) {
            models
        }
    }
}
