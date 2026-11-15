package com.example.oreshik.agent.tools

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.workflow.ScheduledWorkflowWorker
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Schedules only workflows whose steps pass the read-only collaboration allow-list. */
class WorkflowScheduleTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workflow.schedule",
        category = ToolCategory.SKILLS,
        description = "Schedule, inspect or cancel a saved read-only workflow. Periodic execution is persistent across app restarts; minimum interval is 15 minutes. Background execution requires all tool permissions to be pre-approved.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf(
            "action" to "string (schedule, list, cancel)",
            "workflow_id" to "string",
            "interval_minutes" to "integer (15–43200)",
            "network_required" to "boolean",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        val manager = WorkManager.getInstance(context.app)
        val action = args["action"]?.asString?.lowercase()?.trim() ?: "list"
        val id = args["workflow_id"]?.asString?.trim().orEmpty()
        if (id.isNotBlank() && !id.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) {
            return@withContext ToolResult.failure("Invalid workflow id.")
        }
        val uniqueName = "oreshik-workflow-$id"
        when (action) {
            "schedule" -> runCatching {
                require(id.isNotBlank()) { "workflow_id is required." }
                val directory = File(context.app.filesDir, "agent-workflows").canonicalFile
                val workflow = File(directory, "$id.json").canonicalFile
                require(workflow.parentFile == directory && workflow.isFile) { "Saved workflow not found." }
                val root = com.google.gson.JsonParser.parseString(workflow.readText()).asJsonObject
                val steps = root.getAsJsonArray("steps") ?: error("Workflow steps are missing.")
                require(steps.size() in 1..12) { "Workflow must contain 1–12 steps." }
                var needsNetwork = false
                val registry = ToolRegistry(context.app, context.runtime)
                steps.forEachIndexed { index, item ->
                    val name = item.asJsonObject.get("tool")?.asString.orEmpty()
                    require(WorkflowToolPolicy.canRun(name)) {
                        "Step ${index + 1} is not approved for background read-only execution: $name"
                    }
                    val tool = registry.get(name) ?: error("Workflow tool is unavailable: $name")
                    if (ToolPermission.NETWORK in tool.definition.permissions) needsNetwork = true
                }
                val interval = (args["interval_minutes"]?.asLong ?: 60L).coerceIn(15L, 43_200L)
                val network = (args["network_required"]?.asBoolean ?: false) || needsNetwork
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (network) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                    .build()
                val request = PeriodicWorkRequestBuilder<ScheduledWorkflowWorker>(interval, TimeUnit.MINUTES)
                    .setInputData(androidx.work.Data.Builder().putString(ScheduledWorkflowWorker.KEY_WORKFLOW_ID, id).build())
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                manager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.REPLACE, request)
                ToolResult.success("Scheduled workflow $id every $interval minutes. WorkManager will run it when constraints and battery conditions permit.")
            }.getOrElse { ToolResult.failure("Could not schedule workflow: ${it.message}") }

            "list" -> runCatching {
                val infos = if (id.isBlank()) {
                    val directory = File(context.app.filesDir, "agent-workflows")
                    directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".run.json") && !it.name.endsWith(".last-run.json") }?.flatMap { file ->
                        manager.getWorkInfosForUniqueWork("oreshik-workflow-${file.nameWithoutExtension}").get()
                            .map { info -> "${file.nameWithoutExtension}: ${info.state}" }
                    }.orEmpty()
                } else {
                    manager.getWorkInfosForUniqueWork(uniqueName).get().map { it.state.name }
                }
                ToolResult.success(infos.ifEmpty { listOf("No scheduled workflows.") }.joinToString("\n"))
            }.getOrElse { ToolResult.failure("Could not read workflow schedules: ${it.message}") }

            "cancel" -> runCatching {
                require(id.isNotBlank()) { "workflow_id is required." }
                manager.cancelUniqueWork(uniqueName)
                ToolResult.success("Cancellation requested for scheduled workflow $id.")
            }.getOrElse { ToolResult.failure("Could not cancel workflow: ${it.message}") }

            else -> ToolResult.failure("Supported actions: schedule, list, cancel.")
        }
    }
}
