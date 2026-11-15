package com.example.oreshik.agent.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Small, explicit root bridge. Root is never assumed: su is probed and every elevated command is opt-in. */
class RootManager(private val context: Context) {

    private companion object {
        const val TAG = "Root"
    }

    data class RootStatus(val available: Boolean, val uid: String = "", val suPath: String = "")

    @Volatile private var cached: RootStatus? = null
    @Volatile private var cachedSelinuxMode: String? = null

    fun status(force: Boolean = false, timeoutSeconds: Long = 5): RootStatus {
        if (!force) cached?.let { return it }
        val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        val su = candidates.firstOrNull { path -> File(path).canExecute() } ?: "su"
        return try {
            val (exitCode, rawOutput) = runCaptured(
                ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start(),
                timeoutSeconds = timeoutSeconds.coerceIn(1, 60),
                maxChars = 4096,
            )
            val output = rawOutput.trim()
            val ok = exitCode == 0 && Regex("\\buid=0\\b").containsMatchIn(output)
            val result = RootStatus(ok, if (ok) output else "", if (ok) su else "")
            cached = result
            AppLogger.i(TAG, "su probe: available=$ok via $su")
            result
        } catch (_: Exception) {
            AppLogger.w(TAG, "su probe failed — root unavailable")
            val unavailable = RootStatus(available = false)
            cached = unavailable
            unavailable
        }
    }

    fun isEnabled(): Boolean = AgentConfig.allowRoot
    fun canUseRoot(): Boolean = AgentConfig.allowRoot && status().available

    fun getSelinuxMode(forceRefresh: Boolean = false): String {
        if (!forceRefresh) cachedSelinuxMode?.let { return it }
        
        val rootAvailable = status().available
        val mode = if (rootAvailable) {
            // Use root/su to read the enforce file since untrusted_app can't read selinuxfs directly
            try {
                val suPath = status().suPath
                val (code, rawOutput) = runCaptured(
                    ProcessBuilder(suPath, "-c", "cat /sys/fs/selinux/enforce").redirectErrorStream(true).start(),
                    timeoutSeconds = 3,
                    maxChars = 256,
                )
                val output = if (code == 0) rawOutput.trim() else ""
                when (output) {
                    "1" -> "Enforcing"
                    "0" -> "Permissive"
                    else -> "Unknown"
                }
            } catch (_: Exception) {
                // Fallback to getenforce binary
                try {
                    val (code, out) = runCaptured(
                        ProcessBuilder("/system/bin/getenforce").redirectErrorStream(true).start(),
                        timeoutSeconds = 3,
                        maxChars = 256,
                    )
                    out.trim().takeIf { code == 0 && it.isNotBlank() } ?: "Unknown"
                } catch (_: Exception) {
                    "Unknown"
                }
            }
        } else {
            // No root - try getenforce binary directly
            try {
                val (code, out) = runCaptured(
                    ProcessBuilder("/system/bin/getenforce").redirectErrorStream(true).start(),
                    timeoutSeconds = 3,
                    maxChars = 256,
                )
                out.trim().takeIf { code == 0 && it.isNotBlank() } ?: "Unknown"
            } catch (_: Exception) {
                "Unknown"
            }
        }
        
        cachedSelinuxMode = mode
        return mode
    }

    fun setSelinuxPermissive(permissive: Boolean): Pair<Boolean, String> {
        if (!status(force = true).available) return false to "Root/su is required to update SELinux mode."
        val target = if (permissive) "0" else "1"
        val (code, out) = execute("setenforce $target", 10)
        val mode = getSelinuxMode(forceRefresh = true)
        val success = (code == 0) && (((permissive) && (mode == "Permissive")) || ((!permissive) && (mode == "Enforcing")))
        return success to (if (success) "SELinux mode updated to $mode" else "Failed to update SELinux: $out (Current mode: $mode)")
    }

    fun fixSelinuxContextForRuntime(
        path: String,
        executablePaths: Collection<String> = emptyList(),
    ): Pair<Boolean, String> {
        if (!status(force = true).available) return false to "Root/su required to fix SELinux contexts."
        require(path.isNotBlank() && '\u0000' !in path) { "A valid runtime path is required." }
        val runtimeRoot = File(path).canonicalFile
        val managedRuntimeRoot = File(context.filesDir, "runtimes").canonicalFile
        require(runtimeRoot.path.startsWith(managedRuntimeRoot.path + File.separator)) {
            "SELinux runtime labeling is restricted to app-managed runtime packs."
        }
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        val entrypoints = executablePaths.map { relativePath ->
            val normalized = relativePath.replace('\\', '/')
            require(
                normalized.isNotBlank() && !normalized.startsWith('/') &&
                    !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
                    normalized.split('/').none { it.isBlank() || it == "." || it == ".." },
            ) { "Invalid runtime executable path: " + relativePath }
            val candidate = File(runtimeRoot, normalized).canonicalFile
            require(candidate.path.startsWith(runtimeRoot.path + File.separator)) {
                "Runtime executable path escapes its pack: " + relativePath
            }
            require(candidate.isFile) { "Runtime executable is missing: " + relativePath }
            candidate
        }.distinct()

        // Android also needs executable labels on ELF shared libraries and helper
        // binaries (for example git-remote-https), not only manifest entrypoints.
        // Include shebang scripts as well, while leaving ordinary config/data files
        // writable under app_data_file.
        val discoveredCode = runtimeRoot.walkTopDown().filter { it.isFile }.mapNotNull { file ->
            runCatching {
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(runtimeRoot.path + File.separator)) return@runCatching null
                val signature = ByteArray(4)
                val count = canonical.inputStream().use { it.read(signature) }
                val shebang = count >= 2 && signature[0] == '#'.code.toByte() && signature[1] == '!'.code.toByte()
                val elf = count == 4 && signature[0] == 0x7f.toByte() &&
                    signature[1] == 'E'.code.toByte() && signature[2] == 'L'.code.toByte() &&
                    signature[3] == 'F'.code.toByte()
                canonical.takeIf { shebang || elf }
            }.getOrNull()
        }.toList()
        val codeFiles = (entrypoints + discoveredCode).distinct()

        val quotedPath = quote(runtimeRoot.absolutePath)
        val contextCommand =
            "(chcon -Rh u:object_r:app_data_file:s0 " + quotedPath + " 2>/dev/null || " +
                "restorecon -R " + quotedPath + " 2>/dev/null)"
        // App data is writable by the app but Android denies executing files with
        // that label. Label only manifest-declared entrypoints as installed app
        // code; mutable runtime files and parent directories stay app-writable.
        val executableCommands = codeFiles.joinToString(" && ") { executable ->
            "chcon u:object_r:apk_data_file:s0 " + quote(executable.absolutePath) + " 2>/dev/null"
        }
        val cmd = listOf(contextCommand, executableCommands)
            .filter { it.isNotBlank() }
            .joinToString(" && ")
        val (code, out) = execute(cmd, 15)
        val detail = if (code == 0) "SELinux context fixed for $path"
            else "Failed to fix SELinux context for $path" + if (out.isBlank()) "" else ": $out"
        return (code == 0) to detail
    }

    fun execute(command: String, timeoutSeconds: Long): Pair<Int, String> {
        require(AgentConfig.allowRoot) { "Root execution is disabled in Agent Settings." }
        val workspace = AgentConfig.workspaceRoot(context)
        val result = executeRootCommand(
            command,
            timeoutSeconds,
            workingDirectory = workspace,
        )
        WorkspaceOwnershipGuard.markMayBeRootOwned()
        return result
    }

    /** Return root-created workspace entries to the app UID while preserving the private workspace boundary. */
    fun repairWorkspaceOwnership(workspace: File): Pair<Int, String> {
        require(AgentConfig.allowRoot) { "Root execution is disabled in Agent Settings." }
        val appFiles = context.filesDir.canonicalFile
        val canonicalWorkspace = workspace.canonicalFile
        val selectedWorkspace = AgentConfig.workspaceRoot(context).canonicalFile
        require(canonicalWorkspace.parentFile == appFiles && canonicalWorkspace == selectedWorkspace) {
            "Ownership repair is limited to the private app workspace."
        }
        val rawWorkspace = File(appFiles, canonicalWorkspace.name)
        val stat = Os.lstat(rawWorkspace.absolutePath)
        require(OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISLNK(stat.st_mode)) {
            "The private app workspace must be a real directory."
        }

        val appUid = android.os.Process.myUid()
        val appGid = Os.stat(appFiles.absolutePath).st_gid
        val quotedRoot = shellQuote(canonicalWorkspace.absolutePath)
        val owner = "$appUid:$appGid"
        val command = "find $quotedRoot -xdev ! -type l -exec chown $owner {} + && " +
            "find $quotedRoot -xdev ! -type l -exec chmod u+rwX {} + && " +
            // A workspace may be restored from another install. Its files can
            // already have this app UID and permissive modes, yet retain a stale
            // MCS category that makes them invisible to the current app process.
            // Restore the private app-data label as well as Unix ownership/modes,
            // skipping symlinks so their targets cannot escape the workspace.
            "find $quotedRoot -mindepth 1 -xdev ! -type l -exec chcon u:object_r:app_data_file:s0 {} +"
        return executeRootCommand(command, 60, workingDirectory = appFiles)
    }

    private fun executeRootCommand(
        command: String,
        timeoutSeconds: Long,
        workingDirectory: File,
    ): Pair<Int, String> {
        val rootStatus = status()
        require(rootStatus.available) { "No usable root/su provider was detected." }
        val suPath = rootStatus.suPath
        val shellCommand = "cd ${shellQuote(workingDirectory.absolutePath)} && { $command; }"
        AppLogger.d(TAG, "exec: ${command.take(120)}")
        val started = System.currentTimeMillis()
        // su must start from app-owned storage; entering a root-owned workspace
        // as the app UID can fail before su gets a chance to repair it.
        val p = ProcessBuilder(suPath, "-c", shellCommand).directory(context.filesDir)
            .redirectErrorStream(true).start()
        val (exitCode, output) = runCaptured(p, timeoutSeconds, 1_000_000)
        AppLogger.d(TAG, "exec done rc=" + exitCode + " in " + (System.currentTimeMillis() - started) + "ms")
        if (exitCode < 0) AppLogger.w(TAG, "exec timed out after " + timeoutSeconds + "s: " + command.take(120))
        return exitCode to output
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun runCaptured(
        process: Process,
        timeoutSeconds: Long,
        maxChars: Int,
    ): Pair<Int, String> {
        val executor = Executors.newSingleThreadExecutor()
        val outputFuture = executor.submit<String> {
            process.inputStream.bufferedReader().use { it.readTextLimited(maxChars) }
        }
        try {
            if (!process.waitFor(timeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)) {
                process.destroyForcibly()
                val output = runCatching { outputFuture.get(1, TimeUnit.SECONDS) }
                    .getOrDefault("[process output unavailable]")
                outputFuture.cancel(true)
                return -1 to output
            }
            val output = runCatching { outputFuture.get(2, TimeUnit.SECONDS) }
                .getOrDefault("[process output unavailable]")
            return process.exitValue() to output
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return -1 to "[process interrupted]"
        } finally {
            runCatching { process.inputStream.close() }
            outputFuture.cancel(true)
            executor.shutdownNow()
        }
    }
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val chars = CharArray(8192)
    val out = StringBuilder(minOf(max, 32768))
    var total = 0
    var truncated = false
    while (true) {
        val count = read(chars)
        if (count < 0) break
        val take = minOf(count, (max - total).coerceAtLeast(0))
        if (take > 0) {
            out.appendRange(chars, 0, take)
            total += take
        }
        if (take < count) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}
