package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory line-oriented shell sessions; no PTY, privilege escalation or command persistence. */
private object TerminalSessions {
    private const val MAX_SESSIONS = 4
    private const val MAX_BUFFER_CHARS = 64 * 1024
    private data class Session(
        val id: String,
        val process: Process,
        val startedAt: Long,
        val output: StringBuilder = StringBuilder(),
        val lock: Any = Any(),
    )
    private val sessions = ConcurrentHashMap<String, Session>()

    fun start(workspace: File, home: File): String {
        cleanupDead()
        require(sessions.size < MAX_SESSIONS) { "Maximum of $MAX_SESSIONS concurrent terminal sessions reached." }
        val shell = if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "sh"
        val process = ProcessBuilder(shell)
            .directory(workspace.canonicalFile)
            .redirectErrorStream(true)
            .apply {
                environment()["TERM"] = "dumb"
                environment()["HOME"] = home.absolutePath
                environment()["PWD"] = workspace.canonicalPath
            }
            .start()
        val id = "term_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val session = Session(id, process, System.currentTimeMillis())
        sessions[id] = session
        Thread({
            runCatching {
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(session.lock) {
                            session.output.append(buffer, 0, count)
                            if (session.output.length > MAX_BUFFER_CHARS) {
                                session.output.delete(0, session.output.length - MAX_BUFFER_CHARS)
                            }
                        }
                    }
                }
            }
        }, "oreshik-terminal-$id").apply { isDaemon = true; start() }
        return id
    }

    fun write(id: String, command: String) {
        require(command.isNotBlank() && command.length <= 8192) { "Command must contain 1–8192 characters." }
        val session = sessions[id] ?: error("Terminal session not found.")
        require(session.process.isAlive) { "Terminal process has exited." }
        synchronized(session.lock) {
            OutputStreamWriter(session.process.outputStream, StandardCharsets.UTF_8).useCommandWrite(command)
        }
    }

    fun read(id: String, maxChars: Int): String {
        val session = sessions[id] ?: error("Terminal session not found.")
        synchronized(session.lock) {
            val count = maxChars.coerceIn(1000, 12_000).coerceAtMost(session.output.length)
            val result = session.output.substring(0, count)
            session.output.delete(0, count)
            if (!session.process.isAlive && session.output.isEmpty()) sessions.remove(id, session)
            return result.ifBlank { if (session.process.isAlive) "No new output yet." else "Process exited; no unread output." }
        }
    }

    fun list(): String {
        cleanupDead()
        return sessions.values.sortedBy { it.startedAt }.joinToString("\n") {
            "${it.id} · pid=${it.process.pid()} · ${if (it.process.isAlive) "running" else "exited"} · started=${it.startedAt}"
        }.ifBlank { "No active terminal sessions." }
    }

    fun stop(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        runCatching { OutputStreamWriter(session.process.outputStream, StandardCharsets.UTF_8).useCommandWrite("exit") }
        if (session.process.isAlive) {
            session.process.destroy()
            if (!session.process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                session.process.destroyForcibly()
            }
        }
        return true
    }

    private fun cleanupDead() {
        sessions.entries.removeIf { entry ->
            val session = entry.value
            !session.process.isAlive && synchronized(session.lock) { session.output.isEmpty() }
        }
    }

    private fun OutputStreamWriter.useCommandWrite(command: String) {
        write(command)
        write("\n")
        flush()
    }
}

class TerminalSessionTool : AgentTool {
    override val definition = ToolDefinition(
        name = "terminal.session",
        category = ToolCategory.PROCESS,
        description = "Start, list, write to, read from, and stop persistent line-oriented shell sessions in the app-owned workspace. Requires process execution permission. This is a pipe-based shell, not a full PTY; sessions end when the app process exits.",
        permissions = setOf(ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf(
            "action" to "string (start, list, write, read, stop)",
            "session_id" to "string",
            "command" to "string (write; max 8192 characters)",
            "max_chars" to "integer (read; 1000–12000)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "list") {
            "start" -> runCatching {
                require(context.workspace.isDirectory) { "Workspace directory is unavailable." }
                val id = TerminalSessions.start(context.workspace, context.app.filesDir)
                ToolResult.success("Terminal session started: $id\nWorking directory: ${context.workspace.canonicalPath}")
            }.getOrElse { ToolResult.failure("Could not start terminal session: ${it.message}") }
            "list" -> ToolResult.success(TerminalSessions.list())
            "write" -> runCatching {
                val id = args["session_id"]?.asString.orEmpty()
                val command = args["command"]?.asString.orEmpty()
                TerminalSessions.write(id, command)
                ToolResult.success("Sent command to terminal session $id.")
            }.getOrElse { ToolResult.failure("Terminal write failed: ${it.message}") }
            "read" -> runCatching {
                val id = args["session_id"]?.asString.orEmpty()
                val max = args["max_chars"]?.asInt ?: 8000
                ToolResult.success(TerminalSessions.read(id, max))
            }.getOrElse { ToolResult.failure("Terminal read failed: ${it.message}") }
            "stop" -> {
                val id = args["session_id"]?.asString.orEmpty()
                if (TerminalSessions.stop(id)) ToolResult.success("Stopped terminal session $id.")
                else ToolResult.failure("Terminal session not found.")
            }
            else -> ToolResult.failure("Supported actions: start, list, write, read, stop.")
        }
    }
}
