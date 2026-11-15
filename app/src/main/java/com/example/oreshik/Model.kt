package com.example.oreshik

data class Model(
    val id: String,
    val description: String = "",
    val ownedBy: String = "",
    val reasoning: Boolean = false,
    /** Vision-capable (image input) model. */
    val vision: Boolean = false,
    /** Audio-capable chat model which accepts input_audio parts. */
    val audio: Boolean = false,
    // NOTE: never list "ultra" — NVIDIA NIM rejects reasoning_effort="ultra"
    // with HTTP 400 unknown-variant, which the fallback chain misreads as a
    // tool-capability failure and silently disables tool calling for the model.
    val thinkingLevels: List<String> = listOf("off", "low", "medium", "high", "max"),
    /**
     * Live-test status for this model against its provider:
     *  - null  = not tested yet
     *  - true  = responded correctly to a real (cheap) completion probe
     *  - false = failed (paid-only, rate-limited, unavailable, error…)
     */
    val tested: Boolean? = null,
    /** Short explanation when [tested] is false (e.g. "HTTP 402: requires credits"). */
    val testNote: String = "",
    /** null = not verified, true = a live reasoning-effort probe succeeded, false = rejected. */
    val reasoningVerified: Boolean? = null,
) {
    /** Coarse category used for icon + probe message selection. */
    val kind: String
        get() = when {
            vision -> "vision"
            audio -> "audio"
            reasoning -> "reasoning"
            id.contains("coder", ignoreCase = true) || id.contains("code", ignoreCase = true) -> "coding"
            else -> "general"
        }
}

interface ModelSelectionListener {
    fun onModelSelected(model: Model)
}

/** Capability hints supplied by provider catalogs, with conservative ID fallbacks. */
object ModelCapabilities {
    data class ReasoningCapability(
        val supported: Boolean,
        val levels: List<String>,
        /** null means provider metadata did not explicitly advertise support. */
        val verified: Boolean?,
    )

    /** Prefer explicit provider metadata; use model-name heuristics only as a probe candidate. */
    fun reasoningCapability(entry: com.google.gson.JsonObject, id: String, description: String = ""): ReasoningCapability {
        val capabilityObject = entry.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
        val parameterArrays = listOfNotNull(
            entry.getAsJsonArray("supported_parameters"),
            capabilityObject?.getAsJsonArray("supported_parameters"),
            entry.getAsJsonArray("supported_capabilities"),
        )
        val levelArrays = listOfNotNull(
            entry.getAsJsonArray("reasoning_effort_levels"),
            entry.getAsJsonArray("supported_reasoning_efforts"),
            entry.getAsJsonArray("thinking_levels"),
            capabilityObject?.getAsJsonArray("reasoning_effort_levels"),
            capabilityObject?.getAsJsonArray("thinking_levels"),
        )
        val advertisedLevels = levelArrays.flatMap { array ->
            array.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.lowercase() }
        }.filter { it in setOf("minimal", "low", "medium", "high", "xhigh", "max") }.distinct()
        val advertisedParameters = parameterArrays.flatMap { array ->
            array.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.lowercase() }
        }
        val explicitFlags = listOfNotNull(
            entry.get("supports_reasoning"),
            entry.get("reasoning_supported"),
            capabilityObject?.get("supports_reasoning"),
            capabilityObject?.get("reasoning"),
        )
        val explicitFlag = explicitFlags.firstNotNullOfOrNull { value ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
        }
        val explicit = explicitFlag != null || parameterArrays.isNotEmpty() || levelArrays.isNotEmpty()
        val advertised = explicitFlag ?: (advertisedParameters.any {
            it.contains("reasoning_effort") || it == "reasoning" || it.contains("thinking")
        } || advertisedLevels.isNotEmpty())
        val heuristicText = "$id $description".lowercase()
        val heuristic = listOf("deepseek-r", "deepseek-v3.2", "deepseek-v4", "qwen3", "qwen3.5", "qwq", "reason", "thinking", "glm-", "nemotron-", "o1", "o3", "o4", "r1").any { it in heuristicText }
        val supported = if (explicit) advertised else heuristic
        val levels = when {
            !supported -> listOf("off")
            advertisedLevels.isNotEmpty() -> advertisedLevels
            else -> listOf("low", "medium", "high")
        }
        return ReasoningCapability(supported, levels, if (explicit) advertised else null)
    }

    fun hasInputModality(entry: com.google.gson.JsonObject, modality: String): Boolean {
        val arrays = listOfNotNull(
            entry.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities"),
            entry.getAsJsonArray("input_modalities"),
            entry.getAsJsonObject("capabilities")?.getAsJsonArray("input_modalities"),
        )
        if (arrays.isNotEmpty()) {
            return arrays.any { values -> values.any { it.isJsonPrimitive && it.asString.equals(modality, ignoreCase = true) } }
        }
        val id = entry.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val description = entry.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return when (modality.lowercase()) {
            "image" -> listOf("vision", "-vl", "_vl", "llava", "pixtral", "4o", "gpt-4.1").any { (id + " " + description).contains(it, true) }
            "audio" -> supportsChatAudio(id, description)
            else -> false
        }
    }

    fun supportsChatAudio(id: String, description: String = ""): Boolean {
        val value = "$id $description".lowercase()
        if (listOf("whisper", "transcrib", "text-to-speech", "tts").any(value::contains)) return false
        return listOf("gpt-audio", "audio-preview", "audio-input", "audio-chat").any(value::contains)
    }
}
