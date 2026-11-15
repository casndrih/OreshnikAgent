package com.example.oreshik

import com.example.oreshik.ai.NvidiaSanitizer
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the NVIDIA NIM request-history sanitizer.
 *
 * Root cause this guards against: NVIDIA NIM serves models like
 * meta/llama-3.2-11b-vision-instruct through vLLM, where several tool-call
 * parsers enforce ONE tool call per assistant turn. The agent loop stores a
 * model turn with PARALLEL tool calls (tool_calls=[A, B]) plus both tool
 * results into the persisted session history; from then on EVERY request
 * replaying that history failed before the model even ran:
 *
 * HTTP 400: {"error":{"message":"This model only supports single tool-calls
 * at once! ...","type":"BadRequestError","param":null,"code":400}}
 *
 * The sanitizer rewrites the outgoing wire copy into sequential pairs:
 * assistant(call_a) → tool(call_a) → assistant(call_b) → tool(call_b),
 * which every OpenAI-compatible server accepts.
 */
class NvidiaSanitizerTest {

    /** History shape that produced the reported HTTP 400 (parallel tool calls). */
    private fun parallelToolCallHistory(): JsonArray {
        val raw = """
            [
              {"role":"system","content":"You are a serious autonomous coding agent."},
              {"role":"user","content":"Check the time and search the web."},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"time.now","arguments":"{}"}},
                {"id":"call_b","type":"function","function":{"name":"web.search","arguments":"{\"query\":\"weather\"}"}}
              ]},
              {"role":"tool","tool_call_id":"call_a","name":"time.now","content":"03:14"},
              {"role":"tool","tool_call_id":"call_b","name":"web.search","content":"[]"},
              {"role":"assistant","content":"It is 03:14."}
            ]
        """.trimIndent()
        return JsonParser.parseString(raw).asJsonArray
    }

    @Test
    fun `parallel tool-call turn is split into sequential assistant-tool pairs`() {
        val out = NvidiaSanitizer.sanitizeMessages(parallelToolCallHistory())

        // 1 system + 1 user + 2 assistant/tool pairs + 1 final assistant = 7
        assertEquals(7, out.size())

        val firstSplit = out[2].asJsonObject
        assertEquals("assistant", firstSplit["role"].asString)
        assertEquals(1, firstSplit.getAsJsonArray("tool_calls").size())
        val firstCall = firstSplit["tool_calls"].asJsonArray[0].asJsonObject
        assertEquals("call_a", firstCall["id"].asString)
        assertEquals("time.now", firstCall["function"].asJsonObject["name"].asString)

        val firstResult = out[3].asJsonObject
        assertEquals("tool", firstResult["role"].asString)
        assertEquals("call_a", firstResult["tool_call_id"].asString)
        assertEquals("03:14", firstResult["content"].asString)

        val secondSplit = out[4].asJsonObject
        assertEquals("assistant", secondSplit["role"].asString)
        assertEquals(1, secondSplit["tool_calls"].asJsonArray.size())
        assertEquals("call_b", secondSplit["tool_calls"].asJsonArray[0].asJsonObject["id"].asString)

        val secondResult = out[5].asJsonObject
        assertEquals("tool", secondResult["role"].asString)
        assertEquals("call_b", secondResult["tool_call_id"].asString)
        assertEquals("[]", secondResult["content"].asString)

        val final = out[6].asJsonObject
        assertEquals("assistant", final["role"].asString)
        assertEquals("It is 03:14.", final["content"].asString)
    }

    @Test
    fun `first split assistant keeps original content, continuations use null content`() {
        val history = parallelToolCallHistory()
        // Give the parallel turn visible content to prove it is not duplicated.
        history[2].asJsonObject.addProperty("content", "Running two tools…")
        val out = NvidiaSanitizer.sanitizeMessages(history)

        assertEquals("Running two tools…", out[2].asJsonObject["content"].asString)
        assertTrue("continuation assistant must carry JSON null content", out[4].asJsonObject["content"].isJsonNull)
    }

    @Test
    fun `history with only single tool calls passes through unchanged`() {
        val raw = """
            [
              {"role":"user","content":"Hi"},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"time.now","arguments":"{}"}}
              ]},
              {"role":"tool","tool_call_id":"call_1","name":"time.now","content":"03:14"},
              {"role":"assistant","content":"Done."}
            ]
        """.trimIndent()
        val input = JsonParser.parseString(raw).asJsonArray
        val out = NvidiaSanitizer.sanitizeMessages(input)
        assertEquals(input.toString(), out.toString())
    }

    @Test
    fun `calls without recorded tool results get synthesized tool replies`() {
        val raw = """
            [
              {"role":"user","content":"Do two things"},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"a","arguments":"{}"}},
                {"id":"call_b","type":"function","function":{"name":"b","arguments":"{}"}}
              ]}
            ]
        """.trimIndent()
        val out = NvidiaSanitizer.sanitizeMessages(JsonParser.parseString(raw).asJsonArray)
        // assistant(call_a) → tool(call_a, synthesized) → assistant(call_b) →
        // tool(call_b, synthesized) → user
        assertEquals(5, out.size())
        val synth = out[4].asJsonObject
        assertEquals("tool", synth["role"].asString)
        assertEquals("call_b", synth["tool_call_id"].asString)
        assertTrue(synth["content"].asString.contains("no recorded result"))
        assertEquals("assistant", out[3].asJsonObject["role"].asString)
        assertEquals("call_b", out[3].asJsonObject["tool_calls"].asJsonArray[0].asJsonObject["id"].asString)
    }

    @Test
    fun `trailing tool results with no matching call are preserved in order`() {
        val raw = """
            [
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"a","arguments":"{}"}},
                {"id":"call_b","type":"function","function":{"name":"b","arguments":"{}"}}
              ]},
              {"role":"tool","tool_call_id":"call_b","name":"b","content":"B"},
              {"role":"tool","tool_call_id":"call_a","name":"a","content":"A"},
              {"role":"user","content":"Thanks"}
            ]
        """.trimIndent()
        val out = NvidiaSanitizer.sanitizeMessages(JsonParser.parseString(raw).asJsonArray)
        // assistant(call_a) → tool(call_a) → assistant(call_b) → tool(call_b) → user
        // (results are paired BY ID even though they were stored in reverse order)
        assertEquals(5, out.size())
        assertEquals("call_a", out[1].asJsonObject["tool_call_id"].asString)
        assertEquals("A", out[1].asJsonObject["content"].asString)
        assertEquals("call_b", out[3].asJsonObject["tool_call_id"].asString)
        assertEquals("B", out[3].asJsonObject["content"].asString)
        assertEquals("user", out[4].asJsonObject["role"].asString)
        assertEquals("Thanks", out[4].asJsonObject["content"].asString)
    }

    @Test
    fun `tool_call ids missing or null do not crash and still split`() {
        val raw = """
            [
              {"role":"assistant","content":null,"tool_calls":[
                {"type":"function","function":{"name":"a","arguments":"{}"}},
                {"id":null,"type":"function","function":{"name":"b","arguments":"{}"}}
              ]},
              {"role":"tool","tool_call_id":"call_0","name":"b","content":"B"}
            ]
        """.trimIndent()
        val out = NvidiaSanitizer.sanitizeMessages(JsonParser.parseString(raw).asJsonArray)
        // assistant(call_0) → tool(call_0, "B", paired positionally) →
        // assistant(call_1) → tool(call_1, synthesized)
        assertEquals(4, out.size())
        assertEquals("assistant", out[0].asJsonObject["role"].asString)
        assertEquals("call_0", out[0].asJsonObject["tool_calls"].asJsonArray[0].asJsonObject["id"].asString)
        assertEquals("tool", out[1].asJsonObject["role"].asString)
        assertEquals("call_0", out[1].asJsonObject["tool_call_id"].asString)
        assertEquals("B", out[1].asJsonObject["content"].asString)
        assertEquals("assistant", out[2].asJsonObject["role"].asString)
        assertEquals("call_1", out[2].asJsonObject["tool_calls"].asJsonArray[0].asJsonObject["id"].asString)
        assertEquals("tool", out[3].asJsonObject["role"].asString)
        assertEquals("call_1", out[3].asJsonObject["tool_call_id"].asString)
        assertTrue(out[3].asJsonObject["content"].asString.contains("no recorded result"))
    }

    @Test
    fun `plain history without tool calls passes through unchanged`() {
        val raw = """
            [
              {"role":"system","content":"s"},
              {"role":"user","content":"Hi"},
              {"role":"assistant","content":"Hello!"}
            ]
        """.trimIndent()
        val input = JsonParser.parseString(raw).asJsonArray
        val out = NvidiaSanitizer.sanitizeMessages(input)
        assertEquals(input.toString(), out.toString())
    }

    @Test
    fun `object wrapper overload returns the same sanitized array`() {
        val wrapper = JsonObject().apply { add("messages", parallelToolCallHistory()) }
        val out = NvidiaSanitizer.sanitizeMessages(wrapper)
        assertEquals(7, out.size())
        assertEquals("assistant", out[2].asJsonObject["role"].asString)
    }

    @Test
    fun `input history is not mutated`() {
        val history = parallelToolCallHistory()
        NvidiaSanitizer.sanitizeMessages(history)
        // The caller's stored history must keep the original parallel turn.
        val assistant = history[2].asJsonObject
        assertEquals(2, assistant.getAsJsonArray("tool_calls").size())
    }

    @Test
    fun `empty and malformed inputs do not crash`() {
        assertEquals(0, NvidiaSanitizer.sanitizeMessages(JsonObject()).size())
        assertEquals(0, NvidiaSanitizer.sanitizeMessages(JsonArray()).size())
        val weird = JsonArray().apply { add("keep-me") }
        val out = NvidiaSanitizer.sanitizeMessages(weird)
        assertEquals("keep-me", out[0].asString)
    }
}
