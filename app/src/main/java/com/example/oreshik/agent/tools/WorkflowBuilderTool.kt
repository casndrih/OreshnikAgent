package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** User-defined, persisted read-only tool workflows with a strict delegation allow-list. */
class WorkflowBuilderTool : AgentTool {
    private data class Step(val tool: String, val arguments: JsonObject, val retries: Int = 0)
    private data class Workflow(val id: String, val name: String, val steps: List<Step>, val createdAt: Long)
    private data class RunState(val workflowId: String, val nextStep: Int, val status: String, val lastError: String = "", val updatedAt: Long = System.currentTimeMillis())

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override val definition = ToolDefinition(
        name = "workflow.manage",
        category = ToolCategory.SKILLS,
        description = "Build, list, delete and run saved read-only tool workflows. Workflow steps are limited to local inspection and static analysis; network probes, writes, shell execution, device actions and credential-bearing tools are not allowed.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf(
            "action" to "string (list, create, delete, run, resume)",
            "id" to "string",
            "name" to "string",
            "steps" to "array of {tool, arguments, retries} objects (retries 0–2)",
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

    private fun stateFile(context: ToolContext, id: String): File = File(directory(context), "$id.run.json").canonicalFile.also {
        require(it.parentFile == directory(context).canonicalFile) { "Workflow state path escaped storage." }
    }

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "list") {
            "list" -> runCatching {
                val entries = directory(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".run.json") && !it.name.endsWith(".last-run.json") }.orEmpty()
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
                    require(WorkflowToolPolicy.canRun(tool)) {
                        "Step ${index + 1}: '$tool' is not an approved read-only workflow tool."
                    }
                    val params = step["arguments"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    val retries = (step["retries"]?.asInt ?: 0).coerceIn(0, 2)
                    Step(tool, params.deepCopy(), retries)
                }
                val workflow = Workflow(id, name, steps, System.currentTimeMillis())
                val destination = file(context, id)
                require(!destination.exists()) { "Workflow already exists. Delete it before replacing." }
                require(directory(context).listFiles()?.count { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".run.json") && !it.name.endsWith(".last-run.json") }?.let { it < 30 } != false) {
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
                stateFile(context, id).delete()
                ToolResult.success("Deleted workflow $id.")
            }.getOrElse { ToolResult.failure("Workflow deletion failed: ${it.message}") }

            "run", "resume" -> {
                val invoker = context.invokeCollaborativeTool
                    ?: return@withContext ToolResult.failure("Workflow execution is available only during an active agent run.")
                runCatching {
                    val id = args["id"]?.asString.orEmpty()
                    val workflow = gson.fromJson(file(context, id).readText(), Workflow::class.java)
                    require(workflow.steps.size in 1..12) { "Workflow step count is invalid." }
                    val statePath = stateFile(context, id)
                    var next = if (args["action"]?.asString?.equals("resume", ignoreCase = true) == true) {
                        require(statePath.isFile) { "No saved run state exists. Run the workflow first." }
                        val state = gson.fromJson(statePath.readText(), RunState::class.java)
                        require(state.workflowId == id && state.status in setOf("failed", "paused")) { "Only a failed or paused workflow can be resumed." }
                        state.nextStep.coerceIn(0, workflow.steps.size)
                    } else 0
                    require(next < workflow.steps.size) { "Workflow has no remaining steps." }
                    statePath.writeText(gson.toJson(RunState(id, next, "running")), Charsets.UTF_8)
                    val output = StringBuilder("Workflow: ${workflow.name}\nStarting at step ${next + 1}/${workflow.steps.size}\n")
                    while (next < workflow.steps.size) {
                        val step = workflow.steps[next]
                        require(WorkflowToolPolicy.canRun(step.tool)) {
                            "Workflow contains a tool that is no longer approved: ${step.tool}"
                        }
                        context.onProgress?.invoke("Workflow ${workflow.name}: step ${next + 1}/${workflow.steps.size} · ${step.tool}")
                        var result: ToolResult? = null
                        for (attempt in 0..step.retries.coerceIn(0, 2)) {
                            result = invoker(step.tool, step.arguments)
                            if (result?.ok == true) break
                            if (attempt < step.retries) context.onProgress?.invoke("Retrying ${step.tool} (${attempt + 1}/${step.retries})")
                        }
                        val completed = result ?: ToolResult.failure("No result returned.")
                        output.append("\n").append(next + 1).append(". ").append(step.tool)
                        if (!completed.ok) {
                            val error = completed.error.orEmpty().take(1000)
                            output.append(" — FAILED: ").append(error)
                            statePath.writeText(gson.toJson(RunState(id, next, "failed", error)), Charsets.UTF_8)
                            return@withContext ToolResult.failure(output.toString() + "\nResume with action=resume.")
                        }
                        output.append(" — OK\n").append(completed.output.take(5000))
                        next++
                        statePath.writeText(gson.toJson(RunState(id, next, "running")), Charsets.UTF_8)
                        if (output.length > 24_000) break
                    }
                    val status = if (next >= workflow.steps.size) "completed" else "paused"
                    statePath.writeText(gson.toJson(RunState(id, next, status)), Charsets.UTF_8)
                    ToolResult.success(output.append("\nStatus: ").append(status).toString())
                }.getOrElse { ToolResult.failure("Workflow run failed: ${it.message}") }
            }
            else -> ToolResult.failure("Supported actions: list, create, delete, run, resume.")
        }
    }
}
