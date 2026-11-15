package com.example.oreshik

import com.example.oreshik.ai.MistralSanitizer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Mistral request-body sanitizer.
 *
 * Root cause this guards against: Mistral's reasoning models return their
 * chain-of-thought in a `reasoning` member of the assistant message. The agent
 * stores that message verbatim in history and replays it in the next request,
 * and Mistral's strict schema rejects the extra field with HTTP 422
 * `extra_forbidden` at ["body","messages",N,"assistant","reasoning"] — which
 * broke the second turn of every conversation (see log 2026-10-12 21:43:06).
 */
class MistralSanitizerTest {

    /** Exact request shape that produced the reported HTTP 422. */
    private fun poisonedHistory(): JsonObject {
        val raw = """
            {"messages":[
              {"role":"system","content":"You are a serious autonomous coding agent."},
              {"role":"user","content":"Hi"},
              {"role":"assistant","content":"Hello! How can I help you today?",
               "reasoning":"The user just said \"Hi\". This is a greeting. I should respond politely and offer help.",
               "tool_calls":null},
              {"role":"tool","tool_call_id":"call_1","name":"memory.search","content":"[]"},
              {"role":"assistant","content":"Refusal text",
               "reasoning_content":"chain of thought variant field"}
            ]}
        """.trimIndent()
        return JsonParser.parseString(raw).asJsonObject
    }

    @Test
    fun `assistant reasoning field is stripped from replayed history`() {
        val out = MistralSanitizer.sanitizeMessages(poisonedHistory())
        for (i in 0 until out.size()) {
            val msg = out[i].asJsonObject
            assertFalse("reasoning must not be sent to Mistral", msg.has("reasoning"))
            assertFalse("reasoning_content must not be sent to Mistral", msg.has("reasoning_content"))
        }
        // The visible answer content is preserved.
        val assistant = out[2].asJsonObject
        assertEquals("assistant", assistant.get("role").asString)
        assertEquals("Hello! How can I help you today?", assistant.get("content").asString)
    }

    @Test
    fun `explicit json nulls such as tool_calls are removed`() {
        val out = MistralSanitizer.sanitizeMessages(poisonedHistory())
        val assistant = out[2].asJsonObject
        assertFalse("JSON null members must be stripped", assistant.has("tool_calls"))
    }

    @Test
    fun `non-string members like tool calls on tool messages survive`() {
        val out = MistralSanitizer.sanitizeMessages(poisonedHistory())
        val tool = out[3].asJsonObject
        assertEquals("tool", tool.get("role").asString)
        assertEquals("call_1", tool.get("tool_call_id").asString)
        assertEquals("memory.search", tool.get("name").asString)
        assertEquals("[]", tool.get("content").asString)
    }

    @Test
    fun `input history object is not mutated`() {
        val history = poisonedHistory()
        MistralSanitizer.sanitizeMessages(history)
        // The caller's original history (kept in memory for future turns and
        // persistence) must still contain everything, including reasoning.
        val assistant = history.getAsJsonArray("messages")[2].asJsonObject
        assertTrue(assistant.has("reasoning"))
        assertTrue(assistant.has("tool_calls"))
    }

    @Test
    fun `messages missing or non-array yields empty array without crashing`() {
        assertEquals(0, MistralSanitizer.sanitizeMessages(JsonObject()).size())
        val weird = JsonObject().apply { addProperty("messages", "not-an-array") }
        assertEquals(0, MistralSanitizer.sanitizeMessages(weird).size())
    }

    @Test
    fun `non-object entries are passed through untouched`() {
        val raw = """{"messages":["keep-me",{"role":"user","content":"Hi","reasoning":"x"}]}"""
        val out = MistralSanitizer.sanitizeMessages(JsonParser.parseString(raw).asJsonObject)
        assertEquals("keep-me", out[0].asString)
        assertFalse(out[1].asJsonObject.has("reasoning"))
        assertEquals("Hi", out[1].asJsonObject.get("content").asString)
    }

    @Test
    fun `clean history passes through unchanged`() {
        val raw = """
            {"messages":[
              {"role":"system","content":"s"},
              {"role":"user","content":"Hi"}
            ]}
        """.trimIndent()
        val out = MistralSanitizer.sanitizeMessages(JsonParser.parseString(raw).asJsonObject)
        assertEquals(2, out.size())
        assertEquals("s", out[0].asJsonObject.get("content").asString)
        assertEquals("Hi", out[1].asJsonObject.get("content").asString)
    }
}
