package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun ctxFile(context: ToolContext, path: String): File {
    val root = context.workspace.canonicalFile
    val target = File(root, path).canonicalFile
    require(
        (target.path == root.path) || target.path.startsWith(root.path + File.separator),
    ) { "Path escapes workspace" }
    return target
}

private const val MAX_PATCH_FILE_BYTES = 10L * 1024L * 1024L

class FilePatchTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.patch",
        category = ToolCategory.FILESYSTEM,
        description = "Apply an exact old-text to new-text edit to a UTF-8 workspace file. Fails safely if old_text is not found exactly.",
        permissions = setOf(ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("path" to "string", "old_text" to "string", "new_text" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val pathArg = o["path"].asString
            val f = ctxFile(context, pathArg)
            if (!f.isFile) return@withContext ToolResult(ok = false, error = "File not found: $pathArg")
            if (f.length() > MAX_PATCH_FILE_BYTES) {
                return@withContext ToolResult(
                    ok = false,
                    error = "File is too large to patch safely (${f.length()} bytes; limit is $MAX_PATCH_FILE_BYTES)",
                )
            }
            val before = f.readText()
            val old = o["old_text"].asString
            if (old.isEmpty()) return@withContext ToolResult(ok = false, error = "old_text must not be empty")
            val first = before.indexOf(old)
            val second = if (first >= 0) before.indexOf(old, first + 1) else -1
            val count = when {
                first < 0 -> 0
                second >= 0 -> 2
                else -> 1
            }
            if (count != 1) {
                return@withContext ToolResult(
                    ok = false,
                    error = "Expected exactly one matching old_text block, found $count",
                )
            }
            f.writeText(before.replace(old, o["new_text"].asString))
            AppLogger.d(TAG, "file.patch applied to $pathArg")
            ToolResult(ok = true, "Patched $pathArg")
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.patch failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}

class FileDeleteTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.delete",
        category = ToolCategory.FILESYSTEM,
        description = "Delete a file or empty directory inside the workspace.",
        permissions = setOf(ToolPermission.DELETE, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("path" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val path = arguments.asJsonObject["path"].asString
            val f = ctxFile(context, path)
            if (!f.exists()) return@withContext ToolResult(ok = false, error = "Not found")
            val ok = f.delete()
            AppLogger.i(TAG, "file.delete $path → $ok")
            ToolResult(ok, if (ok) "Deleted $path" else "Delete failed")
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.delete failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}

private const val TAG = "FileTools"
