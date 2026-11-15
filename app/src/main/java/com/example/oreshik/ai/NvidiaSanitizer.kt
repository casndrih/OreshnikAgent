package com.example.oreshik.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * NVIDIA NIM request-history sanitizer (pure Kotlin, unit-testable).
 *
 * NVIDIA NIM serves Llama/Nemotron models through vLLM, and several tool-call
 * parsers on that stack enforce ONE tool call per assistant turn. The moment
 * ANY message in the replayed history contains an `assistant` entry whose
 * `tool_calls` array has more than one element, the request is rejected with
 * HTTP 400 before the model even runs:
 *
 * {"error":{"message":"This model only supports single tool-calls at once!",
 *           "type":"BadRequestError","param":null,"code":400}}
 *
 * The agent loop executes parallel tool calls and stores that raw assistant
 * message in the persisted session history, so every subsequent run of that
 * session died at step 1 (observed on meta/llama-3.2-11b-vision-instruct).
 *
 * This sanitizer rewrites the OUTGOING wire copy of the history (the session
 * history object itself is never mutated) from the parallel form
 *
 *   assistant(tool_calls=[A, B], content) → tool(A) → tool(B)
 *
 * into the equivalent sequential form that every OpenAI-compatible server
 * accepts:
 *
 *   assistant A with one tool call (keeping the original content) → tool A →
 *   assistant B with the next tool call (JSON-null content) → tool B
 *
 * The first split assistant keeps the original visible content; continuations
 * are serialized as JSON-null content, matching how OpenAI-compatible servers
 * render continued tool-call turns. Each call is paired with its recorded
 * result (by id, falling back to positional order, ids re-stamped so each
 * pair is consistent); when no result exists a synthesized `role:"tool"`
 * reply keeps the assistant/tool alternation well-formed. Orphan tool
 * messages are never emitted, because strict servers reject a tool reply
 * that does not follow its own assistant tool-call turn just as much as
 * they reject parallel calls.
 */
internal object NvidiaSanitizer {

    /** Entry point for callers holding the {"messages":[...]} wrapper object. */
    fun sanitizeMessages(messages: JsonObject): JsonArray {
        val element = messages["messages"]
        val source = if ((element != null) && element.isJsonArray) element.asJsonArray else JsonArray()
        return sanitizeHistory(source)
    }

    /** Entry point for callers already holding the bare message array. */
    fun sanitizeMessages(messages: JsonArray): JsonArray = sanitizeHistory(messages)

    private fun sanitizeHistory(source: JsonArray): JsonArray {
        val cleaned = JsonArray()
        var i = 0
        while (i < source.size()) {
            val item = source[i]
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject
            if ((obj == null) || (roleOf(obj) != "assistant")) {
                cleaned.add(item.deepCopy())
                i++
                continue
            }
            val calls = arrayOrNull(obj)
            if ((calls == null) || (calls.size() < 2)) {
                // 0 or 1 tool call: already valid everywhere.
                cleaned.add(obj.deepCopy())
                i++
                continue
            }
            // Parallel tool-call turn: consume the tool results that follow it.
            val followingTools = ArrayList<JsonObject>()
            var j = i + 1
            while (j < source.size()) {
                val next = source[j]
                val nextObj = next.takeIf { it.isJsonObject }?.asJsonObject
                if ((nextObj == null) || (roleOf(nextObj) != "tool")) break
                followingTools.add(nextObj)
                j++
            }
            for (k in 0 until calls.size()) {
                val callId = stringOf(calls[k], "id")

                // Pair this call with its recorded result: prefer the result whose
                // tool_call_id matches, otherwise the next result in order (saved
                // histories occasionally carry missing or unmatched ids). With no
                // result at all, synthesize one so the alternation stays valid.
                val idMatch = if (callId.isNotBlank()) {
                    followingTools.indexOfFirst { tool -> stringOf(tool, "tool_call_id") == callId }
                } else -1
                val toolReply: JsonObject = when {
                    idMatch >= 0 -> followingTools.removeAt(idMatch).deepCopy()
                    followingTools.isNotEmpty() -> followingTools.removeAt(0).deepCopy()
                    else -> JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", "")
                        addProperty("content", "(no recorded result for this tool call)")
                    }
                }

                // The split assistant's call and its tool reply must share one id.
                val pairId = callId.ifBlank { stringOf(toolReply, "tool_call_id").ifBlank { "call_$k" } }
                val callCopy = calls[k].deepCopy()
                if (callCopy.isJsonObject) callCopy.asJsonObject.addProperty("id", pairId)
                toolReply.addProperty("tool_call_id", pairId)

                val content: JsonElement = if (k == 0) obj["content"] ?: JsonNull.INSTANCE else JsonNull.INSTANCE
                val splitAssistant = JsonObject()
                for ((key, value) in obj.entrySet()) {
                    if ((key == "content") || (key == "tool_calls")) continue
                    splitAssistant.add(key, value.deepCopy())
                }
                splitAssistant.add("content", content.deepCopy())
                splitAssistant.add("tool_calls", JsonArray().apply { add(callCopy) })
                cleaned.add(splitAssistant)
                cleaned.add(toolReply)
            }
            i = j
        }
        return cleaned
    }

    private fun roleOf(message: JsonObject): String = stringOf(message, "role")

    private fun stringOf(element: JsonElement, name: String): String {
        if (!element.isJsonObject) return ""
        val member = element.asJsonObject[name] ?: return ""
        if (member.isJsonNull || !member.isJsonPrimitive) return ""
        return runCatching { member.asJsonPrimitive.asString }.getOrDefault("")
    }

    private fun arrayOrNull(obj: JsonObject): JsonArray? {
        val element = obj["tool_calls"] ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asJsonArray }.getOrNull()
    }
}
