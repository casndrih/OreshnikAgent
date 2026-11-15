package com.example.oreshik.logging

import android.content.Context
import com.example.oreshik.agent.core.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.util.Locale

/**
 * Append-only, app-private audit trail for agent tool execution.
 *
 * Arguments are recursively redacted before they are persisted. Tool output is
 * deliberately not stored: the audit record keeps only the outcome and a short,
 * redacted error summary so secrets and large workspace contents are not copied
 * into a second log.
 */
object ToolAuditLog {
    private const val FILE_NAME = "tool-audit.jsonl"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_TEXT_CHARS = 4_000
    private val lock = Any()
    private val gson = Gson()
    private val secretKey = Regex(
        "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|cookie|private[_-]?key|credential)"
    )
    private val inlineSecret = Regex(
        "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|cookie|private[_-]?key|credential)(\\s*[:=]\\s*)([^\\s,;]+)"
    )
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")

    fun record(
        context: Context,
        sessionId: String?,
        tool: String,
        arguments: JsonElement?,
        decision: String,
        result: ToolResult? = null,
        durationMs: Long? = null,
    ) {
        runCatching {
            val row = JsonObject().apply {
                addProperty("timestamp", System.currentTimeMillis())
                addProperty("sessionId", sessionId.orEmpty().take(128))
                addProperty("tool", tool.take(160))
                addProperty("decision", decision.take(32))
                add("arguments", redact(arguments))
                result?.let {
                    addProperty("ok", it.ok)
                    it.error?.takeIf(String::isNotBlank)?.let { error ->
                        addProperty("error", redactText(error).take(500))
                    }
                }
                durationMs?.let { addProperty("durationMs", it.coerceAtLeast(0L)) }
            }
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            synchronized(lock) {
                if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                    val previous = File(file.parentFile, "$FILE_NAME.1")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(gson.toJson(row) + "\n", Charsets.UTF_8)
            }
        }.onFailure { AppLogger.w("ToolAuditLog", "Could not write audit event", it) }
    }

    /** Returns the newest bounded audit records, newest first. */
    fun readRecent(context: Context, limit: Int = 200): List<String> = runCatching {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf(File::exists)
                ?.readLines(Charsets.UTF_8)
                ?.takeLast(limit.coerceIn(1, 2_000))
                ?.asReversed()
                .orEmpty()
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context): Boolean = runCatching {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME).let { !it.exists() || it.delete() }
        }
    }.getOrDefault(false)

    private fun redact(value: JsonElement?): JsonElement = when {
        value == null || value is JsonNull -> JsonNull.INSTANCE
        value.isJsonObject -> JsonObject().apply {
            value.asJsonObject.entrySet().forEach { (key, child) ->
                add(key, if (secretKey.containsMatchIn(key)) JsonPrimitive("[REDACTED]") else redact(child))
            }
        }
        value.isJsonArray -> JsonArray().apply {
            value.asJsonArray.forEach { add(redact(it)) }
        }
        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
            JsonPrimitive(redactText(value.asString).take(MAX_TEXT_CHARS))
        else -> value.deepCopy()
    }

    private fun redactText(value: String): String = bearer.replace(
        inlineSecret.replace(value) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" },
        "Bearer [REDACTED]",
    ).replace(Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}"), "[REDACTED_KEY]")
}
