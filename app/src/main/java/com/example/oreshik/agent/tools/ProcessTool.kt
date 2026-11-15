package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.agent.security.WorkspaceOwnershipGuard
import com.example.oreshik.agent.runtime.ShellWords
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessExecTool : AgentTool {
    override val definition = ToolDefinition(
        name = "process.exec",
        category = ToolCategory.PROCESS,
        description = "Execute a shell command inside the app workspace using the Android shell environment.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("command" to "string", "timeout_seconds" to "integer"),
    )

    /** Commands with pure-Python protocol fallbacks (ssh/smb/git-lite/etc). */
    private val protocolFallback = setOf(
        "ssh", "scp", "sftp", "sshpass", "smb", "smtp", "ftp", "rdp", "telnet", "openssl", "busybox",
        "zip", "unzip", "tar", "gzip", "bzip2", "xz", "curl", "wget", "tree", "eza", "bat", "fd", "rg", "fzf", "zoxide", "btop", "jq", "yq",
        "git", "gh", "composer", "zsh", "tmux", "nvim", "neovim", "clang", "make", "go", "rustc", "cargo",
        "inetutils", "dnsutils", "net-tools", "iproute2", "util-linux", "procps", "psmisc", "socat", "nmap", "ping", "nslookup", "dig", "ifconfig", "netstat", "route", "arp", "ip", "ss",
        "coreutils", "findutils", "diffutils", "grep", "sed", "gawk", "awk", "less", "file", "which", "diff", "diff3", "cmp", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "hostname", "uname", "id", "whoami", "date", "df", "du", "ps", "free", "uptime",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val command = o["command"]?.asString ?: return@withContext ToolResult(ok = false, error = "Missing command")
        val timeout = (o["timeout_seconds"]?.asLong ?: 60L).coerceIn(1, 600)

        if (Regex("(^|[;&|])\\s*(rm -rf /|mkfs|dd if=/dev|reboot|shutdown)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            return@withContext ToolResult(ok = false, error = "Command rejected by safety policy")
        }

        try {
            WorkspaceOwnershipGuard.ensureAppOwned(context.app, context.workspace)
            val first = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase().orEmpty()
            val runtime = context.runtime

            // There is no standalone Android pip executable. Route pip through
            // the embedded interpreter so installs land in the private workspace.
            val commandWords = ShellWords.split(command)
            val pipArgs = when {
                commandWords.firstOrNull()?.lowercase() in setOf("pip", "pip3") -> commandWords.drop(1)
                commandWords.firstOrNull()?.lowercase() in setOf("python", "python3") &&
                    commandWords.getOrNull(1) == "-m" && commandWords.getOrNull(2) == "pip" -> commandWords.drop(3)
                else -> null
            }
            if (pipArgs != null) {
                val result = runtime.python.executePip(pipArgs, context.workspace, timeout.toInt()).getOrNull()
                    ?: return@withContext ToolResult(ok = false, error = "Python pip runtime failed")
                val output = buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) append("\nSTDERR:\n").append(result.stderr)
                    if (result.error != null && result.stderr.isBlank()) append(result.error)
                }
                return@withContext ToolResult(result.ok, output, result.error, result.exitCode)
            }

            if (first == "sqlmap") {
                val sqlmapPack = runCatching { runtime.ensureSqlmapPack() }.getOrElse {
                    return@withContext ToolResult(ok = false, error = "Bundled sqlmap runtime setup failed: ${it.message}")
                }
                val result = runtime.python.executeSqlmap(
                    commandWords.drop(1),
                    context.workspace,
                    sqlmapPack.root,
                ).getOrNull()
                    ?: return@withContext ToolResult(ok = false, error = "sqlmap runtime failed")
                val output = buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) append("\nSTDERR:\n").append(result.stderr)
                    if (result.error != null && result.stderr.isBlank()) append(result.error)
                }
                return@withContext ToolResult(
                    result.ok,
                    output,
                    result.error,
                    result.exitCode,
                    mapOf("runtime" to "sqlmap", "command" to first),
                )
            }

            // 1) Real bundled binary? Execute via root shell with the full runtime env.
            val bundled = if (first.isNotBlank()) runtime.resolveExecutable(first) else null
            val root = RootManager(context.app)
            // Process execution does not imply permission to elevate. The user
            // controls root independently in Settings; when it is off, use the
            // ordinary app UID even on a rooted device.
            val rootAllowed = AgentConfig.allowRoot && root.canUseRoot()
            if ((bundled != null) && rootAllowed) {
                AppLogger.d("ProcessTool", "Executing '$first' via bundled binary: ${bundled.absolutePath}")
                val env = runtime.environment()
                val exports = buildString {
                    for ((k, v) in env) {
                        val safe = v.replace("\\", "\\\\").replace("'", "'\\''")
                        append("export $k='$safe'; ")
                    }
                }
                val full = "$exports cd '${context.workspace.absolutePath}' && ($command)"
                val p = ProcessBuilder(root.status().suPath, "-c", full)
                    .directory(context.app.filesDir)
                    .redirectErrorStream(true)
                    .start()
                val result = try {
                    collectProcess(p, timeout, first, "bundled-root")
                } finally {
                    WorkspaceOwnershipGuard.markMayBeRootOwned()
                }
                // Prefer bundled result even on non-zero exit (real binary output beats stubs)
                return@withContext result
            }

            // 2) Python protocol fallback (pure-python implementations of ssh/git-lite/etc).
            if (first in protocolFallback) {
                val pyResult = context.runtime.python.executeProtocol(command, context.workspace)
                val runtimeResult = pyResult.getOrNull()
                if ((pyResult.isSuccess) && (runtimeResult != null)) {
                    AppLogger.d("ProcessTool", "Executed '$first' via app-owned python fallback (rc=${runtimeResult.exitCode})")
                    return@withContext ToolResult(
                        runtimeResult.ok,
                        runtimeResult.stdout + runtimeResult.stderr,
                        runtimeResult.error,
                        runtimeResult.exitCode,
                        mapOf("runtime" to "app-owned-python", "command" to first),
                    )
                }
            }

            // 3) System shell fallback with runtime env on PATH.
            val runtimeEnv = context.runtime.environment()
            AppLogger.d("ProcessTool", "Executing command via system shell: $command")

            val p = if (rootAllowed) {
                val rootCommand = "cd '${context.workspace.absolutePath}' && { $command; }"
                ProcessBuilder(root.status().suPath, "-c", rootCommand)
                    .directory(context.app.filesDir)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(runtimeEnv) }
                    .start()
            } else {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(context.workspace)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(runtimeEnv) }
                    .start()
            }

            try {
                collectProcess(p, timeout, first, if (rootAllowed) "root-shell" else "/system/bin/sh")
            } finally {
                if (rootAllowed) WorkspaceOwnershipGuard.markMayBeRootOwned()
            }
        } catch (e: Exception) {
            AppLogger.e("ProcessTool", "Command execution failed: $command", e)
            ToolResult(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}

private suspend fun collectProcess(p: Process, timeout: Long, name: String, path: String): ToolResult = withContext(Dispatchers.IO) {
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit<String> { p.inputStream.bufferedReader().use { it.readTextLimited(1_000_000) } }
    try {
        val finished = p.waitFor(timeout, TimeUnit.SECONDS)
        if (!finished) {
            p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            val partial = runCatching { future[2, TimeUnit.SECONDS] }.getOrDefault("[process output unavailable]")
            future.cancel(true)
            ToolResult(ok = false, partial, "Timed out after ${timeout}s")
        } else {
            val output = runCatching { future[2, TimeUnit.SECONDS] }.getOrDefault("[process output unavailable]")
            ToolResult(
                p.exitValue() == 0,
                output,
                if (p.exitValue() == 0) null else "$name exited with code ${p.exitValue()}",
                p.exitValue(),
                mapOf("runtime_path" to path),
            )
        }
    } finally { executor.shutdownNow() }
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val buf = CharArray(8192); val out = StringBuilder(); var total = 0; var truncated = false
    while (true) {
        val n = read(buf); if (n < 0) break
        val take = minOf(n, (max - total).coerceAtLeast(0))
        if (take > 0) {
            out.appendRange(buf, 0, take)
            total += take
        }
        if (take < n) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}
