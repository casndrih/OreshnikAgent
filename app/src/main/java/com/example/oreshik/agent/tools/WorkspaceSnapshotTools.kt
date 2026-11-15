package com.example.oreshik.agent.tools

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounded, device-bound AES-GCM encrypted workspace snapshots with traversal-safe restore. */
private object WorkspaceSnapshots {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    private const val KEY_ALIAS = "oreshnik.workspace.snapshots.aesgcm.v1"
    private val MAGIC = byteArrayOf(0x4f, 0x57, 0x53, 0x31) // OWS1
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

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encryptedOutput(destination: File): CipherOutputStream {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = cipher.iv
        require(iv.size in 12..16) { "Android Keystore returned an invalid AES-GCM IV." }
        destination.parentFile?.mkdirs()
        val raw = FileOutputStream(destination)
        try {
            raw.write(MAGIC)
            raw.write(iv.size)
            raw.write(iv)
            return CipherOutputStream(raw, cipher)
        } catch (error: Exception) {
            raw.close()
            destination.delete()
            throw error
        }
    }

    private fun encryptFile(source: File, destination: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = cipher.iv
        require(iv.size in 12..16) { "Android Keystore returned an invalid AES-GCM IV." }
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { raw ->
            raw.write(MAGIC)
            raw.write(iv.size)
            raw.write(iv)
            CipherOutputStream(raw, cipher).use { encrypted ->
                FileInputStream(source).use { input -> input.copyTo(encrypted, 64 * 1024) }
            }
        }
    }

    private fun readExactly(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw java.io.EOFException("Encrypted snapshot header is truncated.")
            offset += count
        }
    }

    /** Decrypt to a temporary ZIP first so the GCM authentication tag is checked before restore. */
    private fun decryptFile(source: File, destination: File) {
        destination.delete()
        try {
            FileInputStream(source).use { raw ->
                val magic = ByteArray(MAGIC.size)
                readExactly(raw, magic)
                require(magic.contentEquals(MAGIC)) { "Snapshot format is invalid or unsupported." }
                val ivLength = raw.read()
                require(ivLength in 12..16) { "Snapshot encryption header is invalid." }
                val iv = ByteArray(ivLength)
                readExactly(raw, iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                CipherInputStream(raw, cipher).use { encrypted ->
                    FileOutputStream(destination).use { output -> encrypted.copyTo(output, 64 * 1024) }
                }
            }
            require(destination.isFile && destination.length() > 0L) { "Decrypted snapshot is empty." }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    /** Convert snapshots created by older app versions from plaintext ZIP to encrypted OWS1. */
    @Synchronized
    private fun migrateLegacySnapshots(context: ToolContext) {
        val directory = store(context)
        directory.listFiles()?.filter { it.isFile && it.extension == "zip" }?.forEach { legacy ->
            val encrypted = File(directory, "${legacy.nameWithoutExtension}.wss")
            if (encrypted.isFile) {
                legacy.delete()
                return@forEach
            }
            val temporary = File(directory, ".${legacy.nameWithoutExtension}.migrate.tmp")
            try {
                encryptFile(legacy, temporary)
                check(temporary.renameTo(encrypted)) { "Could not activate encrypted snapshot migration." }
                check(legacy.delete()) { "Could not remove the legacy plaintext snapshot." }
            } finally {
                temporary.delete()
            }
        }
    }

    @Synchronized
    fun create(context: ToolContext, label: String = "manual"): String {
        migrateLegacySnapshots(context)
        val workspace = root(context)
        val directory = store(context)
        val id = "ws_" + System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString().take(8)
        val destination = File(directory, "${id}.wss")
        var count = 0
        var total = 0L
        try {
            ZipOutputStream(encryptedOutput(destination)).use { zip ->
                workspace.walkTopDown().onEnter { dir -> dir == workspace || dir.name !in ignored }.forEach { file ->
                    if (!file.isFile) return@forEach
                    val canonical = file.canonicalFile
                    require(canonical.path.startsWith(workspace.path + File.separator)) { "Workspace path escaped root." }
                    val relative = safeRelative(canonical.relativeTo(workspace).invariantSeparatorsPath)
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
        // Keep the newest 20 encrypted snapshots; never remove the snapshot just created.
        directory.listFiles()?.filter { it.isFile && it.extension == "wss" && it.name != destination.name }
            ?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
        return "${id}|${label}|${count}|${total}"
    }

    @Synchronized
    fun list(context: ToolContext): List<String> {
        migrateLegacySnapshots(context)
        return store(context).listFiles()?.filter { it.isFile && it.extension == "wss" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                "${file.nameWithoutExtension} · AES-256-GCM encrypted · ${file.length()} bytes · " +
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified()))
            }
            .orEmpty()
    }

    @Synchronized
    fun restore(context: ToolContext, id: String): String {
        require(id.matches(Regex("^ws_[0-9]{10,}_[A-Fa-f0-9]{8}$"))) { "Invalid snapshot ID." }
        migrateLegacySnapshots(context)
        val directory = store(context)
        val archive = File(directory, "${id}.wss").canonicalFile
        require(archive.parentFile == directory.canonicalFile && archive.isFile) { "Snapshot not found." }
        val workspace = root(context)
        val decryptedArchive = File(context.app.cacheDir, "workspace-restore-${id}.zip")
        val stage = File(context.app.cacheDir, "workspace-restore-${id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // Authenticate the entire encrypted payload before touching workspace files.
            decryptFile(archive, decryptedArchive)
            // Automatic encrypted safety point before changing any workspace content.
            val safety = create(context, "pre-restore")
            var entries = 0
            var total = 0L
            ZipInputStream(FileInputStream(decryptedArchive)).use { zip ->
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
            return "Restored ${entries} files from ${id}. Automatic safety snapshot: ${safety.substringBefore('|')}."
        } finally {
            stage.deleteRecursively()
            decryptedArchive.delete()
        }
    }
}

class WorkspaceSnapshotCreateTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workspace.snapshot",
        category = ToolCategory.FILESYSTEM,
        description = "Create an AES-256-GCM encrypted, bounded app-private snapshot of the current workspace, excluding dependency/build caches.",
        permissions = setOf(ToolPermission.READ_WORKSPACE),
        parameters = mapOf("label" to "string"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val label = arguments.asJsonObject["label"]?.asString?.take(80)?.ifBlank { "manual" } ?: "manual"
            val result = WorkspaceSnapshots.create(context, label).split('|')
            ToolResult.success("Encrypted snapshot created: ${result[0]} · ${result[2]} files · ${result[3]} bytes")
        }.getOrElse { ToolResult.failure("Snapshot failed: ${it.message}") }
    }
}

class WorkspaceSnapshotListTool : AgentTool {
    override val definition = ToolDefinition(
        name = "workspace.snapshots",
        category = ToolCategory.FILESYSTEM,
        description = "List AES-256-GCM encrypted app-private workspace snapshots.",
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
        description = "Restore an AES-256-GCM encrypted workspace snapshot. Authenticates the archive and creates an automatic encrypted pre-restore safety snapshot first. This replaces regular workspace files but preserves ignored build/dependency directories.",
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
