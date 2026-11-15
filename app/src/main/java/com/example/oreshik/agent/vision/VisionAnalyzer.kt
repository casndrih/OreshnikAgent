package com.example.oreshik.agent.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.oreshik.ai.AiProvider
import com.example.oreshik.ai.ProviderManager
import com.example.oreshik.ai.ProviderRegistry
import com.example.oreshik.ai.ProviderEndpointPolicy
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Provider-aware vision analysis for Android computer-use workflows.
 *
 * Screenshots are sent to the active provider first, then to other configured
 * providers as fallbacks. Each route uses its own endpoint and credentials;
 * credentials are never copied between providers. Known vision-language models
 * are preferred, and unknown candidates must pass a small image-capability probe
 * before they are used for a full screenshot request.
 *
 * Work is bounded to keep the UI responsive:
 * - No more than [MAX_VALIDATE_PROBES] uncached validation probes per analysis.
 * - No more than [MAX_REAL_ATTEMPTS] full screenshot requests per provider route.
 * - Validation results are cached for the lifetime of this process.
 *
 * When every route fails, callers can fall back to the accessibility tree.
 */
object VisionAnalyzer {

    private const val TAG = "VisionAnalyzer"
    private const val MAX_IMAGE_DIMENSION = 1280
    private const val JPEG_QUALITY = 80
    private const val MAX_ROUTES = 3

    /** Maximum number of uncached, low-cost image validation requests per analysis. */
    private const val MAX_VALIDATE_PROBES = 4

    /** Maximum full-size screenshot requests allowed for each provider route. */
    private const val MAX_REAL_ATTEMPTS = 2

    /** Direct NVIDIA fallback. Used only with a credential that starts with nvapi-. */
    private const val NVIDIA_FALLBACK_BASE = "https:" + "//integrate.api.nvidia.com/v1"

    private val gsonInstance = Gson()

    /** Short timeouts for model discovery and tiny validation requests. */
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Dedicated direct client for screenshot uploads and vision completions. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** A provider endpoint and the credential belonging to that provider only. */
    private data class Route(
        val providerId: String,
        val name: String,
        /** API base URL, normally ending in `/v1`, with no trailing slash. */
        val baseUrl: String,
        val apiKey: String,
    )

    /** A per-call budget shared by all provider routes. */
    private data class ProbeBudget(var used: Int = 0)

    /** Resolved model selected for each provider. */
    private val cachedModel = ConcurrentHashMap<String, String>()

    /** Cached capability results keyed by `providerId::model`. */
    private val validatedOk = ConcurrentHashMap.newKeySet<String>()
    private val validatedFail = ConcurrentHashMap.newKeySet<String>()

    /** Cursor used to avoid repeatedly probing the same catalog candidates. */
    private val candidateCursor = ConcurrentHashMap<String, Int>()

    data class Analysis(
        val ok: Boolean,
        /** Plain-language observations produced from the screenshot. */
        val observations: String,
        /** Suggested next action, if the model provided one. */
        val nextAction: String? = null,
        /** Suggested tap target in the analyzed image's pixel coordinates. */
        val tapX: Int? = null,
        val tapY: Int? = null,
        /** Optional swipe endpoints in analyzed-image pixel coordinates. */
        val swipeFromX: Int? = null,
        val swipeFromY: Int? = null,
        val swipeToX: Int? = null,
        val swipeToY: Int? = null,
        /** Optional action duration in milliseconds. */
        val durationMs: Int? = null,
        /** Structured progress and stop signals, when provided by the model. */
        val goalProgress: String? = null,
        val failDetected: Boolean? = null,
        /** Compact text suitable for appending to an agent tool result. */
        val report: String = "",
        val error: String? = null,
        /** Provider route that returned the answer, for UI display. */
        val viaProvider: String = "",
    )

    /**
     * Analyze [bitmap] through the active provider, with configured providers as
     * fallbacks. [onProgress] receives short status messages on the caller's
     * thread; it is intentionally a regular, non-suspending callback.
     */
    fun analyze(
        bitmap: Bitmap,
        question: String,
        onProgress: ((String) -> Unit)? = null
    ): Analysis {
        if (ProviderManager.isOfflineMode()) {
            return Analysis(
                ok = false,
                observations = "",
                error = "Screenshot analysis uses a vision provider and is unavailable while Offline GGUF mode is active.",
            )
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return Analysis(ok = false, observations = "", error = "Invalid screenshot bitmap")
        }

        val startedAt = System.currentTimeMillis()
        val probeBudget = ProbeBudget()
        var lastError = "No vision route available"

        for (route in buildRoutes().take(MAX_ROUTES)) {
            onProgress?.invoke("Vision: asking ${route.name}…")
            var attemptsUsed = 0
            val attemptedModels = mutableSetOf<String>()

            while (attemptsUsed < MAX_REAL_ATTEMPTS) {
                val model = nextModel(route, probeBudget, attemptedModels)
                if (model == null) {
                    lastError = "${route.name}: no working vision model"
                    AppLogger.w(TAG, "No usable vision model for ${route.name}")
                    break
                }

                attemptedModels += model
                onProgress?.invoke("Vision: $model (${route.name})…")
                val responseText = request(route, model, bitmap, question)
                if (responseText != null) {
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    AppLogger.i(
                        TAG,
                        "Vision succeeded: model=$model, provider=${route.name}, elapsed=${elapsedMs}ms, probes=${probeBudget.used}",
                    )
                    return parseAnalysis(responseText, model).copy(viaProvider = route.name)
                }

                attemptsUsed++
                lastError = "${route.name}/$model request failed"
                AppLogger.w(TAG, "Vision request failed: model=$model, provider=${route.name}")
                cachedModel.remove(route.providerId, model)
            }
        }

        AppLogger.w(
            TAG,
            "Vision analysis exhausted all routes: $lastError " +
                    "(probes=${probeBudget.used}, elapsed=${System.currentTimeMillis() - startedAt}ms)",
        )
        return Analysis(ok = false, observations = "", error = lastError)
    }

    /** Compact one-line description used alongside accessibility reads. */
    fun describeScreen(bitmap: Bitmap): String {
        val outcome = analyze(
            bitmap,
            "Describe this phone screen in 2-3 short sentences: visible app, key UI elements, anything blocking interaction.",
        )

        return if (outcome.ok) {
            buildString {
                append("[Vision] ").append(outcome.observations)
                outcome.nextAction
                    ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                    ?.let { append(" Suggested next: ").append(it).append('.') }
            }
        } else {
            "[Vision] unavailable: ${outcome.error.orEmpty().take(120)}"
        }
    }

    // ---------------------------------------------------------------------
    // Provider routing
    // ---------------------------------------------------------------------

    /**
     * Route order: active provider, remaining configured providers, then the
     * direct NVIDIA fallback (only when a genuine NVIDIA API key is available).
     */
    private fun buildRoutes(): List<Route> {
        val routes = mutableListOf<Route>()
        val activeProvider = runCatching { ProviderManager.getActiveProvider() }.getOrNull()

        if (activeProvider != null) {
            val activeRoute = routeOf(activeProvider)
            if (ProviderEndpointPolicy.error(activeRoute.baseUrl) != null) {
                AppLogger.w(TAG, "Skipping insecure vision endpoint for ${activeRoute.name}")
            } else if (activeRoute.providerId != "nvidia" || activeRoute.apiKey.startsWith("nvapi-")) {
                routes += activeRoute
                AppLogger.d(
                    TAG,
                    "Primary vision route: ${activeRoute.name} (${activeRoute.providerId})"
                )
            } else {
                AppLogger.w(TAG, "Skipping NVIDIA route: no nvapi- credential is configured")
            }
        } else {
            AppLogger.w(TAG, "No active provider is available for vision routing")
        }

        val providers = runCatching { ProviderRegistry.getAllProviders() }
            .getOrDefault(emptyList())

        for (provider in providers) {
            if (provider.id == "nvidia" || routes.any { it.providerId == provider.id }) continue

            val candidate = routeOf(provider)
            if (ProviderEndpointPolicy.error(candidate.baseUrl) != null) {
                AppLogger.w(TAG, "Skipping insecure vision endpoint for ${candidate.name}")
            } else if (candidate.apiKey.isNotBlank()) {
                routes += candidate
            } else {
                AppLogger.d(
                    TAG,
                    "Skipping unconfigured provider route: ${provider.displayName} (no API key)"
                )
            }
        }

        if (routes.none { it.providerId == "nvidia" }) {
            val nvidiaKey = runCatching {
                ProviderRegistry.getProvider("nvidia")
                    ?.getApiKeys()
                    .orEmpty()
                    .firstOrNull { it.startsWith("nvapi-") }
            }.getOrNull().orEmpty()

            if (nvidiaKey.isNotBlank()) {
                routes += Route(
                    providerId = "nvidia",
                    name = "NVIDIA NIM",
                    baseUrl = NVIDIA_FALLBACK_BASE,
                    apiKey = nvidiaKey,
                )
                AppLogger.d(TAG, "Added direct NVIDIA fallback route")
            }
        }

        return routes
    }

    private fun routeOf(provider: AiProvider): Route {
        val activeKey = provider.getActiveKey().orEmpty()
        val keys = provider.getApiKeys()
        val apiKey = if (provider.id == "nvidia") {
            (sequenceOf(activeKey) + keys.asSequence())
                .firstOrNull { it.startsWith("nvapi-") }
                .orEmpty()
        } else {
            activeKey.takeIf { it.isNotBlank() } ?: keys.firstOrNull().orEmpty()
        }

        return Route(
            providerId = provider.id,
            name = provider.displayName,
            baseUrl = provider.baseUrl.trimEnd('/'),
            apiKey = apiKey,
        )
    }

    // ---------------------------------------------------------------------
    // Model discovery and capability validation
    // ---------------------------------------------------------------------

    /**
     * Select the next usable model: configured override for the active provider,
     * cached selection, then catalog candidates. Uncached candidates are probed
     * only while this analysis still has validation budget available.
     */
    private fun nextModel(
        route: Route,
        probeBudget: ProbeBudget,
        attemptedModels: Set<String>,
    ): String? {
        val activeProviderId = runCatching { ProviderManager.getActiveProvider().id }.getOrNull()
        val configuredModel = AgentConfig.visionModel.takeIf {
            route.providerId == activeProviderId && it.isNotBlank()
        }

        if (configuredModel != null) {
            when {
                !isVlModel(configuredModel) -> AppLogger.w(
                    TAG,
                    "Configured vision model '$configuredModel' is not recognized as a vision-language model; checking the catalog",
                )

                configuredModel in attemptedModels -> Unit
                validatedFail.contains(cacheKey(route, configuredModel)) -> AppLogger.w(
                    TAG,
                    "Configured vision model '$configuredModel' failed its capability check; checking the catalog",
                )

                validatedOk.contains(cacheKey(route, configuredModel)) -> return configuredModel
                probeBudget.used < MAX_VALIDATE_PROBES -> {
                    probeBudget.used++
                    if (validateModel(route, configuredModel)) {
                        cachedModel[route.providerId] = configuredModel
                        return configuredModel
                    }
                }
            }
        }

        cachedModel[route.providerId]?.let { cached ->
            if (cached !in attemptedModels && !validatedFail.contains(cacheKey(route, cached))) {
                if (validatedOk.contains(cacheKey(route, cached))) return cached
                if (probeBudget.used < MAX_VALIDATE_PROBES) {
                    probeBudget.used++
                    if (validateModel(route, cached)) return cached
                    cachedModel.remove(route.providerId, cached)
                }
            }
        }

        val candidates = fetchCandidates(route)
        if (candidates.isEmpty()) {
            AppLogger.w(
                TAG,
                "No model candidates found for ${route.name} at ${route.baseUrl}/models"
            )
            return null
        }

        var cursor = candidateCursor.getOrDefault(route.providerId, 0).coerceIn(0, candidates.size)
        while (cursor < candidates.size) {
            val model = candidates[cursor]
            val key = cacheKey(route, model)

            if (model in attemptedModels || validatedFail.contains(key)) {
                cursor++
                continue
            }

            if (validatedOk.contains(key)) {
                candidateCursor[route.providerId] = cursor + 1
                cachedModel[route.providerId] = model
                return model
            }

            if (probeBudget.used >= MAX_VALIDATE_PROBES) {
                // Keep this candidate as the next one to inspect on the next call.
                candidateCursor[route.providerId] = cursor
                return null
            }

            cursor++
            candidateCursor[route.providerId] = cursor
            probeBudget.used++
            if (validateModel(route, model)) {
                cachedModel[route.providerId] = model
                AppLogger.i(TAG, "Vision model selected for ${route.name}: $model")
                return model
            }
        }

        // Reset only after reaching the end, so transient provider failures can be retried later.
        candidateCursor[route.providerId] = 0
        return null
    }

    /** Common model-name markers for vision-language/chat models. */
    private val VL_MARKERS = listOf(
        "-vl", "vlm", "vision", "vila", "llava", "fuyu", "internvl",
        "minicpm-v", "paligemma", "idefics", "molmo", "pixtral", "pixtrer",
        "gemma-3", "gemma-4", "glm-4v", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
        "qwen3-vl", "deepseek-vl", "cogvlm", "grok-vision", "grok-2-vision",
        "ui-tars", "hunyuan-vision", "step-1v", "llama-3.2-vision",
        "gpt-4o", "gpt-4.1", "gpt-4-vision", "claude-3", "claude-4", "gemini",
    )

    /** Exclusions prevent non-chat models from being selected by name alone. */
    private val NON_VL_MARKERS = listOf(
        "embed", "rerank", "guard", "clip", "diffusion", "stable-diffusion",
        "sdxl", "whisper", "tts", "moderation", "ocr", "codestral",
        "devstral", "mathstral",
    )

    /** A conservative name check; the image probe remains the final capability check. */
    private fun isVlModel(id: String): Boolean {
        val normalized = id.lowercase()
        if (NON_VL_MARKERS.any(normalized::contains)) return false
        return VL_MARKERS.any(normalized::contains)
    }

    private fun fetchCandidates(route: Route): List<String> {
        return try {
            val request = Request.Builder()
                .url("${route.baseUrl}/models")
                .get()
                .apply {
                    if (route.apiKey.isNotBlank()) header("Authorization", "Bearer ${route.apiKey}")
                }
                .build()

            val startedAt = System.currentTimeMillis()
            probeHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(
                        TAG,
                        "Model catalog failed for ${route.name}: HTTP ${response.code} " +
                                "(${System.currentTimeMillis() - startedAt}ms)",
                    )
                    return emptyList()
                }

                val responseBody = response.body?.string().orEmpty()
                val root =
                    JsonParser.parseString(responseBody).takeIf { it.isJsonObject }?.asJsonObject
                        ?: return emptyList()
                val ids = root.getAsJsonArray("data")
                    ?.mapNotNull { entry ->
                        runCatching {
                            entry.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                                ?.takeIf(String::isNotBlank)
                        }.getOrNull()
                    }
                    .orEmpty()
                    .distinct()

                val visionCandidates = ids
                    .filter(::isVlModel)
                    .sortedByDescending { it.endsWith(":free", ignoreCase = true) }
                val remainingCandidates = ids
                    .asSequence()
                    .filterNot(::isVlModel)
                    .sortedByDescending { it.endsWith(":free", ignoreCase = true) }
                    .take(30)
                    .toList()

                // Prefer recognizable VL models, but allow an unknown model to
                // qualify through the explicit image-capability probe.
                val orderedCandidates = (visionCandidates + remainingCandidates).distinct()
                AppLogger.d(
                    TAG,
                    "Model catalog ${route.name}: ${ids.size} total, ${visionCandidates.size} named VL " +
                            "(${System.currentTimeMillis() - startedAt}ms)",
                )
                orderedCandidates
            }
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "Model catalog error on ${route.name}: ${e.javaClass.simpleName}: ${e.message}"
            )
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // Vision request and model validation
    // ---------------------------------------------------------------------

    /** Send one full screenshot request. Returns assistant content or null. */
    private fun request(route: Route, model: String, bitmap: Bitmap, question: String): String? {
        val encodedImage = encode(bitmap) ?: run {
            AppLogger.w(TAG, "Could not encode screenshot for vision request")
            return null
        }
        val imageDataUrl = "data:image/jpeg;base64,$encodedImage"

        val content = JsonArray().apply {
            add(imageContent(imageDataUrl))
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt(question))
            })
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
            addProperty("temperature", 0.1)
            addProperty("max_tokens", 300)
        }

        AppLogger.i(
            TAG,
            "Sending screenshot to model=$model via provider=${route.name} (${bitmap.width}x${bitmap.height})"
        )
        val startedAt = System.currentTimeMillis()

        return try {
            val request = Request.Builder()
                .url("${route.baseUrl}/chat/completions")
                .post(gson().toJson(payload).toRequestBody("application/json".toMediaType()))
                .apply {
                    if (route.apiKey.isNotBlank()) header("Authorization", "Bearer ${route.apiKey}")
                }
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsedMs = System.currentTimeMillis() - startedAt

                if (!response.isSuccessful) {
                    AppLogger.w(
                        TAG,
                        "Vision HTTP ${response.code} from ${route.name} ($model) in ${elapsedMs}ms: ${
                            body.take(
                                200
                            )
                        }",
                    )
                    if (response.code == 400 || response.code == 403 || response.code == 404) {
                        validatedFail.add(cacheKey(route, model))
                    }
                    return null
                }

                val parsed = runCatching { JsonParser.parseString(body) }
                    .getOrNull()
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                val text = parsed?.let(::extractCompletionText).orEmpty()

                if (text.isBlank()) {
                    AppLogger.w(
                        TAG,
                        "Empty vision response from ${route.name} ($model) in ${elapsedMs}ms"
                    )
                    null
                } else {
                    validatedOk.add(cacheKey(route, model))
                    text
                }
            }
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "Vision request error via ${route.name} ($model): ${e.javaClass.simpleName}: ${e.message} " +
                        "after ${System.currentTimeMillis() - startedAt}ms",
            )
            null
        }
    }

    private fun imageContent(dataUrl: String): JsonObject = JsonObject().apply {
        addProperty("type", "image_url")
        add("image_url", JsonObject().apply { addProperty("url", dataUrl) })
    }

    /** Extract text while tolerating APIs that return content as text-part arrays. */
    private fun extractCompletionText(response: JsonObject): String {
        val message = response.getAsJsonArray("choices")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.getAsJsonObject("message")
            ?: return ""

        val content = message.get("content") ?: return ""
        if (content.isJsonNull) return ""
        if (content.isJsonPrimitive) return runCatching { content.asString }.getOrDefault("")
        if (!content.isJsonArray) return ""

        return content.asJsonArray.mapNotNull { part ->
            runCatching {
                part.asJsonObject.get("text")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }.getOrNull()
        }.joinToString(" ").trim()
    }

    /** Low-cost 1×1 image probe. The result is cached per provider/model pair. */
    private fun validateModel(route: Route, model: String): Boolean {
        val key = cacheKey(route, model)
        if (validatedOk.contains(key)) return true
        if (validatedFail.contains(key)) return false

        return try {
            val testBitmap = createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val compressedBytes = ByteArrayOutputStream().use { output ->
                try {
                    testBitmap.eraseColor(Color.WHITE)
                    if (testBitmap.compress(Bitmap.CompressFormat.JPEG, 10, output)) {
                        output.toByteArray()
                    } else {
                        ByteArray(0)
                    }
                } finally {
                    testBitmap.recycle()
                }
            }
            if (compressedBytes.isEmpty()) {
                validatedFail.add(key)
                AppLogger.w(TAG, "Could not create validation image for $model on ${route.name}")
                return false
            }

            val dataUrl =
                "data:image/jpeg;base64,${Base64.encodeToString(compressedBytes, Base64.NO_WRAP)}"
            val payload = JsonObject().apply {
                addProperty("model", model)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", JsonArray().apply {
                            add(imageContent(dataUrl))
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", "reply with ok")
                            })
                        })
                    })
                })
                addProperty("max_tokens", 5)
            }

            val startedAt = System.currentTimeMillis()
            val request = Request.Builder()
                .url("${route.baseUrl}/chat/completions")
                .post(gson().toJson(payload).toRequestBody("application/json".toMediaType()))
                .apply {
                    if (route.apiKey.isNotBlank()) header("Authorization", "Bearer ${route.apiKey}")
                }
                .build()

            probeHttp.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    validatedOk.add(key)
                    AppLogger.i(
                        TAG,
                        "Vision probe passed: $model on ${route.name} (${System.currentTimeMillis() - startedAt}ms)"
                    )
                    true
                } else {
                    validatedFail.add(key)
                    AppLogger.w(
                        TAG,
                        "Vision probe failed: HTTP ${response.code}, model=$model, provider=${route.name}, " +
                                "elapsed=${System.currentTimeMillis() - startedAt}ms",
                    )
                    false
                }
            }
        } catch (e: Exception) {
            validatedFail.add(key)
            AppLogger.w(
                TAG,
                "Vision probe error for $model on ${route.name}: ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    // ---------------------------------------------------------------------
    // Response parsing and screenshot preparation
    // ---------------------------------------------------------------------

    private fun parseAnalysis(text: String, model: String): Analysis {
        val fallbackText = text.take(400)
        val jsonText = extractJson(text) ?: return Analysis(
            ok = true,
            observations = fallbackText,
            report = "[Vision/$model] $fallbackText",
        )

        val json = runCatching { JsonParser.parseString(jsonText) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return Analysis(
                ok = true,
                observations = fallbackText,
                report = "[Vision/$model] $fallbackText",
            )

        val observations = json.stringValue("observations").take(300)
        // Accept both keys because existing task-specific prompts use "action"
        // while the shared vision prompt and older callers use "next_action".
        val nextAction = json.stringValue("next_action")
            .ifBlank { json.stringValue("action") }
            .takeIf(String::isNotBlank)
        val tapTarget = json.get("tap_target")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val tapX = tapTarget?.intValue("x")
        val tapY = tapTarget?.intValue("y")
        val swipeFrom = json.objectValue("swipe_from") ?: json.objectValue("swipe_start")
        val swipeTo = json.objectValue("swipe_to") ?: json.objectValue("swipe_end")
        val swipeFromX = swipeFrom?.intValue("x")
        val swipeFromY = swipeFrom?.intValue("y")
        val swipeToX = swipeTo?.intValue("x")
        val swipeToY = swipeTo?.intValue("y")
        val durationMs = json.intValue("duration_ms")?.coerceIn(80, 5000)
        val goalProgress = json.stringValue("goal_progress").takeIf(String::isNotBlank)
        val failDetected = json.booleanValue("fail_detected")
        val reason = json.stringValue("reason").take(120)

        val report = buildString {
            append("[Vision/").append(model).append("] ")
            append(observations.ifBlank { "(no observations)" })
            if (!nextAction.isNullOrBlank() && !nextAction.equals("none", ignoreCase = true)) {
                append(" • next: ").append(nextAction)
                if (tapX != null && tapY != null) append(" at (").append(tapX).append(',')
                    .append(tapY).append(')')
            }
            if (goalProgress != null) append(" • progress: ").append(goalProgress)
            if (failDetected == true) append(" • fail condition detected")
            if (reason.isNotBlank()) append(" • ").append(reason)
        }

        return Analysis(
            ok = true,
            observations = observations,
            nextAction = nextAction,
            tapX = tapX,
            tapY = tapY,
            report = report,
            swipeFromX = swipeFromX,
            swipeFromY = swipeFromY,
            swipeToX = swipeToX,
            swipeToY = swipeToY,
            durationMs = durationMs,
            goalProgress = goalProgress,
            failDetected = failDetected,
        )
    }

    /** Tolerantly extracts the first balanced JSON object from model output. */
    private fun extractJson(text: String): String? {
        val cleaned = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until cleaned.length) {
            val char = cleaned[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** Encode a screenshot as a JPEG data URL payload (Base64 only is returned). */
    private fun encode(bitmap: Bitmap): String? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        var resized: Bitmap? = null
        return try {
            val largestDimension = maxOf(bitmap.width, bitmap.height)
            val scaleFactor = minOf(1f, MAX_IMAGE_DIMENSION.toFloat() / largestDimension.toFloat())
            val imageToEncode = if (scaleFactor < 1f) {
                bitmap.scale(
                    (bitmap.width * scaleFactor).toInt().coerceAtLeast(1),
                    (bitmap.height * scaleFactor).toInt().coerceAtLeast(1),
                ).also { resized = it }
            } else {
                bitmap
            }

            ByteArrayOutputStream().use { output ->
                if (!imageToEncode.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    return null
                }
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Screenshot encoding error: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            resized?.recycle()
        }
    }

    private fun prompt(question: String): String = """
        You are the vision module of an Android automation agent. The image is a phone screenshot.
        Return ONLY valid JSON, without Markdown fences, using this shape:
        {"observations":"what is visible in 1-3 short sentences","next_action":"tap|swipe|long_press|back|home|none","tap_target":{"x":0,"y":0},"swipe_from":{"x":0,"y":0},"swipe_to":{"x":0,"y":0},"duration_ms":350,"goal_progress":"not_started|in_progress|near|reached","fail_detected":false,"reason":"brief reason, at most 15 words"}
        Set tap_target to null when there is no specific tap target. Coordinates must be relative to the supplied image; the caller converts them to screen coordinates.
        Task context: ${question.take(1800)}
    """.trimIndent()

    private fun JsonObject.stringValue(name: String): String {
        val value = get(name) ?: return ""
        if (value.isJsonNull || !value.isJsonPrimitive) return ""
        return runCatching { value.asString }.getOrDefault("")
    }

    private fun JsonObject.intValue(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.booleanValue(name: String): Boolean? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asBoolean }.getOrNull()
    }

    private fun cacheKey(route: Route, model: String): String = "${route.providerId}::$model"

    private fun gson(): Gson = gsonInstance
}
