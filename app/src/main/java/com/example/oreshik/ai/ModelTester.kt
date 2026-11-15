package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
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
    enum class ModelStatus { ONLINE, OFFLINE, TIMEOUT, PROBING }

    private fun noopProgress(@Suppress("UNUSED_PARAMETER") done: Int, @Suppress("UNUSED_PARAMETER") total: Int, @Suppress("UNUSED_PARAMETER") latest: Model) { /* default no-op */ }

    private val NOOP_PROGRESS: (done: Int, total: Int, latest: Model) -> Unit = ::noopProgress

    /** "providerId::modelId" → probe outcome. */
    private val results = ConcurrentHashMap<String, ProbeResult>()

    data class ProbeResult(
        val ok: Boolean,
        val note: String,
        val latencyMs: Long,
        val atMs: Long = System.currentTimeMillis(),
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
        isTransientNote(m.testNote) -> ModelStatus.TIMEOUT
        else -> ModelStatus.OFFLINE
    }

    internal fun isTransientNote(note: String): Boolean {
        val n = note.lowercase()
        return n.contains("timed out") || n.contains("timeout") ||
                n.contains("rate limited") || n.contains("rate-limit") ||
                n.contains("http 408") || n.contains("http 429") ||
                n.contains("http 504") || n.contains("http 524") ||
                n.contains("network error")
    }

    /** Probe with a cache short-circuit: returns the cached outcome when present. */
    private fun probeCached(providerId: String, m: Model, baseUrl: String, key: String): Model {
        val cacheKey = "$providerId::${m.id}"
        val cached = results[cacheKey]
        if (cached != null) {
            AppLogger.d("ModelTester", "probe cache hit for $cacheKey")
            return m.copy(tested = cached.ok, testNote = cached.note)
        }
        val out = runProbe(providerId, m, baseUrl, key)
        results[cacheKey] = ProbeResult(
            ok = out.tested == true,
            note = out.testNote,
            latencyMs = 0,
            atMs = System.currentTimeMillis(),
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
        try {
            val root = JsonObject().apply {
                addProperty("model", m.id)
                add("messages", probeMessages(m.kind))
                addProperty("max_tokens", if (m.kind == "reasoning") 512 else 16)
                addProperty("temperature", 0.0)
            }
            val body = gson.toJson(root).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .post(body)
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - t0
                if (!response.isSuccessful) {
                    val note = prettyError(response.code, text)
                    AppLogger.i("ModelTester", "$providerId ${m.id} → HTTP ${response.code} in ${ms}ms ($note)")
                    return m.copy(tested = false, testNote = note)
                }
                val parsed = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
                val hasContent = runCatching {
                    val choice = parsed?.getAsJsonArray("choices")?.get(0)?.asJsonObject
                    val msg = choice?.getAsJsonObject("message")
                    (msg != null) && !msg["content"].isJsonNull &&
                            msg["content"].asString.isNotBlank()
                }.getOrDefault(defaultValue = false)
                if (!hasContent) {
                    return m.copy(tested = false, testNote = "empty response")
                }
                AppLogger.i("ModelTester", "$providerId ${m.id} → OK in ${ms}ms")
                return m.copy(tested = true, testNote = "")
            }
        } catch (t: Throwable) {
            AppLogger.w("ModelTester", "$providerId ${m.id} probe failed: ${t.message}")
            return m.copy(tested = false, testNote = t.message?.take(80) ?: "network error")
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
                mapOf("id" to m.id, "tested" to m.tested, "note" to m.testNote),
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
            val byId = list.associate { (it["id"] as? String ?: "") to (it["tested"] as? Boolean) }
            val notes = list.associate { (it["id"] as? String ?: "") to (it["note"] as? String ?: "") }
            models.map { m ->
                val tested = byId[m.id]
                if (tested != null) m.copy(tested = tested, testNote = notes[m.id].orEmpty()) else m
            }
        } catch (_: Exception) {
            models
        }
    }
}
