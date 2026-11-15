package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RootTool"

class RootExecTool(private val root: RootManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "root.exec",
        category = ToolCategory.ROOT,
        description = "Execute a deliberately elevated command through the device's su/root provider. Use only when a normal app-owned operation cannot perform the task.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS, ToolPermission.ROOT),
        parameters = mapOf("command" to "string", "timeout_seconds" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val command = arguments.asJsonObject["command"]?.asString
            ?: return@withContext ToolResult(ok = false, error = "Missing command")
        if (command.isBlank()) return@withContext ToolResult(ok = false, error = "Empty root command")
        val timeout = (arguments.asJsonObject["timeout_seconds"]?.asLong ?: 60L).coerceIn(1, 600)
        if (!root.canUseRoot()) {
            return@withContext ToolResult(
                ok = false,
                error = "Root is unavailable or disabled. Enable Allow root in Settings and grant your root manager prompt if requested.",
            )
        }
        return@withContext try {
            val (code, output) = root.execute(command, timeout)
            ToolResult(
                code == 0,
                output = output,
                error = if (code == 0) null else "Root command exited with code $code",
                exitCode = code,
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "root.exec failed: ${e.message}", e)
            ToolResult(ok = false, error = e.message ?: "Root execution failed")
        }
    }
}

class RootInfoTool(private val root: RootManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "root.info",
        category = ToolCategory.ROOT,
        description = "Check whether an Android su/root provider is available and whether agent root access is enabled.",
        permissions = setOf(ToolPermission.ROOT),
        parameters = emptyMap(),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "root.info: enabled=${root.isEnabled()} available=${root.status().available}")
        ToolResult(
            ok = true,
            "root_enabled=${root.isEnabled()}\nroot_available=${root.status().available}\nuid=${root.status().uid.ifBlank { "not root" }}\nsu=${root.status().suPath.ifBlank { "not found" }}",
        )
    }
}
