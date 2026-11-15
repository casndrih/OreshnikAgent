package com.example.oreshik.logging

import android.util.Log

/**
 * Centralized logging utility for the app.
 * Provides structured logging with tags, levels, and crash/process tracing.
 * Logs can be viewed via: adb logcat -s "OreshikApp:*"
 */
object AppLogger {
    private const val DEFAULT_TAG = "OreshikApp"
    private var minLogLevel = Log.DEBUG
    private var fileLoggingEnabled = false
    private var logFile: java.io.File? = null
    @Volatile private var crashHandlerInstalled = false

    enum class Level(val androidLevel: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        ASSERT(Log.ASSERT)
    }

    /**
     * Installs a global uncaught-exception handler that logs any crash
     * (with full stack trace) to logcat + file before the process dies.
     * Idempotent; call once from Application.onCreate().
     */
    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logInternal(
                    Level.ERROR, "Global/Crash",
                    "UNCAUGHT on thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}",
                    throwable
                )
            }
            // Chain to the previous handler so the system crash flow is preserved.
            previous?.uncaughtException(thread, throwable)
        }
        logInternal(Level.INFO, "AppLogger", "Global crash handler installed")
    }

    /**
     * Initialize logger with custom settings.
     * Call this in Application.onCreate()
     */
    fun init(context: android.content.Context, minLevel: Level = Level.DEBUG, enableFileLogging: Boolean = true) {
        minLogLevel = minLevel.androidLevel
        fileLoggingEnabled = enableFileLogging
        if (enableFileLogging) {
            logFile = java.io.File(context.filesDir, "oreshik_app.log")
            // Rotate if > 5MB
            if (logFile!!.exists() && logFile!!.length() > 5 * 1024 * 1024) {
                logFile!!.delete()
            }
        }
        logInternal(Level.INFO, "AppLogger", "AppLogger initialized")
    }

    /** Set minimum log level at runtime */
    fun setMinLevel(level: Level) {
        minLogLevel = level.androidLevel
    }

    /** Get current minimum log level */
    fun getMinLevel(): Level = Level.values().first { it.androidLevel == minLogLevel }

    /** Check if a level is enabled */
    private fun isEnabled(level: Level): Boolean = level.androidLevel >= minLogLevel

    /** Core logging function */
    private fun logInternal(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(level)) return
        
        val fullTag = "$DEFAULT_TAG:$tag"
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val threadName = Thread.currentThread().name
        val formattedMessage = "[$timestamp][$threadName] $message"

        // Android logcat
        when (level) {
            Level.VERBOSE -> Log.v(fullTag, formattedMessage)
            Level.DEBUG -> Log.d(fullTag, formattedMessage)
            Level.INFO -> Log.i(fullTag, formattedMessage)
            Level.WARN -> Log.w(fullTag, formattedMessage, throwable)
            Level.ERROR -> Log.e(fullTag, formattedMessage, throwable)
            Level.ASSERT -> Log.wtf(fullTag, formattedMessage, throwable)
        }

        // File logging
        if (fileLoggingEnabled && logFile != null) {
            try {
                val writer = java.io.FileWriter(logFile!!, true)
                val throwableStr = throwable?.let { 
                    java.io.StringWriter().also { sw -> it.printStackTrace(java.io.PrintWriter(sw)) }.toString()
                } ?: ""
                writer.write("$formattedMessage$throwableStr\n")
                writer.close()
            } catch (_: Exception) {
                // Ignore file logging failures
            }
        }
    }

    // Convenience methods with tag parameter
    fun v(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.VERBOSE, tag, message, throwable)
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.ERROR, tag, message, throwable)
    }

    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        logInternal(Level.ASSERT, tag, message, throwable)
    }

    /** Log a crash with full context */
    fun logCrash(tag: String, throwable: Throwable, context: String = "") {
        val msg = "CRASH${if (context.isNotBlank()) " in $context" else ""}: ${throwable.message}"
        logInternal(Level.ERROR, tag, msg, throwable)
    }

    /** Log process execution details */
    fun logProcess(tag: String, command: String, exitCode: Int?, stdout: String, stderr: String, durationMs: Long) {
        val msg = "Process: $command\nExit: $exitCode | Duration: ${durationMs}ms\nSTDOUT: ${stdout.take(500)}\nSTDERR: ${stderr.take(500)}"
        if (exitCode == 0) logInternal(Level.DEBUG, tag, msg) else logInternal(Level.WARN, tag, msg)
    }

    /** Log network/HTTP request */
    fun logNetwork(tag: String, method: String, url: String, statusCode: Int?, durationMs: Long, error: String? = null) {
        val msg = "$method $url -> ${statusCode ?: "ERR"} (${durationMs}ms)${if (error != null) " Error: $error" else ""}"
        logInternal(if (error != null) Level.WARN else Level.DEBUG, tag, msg)
    }

    /** Log database operation */
    fun logDatabase(tag: String, operation: String, table: String, durationMs: Long, rowsAffected: Int? = null, error: String? = null) {
        val msg = "DB $operation on $table (${durationMs}ms)${rowsAffected?.let { " rows=$it" } ?: ""}${error?.let { " Error: $it" } ?: ""}"
        logInternal(if (error != null) Level.ERROR else Level.DEBUG, tag, msg)
    }

    /** Log agent/tool execution */
    fun logAgent(tag: String, tool: String, status: String, durationMs: Long, outputPreview: String, error: String? = null) {
        val msg = "Agent Tool: $tool | $status | ${durationMs}ms | Out: ${outputPreview.take(200)}${error?.let { " | Err: $it" } ?: ""}"
        logInternal(if (error != null || status == "failed") Level.WARN else Level.INFO, tag, msg)
    }

    /** Get log file for sharing/debugging */
    fun getLogFile(): java.io.File? = logFile

    /** Clear log file */
    fun clearLogFile() {
        logFile?.delete()
        logInternal(Level.INFO, "AppLogger", "Log file cleared")
    }

    /** Read recent log entries */
    fun readRecentLogs(lines: Int = 200): List<String> {
        return logFile?.let { file ->
            file.readLines().takeLast(lines)
        } ?: emptyList()
    }
}

/** Extension for easy logging from any class */
fun Any.logV(message: String, throwable: Throwable? = null) = AppLogger.v(this.javaClass.simpleName, message, throwable)
fun Any.logD(message: String, throwable: Throwable? = null) = AppLogger.d(this.javaClass.simpleName, message, throwable)
fun Any.logI(message: String, throwable: Throwable? = null) = AppLogger.i(this.javaClass.simpleName, message, throwable)
fun Any.logW(message: String, throwable: Throwable? = null) = AppLogger.w(this.javaClass.simpleName, message, throwable)
fun Any.logE(message: String, throwable: Throwable? = null) = AppLogger.e(this.javaClass.simpleName, message, throwable)
fun Any.logWtf(message: String, throwable: Throwable? = null) = AppLogger.wtf(this.javaClass.simpleName, message, throwable)
