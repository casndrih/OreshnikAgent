package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.agent.runtime.ShellWords
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement

private const val TAG = "PythonTool"

class PythonExecTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "python.exec",
        category = ToolCategory.PROCESS,
        description = "Execute Python code using the app-owned CPython runtime. The current directory is the workspace.",
        permissions = setOf(ToolPermission.EXECUTE_PYTHON),
        parameters = mapOf("code" to "string", "timeout_seconds" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val obj = arguments.asJsonObject
        val code = obj["code"]?.asString ?: return ToolResult(ok = false, error = "Missing code")
        val timeout = obj["timeout_seconds"]?.asInt ?: 60
        AppLogger.d(TAG, "python.exec (${code.length} chars, timeout=${timeout}s)")
        val pyResult = runtime.python.execute(code, context.workspace, timeout)
        val runtimeResult = pyResult.getOrNull()
        if (runtimeResult != null) {
            val output = buildString {
                append(runtimeResult.stdout)
                if (runtimeResult.stderr.isNotBlank()) {
                    append("\nSTDERR:\n")
                    append(runtimeResult.stderr)
                }
            }
            return ToolResult(runtimeResult.ok, output, runtimeResult.error, runtimeResult.exitCode)
        }
        val failure = pyResult.exceptionOrNull()
        AppLogger.w(TAG, "python.exec runtime failed: ${failure?.message}")
        return ToolResult(ok = false, error = "Python runtime failed: ${failure?.message}")
    }
}

class PythonRunFileTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "python.run_file",
        category = ToolCategory.PROCESS,
        description = "Run an existing Python source file in the app-owned CPython runtime.",
        permissions = setOf(ToolPermission.EXECUTE_PYTHON, ToolPermission.READ_WORKSPACE),
        parameters = mapOf("path" to "string", "timeout_seconds" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val o = arguments.asJsonObject
        val path = o["path"]?.asString ?: return ToolResult(ok = false, error = "Missing path")
        val root = context.workspace.canonicalFile
        val f = java.io.File(root, path).canonicalFile
        if ((!f.path.startsWith(root.path + java.io.File.separator)) || (!f.isFile)) {
            return ToolResult(ok = false, error = "Python file not found in workspace")
        }
        AppLogger.d(TAG, "python.run_file $path")
        return PythonExecTool(runtime).execute(
            context,
            com.google.gson.JsonObject().apply {
                addProperty("code", f.readText())
                addProperty("timeout_seconds", o["timeout_seconds"]?.asInt ?: 60)
            },
        )
    }
}

class PythonPipTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "python.pip",
        category = ToolCategory.RUNTIME,
        description = "Run pip for the app-owned CPython. Install and uninstall commands are scoped to private workspace .python-packages; installs accept Android-compatible wheels only.",
        permissions = setOf(ToolPermission.EXECUTE_PYTHON, ToolPermission.NETWORK, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("command" to "string", "timeout_seconds" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val obj = arguments.asJsonObject
        val command = obj["command"]?.asString.orEmpty()
        val args = ShellWords.split(command)
        if (args.isEmpty()) return ToolResult(ok = false, error = "Provide a pip command, such as 'install requests' or 'list'")
        val timeout = (obj["timeout_seconds"]?.asInt ?: 600).coerceIn(1, 1800)
        val result = runtime.python.executePip(args, context.workspace, timeout).getOrNull()
            ?: return ToolResult(ok = false, error = "Python pip runtime failed")
        val output = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) append("\nSTDERR:\n").append(result.stderr)
            if (result.error != null && result.stderr.isBlank()) append(result.error)
        }
        return ToolResult(result.ok, output, result.error, result.exitCode)
    }
}
