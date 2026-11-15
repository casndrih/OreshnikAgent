package com.example.oreshik

import com.example.oreshik.agent.core.ToolJsonSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the shared tool-schema builder that backs every agent
 * tool call (ToolJsonSchema, used by AgentEngine.schema()).
 *
 * Bugs these tests guard against:
 *
 * 1. Flat-style annotations inside the type string — e.g. TrafficTools'
 *    `"url" to "string (required, http/https)"` — were emitted verbatim as the
 *    JSON Schema "type" value. Mistral's strict tool-schema validator rejects
 *    the whole request with HTTP 400 invalid_request_tool_schema:
 *    "'string (required, http/https)' is not valid under any of the given
 *    schemas" (error code 3260). OpenAI-compatible servers tolerated it, which
 *    is why the failure only surfaced on Mistral.
 *
 * 2. The wrapped declaration style (type/properties/required) produced a
 *    structurally invalid schema in the original builder, which made NVIDIA
 *    reject web.browser (and any other wrapped-style tool) with a JSON error
 *    on every invocation regardless of the requested action.
 */
class ToolSchemaTest {

    // ------------------------------------------------------------------
    // Mistral HTTP 400 invalid_request_tool_schema regression
    // ------------------------------------------------------------------

    @Test
    fun `flat style annotations in type string are stripped for mistral`() {
        val params = linkedMapOf<String, Any?>(
            "url" to "string (required, http/https)",
            "method" to "string (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS, default GET)",
            "headers" to "object (request headers)",
            "body" to "string (request body, optional)",
            "save_response" to "boolean (also save dump to workspace traffic/, default true)",
            "max_body_chars" to "integer (inline body limit, default 20000)",
        )
        val schema = ToolJsonSchema.build(params)
        assertEquals("object", schema["type"].asString)
        val props = schema.getAsJsonObject("properties")
        // Every type must be a bare JSON-Schema primitive — no "(...)" payloads.
        assertEquals("string", props.getAsJsonObject("url")["type"].asString)
        assertEquals("string", props.getAsJsonObject("method")["type"].asString)
        assertEquals("object", props.getAsJsonObject("headers")["type"].asString)
        assertEquals("string", props.getAsJsonObject("body")["type"].asString)
        assertEquals("boolean", props.getAsJsonObject("save_response")["type"].asString)
        assertEquals("integer", props.getAsJsonObject("max_body_chars")["type"].asString)
        // The dropped annotation text survives as the description.
        assertEquals("http/https", props.getAsJsonObject("url")["description"].asString)
        val description = props.getAsJsonObject("method")["description"].asString
        assertTrue(description.contains("GET/POST"))
        assertTrue(description.contains("default GET"))
    }

    @Test
    fun `flat style required annotation promotes parameter into required array`() {
        val params = linkedMapOf<String, Any?>(
            "url" to "string (required, http/https)",
            "capture_id" to "string (required)",
            "limit" to "integer (optional, default 20)",
        )
        val schema = ToolJsonSchema.build(params)
        val required = schema.getAsJsonArray("required")
        assertEquals(2, required.size())
        val requiredStrings = ArrayList<String>(required.size())
        for (element in required) {
            requiredStrings += element.asString
        }
        assertTrue(requiredStrings.containsAll(listOf("url", "capture_id")))
        // "optional" must not be treated as required.
        assertFalse(requiredStrings.contains("limit"))
    }

    @Test
    fun `flat style trailing prose after annotation becomes description`() {
        val schema = ToolJsonSchema.build(
            mapOf("pattern" to "string (required) - hex bytes like 'DE AD BE EF' or '0xDEADBEEF'"),
        )
        val pattern = schema.getAsJsonObject("properties").getAsJsonObject("pattern")
        assertEquals("string", pattern["type"].asString)
        val description = pattern["description"].asString
        assertTrue(description.contains("hex bytes like 'DE AD BE EF'"))
        assertFalse(description.contains("(required)"))
    }

    @Test
    fun `plain types without annotations are unchanged`() {
        val schema = ToolJsonSchema.build(linkedMapOf("max_nodes" to "integer", "text" to "string"))
        val props = schema.getAsJsonObject("properties")
        assertEquals("integer", props.getAsJsonObject("max_nodes")["type"].asString)
        assertEquals("string", props.getAsJsonObject("text")["type"].asString)
        assertNull(props.getAsJsonObject("max_nodes")["description"])
        assertEquals(0, schema.getAsJsonArray("required").size())
    }

    @Test
    fun `unknown and empty type values fall back to string`() {
        val schema = ToolJsonSchema.build(linkedMapOf("weird" to "blob", "blank" to ""))
        val props = schema.getAsJsonObject("properties")
        assertEquals("string", props.getAsJsonObject("weird")["type"].asString)
        assertEquals("string", props.getAsJsonObject("blank")["type"].asString)
    }

    @Test
    fun `shorthand type aliases map to json schema primitives`() {
        val schema = ToolJsonSchema.build(
            linkedMapOf("count" to "int", "ratio" to "double", "flag" to "bool", "label" to "str"),
        )
        val props = schema.getAsJsonObject("properties")
        assertEquals("integer", props.getAsJsonObject("count")["type"].asString)
        assertEquals("number", props.getAsJsonObject("ratio")["type"].asString)
        assertEquals("boolean", props.getAsJsonObject("flag")["type"].asString)
        assertEquals("string", props.getAsJsonObject("label")["type"].asString)
    }

    // ------------------------------------------------------------------
    // Wrapped style (NVIDIA web.browser regression)
    // ------------------------------------------------------------------

    @Test
    fun `wrapped style with required list and enum produces valid schema`() {
        val params = linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "Action to perform",
                    "enum" to listOf("navigate", "click", "type", "screenshot"),
                ),
                "url" to mapOf("type" to "string", "description" to "URL for navigate action")
            ),
            "required" to listOf("action")
        )
        val schema = ToolJsonSchema.build(params)
        assertEquals("object", schema["type"].asString)
        val props = schema.getAsJsonObject("properties")
        val action = props.getAsJsonObject("action")
        assertEquals("string", action["type"].asString)
        val enum = action.getAsJsonArray("enum")
        assertEquals(4, enum.size())
        assertEquals("navigate", enum[0].asString)
        // The required list must be a real JSON array of names.
        val required = schema.getAsJsonArray("required")
        assertEquals(1, required.size())
        assertEquals("action", required[0].asString)
        // url must not appear in required
        val requiredNames = ArrayList<String>(required.size())
        for (element in required) {
            requiredNames += element.asString
        }
        assertEquals(-1, requiredNames.indexOfFirst { name -> name == "url" })
    }

    @Test
    fun `wrapped style nested properties are recursively sanitized`() {
        val params = linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "values" to mapOf(
                    "type" to "array",
                    "description" to "Array of hex values",
                    "items" to mapOf("type" to "string")
                ),
                "paths" to mapOf("type" to "array", "description" to "Custom paths")
            ),
            "required" to listOf<String>()
        )
        val schema = ToolJsonSchema.build(params)
        val values = schema.getAsJsonObject("properties").getAsJsonObject("values")
        assertEquals("array", values["type"].asString)
        assertEquals("string", values.getAsJsonObject("items")["type"].asString)
    }

    // ------------------------------------------------------------------
    // Structural invariants
    // ------------------------------------------------------------------

    @Test
    fun `empty parameters produce valid empty schema`() {
        val schema = ToolJsonSchema.build(emptyMap())
        assertEquals("object", schema["type"].asString)
        assertEquals(0, schema.getAsJsonObject("properties").entrySet().size)
        assertEquals(0, schema.getAsJsonArray("required").size())
    }

    @Test
    fun `required key inside flat style is honored`() {
        val params = linkedMapOf<String, Any?>(
            "x" to "integer",
            "y" to "integer",
            "required" to listOf("x", "y")
        )
        val schema = ToolJsonSchema.build(params)
        val required = schema.getAsJsonArray("required")
        assertEquals(2, required.size())
        assertEquals("x", required[0].asString)
        assertEquals("y", required[1].asString)
        // "required" must not leak through as a property
        assertFalse(schema.getAsJsonObject("properties").has("required"))
    }

    @Test
    fun `every produced type value is a valid json schema primitive`() {
        // Mirrors the wire format Mistral validates: tools=[{type:function,
        // function:{parameters:{type:object,properties:{...}}}}]
        val tools = listOf(
            // TrafficTools.http.capture — the exact declaration that triggered the 400
            linkedMapOf<String, Any?>(
                "url" to "string (required, http/https)",
                "method" to "string (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS, default GET)",
                "headers" to "object (request headers)",
                "body" to "string (request body, optional)",
                "save_response" to "boolean (also save dump to workspace traffic/, default true)",
                "max_body_chars" to "integer (inline body limit, default 20000)"
            ),
            // MemoryManipulationTools.mem.scan — "(required) - prose" style
            linkedMapOf<String, Any?>(
                "pid" to "integer (required)",
                "pattern" to "string (required) - hex bytes like 'DE AD BE EF' or '0xDEADBEEF'",
                "type" to "string (bytes|string|int32|int64|float|double, default bytes)",
                "max_results" to "integer (1-5000, default 100)"
            )
        )
        val valid = setOf("string", "number", "integer", "boolean", "object", "array", "null")
        tools.forEach { params ->
            val props = ToolJsonSchema.build(params).getAsJsonObject("properties")
            props.entrySet().forEach { (name, node) ->
                val type = node.asJsonObject.get("type").asString
                assertTrue(
                    "property '$name' has non-primitive type '$type' — Mistral would reject this",
                    type in valid
                )
                assertFalse("(annotation) leaked into type for '$name'", type.contains('('))
            }
        }
    }
}
