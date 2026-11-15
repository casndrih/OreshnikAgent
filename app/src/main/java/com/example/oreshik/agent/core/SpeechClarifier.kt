package com.example.oreshik.agent.core

import com.example.oreshik.logging.AppLogger

/**
 * Intelligent speech clarification.
 *
 * Decides whether a transcribed voice command should be executed as-is, clarified
 * with the user, or interpreted as a likely transcription error that needs
 * confirmation. Runs ONLY on the voice-execution path (WakeWordService) - typed
 * chat is never interrupted by speech-specific clarification.
 *
 * Two-stage evaluation (reuses the existing agent model - no redundant NLP system):
 * 1. Fast local heuristics detect likely transcription confusions (verb swaps
 *    like "sets"/"search", garbled tokens, mixed-up operations).
 * 2. Only when heuristics are unsure does it ask the model to sanity-check the
 *    interpreted intent against the available tools.
 */
class SpeechClarifier {

    private companion object {
        const val TAG = "SpeechClarifier"
    }

    enum class Outcome { CONFIDENT, NEEDS_CLARIFICATION, LIKELY_TRANSCRIPTION_ERROR }

    data class Result(
        val outcome: Outcome,
        /** Question to speak when clarification is needed. */
        val question: String = "",
        /** Revised command when the original was likely a transcription error. */
        val revisedCommand: String = "",
        /** Why this verdict was reached (for logs/UI, never spoken). */
        val reason: String = "",
    )

    // ------------------------------------------------------------------
    // Stage 1: local heuristics (fast, offline, no model call)
    // ------------------------------------------------------------------

    /** Verb pairs the recognizer commonly swaps or mishears. */
    private val verbConfusions = listOf(
        "sets" to "search", "set me" to "search", "searches" to "search",
        "sent me" to "send", "scent" to "send", "sand me" to "send",
        "opin" to "open", "deleat" to "delete", "dilate" to "delete",
        "messenger" to "message", "massage" to "message",
        "whats app" to "WhatsApp", "whatsup" to "WhatsApp",
        "g mail" to "Gmail", "google mail" to "Gmail",
    )

    /** Words that usually indicate the recognizer produced filler/garbage. */
    private val garbageIndicators = listOf(
        "something", "that thing", "whatever", "um", "uh", "err",
    )

    fun evaluate(transcript: String, confidence: Float = -1f): Result {
        val text = transcript.trim()
        if (text.isBlank()) {
            AppLogger.d(TAG, "Clarifier: empty transcript → NEEDS_CLARIFICATION")
            return Result(
                Outcome.NEEDS_CLARIFICATION,
                question = "Sorry, I didn't catch that. What would you like me to do?",
                reason = "empty transcript",
            )
        }

        // Very low recognizer confidence is a strong ambiguity signal.
        if ((confidence >= 0f) && (confidence <= 0.35f)) {
            AppLogger.d(TAG, "Clarifier: low confidence ($confidence) → NEEDS_CLARIFICATION")
            return Result(
                Outcome.NEEDS_CLARIFICATION,
                question = "I heard \"$text\" - did you mean that, or something else?",
                reason = "low recognizer confidence $confidence",
            )
        }

        val lower = text.lowercase()

        // Known verb confusion: propose the corrected command for confirmation.
        for ((heard, intended) in verbConfusions) {
            if ((heard != intended.lowercase()) && (lower.contains(heard))) {
                val corrected = applyCorrection(text, heard, intended)
                AppLogger.i(TAG, "Clarifier: likely mishear \"$heard\" → \"$intended\"")
                return Result(
                    Outcome.LIKELY_TRANSCRIPTION_ERROR,
                    question = "Did you mean: \"$corrected\"?",
                    revisedCommand = corrected,
                    reason = "possible mishear: \"$heard\" -> \"$intended\"",
                )
            }
        }

        // Garbage indicators with no actionable verb: ask what the user meant.
        val hasActionVerb = listOf(
            "open", "close", "delete", "send", "search", "create",
            "make", "install", "uninstall", "ssh", "call", "text", "message", "browse",
            "start", "stop", "kill", "find", "write", "read", "launch",
        ).any { lower.contains(it) }
        if (!hasActionVerb && garbageIndicators.any { lower.contains(it) }) {
            AppLogger.d(TAG, "Clarifier: filler without action verb → NEEDS_CLARIFICATION")
            return Result(
                Outcome.NEEDS_CLARIFICATION,
                question = "I'm not sure what you mean by \"$text\". Could you say the full command?",
                reason = "no actionable verb + filler word",
            )
        }

        return Result(Outcome.CONFIDENT, reason = "heuristics passed")
    }

    private fun applyCorrection(text: String, heard: String, intended: String): String {
        // Only replace the misheard phrase ("sets me X videos" -> "search me X
        // videos"). The replacement stays conservative so the user always
        // confirms the revised command before execution.
        val regex = Regex(Regex.escape(heard), RegexOption.IGNORE_CASE)
        return regex.replace(text, intended)
    }

    // ------------------------------------------------------------------
    // Stage 2: model-assisted intent sanity check (only when needed)
    // ------------------------------------------------------------------

    /**
     * Asks the configured model to check whether the given transcript is a coherent,
     * executable instruction. Returns the transcript when CLEAR, or null when
     * the model judges the intent ambiguous. Fail-open on any error so a
     * clarification-check outage never blocks execution.
     * Public API kept for future interactive clarification flows.
     */
    @Suppress("unused")
    suspend fun modelIntentCheck(
        transcript: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): String? {
        val client = AgentApiClient()
        val messages = com.google.gson.JsonArray().apply {
            add(
                com.google.gson.JsonObject().apply {
                    addProperty("role", "system")
                    addProperty(
                        "content",
                        "You sanity-check voice commands for a device assistant. " +
                            "Given a possibly-misheard transcription, answer with exactly one word: " +
                            "CLEAR when the request is a coherent executable instruction, " +
                            "or AMBIGUOUS when the intent is unclear or contradictory. No other output.",
                    )
                },
            )
            add(
                com.google.gson.JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", transcript)
                },
            )
        }
        val answer = runCatching {
            val result = client.completeText(baseUrl, apiKey, model, messages, "off")
            result.getOrNull()
                ?.arrayOrNull("choices")?.firstOrNull()?.asJsonObject
                ?.objectOrNull("message")?.get("content")?.takeUnless { it.isJsonNull }?.asString
                .orEmpty().trim().uppercase()
        }.getOrDefault("")
        val verdict = when {
            // Model judged the intent ambiguous -> signal clarification.
            answer.startsWith("AMBIGUOUS") -> null
            // CLEAR or unrecognized/network-failure replies: fail open so a
            // clarification-check outage never blocks execution.
            else -> transcript
        }
        AppLogger.d(TAG, "Intent check: model answered '${answer.take(40)}' → ${if (verdict == null) "AMBIGUOUS" else "CLEAR"}")
        return verdict
    }
}
