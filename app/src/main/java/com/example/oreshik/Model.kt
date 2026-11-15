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
