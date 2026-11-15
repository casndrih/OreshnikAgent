package com.example.oreshik.agent.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.oreshik.agent.core.WorkflowToolPolicy
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolRegistry
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.agent.security.WorkspaceOwnershipGuard
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/** Executes only the persisted read-only workflow allow-list; background jobs never show approval UI. */
class ScheduledWorkflowWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_WORKFLOW_ID).orEmpty()
        if (!id.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) return Result.failure()
        val directory = File(applicationContext.filesDir, "agent-workflows").canonicalFile
        val workflowFile = File(directory, "$id.json").canonicalFile
        if (workflowFile.parentFile != directory || !workflowFile.isFile) return Result.failure()
        return try {
            val root = JsonParser.parseString(workflowFile.readText()).asJsonObject
            val steps = root.getAsJsonArray("steps") ?: return Result.failure()
            if (steps.size() !in 1..12) return Result.failure()
            val runtime = RuntimeManager(applicationContext)
            val registry = ToolRegistry(applicationContext, runtime)
            val permissions = PermissionManager(applicationContext)
            val toolContext = ToolContext(
                workspace = runtime.workspace,
                app = applicationContext,
                permissionManager = permissions,
                runtime = runtime,
                sessionId = "scheduled-$id",
            )
            val report = StringBuilder()
            for ((index, item) in steps.withIndex()) {
                val step = item.asJsonObject
                val name = step.get("tool")?.asString.orEmpty()
                if (!WorkflowToolPolicy.canRun(name)) {
                    report.append("Step ${index + 1}: rejected non-allow-listed tool $name")
                    saveReport(directory, id, "blocked", report.toString())
                    return Result.failure()
                }
                val tool = registry.get(name) ?: run {
                    report.append("Step ${index + 1}: tool unavailable: $name")
                    saveReport(directory, id, "failed", report.toString())
                    return Result.failure()
                }
                if (!permissions.check(name, tool.definition.permissions)) {
                    report.append("Step ${index + 1}: permission not pre-approved: $name")
                    saveReport(directory, id, "permission_required", report.toString())
                    return Result.failure()
                }
                WorkspaceOwnershipGuard.ensureAppOwned(applicationContext, runtime.workspace)
                val args = step.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: com.google.gson.JsonObject()
                val result = tool.execute(toolContext, args)
                report.append(index + 1).append(". ").append(name).append(" — ")
                    .append(if (result.ok) "OK" else "FAILED")
                    .append(": ").append((result.output.ifBlank { result.error.orEmpty() }).take(2500)).append('\n')
                if (!result.ok) {
                    saveReport(directory, id, "failed", report.toString())
                    return Result.failure()
                }
            }
            saveReport(directory, id, "completed", report.toString())
            Result.success()
        } catch (failure: Exception) {
            AppLogger.e(TAG, "Scheduled workflow failed (${failure.javaClass.simpleName})", failure)
            saveReport(directory, id, "error", failure.message.orEmpty().take(1000))
            Result.retry()
        }
    }

    private fun saveReport(directory: File, id: String, status: String, report: String) {
        runCatching {
            val target = File(directory, "$id.last-run.json")
            val payload = mapOf(
                "workflowId" to id,
                "status" to status,
                "finishedAt" to System.currentTimeMillis(),
                "report" to report.take(24_000),
            )
            target.writeText(Gson().toJson(payload), Charsets.UTF_8)
        }
    }

    companion object {
        const val KEY_WORKFLOW_ID = "workflow_id"
        private const val TAG = "ScheduledWorkflow"
    }
}
