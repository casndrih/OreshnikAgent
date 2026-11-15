package com.example.oreshik.agent.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.util.ArrayDeque

/**
 * A small in-memory evidence ledger shared only during one agent run. It lets a
 * follow-up analysis tool inspect prior tool findings without persisting target
 * data into the app database or another chat.
 */
class ToolCollaborationSession(val id: String) {
    data class Evidence(
        val tool: String,
        val target: String,
        val ok: Boolean,
        val excerpt: String,
    )

    private val lock = Any()
    private val evidence = ArrayDeque<Evidence>()

    fun record(tool: AgentTool, arguments: JsonElement, result: ToolResult) {
        val name = tool.definition.name
        val isSensitiveMemoryAction = name in MEMORY_ACTION_TOOLS
        if (name !in EVIDENCE_TOOLS && !isSensitiveMemoryAction) return
        val item = Evidence(
            tool = name,
            target = safeTarget(arguments.argumentsAsJsonObjectOrNull()),
            ok = result.ok,
            excerpt = if (isSensitiveMemoryAction) {
                if (result.ok) "Memory action completed; refer to its original tool result for the approved details."
                else "Memory action failed; refer to its original tool result for details."
            } else buildString {
                result.error?.takeIf(String::isNotBlank)?.let { append("Error: ").append(it) }
                if (result.output.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(result.output)
                }
                if (result.metadata.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("Metadata: ").append(result.metadata.entries.take(8).joinToString { (key, value) ->
                        "$key=${value.toString().take(240)}"
                    })
                }
            }.take(MAX_EXCERPT_CHARS).ifBlank { if (result.ok) "Completed with no text output." else "Tool failed." },
        )
        synchronized(lock) {
            evidence.addLast(item)
            while (evidence.size > MAX_ITEMS) evidence.removeFirst()
        }
    }

    fun recent(limit: Int = MAX_ITEMS): List<Evidence> = synchronized(lock) {
        evidence.toList().takeLast(limit.coerceIn(1, MAX_ITEMS))
    }

    private fun safeTarget(arguments: JsonObject?): String {
        if (arguments == null) return "unspecified target"
        val file = listOf("file_path", "apk_path", "ida_database", "scan_results")
            .firstNotNullOfOrNull { key -> arguments.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }
        if (file != null) return "file:${file.substringAfterLast('/').substringAfterLast('\\').take(100)}"
        val rawUrl = arguments.get("url")?.takeIf { it.isJsonPrimitive }?.asString
        if (!rawUrl.isNullOrBlank()) {
            return runCatching {
                val uri = URI(rawUrl)
                val host = uri.host ?: return@runCatching "url:${rawUrl.substringBefore('?').take(160)}"
                "url:${uri.scheme ?: "https"}://$host${uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()}${uri.path.orEmpty().take(100)}"
            }.getOrDefault("url:${rawUrl.substringBefore('?').take(160)}")
        }
        arguments.get("host")?.takeIf { it.isJsonPrimitive }?.asString?.let { return "host:${it.take(120)}" }
        arguments.get("pid")?.takeIf { it.isJsonPrimitive }?.let { return "pid:${it.asString.take(24)}" }
        return "current device"
    }

    private fun JsonElement.argumentsAsJsonObjectOrNull(): JsonObject? =
        runCatching { if (isJsonObject) asJsonObject else null }.getOrNull()

    companion object {
        private const val MAX_ITEMS = 16
        private const val MAX_EXCERPT_CHARS = 2_400
        private val EVIDENCE_TOOLS = setOf(
            "re.ida.capabilities", "re.ida.analyze", "re.ida.xref", "re.r2.analyze", "re.rabin2",
            "re.ghidra.capabilities", "re.ghidra.analyze", "re.ghidra.decompile", "re.ghidra.xref",
            "re.mem.ida_correlate", "mem.regions", "mem.scan", "mem.visual.verify", "mem.inject.verify",
            "androidreadscreen", "android_screenshot", "androidanalyze",
            "web.sqli.passive", "web.xssscan", "web.headervuln", "net.portscan",
        )
        /** Keep status-only links to mutating memory calls so a later visual
         * check can follow up without retaining addresses, values, or payloads. */
        private val MEMORY_ACTION_TOOLS = setOf(
            "mem.write", "mem.inject", "mem.visual.adjust", "mem.stealth", "mem.zygisk", "mem.visual.autoplay",
        )
    }
}

/** Tools that can be delegated by a bounded collaboration workflow. Mutating,
 * injection, stealth, device-action, and arbitrary shell tools are deliberately
 * excluded; each delegated tool still goes through its own permission broker. */
object ToolCollaborationPolicy {
    private val allowed = setOf(
        "re.ida.capabilities", "re.ida.analyze", "re.ida.xref", "re.r2.analyze", "re.rabin2",
        "re.ghidra.capabilities", "re.ghidra.analyze", "re.ghidra.decompile", "re.ghidra.xref",
        "re.mem.ida_correlate", "mem.regions", "mem.scan", "mem.visual.verify", "mem.inject.verify",
        "androidreadscreen", "android_screenshot", "androidanalyze",
        "web.sqli.passive", "web.xssscan", "web.headervuln", "net.portscan",
    )

    fun canDelegate(name: String): Boolean = name in allowed
}
