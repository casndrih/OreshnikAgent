package com.example.oreshik.agent.runtime

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.oreshik.agent.security.WorkspaceOwnershipGuard
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PythonRuntime(private val context: Context) {

    private companion object {
        private const val TAG = "PythonRuntime"
    }

    init {
        AppLogger.d(TAG, "PythonRuntime created")
    }

    private val gson = Gson()
    private val sqlmapMutex = Mutex()

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            AppLogger.i(TAG, "Starting Chaquopy Python runtime")
            Python.start(AndroidPlatform(context))
        }
    }

    /**
     * Real availability check for the embedded Chaquopy CPython runtime.
     * The embedded interpreter has no bin/python3 ELF on disk, so filesystem
     * probing (resolveExecutable) always reports MISSING even though
     * python.exec runs fine. This verifies the live interpreter instead.
     */
    fun isAvailable(): Boolean = try {
        ensureStarted()
        runCatching { Python.getInstance() }.isSuccess
    } catch (t: Throwable) {
        AppLogger.e(TAG, "Python availability check failed: ${t.message}", t)
        false
    }

    fun isPipAvailable(): Boolean = try {
        ensureStarted()
        Python.getInstance().getModule("pip").get("__version__") != null
    } catch (t: Throwable) {
        AppLogger.e(TAG, "pip availability check failed: ${t.message}", t)
        false
    }

    suspend fun execute(
        code: String,
        cwd: File,
        timeoutSeconds: Int = 60,
    ): Result<RuntimeResult> = withContext(Dispatchers.IO) {
        try {
            WorkspaceOwnershipGuard.ensureAppOwned(context, cwd)
            AppLogger.d(TAG, "execute: ${code.length} chars, cwd=${cwd.canonicalPath}, timeout=${timeoutSeconds}s")
            ensureStarted()
            val module = Python.getInstance().getModule("agent_runtime")
            val result = module.callAttr("execute", code, cwd.canonicalPath, timeoutSeconds).toString()
            val obj = gson.fromJson(result, JsonObject::class.java)
            val runtimeResult = RuntimeResult(
                ok = obj["ok"]?.asBoolean == true,
                stdout = obj["stdout"]?.asString.orEmpty(),
                stderr = obj["stderr"]?.asString.orEmpty(),
                exitCode = obj["exit_code"]?.asInt,
                error = obj["error"]?.asString,
            )
            Result.success(runtimeResult)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "execute failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun executeProtocol(command: String, cwd: File): Result<RuntimeResult> = withContext(Dispatchers.IO) {
        try {
            WorkspaceOwnershipGuard.ensureAppOwned(context, cwd)
            AppLogger.d(TAG, "executeProtocol: $command, cwd=${cwd.canonicalPath}")
            ensureStarted()
            val module = Python.getInstance().getModule("protocol_runtime")
            val result = module.callAttr("execute_command", command, cwd.canonicalPath).toString()
            val obj = gson.fromJson(result, JsonObject::class.java)
            val runtimeResult = RuntimeResult(
                ok = obj["ok"]?.asBoolean == true,
                stdout = obj["stdout"]?.asString.orEmpty(),
                stderr = obj["stderr"]?.asString.orEmpty(),
                exitCode = obj["exit_code"]?.asInt,
                error = obj["error"]?.asString,
            )
            Result.success(runtimeResult)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "executeProtocol failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    suspend fun executePip(args: List<String>, cwd: File, timeoutSeconds: Int = 600): Result<RuntimeResult> = withContext(Dispatchers.IO) {
        try {
            WorkspaceOwnershipGuard.ensureAppOwned(context, cwd)
            AppLogger.i(TAG, "pip ${args.joinToString(" ")} (workspace=${cwd.canonicalPath})")
            ensureStarted()
            val module = Python.getInstance().getModule("agent_runtime")
            val result = module.callAttr("run_pip", gson.toJson(args), cwd.canonicalPath, timeoutSeconds).toString()
            val obj = gson.fromJson(result, JsonObject::class.java)
            Result.success(
                RuntimeResult(
                    ok = obj["ok"]?.asBoolean == true,
                    stdout = obj["stdout"]?.asString.orEmpty(),
                    stderr = obj["stderr"]?.asString.orEmpty(),
                    exitCode = obj["exit_code"]?.asInt,
                    error = obj["error"]?.asString,
                ),
            )
        } catch (t: Throwable) {
            AppLogger.e(TAG, "pip failed: ${t.message}", t)
            Result.failure(t)
        }
    }

    /** Run the bundled sqlmap runtime pack with an argv list and preserve its raw output. */
    suspend fun executeSqlmap(args: List<String>, cwd: File, runtimeRoot: File): Result<RuntimeResult> = withContext(Dispatchers.IO) {
        sqlmapMutex.withLock {
            try {
                WorkspaceOwnershipGuard.ensureAppOwned(context, cwd)
                ensureStarted()
                val module = Python.getInstance().getModule("agent_runtime")
                val payload = module.callAttr("run_sqlmap", gson.toJson(args), cwd.canonicalPath, runtimeRoot.canonicalPath).toString()
                val obj = gson.fromJson(payload, JsonObject::class.java)
                Result.success(
                    RuntimeResult(
                        ok = obj["ok"]?.asBoolean == true,
                        stdout = obj["stdout"]?.asString.orEmpty(),
                        stderr = obj["stderr"]?.asString.orEmpty(),
                        exitCode = obj["exit_code"]?.asInt,
                        error = obj["error"]?.takeUnless { it.isJsonNull }?.asString,
                    ),
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "sqlmap execution failed: ${t.message}", t)
                Result.failure(t)
            }
        }
    }

    // Public API kept for protocol capability checks by other runtime components.
    @Suppress("unused")
    fun protocolCommandAvailable(name: String): Boolean =
        name in setOf(
            "ssh", "scp", "sftp", "sshpass", "smb", "smtp", "ftp", "rdp", "telnet", "openssl", "busybox", "pip", "pip3",
            "zip", "unzip", "tar", "gzip", "bzip2", "xz", "curl", "wget", "tree", "eza", "bat", "fd", "rg", "jq", "yq",
            "git", "gh", "zsh", "tmux", "nvim", "neovim", "clang", "make", "go", "rustc", "cargo", "composer", "php", "ruby",
            "node", "npm", "npx", "java", "javac", "jar", "gradle", "kotlinc", "adb", "aapt2", "apksigner", "zipalign", "d8", "sdkmanager",
            "inetutils", "dnsutils", "net-tools", "iproute2", "util-linux", "procps", "psmisc", "socat", "nmap", "sqlmap", "ping", "nslookup", "dig", "ifconfig", "netstat", "route", "arp", "ip", "ss",
            "coreutils", "findutils", "diffutils", "grep", "sed", "gawk", "awk", "less", "file", "which", "find", "diff", "diff3", "cmp", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "hostname", "uname", "id", "whoami", "date", "df", "du", "ps", "free", "uptime",
        )

    data class RuntimeResult(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val error: String?,
    )
}
