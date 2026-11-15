package com.example.oreshik.agent.runtime

import android.content.Context
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Installs toolpack archives (tar.gz) into filesDir/runtimes/<id> using root tar
 * so that symlinks, exec bits and ownership survive extraction on SELinux-enforcing
 * devices (Android 10+ blocks unprivileged exec from app data otherwise).
 */
class ToolPackInstaller(private val context: Context) {

    private companion object {
        private const val TAG = "ToolPackInstaller"
    }

    init {
        AppLogger.d(TAG, "ToolPackInstaller created")
    }

    private val root get() = RootManager(context)

    data class InstallReport(val packId: String, val ok: Boolean, val detail: String)

    private fun appUid(): String {
        val uid = android.os.Process.myUid()
        return uid.toString()
    }

    /**
     * Extract a pack archive into the runtime destination directory.
     * Uses `su tar` (preserves symlinks + permissions, bypasses SELinux exec checks
     * on extraction), then normalizes ownership/context for the app.
     */
    suspend fun installPack(
        packId: String,
        archive: File,
        destination: File,
        expectedFiles: Collection<String> = emptyList(),
        onProgress: (suspend (String) -> Unit)? = null,
    ): InstallReport = withContext(Dispatchers.IO) {
        require(packId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "Invalid pack id: $packId" }
        val destinationParent = destination.parentFile?.canonicalFile
            ?: error("Runtime destination has no parent directory")
        require(destination.name == packId && destination.canonicalFile.parentFile == destinationParent) {
            "Runtime destination is outside its pack directory"
        }
        AppLogger.i(TAG, "Installing pack $packId from ${archive.absolutePath} -> ${destination.absolutePath}")
        val appUid = appUid()
        val tmpDir = File(destination.parentFile, ".tmp-$packId-${UUID.randomUUID()}")
        val backupDir = File(destination.parentFile, ".backup-$packId-${UUID.randomUUID()}")
        val extractDir = File(tmpDir, "extract")
        val payloadDir = File(tmpDir, "payload")
        require(expectedFiles.all(::isSafeRelativePath)) { "Invalid expected path for pack $packId" }
        onProgress?.invoke("Extracting $packId via root tar…")
        // Extract and validate beside the active pack. Keep the current directory
        // intact until extraction, permissions, labels, and entrypoints all pass.
        val expectedChecks = expectedFiles.joinToString(" && ") { relative ->
            "[ -f ${shellQuote(File(payloadDir, relative).absolutePath)} ]"
        }
        val archivedPackDir = shellQuote(File(extractDir, packId).absolutePath)
        val extractedTopLevel = shellQuote(extractDir.absolutePath)
        val payloadPath = shellQuote(payloadDir.absolutePath)
        // Bundled archives normally contain a directory named after the pack.
        // A few vendor SDK archives use a versioned root (for example
        // android-sdk-final/). Strip that wrapper only when it is the archive's
        // sole top-level entry; otherwise preserve the archive layout.
        val normalizeArchiveRoot =
            "if [ -d $archivedPackDir ]; then " +
            "mv $archivedPackDir $payloadPath; " +
            "else " +
            "entry_count=\$(find $extractedTopLevel -mindepth 1 -maxdepth 1 -print | wc -l | tr -d '[:space:]'); " +
            "dir_count=\$(find $extractedTopLevel -mindepth 1 -maxdepth 1 -type d -print | wc -l | tr -d '[:space:]'); " +
            "if [ \"\$entry_count\" = 1 ] && [ \"\$dir_count\" = 1 ]; then " +
            "root_dir=\$(find $extractedTopLevel -mindepth 1 -maxdepth 1 -type d -print | head -n 1); " +
            "mv \"\$root_dir\" $payloadPath; " +
            "else mv $extractedTopLevel $payloadPath; fi; fi"
        val (rc, out) = root.execute(
            "rm -rf ${shellQuote(tmpDir.absolutePath)} ${shellQuote(backupDir.absolutePath)} && " +
            "mkdir -p ${shellQuote(extractDir.absolutePath)} && " +
            "tar -xzf ${shellQuote(archive.absolutePath)} -C ${shellQuote(extractDir.absolutePath)} --no-same-owner && " +
            normalizeArchiveRoot + " && " +
            "chmod -R 755 ${shellQuote(payloadDir.absolutePath)} && " +
            "chown -R $appUid:$appUid ${shellQuote(payloadDir.absolutePath)} && " +
            // -h: label symlinks without dereferencing — packs contain dangling symlinks
            // (git-cvsserver, perl headers) that plain chcon -R chokes on.
            "chcon -Rh u:object_r:app_data_file:s0 ${shellQuote(payloadDir.absolutePath)} && " +
            (if (expectedChecks.isBlank()) "true" else "$expectedChecks") + " && " +
            "if [ -e ${shellQuote(destination.absolutePath)} ]; then " +
            "mv ${shellQuote(destination.absolutePath)} ${shellQuote(backupDir.absolutePath)} || exit 32; fi && " +
            "if mv ${shellQuote(payloadDir.absolutePath)} ${shellQuote(destination.absolutePath)}; then " +
            "rm -rf ${shellQuote(backupDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} || true; true; " +
            "else [ ! -e ${shellQuote(backupDir.absolutePath)} ] || " +
            "mv ${shellQuote(backupDir.absolutePath)} ${shellQuote(destination.absolutePath)}; exit 33; fi",
            timeoutSeconds = 900,
        )
        if (rc != 0) {
            AppLogger.e(TAG, "tar/mv/chown/chcon failed for $packId rc=$rc: $out")
            runCatching { root.execute("rm -rf ${shellQuote(tmpDir.absolutePath)}", timeoutSeconds = 60) }
            return@withContext InstallReport(
                packId = packId,
                ok = false,
                detail = "extraction failed rc=$rc: ${out.take(400)}",
            )
        }

        // Resolve relocatable Termux interpreter paths after the final install directory
        // is known. Do not rewrite every script to /system/bin/sh: that breaks Bash/env
        // semantics and scripts written for other interpreters.
        val prefixReplacement = destination.absolutePath.replace("\\", "\\\\").replace("&", "\\&").replace("#", "\\#")
        val relocateScripts =
            "grep -rIl '@RUNTIME_PREFIX@' ${shellQuote(destination.absolutePath)} 2>/dev/null | " +
            "while IFS= read -r f; do [ -f \"\$f\" ] || continue; " +
            "sed -i 's#@RUNTIME_PREFIX@#$prefixReplacement#g' \"\$f\" || exit 41; done"
        val normalizeSystemSh =
            "grep -rIl '^#!/bin/sh\\|^#!/usr/bin/sh' ${shellQuote(destination.absolutePath)} 2>/dev/null | " +
            "while IFS= read -r f; do [ -f \"\$f\" ] || continue; " +
            "sed -i '1s|^#!.*|#!/system/bin/sh|' \"\$f\" || exit 42; done"
        val (scriptsFixed, scriptsOutput) = root.execute(
            "$relocateScripts && $normalizeSystemSh",
            timeoutSeconds = 300,
        )
        if (scriptsFixed != 0) {
            AppLogger.e(TAG, "Runtime script relocation failed for $packId: $scriptsOutput")
            runCatching { root.execute("rm -rf ${shellQuote(tmpDir.absolutePath)}", timeoutSeconds = 60) }
            return@withContext InstallReport(packId, ok = false, detail = "script relocation failed: ${scriptsOutput.take(300)}")
        }

        val (contextsFixed, contextDetail) = root.fixSelinuxContextForRuntime(
            destination.absolutePath,
            executablePaths = expectedFiles,
        )
        if (!contextsFixed) {
            AppLogger.e(TAG, "Executable SELinux labels failed for $packId: $contextDetail")
            return@withContext InstallReport(packId, ok = false, detail = contextDetail)
        }

        onProgress?.invoke("Permissions fixed for $packId")
        AppLogger.i(TAG, "Pack $packId installed OK")
        InstallReport(
            packId = packId,
            ok = true,
            detail = "installed",
        )
    }

    /** Remove a previously installed runtime pack directory. */
    fun removePackDirectory(packId: String, destination: File) {
        AppLogger.i(TAG, "Removing pack directory $packId at ${destination.absolutePath}")
        runCatching {
            val managedRoot = File(context.filesDir, "runtimes").canonicalFile
            require(destination.canonicalFile.parentFile == managedRoot) { "Refusing to remove a path outside runtimes" }
            if (root.canUseRoot()) {
                root.execute("rm -rf ${shellQuote(destination.absolutePath)}", 60)
            } else {
                destination.deleteRecursively()
            }
        }.onFailure { AppLogger.e(TAG, "Failed removing $packId: ${it.message}", it) }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.isNotBlank() &&
            !normalized.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.split('/').none { it == ".." || it == "." || it.isBlank() }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
