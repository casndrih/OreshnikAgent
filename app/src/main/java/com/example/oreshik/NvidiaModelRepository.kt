package com.example.oreshik

import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.net.ProxyRotator
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NvidiaModelRepository {
    /** Proxy-aware catalog client; rebuilt when ProxyRotator changes the route. */
    @Volatile private var http: OkHttpClient = newHttp(null)

    init {
        ProxyRotator.register("model_catalog") { proxy -> newHttp(proxy) }
        ProxyRotator.onProxyChanged("model_catalog") { entry -> http = newHttp(ProxyRotator.toJavaProxy(entry)) }
    }

    private fun newHttp(proxy: java.net.Proxy?): OkHttpClient = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): Result<List<Model>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(AgentConfig.modelsUrl()).apply {
                if (AgentConfig.apiKey.isNotBlank()) header("Authorization", "Bearer ${AgentConfig.apiKey}")
            }.get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("NVIDIA models " + com.example.oreshik.agent.core.NvidiaAuthErrors.describe(response.code, body, bodyLimit = 500)),
                    )
                }
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: return@withContext Result.success(emptyList())
                val collected = LinkedHashMap<String, Model>()
                for (element in data) {
                    val o = element.asJsonObject
                    val id = o["id"]?.asString?.trim().orEmpty()
                    if (id.isBlank()) continue
                    val owned = o["owned_by"]?.asString.orEmpty()
                    val desc = o["description"]?.asString.orEmpty()
                    val reasoning = isReasoningModel(id, desc)
                    val model = Model(
                        id = id,
                        description = desc.ifBlank { category(id) },
                        ownedBy = owned,
                        reasoning = reasoning,
                        vision = listOf("vl", "vision", "vila", "llava").any { marker -> id.lowercase().contains(marker) },
                        thinkingLevels = levelsFor(id, reasoning),
                    )
                    collected.putIfAbsent(model.id, model)
                }
                val models = collected.values.toMutableList()
                for (i in 1 until models.size) {
                    val current = models[i]
                    var j = i - 1
                    while ((j >= 0) && (models[j].id.lowercase() > current.id.lowercase())) {
                        models[j + 1] = models[j]
                        j--
                    }
                    models[j + 1] = current
                }
                Result.success(models)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun isReasoningModel(id: String, desc: String): Boolean {
        val x = "$id $desc".lowercase()
        return listOf("deepseek-r", "deepseek-v3.2", "deepseek-v4", "qwen3", "qwen3.5", "qwq", "reason", "thinking", "glm-", "nemotron-", "inkling").any { marker -> marker in x }
    }

    private fun levelsFor(id: String, reasoning: Boolean): List<String> {
        if (!reasoning) return listOf("off")
        val x = id.lowercase()
        // NVIDIA NIM accepts only none/minimal/low/medium/high/xhigh/max —
        // "ultra" produces HTTP 400 unknown-variant and disables tool calling.
        val limited = ("glm-5.3" in x) || ("deepseek-v4" in x)
        return if (limited) listOf("low", "high", "max") else listOf("minimal", "low", "medium", "high", "max")
    }

    private fun category(id: String): String {
        val x = id.lowercase()
        return when {
            listOf("coder", "code", "devstral", "starcoder", "qwen3-coder").any { marker -> marker in x } -> "Coding model"
            isReasoningModel(id, "") -> "Reasoning model"
            listOf("vl", "vision", "vila", "llava").any { marker -> marker in x } -> "Vision model"
            else -> "General model"
        }
    }

    /** Live catalog ids whose name marks a vision-capable (VL) model. */
    @Suppress("unused")
    suspend fun visionCandidates(): List<String> = runCatching {
        val all = fetch().getOrDefault(emptyList())
        val ids = ArrayList<String>(all.size)
        for (i in all.indices) {
            ids += all[i].id
        }
        ids.filter { id -> listOf("vl", "vision", "vila", "llava").any { marker -> id.lowercase().contains(marker) } }
    }.getOrDefault(emptyList())

    companion object {
        /** Picks a vision model: user override → catalog auto-pick → static fallback. */
        @Suppress("unused")
        fun resolveVisionModel(candidates: List<String>): String {
            AgentConfig.visionModel.takeIf { it.isNotBlank() }?.let { return it }
            // Prefer small/fast VLMs first for snappy computer-use loops.
            val preferred = listOf(
                "nvidia/vila", "microsoft/phi-3-vision", "meta/llama-3.2-90b-vision-instruct",
                "meta/llama-3.2-11b-vision-instruct", "qwen/qwen2-vl", "microsoft/phi-3.5-vision",
            )
            for (p in preferred) {
                candidates.firstOrNull { candidate -> candidate.lowercase().contains(p.substringAfter('/')) }?.let { return it }
            }
            return candidates.firstOrNull() ?: "microsoft/phi-3-vision"
        }
    }
}
