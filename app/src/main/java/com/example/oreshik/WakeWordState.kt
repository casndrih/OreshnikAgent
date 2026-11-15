package com.example.oreshik

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide, thread-safe live status of the wake-word listener.
 *
 * Written by [WakeWordService] as recognition runs; read by the diagnostics
 * screen ([WakeWordDiagnosticsActivity]) and [SettingsActivity] so the UI can
 * show real system results (last successful audio read, last error, last wake
 * word detection, restarts) instead of invented values.
 */
object WakeWordState {
    enum class AudioStatus { NOT_STARTED, INITIALIZED, RECORDING, ERROR }

    /** Fields for display of last successful recognition activity. */
    data class Snapshot(
        val audioStatus: AudioStatus = AudioStatus.NOT_STARTED,
        val lastAudioReadMs: Long = 0L,
        val lastError: String? = null,
        val lastErrorCode: Int = 0,
        val lastErrorMs: Long = 0L,
        val lastWakeWordMs: Long = 0L,
        val lastWakeWordText: String? = null,
        val lastRestartMs: Long = 0L,
        val restartCount: Int = 0,
        val consecutiveFailures: Int = 0,
        val listeningSinceMs: Long = 0L,
        val serviceRunning: Boolean = false,
        val notificationActive: Boolean = false,
        val recognizerActive: Boolean = false,
    )

    @Volatile var serviceRunning: Boolean = false; private set
    @Volatile var notificationActive: Boolean = false; private set
    @Volatile var recognizerActive: Boolean = false; private set
    @Volatile var audioStatus: AudioStatus = AudioStatus.NOT_STARTED; private set
    @Volatile var listeningSinceMs: Long = 0L; private set
    @Volatile var consecutiveFailures: Int = 0; private set

    private val lastAudioReadMs = AtomicLong(0L)
    private val lastErrorMs = AtomicLong(0L)
    private val lastWakeWordMs = AtomicLong(0L)
    private val lastRestartMs = AtomicLong(0L)
    private val restartCount = AtomicLong(0L)
    private val lastError = AtomicReference<String?>(null)
    private val lastErrorCode = AtomicLong(0L)
    private val lastWakeWordText = AtomicReference<String?>(null)

    // ---------------------------------------------------------------- writes

    fun onServiceStarted() {
        serviceRunning = true
        listeningSinceMs = System.currentTimeMillis()
        audioStatus = AudioStatus.NOT_STARTED
        consecutiveFailures = 0
    }

    fun onServiceStopped() {
        serviceRunning = false
        notificationActive = false
        recognizerActive = false
        audioStatus = AudioStatus.NOT_STARTED
    }

    fun onNotification(active: Boolean) {
        notificationActive = active
    }

    fun onRecognizerCreated() {
        recognizerActive = true
        audioStatus = AudioStatus.INITIALIZED
    }

    fun onRecognizerDestroyed() { recognizerActive = false }

    fun onAudioFrame() {
        audioStatus = AudioStatus.RECORDING
        lastAudioReadMs.set(System.currentTimeMillis())
        consecutiveFailures = 0
    }

    fun onAudioError(message: String, code: Int = 0) {
        audioStatus = AudioStatus.ERROR
        lastError.set(message)
        lastErrorCode.set(code.toLong())
        lastErrorMs.set(System.currentTimeMillis())
    }

    fun onRecognitionResult(text: String) {
        lastAudioReadMs.set(System.currentTimeMillis())
        consecutiveFailures = 0
        val lower = text.trim().lowercase(java.util.Locale.getDefault())
        val isWake = WakeWordService.WAKE_PHRASES.any { phrase -> lower.startsWith(phrase) || (lower == phrase) }
        if (isWake) {
            lastWakeWordMs.set(System.currentTimeMillis())
            lastWakeWordText.set(text.trim().take(80))
        }
    }

    fun onRecognitionError(message: String, code: Int) {
        lastError.set(message)
        lastErrorCode.set(code.toLong())
        lastErrorMs.set(System.currentTimeMillis())
    }

    fun onRestartScheduled() {
        lastRestartMs.set(System.currentTimeMillis())
        restartCount.incrementAndGet()
        consecutiveFailures++
    }

    // ---- Rate-limit state (ERROR_TOO_MANY_REQUESTS cooldown) ----
    @Volatile var rateLimited: Boolean = false; private set
    @Volatile var rateLimitCooldownMs: Long = 0L; private set

    fun onRateLimited(cooldownMs: Long) {
        rateLimited = true
        rateLimitCooldownMs = cooldownMs
    }

    fun onRateLimitCleared() { rateLimited = false; rateLimitCooldownMs = 0L }

    fun snapshot(): Snapshot = Snapshot(
        audioStatus = audioStatus,
        lastAudioReadMs = lastAudioReadMs.get(),
        lastError = lastError.get(),
        lastErrorCode = lastErrorCode.get().toInt(),
        lastErrorMs = lastErrorMs.get(),
        lastWakeWordMs = lastWakeWordMs.get(),
        lastWakeWordText = lastWakeWordText.get(),
        lastRestartMs = lastRestartMs.get(),
        restartCount = restartCount.get().toInt(),
        consecutiveFailures = consecutiveFailures,
        listeningSinceMs = listeningSinceMs,
        serviceRunning = serviceRunning,
        notificationActive = notificationActive,
        recognizerActive = recognizerActive,
    )

    // ---- Root restriction fix (WakeWordRootFixer) ----
    @Volatile var rootFixApplied: Boolean = false; private set
    @Volatile var rootFixReport: String? = null; private set

    fun onRootFixApplied(ok: Boolean, report: String) {
        rootFixApplied = ok
        rootFixReport = report
    }

    /** Clears every diagnostic counter (used by tests). */
    @Suppress("unused")
    fun reset() {
        lastAudioReadMs.set(0L); lastError.set(null); lastErrorCode.set(0L); lastErrorMs.set(0L)
        lastWakeWordMs.set(0L); lastWakeWordText.set(null); lastRestartMs.set(0L)
        restartCount.set(0L); consecutiveFailures = 0; audioStatus = AudioStatus.NOT_STARTED
        listeningSinceMs = 0L
    }
}
