package com.example.oreshik.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Mistral request-body sanitizer (pure Kotlin, unit-testable).
 *
 * Mistral validates the chat-completions body against a strict schema and
 * rejects unknown members with HTTP 422 `extra_forbidden`. Its reasoning
 * models (e.g. magistral-*, mistral-vibe-cli-with-tools) return their
 * chain-of-thought in a `reasoning` member of the assistant message, and the
 * agent replays that message verbatim as history in the next request, so
 * every follow-up turn failed with HTTP 422:
 *
 * {"detail":[{"type":"extra_forbidden",
 *   "loc":["body","messages",3,"assistant","reasoning"],
 *   "msg":"Extra inputs are not permitted", ...}]}
 *
 * This sanitizer strips:
 *  - `reasoning` / `reasoning_content` members from every message (never valid
 *    as INPUT message fields; it also cleans reasoning fields that were
 *    persisted into old saved sessions), and
 *  - explicit JSON nulls such as `"tool_calls": null` (the history builder
 *    already treats those as absent, but the strict schema would reject them).
 *
 * It operates on deep copies so the caller's history object is never mutated.
 */
internal object MistralSanitizer {

    fun sanitizeMessages(messages: JsonObject): JsonArray {
        val element = messages["messages"]
        val source = if ((element != null) && element.isJsonArray) element.asJsonArray else JsonArray()
        val cleaned = JsonArray()
        for (item in source) {
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject
            if (obj == null) {
                cleaned.add(item)
                continue
            }
            val copy = obj.deepCopy()
            copy.remove("reasoning")
            copy.remove("reasoning_content")
            // Remove explicit JSON nulls (e.g. "tool_calls": null).
            val keysToRemove = ArrayList<String>()
            val entryList = copy.entrySet().toList()
            for (i in entryList.indices) {
                if (entryList[i].value.isJsonNull) keysToRemove.add(entryList[i].key)
            }
            for (i in keysToRemove.indices) {
                copy.remove(keysToRemove[i])
            }
            cleaned.add(copy)
        }
        return cleaned
    }
}
