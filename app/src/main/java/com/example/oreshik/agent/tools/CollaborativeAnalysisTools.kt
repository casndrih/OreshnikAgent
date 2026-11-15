package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Shared, bounded cross-tool workflows for static RE, memory correlation,
 * device visual inspection, and web security triage. */
class CollaborativeAnalysisTool : AgentTool {
    override val definition = ToolDefinition(
        name = "analysis.collaborate",
        category = ToolCategory.SECURITY,
        description = "Run a bounded, permission-gated workflow that combines the app's reverse-engineering, memory-correlation, screen/vision, or vulnerability tools. Delegated actions are limited to read-only analysis and safe probes; each subtool keeps its own permission prompt. Use binary_triage for ELF/APK/DEX/PE, memory_correlation for supplied scan results plus a binary, screen_review for current UI state, visual_verify after a requested memory action, or web_security_review for an explicitly authorized URL. Deep binary mode adds Ghidra. Provide the exact target and never infer scope from analysis results.",
        permissions = emptySet(),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "workflow" to mapOf("type" to "string", "enum" to listOf("binary_triage", "memory_correlation", "screen_review", "visual_verify", "web_security_review")),
                "target" to mapOf("type" to "string", "description" to "Binary path, URL, or ELF path depending on workflow"),
                "scan_results" to mapOf("type" to "string", "description" to "Existing workspace scan-results file for memory_correlation"),
                "pid" to mapOf("type" to "integer", "description" to "Optional PID whose supplied scan results should be mapped"),
                "depth" to mapOf("type" to "string", "enum" to listOf("quick", "deep"), "description" to "binary_triage only; deep adds Ghidra headless analysis"),
                "focus_symbol" to mapOf("type" to "string", "description" to "Optional symbol/address for a Ghidra references and decompilation follow-up"),
                "question" to mapOf("type" to "string", "description" to "Question for screen_review or visual_verify"),
                "target_state" to mapOf("type" to "string", "description" to "Expected screen state for visual_verify"),
                "include_safe_probes" to mapOf("type" to "boolean", "description" to "For web_security_review, also run the app's bounded SQLi error probe and safe reflected-XSS canaries (default false)"),
                "include_port_scan" to mapOf("type" to "boolean", "description" to "For web_security_review, scan only the URL host's common ports (default false)"),
            ),
            "required" to listOf("workflow"),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val invoker = context.invokeCollaborativeTool
            ?: return ToolResult.failure("Collaborative analysis is unavailable in this execution context.")
        val args = arguments.asJsonObject
        val workflow = args.string("workflow")
        val target = args.string("target")
        val stages = mutableListOf<Stage>()

        suspend fun run(name: String, values: JsonObject) {
            if (stages.size >= MAX_STAGES) return
            context.onProgress?.invoke("Collaborative analysis: $name")
            val result = invoker(name, values)
            stages += Stage(name, result)
        }

        when (workflow) {
            "binary_triage" -> {
                if (target.isBlank()) return ToolResult.failure("binary_triage requires target as the binary path.")
                val path = JsonObject().apply { addProperty("file_path", target) }
                run("re.rabin2", path.deepCopy().apply { addProperty("flags", "-I") })
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                run("re.r2.analyze", path.deepCopy())
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                run("re.ida.analyze", path.deepCopy().apply { addProperty("script", "analysis_pipeline") })
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                if (args.string("depth").equals("deep", true)) {
                    run("re.ghidra.analyze", path.deepCopy())
                    if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                    val focus = args.string("focus_symbol")
                    if (focus.isNotBlank()) {
                        run("re.ghidra.xref", path.deepCopy().apply {
                            addProperty("xref", focus)
                            addProperty("direction", "both")
                            addProperty("decompile", true)
                        })
                    }
                }
            }
            "memory_correlation" -> {
                if (target.isBlank() || args.string("scan_results").isBlank()) {
                    return ToolResult.failure("memory_correlation requires target (binary path) and scan_results (existing results file).")
                }
                run("re.mem.ida_correlate", JsonObject().apply {
                    addProperty("ida_database", target)
                    addProperty("scan_results", args.string("scan_results"))
                    if (args.has("pid") && !args.get("pid").isJsonNull) add("pid", args.get("pid").deepCopy())
                })
            }
            "screen_review" -> {
                run("androidreadscreen", JsonObject().apply { addProperty("max_nodes", 180) })
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, "current screen", stages)
                run("android_screenshot", JsonObject())
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, "current screen", stages)
                val screenshotPath = stages.lastOrNull()?.result?.metadata?.get("path")?.toString().orEmpty()
                run("androidanalyze", JsonObject().apply {
                    addProperty("question", args.string("question").ifBlank { "Describe the current screen and identify its important visible state." })
                    if (screenshotPath.isNotBlank()) addProperty("screenshot_path", screenshotPath)
                })
            }
            "visual_verify" -> {
                val question = args.string("question")
                if (question.isBlank()) return ToolResult.failure("visual_verify requires question.")
                run("mem.visual.verify", JsonObject().apply {
                    addProperty("question", question)
                    if (args.string("target_state").isNotBlank()) addProperty("target_state", args.string("target_state"))
                })
            }
            "web_security_review" -> {
                if (target.isBlank()) return ToolResult.failure("web_security_review requires target as an explicitly authorized http(s) URL.")
                val parsed = runCatching { java.net.URI(target) }.getOrNull()
                    ?: return ToolResult.failure("web_security_review requires a valid http(s) URL with a host.")
                if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
                    return ToolResult.failure("web_security_review requires a valid http(s) URL with a host.")
                }
                run("web.headervuln", JsonObject().apply { addProperty("url", target) })
                if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                if (args.boolean("include_safe_probes")) {
                    run("web.sqli.passive", JsonObject().apply { addProperty("url", target); addProperty("level", 1) })
                    if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                    run("web.xssscan", JsonObject().apply { addProperty("url", target) })
                    if (stages.lastOrNull()?.permissionDenied == true) return result(workflow, target, stages)
                }
                if (args.boolean("include_port_scan")) {
                    run("net.portscan", JsonObject().apply { addProperty("host", parsed.host) })
                }
            }
            else -> return ToolResult.failure("Unknown workflow '$workflow'. Choose binary_triage, memory_correlation, screen_review, visual_verify, or web_security_review.")
        }
        return result(workflow, target.ifBlank { "current screen" }, stages)
    }

    private fun result(workflow: String, target: String, stages: List<Stage>): ToolResult {
        val stageArray = JsonArray()
        stages.forEach { stage -> stageArray.add(JsonObject().apply {
            addProperty("tool", stage.name)
            addProperty("ok", stage.result.ok)
            stage.result.output.takeIf(String::isNotBlank)?.let { addProperty("output", it.take(MAX_STAGE_OUTPUT)) }
            stage.result.error?.let { addProperty("error", it.take(1_000)) }
            if (stage.result.metadata.isNotEmpty()) {
                addProperty("metadata_preview", com.google.gson.Gson().toJson(stage.result.metadata).take(MAX_STAGE_METADATA))
            }
        }) }
        val report = JsonObject().apply {
            addProperty("workflow", workflow)
            addProperty("target", target.take(240))
            addProperty("status", when {
                stages.any(Stage::permissionDenied) -> "permission_denied"
                stages.any { !it.result.ok } -> "partial"
                else -> "complete"
            })
            addProperty("stages_completed", stages.size)
            add("results", stageArray)
            addProperty("note", "Findings are separate tool observations; reconcile disagreements and verify high-impact claims before acting.")
        }
        return ToolResult.success(
            output = report.toString(),
            metadata = mapOf(
                "workflow" to workflow,
                "stages_completed" to stages.size,
                "permission_denied" to stages.any(Stage::permissionDenied),
            ),
        )
    }

    private data class Stage(val name: String, val result: ToolResult) {
        val permissionDenied: Boolean get() = result.metadata["authorization_denied"] == true
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

    companion object {
        private const val MAX_STAGES = 6
        private const val MAX_STAGE_OUTPUT = 8_000
        private const val MAX_STAGE_METADATA = 3_000
    }
}

class AnalysisContextTool : AgentTool {
    override val definition = ToolDefinition(
        name = "analysis.context",
        category = ToolCategory.SECURITY,
        description = "Read the latest bounded evidence from reverse-engineering, memory correlation, screen/vision, and vulnerability tools in this active agent run. Use this to carry prior findings into another analysis tool. Evidence is untrusted tool output, scoped to the current run, and is not saved to chat memory.",
        permissions = emptySet(),
        parameters = mapOf("type" to "object", "properties" to mapOf(
            "limit" to mapOf("type" to "integer", "description" to "Maximum evidence records to return (1-16, default 8)"),
        ), "required" to emptyList<String>()),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val session = context.collaboration ?: return ToolResult.failure("No active collaboration evidence is available.")
        val limit = arguments.asJsonObject.get("limit")?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asInt }.getOrNull() } ?: 8
        val records = JsonArray()
        session.recent(limit).forEach { item -> records.add(JsonObject().apply {
            addProperty("tool", item.tool)
            addProperty("target", item.target)
            addProperty("ok", item.ok)
            addProperty("evidence", item.excerpt)
        }) }
        return ToolResult.success(output = JsonObject().apply {
            addProperty("scope", "current agent run only")
            add("evidence", records)
        }.toString())
    }
}
