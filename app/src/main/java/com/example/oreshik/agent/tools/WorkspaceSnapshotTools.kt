package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounded, app-private workspace snapshots with traversal-safe restore. */
private object WorkspaceSnapshots {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    private val ignored = setOf(".git", "node_modules", "build", ".gradle", ".cache", ".python-packages", "__pycache__", "dist")

    private fun root(context: ToolContext): File = context.workspace.canonicalFile.also {
        require(it.isDirectory) { "Workspace directory is unavailable." }
    }

    private fun store(context: ToolContext): File = File(context.app.filesDir, "workspace-snapshots").apply { mkdirs() }

    private fun safeRelative(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith("/") && !normalized.contains('\u0000')) { "Invalid snapshot path." }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Snapshot path traversal rejected." }
        return parts.joinToString("/")
    }

    @Synchronized
    fun create(context: ToolContext, label: String = "manual"): String {
        val root = root(context)
        val id = "ws_" + System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString().take(8)
        val destination = File(store(context), "$id.zip")
        var count = 0
        var total = 0L
        try {
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                root.walkTopDown().onEnter { dir -> dir == root || dir.name !in ignored }.forEach { file ->
                    if (!file.isFile) return@forEach
                    val canonical = file.canonicalFile
                    require(canonical.path.startsWith(root.path + File.separator)) { "Workspace path escaped root." }
                    val relative = safeRelative(canonical.relativeTo(root).invariantSeparatorsPath)
                    val length = canonical.length()
                    require(length <= MAX_FILE_BYTES) { "File too large for snapshot: $relative" }
                    total += length
                    require(total <= MAX_TOTAL_BYTES) { "Snapshot exceeds 512 MiB limit." }
                    require(++count <= MAX_ENTRIES) { "Snapshot exceeds $MAX_ENTRIES files." }
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(canonical).use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        // Keep the newest 20 snapshots; never remove the snapshot just created.
        store(context).listFiles()?.filter { it.isFile && it.extension == "zip" && it.name != destination.name }
            ?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
        return "$id|$label|$count|$total"
    }

    fun list(context: ToolContext): List<String> =
        store(context).listFiles()?.filter { it.isFile && it.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file -> "${file.nameWithoutExtension} · ${file.length()} bytes · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified()))}" }
            .orEmpty()

    @Synchronized
    fun restore(context: ToolContext, id: String): String {
        require(id.matches(Regex("^ws_[0-9]{10,}_[A-Fa-f0-9]{8}$"))) { "Invalid snapshot ID." }
        val archive = File(store(context), "$id.zip").canonicalFile
        require(archive.parentFile == store(context).canonicalFile && archive.isFile) { "Snapshot not found." }
        val workspace = root(context)
        // Automatic safety point before changing any workspace content.
        val safety = create(context, "pre-restore")
        val stage = File(context.app.cacheDir, "workspace-restore-$id").apply {
            deleteRecursively()
            mkdirs()
        }
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val relative = safeRelative(entry.name)
                    require(++entries <= MAX_ENTRIES) { "Snapshot contains too many files." }
                    val target = File(stage, relative).canonicalFile
                    require(target.path.startsWith(stage.canonicalPath + File.separator)) { "Snapshot traversal rejected." }
                    target.parentFile?.mkdirs()
                    var fileBytes = 0L
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            total += read
                            require(fileBytes <= MAX_FILE_BYTES && total <= MAX_TOTAL_BYTES) { "Snapshot expands beyond configured limits." }
                            output.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            // Remove current workspace files, retaining ignored build/dependency directories.
            workspace.walkTopDown().onEnter { dir -> dir == workspace || dir.name !in ignored }
                .filter { it.isFile }.toList().forEach { it.delete() }
            stage.walkTopDown().filter { it.isFile }.forEach { staged ->
                val relative = safeRelative(staged.relativeTo(stage).invariantSeparatorsPath)
                val target = File(workspace, relative).canonicalFile
                require(target.path.startsWith(workspace.path + File.separator)) { "Restore target escaped workspace." }
                target.parentFile?.mkdirs()
                staged.copyTo(target, overwrite = true)
            }
            return "Restored $entries files from $id. Automatic safety snapshot: ${safety.substringBefore('|')}."
        } finally {
            stage.deleteRecursively()
        }
    }
}

class WorkspaceSnapshotCreateTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workspace.snapshot",
        category = ToolCategory.FILESYSTEM,
        description = "Create a bounded app-private snapshot of the current workspace, excluding dependency/build caches.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("label" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val label = arguments.asJsonObject["label"]?.asString?.take(80)?.ifBlank { "manual" } ?: "manual"
            val result = WorkspaceSnapshots.create(context, label).split('|')
            ToolResult.success("Snapshot created: ${result[0]} · ${result[2]} files · ${result[3]} bytes")
        }.getOrElse { ToolResult.failure("Snapshot failed: ${it.message}") }
    }
}

class WorkspaceSnapshotListTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workspace.snapshots",
        category = ToolCategory.FILESYSTEM,
        description = "List available app-private workspace snapshots.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = emptyMap(),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching { ToolResult.success(WorkspaceSnapshots.list(context).ifEmpty { listOf("No snapshots yet.") }.joinToString("\n")) }
            .getOrElse { ToolResult.failure("Snapshot listing failed: ${it.message}") }
    }
}

class WorkspaceSnapshotRestoreTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workspace.rollback",
        category = ToolCategory.FILESYSTEM,
        description = "Restore a workspace snapshot. Creates an automatic pre-restore safety snapshot first. This replaces regular workspace files but preserves ignored build/dependency directories.",
        permissions = setOf(ToolPermission.WRITE_WORKSPACE, ToolPermission.DELETE),
        parameters = mapOf("snapshot_id" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val id = arguments.asJsonObject["snapshot_id"]?.asString.orEmpty()
            ToolResult.success(WorkspaceSnapshots.restore(context, id))
        }.getOrElse { ToolResult.failure("Workspace rollback failed: ${it.message}") }
    }
}
