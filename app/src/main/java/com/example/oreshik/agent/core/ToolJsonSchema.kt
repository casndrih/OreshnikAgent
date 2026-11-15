package com.example.oreshik.agent.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Converts a tool's [ToolDefinition.parameters] declaration into a valid JSON
 * Schema object (pure Kotlin, no Android APIs — directly unit-testable).
 *
 * Two declaration styles exist across the codebase and both must produce a
 * schema the inference server accepts (a malformed schema is rejected with a
 * 4xx JSON error before any tool call can ever be dispatched):
 *
 * 1. Flat style — e.g. AccessibilityTools/SystemTools/FileTools/TrafficTools:
 *    `mapOf("max_nodes" to "integer", "url" to "string (required, http/https)")`
 *
 * 2. Wrapped style — e.g. WebBrowserTool/ComputerUseTools:
 *    `mapOf(
 *       "type" to "object",
 *       "properties" to mapOf("action" to mapOf("type" to "string", "enum" to listOf(...), ...)),
 *       "required" to listOf("action")
 *    )`
 *
 * Bugs this implementation guards against:
 *
 * - Flat-style annotations inside the type string ("string (required,
 *   http/https)") are NOT valid JSON Schema types. OpenAI-compatible servers
 *   such as NVIDIA's NIM tolerated them, but Mistral's strict tool-schema
 *   validator rejects the whole request with HTTP 400
 *   `invalid_request_tool_schema` ("'string (required, http/https)' is not
 *   valid under any of the given schemas"). Annotations are stripped and the
 *   bare primitive type is emitted; a "(required)" annotation additionally
 *   promotes the parameter into the schema's real "required" array.
 * - The wrapped style's "type"/"properties"/"required" bookkeeping keys must
 *   not be treated as user parameters (the historic "JsonObject error" on
 *   web.browser).
 */
object ToolJsonSchema {

    /** Flat-style "(required ...)" annotation inside a parameter type string. */
    private val FLAT_REQUIRED_REGEX = Regex("\\(\\s*required\\b", RegexOption.IGNORE_CASE)

    /** JSON-Schema primitive types accepted by strict providers such as Mistral. */
    private val VALID_SCHEMA_TYPES = setOf("string", "number", "integer", "boolean", "object", "array", "null")

    /** Common shorthand type names mapped to their JSON-Schema primitive. */
    private val SCHEMA_TYPE_ALIASES = mapOf(
        "str" to "string", "int" to "integer", "long" to "integer",
        "float" to "number", "double" to "number", "num" to "number", "bool" to "boolean"
    )

    /** Bookkeeping keys of the wrapped declaration style, never user parameters. */
    private val WRAPPED_BOOKKEEPING_KEYS = setOf("type", "properties", "required")

    fun build(params: Map<String, Any?>): JsonObject {
        if (params.isEmpty()) {
            return JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
                add("required", JsonArray())
            }
        }
        if (params["type"]?.toString() == "object" && params["properties"] is Map<*, *>) {
            return schemaNode(params)
        }
        val properties = JsonObject()
        val required = JsonArray()
        params.forEach { (key, value) ->
            if (key == "required" && value is List<*>) {
                value.forEach { required.add(it.toString()) }
            } else {
                properties.add(key, schemaNode(value))
                // Flat style marks mandatory parameters with a "(required)"
                // annotation inside the type string; surface it as a real
                // JSON Schema "required" entry.
                if (value is String && FLAT_REQUIRED_REGEX.containsMatchIn(value)) {
                    required.add(key)
                }
            }
        }
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            add("required", required)
        }
    }

    /**
     * Converts a raw parameter value (plain type string or descriptor map) to
     * a schema node.
     */
    internal fun schemaNode(value: Any?): JsonObject = when (value) {
        is Map<*, *> -> descriptorMapToSchema(value)
        else -> {
            val raw = value?.toString().orEmpty().trim()
            JsonObject().apply {
                addProperty("type", normalizeSchemaType(raw))
                extractFlatDescription(raw)?.let { addProperty("description", it) }
            }
        }
    }

    /** Recursively converts a descriptor map into a JSON Schema object. */
    private fun descriptorMapToSchema(map: Map<*, *>): JsonObject {
        val node = JsonObject()
        val extraNotes = mutableListOf<String>()
        map.forEach { (k, v) ->
            val key = k.toString()
            when {
                key.equals("type", ignoreCase = true) ->
                    node.addProperty("type", normalizeSchemaType(v?.toString().orEmpty()))
                key.equals("description", ignoreCase = true) && v != null ->
                    node.addProperty("description", v.toString())
                key.equals("enum", ignoreCase = true) && v is List<*> -> {
                    val arr = JsonArray()
                    v.forEach { item ->
                        when (item) {
                            is Map<*, *> -> arr.add(descriptorMapToSchema(item))
                            else -> arr.add(item.toString())
                        }
                    }
                    node.add("enum", arr)
                }
                key.equals("required", ignoreCase = true) && v is List<*> -> {
                    val arr = JsonArray()
                    v.forEach { item -> arr.add(item.toString()) }
                    node.add("required", arr)
                }
                key.equals("items", ignoreCase = true) -> node.add("items", schemaNode(v))
                // Wrapped-style tools nest their real parameters here; each
                // value is a property descriptor (type string or map).
                key.equals("properties", ignoreCase = true) && v is Map<*, *> -> {
                    val props = JsonObject()
                    v.forEach { (pk, pv) -> props.add(pk.toString(), schemaNode(pv)) }
                    node.add("properties", props)
                }
                // Keep the bookkeeping keys of wrapped-style declarations from
                // leaking into the description as "notes".
                key in WRAPPED_BOOKKEEPING_KEYS -> Unit
                else -> if (v != null) extraNotes.add("$key: $v")
            }
        }
        if (!node.has("type")) node.addProperty("type", "string")
        if (extraNotes.isNotEmpty()) {
            val note = extraNotes.joinToString("; ")
            val existing = node.get("description")?.takeUnless { it.isJsonNull }?.asString
            node.addProperty("description", if (existing.isNullOrBlank()) note else "$existing; $note")
        }
        return node
    }

    /** Strips embedded "(...)" annotations from a flat-style type string: "string (required, http/https)" → "string". */
    private fun normalizeSchemaType(raw: String): String {
        val open = raw.indexOf('(')
        val base = if (open >= 0) raw.substring(0, open) else raw
        return sanitizeSchemaType(base.trim())
    }

    /** Maps a raw base type to a JSON-Schema primitive; unknown or empty values fall back to "string". */
    private fun sanitizeSchemaType(base: String): String {
        val lower = base.lowercase()
        val canonical = SCHEMA_TYPE_ALIASES[lower] ?: lower
        return canonical.takeIf { it in VALID_SCHEMA_TYPES } ?: "string"
    }

    /**
     * Extracts a human-readable description from a flat-style declaration,
     * dropping the "required" flag (it becomes a real JSON Schema entry):
     * "string (required) - hex bytes like 'DE AD BE EF'" → "hex bytes like 'DE AD BE EF'".
     */
    private fun extractFlatDescription(raw: String): String? {
        val open = raw.indexOf('(')
        if (open < 0) return null
        val close = raw.indexOf(')', open)
        if (close < 0) return null
        val annotation = raw.substring(open + 1, close)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("required", ignoreCase = true) }
            .joinToString(", ")
        val remainder = raw.substring(close + 1).trim().removePrefix("-").trim()
        return listOf(remainder, annotation)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
    }
}
