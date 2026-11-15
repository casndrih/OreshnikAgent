package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "FileTools"
private const val MAX_SEARCH_ENTRIES = 5_000
private const val MAX_SEARCH_FILE_BYTES = 2_000_000L
private const val MAX_SEARCH_MATCHES = 200
private const val MAX_SEARCH_OUTPUT_CHARS = 200_000
private const val MAX_SEARCH_LINE_CHARS = 2_000

private fun args(e: JsonElement) = e.asJsonObject

private fun ToolContext.safe(path: String): File {
    val root = workspace.canonicalFile
    // Tool callers sometimes include the already-active workspace directory
    // (`workspace/foo`). Resolve that as `foo` instead of nesting another
    // workspace directory under the root. Strip repeated prefixes as well.
    var relativePath = path.trim().replace('\\', '/')
    while (relativePath == root.name || relativePath.startsWith(root.name + "/")) {
        relativePath = relativePath.removePrefix(root.name).removePrefix("/")
    }
    var candidate = File(root, relativePath).absoluteFile
    val rootPrefix = root.path + File.separator
    if (candidate.path.startsWith(rootPrefix)) {
        var suffix = candidate.path.removePrefix(rootPrefix)
        while (suffix == root.name || suffix.startsWith(root.name + File.separator)) {
            suffix = suffix.removePrefix(root.name).removePrefix(File.separator)
        }
        candidate = File(root, suffix)
    }
    val target = candidate.canonicalFile
    require(
        (target.path == root.path) || target.path.startsWith(root.path + File.separator),
    ) { "Path escapes workspace" }
    return target
}

class FileListTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.list",
        category = ToolCategory.FILESYSTEM,
        description = "List files and directories in the workspace.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("path" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val dir = context.safe(args(arguments)["path"]?.asString ?: ".")
            if (!dir.isDirectory) return@withContext ToolResult(ok = false, error = "Not a directory: ${dir.path}")
            val entries = dir.listFiles().orEmpty()
            val sorted = entries.sortedBy(File::getName)
            val out = sorted.joinToString("\n") { f -> if (f.isDirectory) "[DIR] ${f.name}" else "${f.name} (${f.length()} bytes)" }
            AppLogger.d(TAG, "file.list ${dir.path}: ${sorted.size} entries")
            ToolResult(ok = true, out)
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.list failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}

class FileReadTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.read",
        category = ToolCategory.FILESYSTEM,
        description = "Read a UTF-8 text file from the workspace.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("path" to "string", "max_bytes" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val a = args(arguments)
            val pathArg = a["path"].asString
            val f = context.safe(pathArg)
            val max = (a["max_bytes"]?.asInt ?: 200_000).coerceIn(1, 1_000_000)
            if (!f.isFile) return@withContext ToolResult(ok = false, error = "File not found: $pathArg")
            val totalBytes = f.length()
            val bytes = f.inputStream().use { input ->
                val output = ByteArrayOutputStream(minOf(max, 8192))
                val buffer = ByteArray(8192)
                var remaining = max
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            ToolResult(
                ok = true,
                output = text,
                metadata = mapOf(
                    "truncated" to (totalBytes > bytes.size),
                    "bytes" to totalBytes,
                    "bytes_read" to bytes.size,
                ),
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.read failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}

class FileWriteTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.write",
        category = ToolCategory.FILESYSTEM,
        description = "Create or replace a UTF-8 text file inside the workspace.",
        permissions = setOf(ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("path" to "string", "content" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val a = args(arguments)
            val pathArg = a["path"].asString
            val f = context.safe(pathArg)
            f.parentFile?.mkdirs()
            f.writeText(a["content"].asString)
            AppLogger.i(TAG, "file.write $pathArg (${a["content"].asString.length} chars)")
            ToolResult(ok = true, "Wrote $pathArg")
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.write failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}

class FileSearchTool : AgentTool {
    override val definition = ToolDefinition(
        name = "file.search",
        category = ToolCategory.FILESYSTEM,
        description = "Search text recursively in workspace files.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("query" to "string", "path" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement) = withContext(Dispatchers.IO) {
        try {
            val a = args(arguments)
            val query = a["query"].asString
            if (query.isBlank()) return@withContext ToolResult(ok = false, error = "Search query must not be blank")

            val workspaceRoot = context.workspace.canonicalFile
            val root = context.safe(a["path"]?.asString ?: ".")
            if (!root.isDirectory) return@withContext ToolResult(ok = false, error = "Not a directory: " + root.path)

            val pending = java.util.ArrayDeque<File>().apply { add(root) }
            val visitedDirectories = mutableSetOf<String>()
            val output = StringBuilder()
            var scannedEntries = 0
            var matches = 0
            var truncated = false

            while (pending.isNotEmpty() && !truncated) {
                val directory = pending.removeLast()
                val canonicalDirectory = runCatching {
                    context.safe(directory.relativeTo(workspaceRoot).path)
                }.getOrNull() ?: continue
                if (!canonicalDirectory.isDirectory || !visitedDirectories.add(canonicalDirectory.path)) continue

                for (entry in canonicalDirectory.listFiles().orEmpty()) {
                    if (scannedEntries >= MAX_SEARCH_ENTRIES) {
                        truncated = true
                        break
                    }
                    scannedEntries++
                    val relativePath = runCatching { entry.relativeTo(workspaceRoot).path }.getOrNull() ?: continue
                    // Resolve each entry before reading or descending so symlinks cannot
                    // make a workspace search escape its canonical root.
                    val safeEntry = runCatching { context.safe(relativePath) }.getOrNull() ?: continue
                    if (safeEntry.isDirectory) {
                        pending.add(safeEntry)
                        continue
                    }
                    if (!safeEntry.isFile || safeEntry.length() > MAX_SEARCH_FILE_BYTES) continue

                    runCatching {
                        safeEntry.inputStream().bufferedReader().use { reader ->
                            var lineNumber = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                lineNumber++
                                if (!line.contains(query, ignoreCase = true)) continue
                                matches++
                                if (matches > MAX_SEARCH_MATCHES) {
                                    truncated = true
                                    break
                                }
                                val shownLine = line.take(MAX_SEARCH_LINE_CHARS)
                                val suffix = if (line.length > shownLine.length) " … [line truncated]" else ""
                                val hit = safeEntry.relativeTo(workspaceRoot).path + ":" +
                                    lineNumber + ": " + shownLine + suffix + "\n"
                                if (output.length + hit.length > MAX_SEARCH_OUTPUT_CHARS) {
                                    truncated = true
                                    break
                                }
                                output.append(hit)
                            }
                        }
                    }
                    if (truncated) break
                }
            }

            AppLogger.d(TAG, "file.search '" + query + "': " + minOf(matches, MAX_SEARCH_MATCHES) + " match(es)")
            ToolResult(
                ok = true,
                output = output.toString().trimEnd(),
                metadata = mapOf(
                    "matches" to minOf(matches, MAX_SEARCH_MATCHES),
                    "scanned_entries" to scannedEntries,
                    "truncated" to truncated,
                ),
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "file.search failed: ${e.message}")
            ToolResult(ok = false, error = e.message)
        }
    }
}
