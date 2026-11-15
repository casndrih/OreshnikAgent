package com.example.oreshik.logging

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Internal, shareable crash/warning log: files/oreshik_diagnostics.txt */
object AppLogger {
    private const val DEFAULT_TAG = "OreshikApp"
    private const val MAX_LOG_BYTES = 4L * 1024L * 1024L
    private val fileLock = Any()
    @Volatile private var minLogLevel = Log.DEBUG
    @Volatile private var fileLoggingEnabled = false
    @Volatile private var logFile: File? = null
    @Volatile private var crashHandlerInstalled = false

    enum class Level(val androidLevel: Int) {
        VERBOSE(Log.VERBOSE), DEBUG(Log.DEBUG), INFO(Log.INFO),
        WARN(Log.WARN), ERROR(Log.ERROR), ASSERT(Log.ASSERT)
    }

    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(this) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    logInternal(Level.ERROR, "Global/Crash",
                        "FATAL UNCAUGHT on thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}", throwable)
                }
                if (previous != null) previous.uncaughtException(thread, throwable)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        logInternal(Level.INFO, "AppLogger", "Global crash handler installed")
    }

    fun init(context: Context, minLevel: Level = Level.DEBUG, enableFileLogging: Boolean = true) {
        minLogLevel = minLevel.androidLevel
        synchronized(fileLock) {
            logFile = File(context.filesDir, "oreshik_diagnostics.txt")
            fileLoggingEnabled = enableFileLogging
            logFile?.parentFile?.mkdirs()
            rotateIfNeeded()
        }
        logInternal(Level.INFO, "AppLogger", "Diagnostic logging initialized; file logging=$enableFileLogging")
    }

    fun setMinLevel(level: Level) { minLogLevel = level.androidLevel }
    fun getMinLevel(): Level = Level.values().first { it.androidLevel == minLogLevel }
    private fun isEnabled(level: Level): Boolean = level.androidLevel >= minLogLevel

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val backup = File(file.parentFile, file.name + ".1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }

    private fun logInternal(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(level)) return
        val fullTag = "$DEFAULT_TAG:$tag"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val formatted = "[$timestamp][${Thread.currentThread().name}][${level.name}][$tag] $message"
        when (level) {
            Level.VERBOSE -> Log.v(fullTag, formatted, throwable)
            Level.DEBUG -> Log.d(fullTag, formatted, throwable)
            Level.INFO -> Log.i(fullTag, formatted, throwable)
            Level.WARN -> Log.w(fullTag, formatted, throwable)
            Level.ERROR -> Log.e(fullTag, formatted, throwable)
            Level.ASSERT -> Log.wtf(fullTag, formatted, throwable)
        }
        if (!fileLoggingEnabled) return
        synchronized(fileLock) {
            try {
                val file = logFile ?: return
                file.parentFile?.mkdirs()
                rotateIfNeeded()
                BufferedWriter(FileWriter(file, true)).use { writer ->
                    writer.append(formatted).append('\n')
                    throwable?.let {
                        val trace = StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString()
                        writer.append(trace)
                        if (!trace.endsWith("\n")) writer.append('\n')
                    }
                }
            } catch (failure: Exception) {
                Log.e(fullTag, "Could not append diagnostic log: ${failure.message}", failure)
            }
        }
    }

    fun v(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.VERBOSE, tag, message, throwable)
    fun d(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.DEBUG, tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.INFO, tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.ERROR, tag, message, throwable)
    fun wtf(tag: String, message: String, throwable: Throwable? = null) = logInternal(Level.ASSERT, tag, message, throwable)

    fun logCrash(tag: String, throwable: Throwable, context: String = "") =
        logInternal(Level.ERROR, tag, "CRASH${if (context.isNotBlank()) " in $context" else ""}: ${throwable.message}", throwable)

    fun logProcess(tag: String, command: String, exitCode: Int?, stdout: String, stderr: String, durationMs: Long) {
        val msg = "Process: $command\nExit: $exitCode | Duration: ${durationMs}ms\nSTDOUT: ${stdout.take(500)}\nSTDERR: ${stderr.take(500)}"
        logInternal(if (exitCode == 0) Level.DEBUG else Level.WARN, tag, msg)
    }
    fun logNetwork(tag: String, method: String, url: String, statusCode: Int?, durationMs: Long, error: String? = null) {
        val msg = "$method $url -> ${statusCode ?: "ERR"} (${durationMs}ms)${if (error != null) " Error: $error" else ""}"
        logInternal(if (error != null) Level.WARN else Level.DEBUG, tag, msg)
    }
    fun logDatabase(tag: String, operation: String, table: String, durationMs: Long, rowsAffected: Int? = null, error: String? = null) {
        val msg = "DB $operation on $table (${durationMs}ms)${rowsAffected?.let { " rows=$it" } ?: ""}${error?.let { " Error: $it" } ?: ""}"
        logInternal(if (error != null) Level.ERROR else Level.DEBUG, tag, msg)
    }
    fun logAgent(tag: String, tool: String, status: String, durationMs: Long, outputPreview: String, error: String? = null) {
        val msg = "Agent Tool: $tool | $status | ${durationMs}ms | Out: ${outputPreview.take(200)}${error?.let { " | Err: $it" } ?: ""}"
        logInternal(if (error != null || status == "failed") Level.WARN else Level.INFO, tag, msg)
    }

    fun getLogFile(): File? = logFile
    fun clearLogFile() {
        synchronized(fileLock) { logFile?.delete() }
        logInternal(Level.INFO, "AppLogger", "Diagnostic log cleared")
    }
    fun readRecentLogs(lines: Int = 200): List<String> = synchronized(fileLock) {
        logFile?.takeIf { it.exists() }?.readLines()?.takeLast(lines) ?: emptyList()
    }
}

fun Any.logV(message: String, throwable: Throwable? = null) = AppLogger.v(this.javaClass.simpleName, message, throwable)
fun Any.logD(message: String, throwable: Throwable? = null) = AppLogger.d(this.javaClass.simpleName, message, throwable)
fun Any.logI(message: String, throwable: Throwable? = null) = AppLogger.i(this.javaClass.simpleName, message, throwable)
fun Any.logW(message: String, throwable: Throwable? = null) = AppLogger.w(this.javaClass.simpleName, message, throwable)
fun Any.logE(message: String, throwable: Throwable? = null) = AppLogger.e(this.javaClass.simpleName, message, throwable)
fun Any.logWtf(message: String, throwable: Throwable? = null) = AppLogger.wtf(this.javaClass.simpleName, message, throwable)
