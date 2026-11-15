package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** User-defined, persisted read-only tool workflows with a strict delegation allow-list. */
class WorkflowBuilderTool : AgentTool {
    private data class Step(val tool: String, val arguments: JsonObject)
    private data class Workflow(val id: String, val name: String, val steps: List<Step>, val createdAt: Long)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val allowed = setOf(
        "file.list", "file.read", "file.search", "git.status", "git.diff", "git.log",
        "runtime.info", "skills.list", "memory.stats", "analysis.context",
        "re.ida.capabilities", "re.ida.analyze", "re.ida.xref",
        "re.ghidra.capabilities", "re.ghidra.analyze", "re.ghidra.decompile", "re.ghidra.xref",
        "web.sqli.passive", "web.xssscan", "web.headervuln", "net.portscan",
    )

    override val definition = ToolDefinition(
        name = "workflow.manage",
        category = ToolCategory.SKILLS,
        description = "Build, list, delete and run saved read-only tool workflows. Workflow steps are limited to inspection and analysis tools; writes, shell execution, device actions and credential-bearing tools are not allowed.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf(
            "action" to "string (list, create, delete, run)",
            "id" to "string",
            "name" to "string",
            "steps" to "array of {tool, arguments} objects",
        ),
    )

    private fun directory(context: ToolContext) = File(context.app.filesDir, "agent-workflows").apply { mkdirs() }
    private fun validId(id: String) = id.matches(Regex("^[a-zA-Z0-9_-]{1,48}$"))
    private fun file(context: ToolContext, id: String): File {
        require(validId(id)) { "Invalid workflow ID." }
        return File(directory(context), "$id.json").canonicalFile.also {
            require(it.parentFile == directory(context).canonicalFile) { "Workflow path escaped storage." }
        }
    }

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "list") {
            "list" -> runCatching {
                val entries = directory(context).listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
                ToolResult.success(entries.sortedBy { it.name }.joinToString("\n") { entry ->
                    val workflow = gson.fromJson(entry.readText(), Workflow::class.java)
                    "${workflow.id} · ${workflow.name} · ${workflow.steps.size} steps"
                }.ifBlank { "No saved workflows." })
            }.getOrElse { ToolResult.failure("Could not list workflows: ${it.message}") }

            "create" -> runCatching {
                val id = args["id"]?.asString?.trim().orEmpty()
                val name = args["name"]?.asString?.trim().orEmpty()
                require(validId(id)) { "Workflow id must contain 1–48 letters, numbers, underscores or hyphens." }
                require(name.isNotBlank() && name.length <= 80) { "Workflow name must be 1–80 characters." }
                val array = args.getAsJsonArray("steps") ?: error("steps must be an array.")
                require(array.size() in 1..12) { "A workflow must contain 1–12 steps." }
                val steps = array.mapIndexed { index, item ->
                    val step = item.asJsonObject
                    val tool = step["tool"]?.asString?.trim().orEmpty()
                    require(tool in allowed && ToolCollaborationPolicy.canDelegate(tool)) {
                        "Step ${index + 1}: '$tool' is not an approved read-only workflow tool."
                    }
                    val params = step["arguments"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    Step(tool, params.deepCopy())
                }
                val workflow = Workflow(id, name, steps, System.currentTimeMillis())
                val destination = file(context, id)
                require(!destination.exists()) { "Workflow already exists. Delete it before replacing." }
                require(directory(context).listFiles()?.count { it.extension == "json" }?.let { it < 30 } != false) {
                    "Workflow limit reached (30)."
                }
                val serialized = gson.toJson(workflow)
                require(serialized.length <= 32_000) { "Workflow definition exceeds 32 KB." }
                destination.writeText(serialized, Charsets.UTF_8)
                ToolResult.success("Saved workflow '$name' ($id) with ${steps.size} read-only steps.")
            }.getOrElse { ToolResult.failure("Workflow creation failed: ${it.message}") }

            "delete" -> runCatching {
                val id = args["id"]?.asString.orEmpty()
                val target = file(context, id)
                require(target.isFile && target.delete()) { "Workflow not found or could not be deleted." }
                ToolResult.success("Deleted workflow $id.")
            }.getOrElse { ToolResult.failure("Workflow deletion failed: ${it.message}") }

            "run" -> {
                val invoker = context.invokeCollaborativeTool
                    ?: return@withContext ToolResult.failure("Workflow execution is available only during an active agent run.")
                runCatching {
                    val id = args["id"]?.asString.orEmpty()
                    val workflow = gson.fromJson(file(context, id).readText(), Workflow::class.java)
                    require(workflow.steps.size in 1..12) { "Workflow step count is invalid." }
                    val output = StringBuilder("Workflow: ${workflow.name}\n")
                    for ((index, step) in workflow.steps.withIndex()) {
                        require(step.tool in allowed && ToolCollaborationPolicy.canDelegate(step.tool)) {
                            "Workflow contains a tool that is no longer approved: ${step.tool}"
                        }
                        context.onProgress?.invoke("Workflow ${workflow.name}: step ${index + 1}/${workflow.steps.size} · ${step.tool}")
                        val result = invoker(step.tool, step.arguments)
                        output.append("\n").append(index + 1).append(". ").append(step.tool)
                        if (result.ok) {
                            output.append(" — OK\n").append(result.output.take(5000))
                        } else {
                            output.append(" — FAILED: ").append(result.error.orEmpty().take(1000))
                            return@withContext ToolResult.failure(output.toString())
                        }
                        if (output.length > 24_000) break
                    }
                    ToolResult.success(output.toString())
                }.getOrElse { ToolResult.failure("Workflow run failed: ${it.message}") }
            }
            else -> ToolResult.failure("Supported actions: list, create, delete, run.")
        }
    }
}
