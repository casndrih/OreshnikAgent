package com.example.oreshik

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.core.AgentEngine
import com.example.oreshik.agent.security.PermissionBroker
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-on wake-word voice assistant.
 *
 * Runs as a foreground service with the microphone type so it keeps listening both
 * while the app is in the foreground and in the background. Detects wake phrases
 * ("Hey Oreshik", "Hey Bro", "Hi Oreshik", "Hey Casper", "Hi Casper"), extracts the
 * request that follows, executes it through the agent engine (full tool registry:
 * open/close apps, SMS, WhatsApp/Gmail automation, SSH/SMB via the bundled Python
 * runtime, files, web, …), and talks back via TTS - asking for confirmation on
 * destructive/external operations.
 *
 * While speaking, the media volume is boosted (when the setting is enabled) so
 * confirmations are clearly audible, then restored afterwards.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.oreshik.action.STOP_WAKE_WORD"

        private const val CHANNEL = "wake_word_assistant"
        private const val FOREGROUND_ID = 7201
        private const val MIC_MISSING_NOTIFICATION_ID = 7202

        /** Milliseconds of silence after the request before we consider it complete. */
        private const val REQUEST_END_SILENCE_MS = 1200L
        /** How long to wait for a confirmation answer before giving up (measured AFTER the question finishes being spoken). */
        private const val CONFIRM_TIMEOUT_MS = 12_000L
        /** Base restart delay after recognizer errors so we don't spin in a tight error loop. */
        private const val RESTART_BASE_DELAY_MS = 1500L
        /** Cap for the exponential backoff so recovery stays responsive but bounded. */
        private const val RESTART_MAX_DELAY_MS = 30_000L
        /** Minimum gap between destroying one recognizer and creating the next (MIUI ERROR_CLIENT fix). */
        private const val RECREATE_RECOGNIZER_DELAY_MS = 600L
        /** Restart listening if the recognizer has been silent for this long (watchdog). */
        private const val WATCHDOG_TIMEOUT_MS = 20_000L
        /** Maximum retries when the confirmation answer is unclear. */
        private const val MAX_CONFIRM_RETRIES = 2
        /** How long an acknowledged wake word ("Hey Oreshik?") waits for the follow-up request. */
        private const val FOLLOWUP_TIMEOUT_MS = 6000L
        /** Cooldown after ERROR_TOO_MANY_REQUESTS (13): the speech service is rate-limiting us. */
        private const val RATE_LIMIT_BASE_DELAY_MS = 60_000L
        /** Cap for the rate-limit cooldown growth. */
        private const val RATE_LIMIT_MAX_DELAY_MS = 600_000L
        /**
         * After this many consecutive sessions that failed WITHOUT any audio-path
         * callback (dead speech service, missing mic, emulator without a microphone),
         * the retry cadence settles into a long low-duty sleep instead of looping.
         */
        private const val NO_AUDIO_FAILURES_BEFORE_LONG_SLEEP = 5
        /** Long sleep between retries when the recognizer never delivers audio. */
        private const val NO_AUDIO_LONG_SLEEP_MS = 60_000L
        /**
         * Anti-rate-limit damping: after this many sessions in a row that ended
         * almost instantly (speech-service churn), take one short breather instead
         * of more churn - the exact pattern that trips Google's speech-service
         * throttle (ERROR_TOO_MANY_REQUESTS / ERROR_13).
         */
        private const val MAX_INSTANT_SESSIONS_IN_A_ROW = 20
        /** A session shorter than this counts as an instant session for damping. */
        private const val INSTANT_SESSION_MS = 2500L
        /** Breather length after [MAX_INSTANT_SESSIONS_IN_A_ROW] instant sessions. */
        private const val INSTANT_SESSION_BREATH_MS = 45_000L

        val WAKE_PHRASES = listOf(
            "hey oreshik", "hi oreshik", "ok oreshik", "okay oreshik",
            "hey bro", "hi bro", "ok bro", "okay bro",
            "hey casper", "hi casper", "ok casper", "okay casper",
            "hey casper's", "hey caspers",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    /** Guards against double TextToSpeech binds while the async init is in flight. */
    @Volatile private var ttsInitializing = false

    // ---- State machine ----
    private enum class State { IDLE, LISTENING_REQUEST, AWAITING_CLARIFICATION, AWAITING_CONFIRMATION, AWAITING_RETRY, EXECUTING }
    @Volatile private var state = State.IDLE

    private val pendingRequest = StringBuilder()
    private var pendingIsSensitive = false
    private var pendingSummary = ""
    private var confirmRetries = 0
    /** Original transcript retained through clarification so nothing is lost. */
    private var pendingOriginal = ""
    /** Last failed request kept so a spoken "yes" can retry it. */
    private var pendingRetryRequest = ""
    /** True after a wake-word-only utterance; the next speech is treated as the request. */
    @Volatile private var armedForRequest = false
    private val clarifier = com.example.oreshik.agent.core.SpeechClarifier()
    private var confirmTimeoutRunnable: Runnable? = null
    private var restartRunnable: Runnable? = null
    private var requestEndRunnable: Runnable? = null
    private var followUpRunnable: Runnable? = null

    // ---- Recognition-session bookkeeping (lifecycle hardening) ----
    /** True while a SpeechRecognizer session is active. Guards against duplicate sessions. */
    @Volatile private var recognitionInFlight = false
    /** True once onDestroy has run; every deferred runnable checks this before acting. */
    @Volatile private var destroyed = false
    /** Consecutive failed restarts; drives the exponential backoff and resets on healthy audio. */
    private var consecutiveFailures = 0
    /** Consecutive rate-limit hits; drives the long cooldown and resets on a healthy session. */
    private var rateLimitHits = 0
    /** Wall clock of the most recent recognition callback (audio-flow evidence for the watchdog). */
    @Volatile private var lastRecognitionCallbackMs = 0L
    private var watchdogRunnable: Runnable? = null
    /** True once the current session proved the audio path is alive (mic open, RMS flowing). */
    @Volatile private var sessionHadAudio = false
    /** Wall clock when the current recognition session started (churn-damping metric). */
    @Volatile private var sessionStartMs = 0L
    /** Consecutive sessions that ended almost instantly (anti-rate-limit damping). */
    private var instantSessionStreak = 0
    /** Until this wall clock, restarts are paused (one breather to avoid throttling). */
    @Volatile private var breathingUntilMs = 0L

    // Volume boost
    private var boostedVolume = -1
    private var originalVolume = -1

    /** True while TTS is actively speaking (listening pauses so we don't hear ourselves). */
    @Volatile private var isSpeakingNow = false
    /** Optional callback invoked when the current speech finishes. */
    private var speakCallback: (() -> Unit)? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> {
                    AppLogger.i(TAG, "Stop broadcast received")
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AgentConfig.init(this)
        createChannel()

        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }

        initTts()
        // Lift OS/MIUI restrictions for background mic via the app's own root bridge.
        // Runs off-main-thread; root commands are verified and reported to diagnostics.
        scope.launch {
            runCatching {
                com.example.oreshik.agent.security.WakeWordRootFixer.applyAll(this@WakeWordService)
            }.onFailure {
                AppLogger.e(TAG, "WakeWordRootFixer failed", it)
            }
        }
        AppLogger.i(TAG, "WakeWordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.i(TAG, "Stop intent received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!AgentConfig.wakeWordEnabled) {
            AppLogger.i(TAG, "Wake word disabled in settings; not starting")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "RECORD_AUDIO not granted; cannot start wake word listening")
            WakeWordState.onAudioError("Microphone permission not granted")
            notifyMicMissing()
            stopSelf()
            return START_NOT_STICKY
        }

        if (state == State.IDLE) {
            // Fresh listening session (first start or restart after process death).
            WakeWordState.onServiceStarted()
            state = State.LISTENING_REQUEST
            startForegroundCompat()
            startListening()
        } else {
            // Already running: refresh the foreground notification and keep the existing
            // loop. Never create a second recognizer or a second listening loop.
            startForegroundCompat()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
        WakeWordState.onNotification(active = true)
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stopReceiver) }
        destroyRecognizer()
        shutdownTts()
        restoreVolumeIfNeeded()
        scope.cancel()
        WakeWordState.onServiceStopped()
        AppLogger.i(TAG, "WakeWordService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ TTS

    private fun initTts() {
        if (textToSpeech != null || ttsInitializing) return
        ttsInitializing = true
        // The TextToSpeech constructor performs blocking binder calls to the
        // TTS engine service (logcat showed a 5.9s transaction on this device);
        // never run it on this service's main thread or the recognizer loop and
        // notification updates freeze with it. speak() already falls back
        // gracefully while ttsReady is still false.
        Thread {
            runCatching {
                textToSpeech = TextToSpeech(this) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    ttsInitializing = false
                    if (ttsReady) {
                        textToSpeech?.language = Locale.getDefault()
                        textToSpeech?.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    mainHandler.post { onSpeakFinished() }
                                }

                                @Deprecated("Overrides deprecated TTS member")
                                @Suppress("DEPRECATION")
                                override fun onError(utteranceId: String?) {
                                    mainHandler.post { onSpeakFinished() }
                                }
                            },
                        )
                    } else {
                        AppLogger.w(TAG, "TTS init failed; talkback disabled")
                    }
                }
            }.onFailure {
                ttsInitializing = false
                AppLogger.w(TAG, "TTS bind failed: ${it.message}")
            }
        }.start()
    }

    private fun shutdownTts() {
        val tts = textToSpeech
        textToSpeech = null
        ttsReady = false
        ttsInitializing = false
        // stop()/shutdown() are blocking TTS binder calls — keep them off the
        // main thread for the same reason as initTts().
        if (tts != null) {
            Thread {
                runCatching {
                    tts.stop()
                    tts.shutdown()
                }
            }.start()
        }
    }

    /**
     * Speaks [text] via TTS. Pauses recognition while speaking so the assistant
     * doesn't hear itself, boosts the media volume for clarity, and invokes
     * [onDone] (if provided) once the utterance finishes.
     */
    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        speakCallback = onDone
        if (!ttsReady || text.isBlank()) {
            // No TTS available - fall through immediately so the flow still works.
            mainHandler.post { onSpeakFinished() }
            return
        }
        isSpeakingNow = true
        boostVolumeIfNeeded()
        destroyRecognizer()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake_${System.nanoTime()}")
    }

    private fun onSpeakFinished() {
        val callback = speakCallback
        speakCallback = null
        if (isSpeakingNow) {
            isSpeakingNow = false
            restoreVolumeIfNeeded()
        }
        callback?.invoke()
        val awaitingAnswer = (state == State.AWAITING_CONFIRMATION) ||
            (state == State.AWAITING_CLARIFICATION) ||
            (state == State.AWAITING_RETRY)
        if (!destroyed && (state == State.IDLE || state == State.LISTENING_REQUEST || awaitingAnswer)) {
            if (awaitingAnswer) {
                // FIX: keep the AWAITING_* state but bring the microphone back up so
                // the user's confirmation/cancellation answer is actually heard.
                // Previously the recognizer was destroyed for TTS and never restarted
                // in these states, so answers like "proceed" were never captured and
                // the confirmation always timed out ("I didn't hear a confirmation…").
                // The answer window restarts AFTER the question finishes being spoken,
                // so the user always gets the full CONFIRM_TIMEOUT_MS to answer.
                AppLogger.d(TAG, "TTS done; resuming mic + restarting answer window (state=$state)")
                startConfirmTimeout()
                startListening()
            } else {
                state = State.LISTENING_REQUEST
                // A pending backoff restart is now redundant; resume listening immediately.
                restartRunnable?.let { mainHandler.removeCallbacks(it) }
                restartRunnable = null
                startListening()
            }
        }
    }

    // ---------------------------------------------------- Volume boost

    private fun boostVolumeIfNeeded() {
        if (!AgentConfig.wakeWordVolumeBoost) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalVolume = current
        // Target: at least 80% of max, but never lower than the current volume.
        val target = maxOf(current, (max * 0.8f).toInt().coerceAtLeast(1))
        boostedVolume = if (target != current) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            target
        } else {
            -1
        }
    }

    private fun restoreVolumeIfNeeded() {
        val target = boostedVolume
        if (target == -1) { originalVolume = -1; return }
        boostedVolume = -1
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Only restore if the user hasn't changed the volume again meanwhile.
        if (current >= target && originalVolume in 0 until current) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        originalVolume = -1
    }

    // -------------------------------------------------- Speech recognition

    /**
     * Starts a recognition session. Idempotent: if a session is already active or TTS is
     * speaking, this is a no-op, so repeated starts cannot create duplicate recorders or
     * concurrent listening loops.
     */
    private fun startListening() {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "RECORD_AUDIO not granted; cannot listen")
            WakeWordState.onAudioError("Microphone permission not granted")
            notifyMicMissing()
            stopSelf()
            return
        }
        if (isSpeakingNow) return
        if (recognitionInFlight) return

        mainHandler.post {
            if (destroyed || recognitionInFlight || isSpeakingNow) return@post
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    AppLogger.w(TAG, "No speech recognition service is available on this device")
                }
                // Destroy any leftover recognizer first - orphaned recognizers are a
                // known cause of ERROR_RECOGNIZER_BUSY on MIUI.
                if (recognizer != null) destroyRecognizer()
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
                recognizer?.setRecognitionListener(listener)
                sessionHadAudio = false
                recognitionInFlight = true
                sessionStartMs = System.currentTimeMillis()
                WakeWordState.onRecognizerCreated()
                armWatchdog()
                recognizer?.startListening(intent)
                AppLogger.i(TAG, "Recognition session started (instant-session streak=$instantSessionStreak/$MAX_INSTANT_SESSIONS_IN_A_ROW)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start listening", e)
                recognitionInFlight = false
                WakeWordState.onRecognizerDestroyed()
                WakeWordState.onAudioError("Failed to start recognition: ${e.message}")
                scheduleRestart()
            }
        }
    }

    /** Tears down the current recognizer (if any) and clears its watchdog/timers. */
    private fun destroyRecognizer() {
        cancelWatchdog()
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        recognitionInFlight = false
        WakeWordState.onRecognizerDestroyed()
        clearRequestEndTimer()
    }

    /** Marks the current recognition session as ended (terminal callback received). */
    private fun onRecognitionSessionEnded() {
        trackSessionDurationForDamping()
        recognitionInFlight = false
        WakeWordState.onRecognizerDestroyed()
        cancelWatchdog()
    }

    /**
     * Anti-rate-limit damping: sessions that end almost instantly in a row are the
     * signature of a misbehaving speech service — and the churn pattern that trips
     * Google's ERROR_TOO_MANY_REQUESTS throttle. After [MAX_INSTANT_SESSIONS_IN_A_ROW]
     * of them, pause restarts for one breather instead of feeding the throttle.
     */
    private fun trackSessionDurationForDamping() {
        val start = sessionStartMs
        sessionStartMs = 0L
        if (start <= 0) return
        val duration = System.currentTimeMillis() - start
        if (duration >= INSTANT_SESSION_MS) {
            // Healthy-length session: the streak is stale, clear it.
            instantSessionStreak = 0
            return
        }
        instantSessionStreak++
        if (instantSessionStreak >= MAX_INSTANT_SESSIONS_IN_A_ROW) {
            instantSessionStreak = 0
            breathingUntilMs = System.currentTimeMillis() + INSTANT_SESSION_BREATH_MS
            AppLogger.w(TAG, "Instant-session churn detected - pausing listening for ${INSTANT_SESSION_BREATH_MS / 1000}s to avoid speech-service rate limiting")
            WakeWordState.onAudioError("Listening paused ${INSTANT_SESSION_BREATH_MS / 1000}s (churn protection)")
            // Guarantee resumption when the breather ends: all restart paths bail
            // out via canRestartNow() while breathing, so schedule the wake-up here.
            postRestart(INSTANT_SESSION_BREATH_MS + 500)
        }
    }

    /** True when the listening loop can immediately start another session. */
    private fun canRestartNow(): Boolean =
        !destroyed && !recognitionInFlight && !isSpeakingNow &&
            state == State.LISTENING_REQUEST &&
            System.currentTimeMillis() >= breathingUntilMs

    /**
     * Fast restart after a session that simply heard nothing. Silence is the normal,
     * healthy outcome of an always-on listener - it must NOT feed the exponential
     * backoff, otherwise the loop degrades into long deaf gaps (30s+) during which the
     * wake word can never be heard.
     */
    private fun scheduleRestartAfterSilence() {
        if (!canRestartNow()) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            restartRunnable = null
            if (destroyed) return@Runnable
            if (canRestartNow()) startListening()
        }
        restartRunnable = r
        mainHandler.postDelayed(r, RECREATE_RECOGNIZER_DELAY_MS)
    }

    /**
     * Schedules the next listening attempt with exponential backoff. consecutiveFailures is
     * incremented here and reset whenever healthy audio flows, so transient failures recover
     * quickly while persistent failures settle into a bounded, low-duty retry cadence instead
     * of an infinite tight loop.
     */
    private fun scheduleRestart() {
        consecutiveFailures++
        WakeWordState.onRestartScheduled()
        val delay = restartDelayMs()
        AppLogger.w(TAG, "Scheduling recognition restart #$consecutiveFailures in ${delay}ms")
        postRestart(delay)
    }

    /**
     * Recovery from sessions that failed WITHOUT any audio-path callback (no
     * onReadyForSpeech/RMS): a dead or unavailable speech service, a revoked mic,
     * or an emulator without a microphone. These are counted as real failures so
     * the exponential backoff grows; after [NO_AUDIO_FAILURES_BEFORE_LONG_SLEEP]
     * dead sessions in a row the cadence settles into a long low-duty sleep instead
     * of looping forever (the endless ERROR_NO_MATCH cycle seen in the logs).
     */
    private fun scheduleRestartAfterNoAudio() {
        consecutiveFailures++
        WakeWordState.onRestartScheduled()
        val delay = if (consecutiveFailures >= NO_AUDIO_FAILURES_BEFORE_LONG_SLEEP) {
            NO_AUDIO_LONG_SLEEP_MS
        } else {
            restartDelayMs()
        }
        AppLogger.w(
            TAG,
            "No audio from speech service (failure #$consecutiveFailures); retrying in ${delay / 1000}s"
        )
        postRestart(delay)
    }

    private fun postRestart(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { runRestart() }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    /** Body of a scheduled restart; reschedules itself while the churn breather is active. */
    private fun runRestart() {
        restartRunnable = null
        if (destroyed) return
        // Churn-protection breather still active: reschedule until it expires
        // (never start a session mid-breather; never lose the loop afterwards).
        val wait = breathingUntilMs - System.currentTimeMillis()
        if (wait > 0) {
            val r2 = Runnable { runRestart() }
            restartRunnable = r2
            mainHandler.postDelayed(r2, wait + 500)
            return
        }
        if (state != State.EXECUTING && !isSpeakingNow && !recognitionInFlight) {
            state = State.LISTENING_REQUEST
            startListening()
        }
    }

    private fun restartDelayMs(): Long {
        if (consecutiveFailures <= 1) return RESTART_BASE_DELAY_MS
        // After the first recreate-wait, every retry honours the recreate gap, then doubles.
        val shift = (consecutiveFailures - 1).coerceAtMost(5)
        val backoff = maxOf(RECREATE_RECOGNIZER_DELAY_MS, RESTART_BASE_DELAY_MS shl shift)
        return backoff.coerceAtMost(RESTART_MAX_DELAY_MS)
    }

    /**
     * Long cooldown after the speech service rate-limits us (ERROR_TOO_MANY_REQUESTS).
     * Restarting quickly here only deepens the throttle, so wait it out with growth.
     */
    private fun scheduleRestartAfterRateLimit() {
        if (!canRestartNow()) return
        rateLimitHits++
        val shift = (rateLimitHits - 1).coerceAtMost(4)
        val delay = (RATE_LIMIT_BASE_DELAY_MS shl shift).coerceAtMost(RATE_LIMIT_MAX_DELAY_MS)
        AppLogger.w(TAG, "Rate limited by speech service (hit #$rateLimitHits); cooling down ${delay / 1000}s")
        WakeWordState.onRateLimited(delay)
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            restartRunnable = null
            if (destroyed) return@Runnable
            if (canRestartNow()) startListening()
        }
        restartRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    /**
     * Watchdog: if the recognizer stops delivering any callback for [WATCHDOG_TIMEOUT_MS]
     * while a session should be active (recognizer died silently, MIUI reclaimed the mic,
     * vendor speech service crashed), destroy it and schedule a fresh session.
     */
    private fun armWatchdog() {
        cancelWatchdog()
        lastRecognitionCallbackMs = System.currentTimeMillis()
        val r = Runnable {
            watchdogRunnable = null
            if (destroyed || !recognitionInFlight) return@Runnable
            val silentFor = System.currentTimeMillis() - lastRecognitionCallbackMs
            if (silentFor >= WATCHDOG_TIMEOUT_MS) {
                AppLogger.w(TAG, "Watchdog: no recognition callbacks for ${silentFor}ms; restarting session")
                WakeWordState.onAudioError("No audio callbacks for ${silentFor / 1000}s (watchdog restart)")
                destroyRecognizer()
                scheduleRestart()
            } else {
                // Callbacks arrived since arming - keep watching.
                armWatchdog()
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun speechErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        else -> "ERROR_$error"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            // A session actually started (mic path healthy) - any accumulated backoff is stale.
            consecutiveFailures = 0
            rateLimitHits = 0
            WakeWordState.onRateLimitCleared()
            WakeWordState.onAudioFrame()
        }
        override fun onBeginningOfSpeech() {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            WakeWordState.onAudioFrame()
        }
        override fun onRmsChanged(dB: Float) {
            // Fires continuously while audio flows; the cheapest liveness evidence we have.
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            WakeWordState.onAudioFrame()
        }
        override fun onBufferReceived(buffer: ByteArray?) {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            WakeWordState.onAudioFrame()
        }
        override fun onEndOfSpeech() {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            WakeWordState.onAudioFrame()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            sessionHadAudio = true
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val text = list.firstOrNull() ?: return
            // Real audio is flowing; a healthy session resets the failure backoff.
            consecutiveFailures = 0
            WakeWordState.onRecognitionResult(text)
            handleTranscript(text, final = false)
        }

        override fun onResults(results: Bundle?) {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty()
            // FIX: a normally-completed session is also a terminal path. Previously nothing
            // restarted recognition here, so background listening died after one session.
            onRecognitionSessionEnded()
            consecutiveFailures = 0
            WakeWordState.onRecognitionResult(text)
            handleTranscript(text, final = true)
            if (canRestartNow()) scheduleRestartAfterSilence()
        }

        override fun onError(error: Int) {
            lastRecognitionCallbackMs = System.currentTimeMillis()
            onRecognitionSessionEnded()
            val hadAudio = sessionHadAudio
            sessionHadAudio = false
            val name = speechErrorName(error)
            WakeWordState.onRecognitionError(name, error)
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    if (!hadAudio) {
                        // The recognizer failed without a single audio-path callback:
                        // dead speech service, revoked mic, or an emulator without a
                        // microphone. Back off (bounded) instead of looping forever.
                        AppLogger.w(TAG, "Recognition error: $name ($error) with no audio this session - ${SpeechErrors.message(error)}")
                        scheduleRestartAfterNoAudio()
                    } else if (pendingRequest.isNotBlank()) {
                        // Real audio flowed - silence is a healthy outcome. Flush any
                        // accumulated request, otherwise just restart quickly.
                        AppLogger.d(TAG, "Recognition error: $name ($error) after audio; flushing request")
                        handleTranscript(pendingRequest.toString(), final = true)
                        if (canRestartNow()) scheduleRestartAfterSilence()
                    } else {
                        AppLogger.d(TAG, "Recognition error: $name ($error) after audio (plain silence); fast restart")
                        scheduleRestartAfterSilence()
                    }
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    // Hidden API-31 constant; value 13. Back off hard.
                    AppLogger.w(TAG, "Recognition error: $name ($error) - ${SpeechErrors.message(error)}")
                    scheduleRestartAfterRateLimit()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Ensure no orphaned recognizer holds the service, then retry shortly.
                    AppLogger.w(TAG, "Recognition error: $name ($error) - ${SpeechErrors.message(error)}")
                    destroyRecognizer()
                    scheduleRestart()
                }
                else -> {
                    AppLogger.w(TAG, "Recognition error: $name ($error) - ${SpeechErrors.message(error)}")
                    scheduleRestart()
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------- Wake-word state machine

    private fun handleTranscript(text: String, final: Boolean) {
        when (state) {
            State.AWAITING_CONFIRMATION -> {
                // Only FINAL transcripts count as confirmation answers. Partials are
                // truncated ("pro…", "y…") and would burn retry attempts and re-ask
                // while the user is still speaking.
                if (final) handleConfirmationAnswer(text)
                else AppLogger.d(TAG, "Confirmation partial ignored: \"$text\"")
            }
            State.AWAITING_CLARIFICATION -> {
                if (final) handleClarificationAnswer(text)
                else AppLogger.d(TAG, "Clarification partial ignored: \"$text\"")
            }
            State.AWAITING_RETRY -> {
                // "Sorry, that didn't go through. Want me to try again?" — "yes"
                // must actually retry the failed request (previously it was
                // silently dropped because no retry state existed).
                if (final) handleRetryAnswer(text)
                else AppLogger.d(TAG, "Retry partial ignored: \"$text\"")
            }
            State.IDLE, State.LISTENING_REQUEST -> {
                val lower = text.trim().lowercase(Locale.getDefault())
                val wake = WAKE_PHRASES.firstOrNull { lower.startsWith(it) || lower == it }
                if (wake != null) {
                    val request = lower.removePrefix(wake).trim().trimStart(',', ':', '.')
                    if (request.isBlank()) {
                        // Wake word alone - arm for the request that follows. The
                        // next utterance (even without repeating the wake word) is
                        // treated as the request until the follow-up timeout.
                        if (final) {
                            pendingRequest.setLength(0)
                            armedForRequest = true
                            scheduleFollowUpTimer()
                            speak(randomVariant(R.string.wake_word_yes_ack_variants))
                        }
                        return
                    }
                    // A full utterance "wake word + request"
                    pendingRequest.setLength(0)
                    pendingRequest.append(request)
                    armedForRequest = false
                    if (final) finishRequest() else scheduleRequestEndTimer()
                } else if (pendingRequest.isNotBlank()) {
                    // Continuation of a request spoken after the wake word.
                    pendingRequest.append(' ').append(text.trim())
                    armedForRequest = false
                    if (final) finishRequest() else scheduleRequestEndTimer()
                } else if (armedForRequest) {
                    // Request spoken after an acknowledged wake word ("Hey Oreshik" -> "Yes?" -> request)
                    pendingRequest.append(text.trim())
                    armedForRequest = false
                    cancelFollowUpTimer()
                    if (final) finishRequest() else scheduleRequestEndTimer()
                }
            }
            State.EXECUTING -> { /* ignore transcripts while the agent is running */ }
        }
    }

    /**
     * Handles the answer while waiting for confirmation of a sensitive request.
     * Confirm words execute the pending request; cancel words drop it; unclear
     * answers retry (bounded) and eventually cancel safely.
     */
    private fun handleConfirmationAnswer(text: String) {
        val answer = text.trim().lowercase(Locale.getDefault())
        if (answer.isBlank()) return
        val verdict = when {
            isConfirmWord(answer) -> "CONFIRM"
            isCancelWord(answer) -> "CANCEL"
            else -> "UNCLEAR"
        }
        AppLogger.i(TAG, "Confirmation answer heard: \"$answer\" → $verdict")
        when {
            isConfirmWord(answer) -> {
                cancelConfirmTimeout()
                state = State.EXECUTING
                speakThenRun()
            }
            isCancelWord(answer) -> {
                cancelConfirmTimeout()
                resetPending()
                state = State.LISTENING_REQUEST
                speak(randomVariant(R.string.wake_word_cancel_variants))
            }
            else -> {
                confirmRetries++
                if (confirmRetries > MAX_CONFIRM_RETRIES) {
                    cancelConfirmTimeout()
                    resetPending()
                    state = State.LISTENING_REQUEST
                    speak(randomVariant(R.string.wake_word_timeout_variants))
                } else {
                    // Re-ask for a clearer answer
                    speak(randomVariant(R.string.wake_word_didnt_catch_variants))
                }
            }
        }
    }

    /**
     * Handles the answer to an intelligent-clarification question. "Yes" resumes
     * the original request; a spoken correction replaces it and re-runs the
     * normal decision (including the confirmation flow) on the revised command.
     */
    private fun handleClarificationAnswer(text: String) {
        val answer = text.trim()
        if (answer.isBlank()) return
        val lower = answer.lowercase(Locale.getDefault())
        cancelConfirmTimeout()
        when {
            isCancelWord(lower) -> {
                resetPending()
                state = State.LISTENING_REQUEST
                speak(randomVariant(R.string.wake_word_cancel_variants))
            }
            // "Yes, search." -> resume the ORIGINAL request.
            isConfirmWord(lower) -> {
                val original = pendingOriginal.ifBlank { pendingRequest.toString().trim() }
                resetPending()
                state = State.EXECUTING
                speakThenRun(original)
            }
            // A spoken correction: treat it as the new command. The original is
            // preserved so the user can revert context if needed.
            else -> {
                val original = pendingOriginal.ifBlank { pendingRequest.toString().trim() }
                resetPending()
                pendingOriginal = original
                pendingRequest.append(answer)
                finishRequest()
            }
        }
    }

    /**
     * Handles the answer to "Want me to try again?" after a failed request.
     * "Yes" retries the exact failed request; "no" drops it; anything else is
     * treated as a brand-new request.
     */
    private fun handleRetryAnswer(text: String) {
        val answer = text.trim()
        if (answer.isBlank()) return
        val lower = answer.lowercase(Locale.getDefault())
        AppLogger.i(TAG, "Retry answer heard: \"$lower\"")
        cancelConfirmTimeout()
        when {
            isCancelWord(lower) -> {
                pendingRetryRequest = ""
                state = State.LISTENING_REQUEST
                speak(randomVariant(R.string.wake_word_cancel_variants))
            }
            isConfirmWord(lower) -> {
                val request = pendingRetryRequest
                pendingRetryRequest = ""
                if (request.isBlank()) {
                    state = State.LISTENING_REQUEST
                    startListening()
                    return
                }
                state = State.EXECUTING
                speakThenRun(request)
            }
            // Anything else = a new request; fall through to the normal flow.
            else -> {
                pendingRetryRequest = ""
                state = State.LISTENING_REQUEST
                pendingRequest.append(answer)
                finishRequest()
            }
        }
    }

    private fun finishRequest() {
        clearRequestEndTimer()
        cancelFollowUpTimer()
        armedForRequest = false
        val request = pendingRequest.toString().trim()
        if (request.isBlank()) return
        pendingRequest.setLength(0)
        confirmRetries = 0

        // --- Intelligent speech clarification (voice path only, setting-aware) ---
        if (AgentConfig.speechClarificationEnabled && !AgentConfig.isOfflineMode) {
            val verdict = clarifier.evaluate(request)
            when (verdict.outcome) {
                com.example.oreshik.agent.core.SpeechClarifier.Outcome.LIKELY_TRANSCRIPTION_ERROR -> {
                    // Propose the corrected command; keep the original for context.
                    pendingOriginal = request
                    pendingRequest.setLength(0)
                    pendingRequest.append(verdict.revisedCommand)
                    pendingSummary = verdict.revisedCommand
                    state = State.AWAITING_CLARIFICATION
                    startConfirmTimeout()
                    speak(verdict.question)
                    return
                }
                com.example.oreshik.agent.core.SpeechClarifier.Outcome.NEEDS_CLARIFICATION -> {
                    pendingOriginal = request
                    pendingSummary = request
                    state = State.AWAITING_CLARIFICATION
                    startConfirmTimeout()
                    speak(verdict.question)
                    return
                }
                com.example.oreshik.agent.core.SpeechClarifier.Outcome.CONFIDENT -> { /* run below */ }
            }
        }

        pendingIsSensitive = isSensitiveRequest(request)
        if (pendingIsSensitive) {
            pendingSummary = request
            state = State.AWAITING_CONFIRMATION
            startConfirmTimeout()
            speak(randomVariant(R.string.wake_word_confirm_variants))
        } else {
            state = State.EXECUTING
            // FIX: pass the captured request explicitly. speakThenRun() used to re-read
            // pendingRequest (already cleared above), so simple commands like
            // "Hey bro, open YouTube" resolved to a blank request and nothing ran.
            speakThenRun(request)
        }
    }

    private fun speakThenRun(request: String? = null) {
        val r = request?.trim().takeUnless { it.isNullOrEmpty() }
            ?: pendingSummary.ifBlank { pendingRequest.toString().trim() }
        pendingRequest.setLength(0)
        pendingSummary = ""
        if (r.isBlank()) {
            // Should never happen now, but fail loudly instead of a silent no-op.
            AppLogger.e(TAG, "speakThenRun called with a blank request; restarting listening")
            state = State.LISTENING_REQUEST
            startListening()
            return
        }
        AppLogger.i(TAG, "Acknowledging wake-word request: \"$r\"")
        speak(randomVariant(R.string.wake_word_working_variants)) {
            runAgent(r)
        }
    }

    /** Picks a random line from a '|'-separated variant resource for natural, non-robotic talkback. */
    private fun randomVariant(resId: Int): String =
        getString(resId).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            .randomOrNull() ?: getString(R.string.wake_word_working_on_it)

    private fun runAgent(request: String) {
        if (request.isBlank()) {
            state = State.LISTENING_REQUEST
            startListening()
            return
        }
        AppLogger.i(TAG, "Executing wake-word request: $request")

        val permissions = PermissionBroker(PermissionManager(this))
        permissions.onApprovalRequired = { tool, required ->
            com.example.oreshik.agent.security.PermissionApprovalCoordinator.request(
                this@WakeWordService,
                "voice",
                tool,
                required,
            )
        }
        val engine = AgentEngine(this, permissions = permissions)
        // Persistent spoken-conversation memory: survives service restarts (last 20 turns),
        // so the assistant remembers what it just did for you and talks like a person.
        val history = loadVoiceHistory()
        var assistantResponse = ""

        // Register as a cancellable task so a stop request (notification action or
        // spoken interruption) can cancel an in-flight wake-word operation too.
        val task = com.example.oreshik.agent.core.AgentTaskManager.create("wake_word", AgentConfig.model)
        val job = scope.launch {
            com.example.oreshik.agent.core.AgentTaskManager.markRunning(task, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
            val result = engine.run(
                request,
                history,
                AgentConfig.baseUrl,
                AgentConfig.apiKey,
                AgentConfig.model.ifBlank { "default" },
                AgentConfig.reasoningEffort,
                voice = true,
                sessionId = "voice",
            ) { event ->
                if (event.type == AgentEngine.Event.Type.TEXT) assistantResponse += event.text.orEmpty()
            }
            result.exceptionOrNull()?.let {
                AppLogger.e(TAG, "Agent failed: ${it.message}", it)
                if (task.state != com.example.oreshik.agent.core.AgentTaskManager.State.CANCELLING) {
                    assistantResponse = "Sorry, that didn't go through. Want me to try again?"
                    // Keep the request so a spoken "yes" can retry it (AWAITING_RETRY).
                    pendingRetryRequest = request
                }
            }
            val spoken = when (task.state) {
                com.example.oreshik.agent.core.AgentTaskManager.State.CANCELLING -> "Stopped."
                else -> spokenForTts(assistantResponse.trim())
                    .ifBlank { randomVariant(R.string.wake_word_done_variants) }
            }
            AppLogger.i(TAG, "Wake word response: \"$spoken\"")
            saveVoiceTurn(request, spoken)
            com.example.oreshik.agent.core.AgentTaskManager.markTerminal(
                task,
                if (task.state == com.example.oreshik.agent.core.AgentTaskManager.State.CANCELLING)
                    com.example.oreshik.agent.core.AgentTaskManager.State.CANCELLED
                else com.example.oreshik.agent.core.AgentTaskManager.State.COMPLETED
            )
            // Speak the response; when speech ends, onSpeakFinished resumes listening.
            // If a retry is being offered, switch to AWAITING_RETRY so the spoken
            // "yes" is routed to handleRetryAnswer instead of being dropped.
            state = if (pendingRetryRequest.isNotBlank()) State.AWAITING_RETRY else State.LISTENING_REQUEST
            if (pendingRetryRequest.isNotBlank()) startConfirmTimeout()
            speak(spoken)
        }
        task.job = job
    }

    // ------------------------------------------- Voice conversation memory

    /** Loads the persisted spoken-conversation history (oldest first, system prompt restored by the engine). */
    private fun loadVoiceHistory(): MutableList<JsonObject> {
        val prefs = getSharedPreferences("voice_conversation", MODE_PRIVATE)
        val json = prefs.getString("history", null) ?: return mutableListOf()
        return runCatching {
            Gson().fromJson(json, Array<JsonObject>::class.java).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    /** Appends the last spoken turn and trims to the most recent 10 user/assistant pairs. */
    private fun saveVoiceTurn(userText: String, assistantText: String) {
        val prefs = getSharedPreferences("voice_conversation", MODE_PRIVATE)
        val history = loadVoiceHistory()
        history.add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        history.add(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", assistantText) })
        val trimmed = history.takeLast(20)
        prefs.edit { putString("history", Gson().toJson(trimmed)) }
    }

    /**
     * Cleans model output for speech: strips markdown/symbols/URLs, collapses jargon-y
     * preambles, and trims to a spoken-length summary so TTS sounds like a person.
     */
    private fun spokenForTts(raw: String): String {
        var t = raw
            .replace(Regex("```[\\s\\S]*?```"), " code ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*\n]+)\\*"), "$1")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[\\p{So}\\p{C}]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        // Drop typical robotic preambles a person wouldn't say out loud.
        t = t.replace(Regex("^(?i)(sure|okay|certainly)[,:!\\s]+"), "")
            .replace(Regex("^(?i)(i have|i've) (successfully |just )?(completed|finished|executed|done)[^.!?]*[.!?]\\s*"), "")
            .replace(Regex("^(?i)(successfully )?(completed|executed|processed)[^.!?]*[.!?]\\s*"), "")
        if (t.length > 320) {
            val cut = t.indexOf('.', 240).let { if (it > 0) it + 1 else 320 }
            t = t.take(cut).trim()
        }
        return t
    }

    private fun resetPending() {
        pendingRequest.setLength(0)
        pendingIsSensitive = false
        pendingSummary = ""
        confirmRetries = 0
        armedForRequest = false
        cancelFollowUpTimer()
    }

    // ------------------------------------------------- Confirmation words

    /** Exact standalone confirmation words. */
    private val confirmWords = setOf(
        "yes", "yeah", "yep", "yup", "ya", "confirm", "confirmed", "proceed", "continue",
        "do it", "do", "do proceed", "go ahead", "goahead", "go on", "ok", "okay", "kay",
        "k", "sure", "affirmative", "positive", "absolutely", "definitely", "correct",
        "right", "please do", "please", "indeed", "course", "of course", "sounds good",
        "sounds great", "works", "that works", "do that", "execute", "run it", "alright",
        "all right", "sure thing", "ofcourse", "yes please", "yeah please"
    )

    /**
     * Confirmation detection tolerant of recognizer noise around the key word:
     * "yes please", "okay sure do it", "yeah, go ahead with that", "please do"…
     * Matches whole words within the answer, not just the exact full string.
     */
    private fun isConfirmWord(answer: String): Boolean {
        val a = answer.trim().trim('.', '!', ',', '?').lowercase(Locale.getDefault())
        if (a.isBlank()) return false
        // Fast path: exact or prefix match.
        if (confirmWords.contains(a) || confirmWords.any { a.startsWith("$it ") || a.startsWith("$it,") }) return true
        // Word-based: any confirmation word appearing as a whole word in the answer.
        val words = a.split(Regex("[\\s,.!?]+")).filter { it.isNotBlank() }
        if (words.any { it in confirmWords }) return true
        // Bigrams for multi-word phrases split across tokens ("go ahead", "do it").
        if (words.size >= 2) {
            for (i in 0 until words.size - 1) {
                val bigram = "${words[i]} ${words[i + 1]}"
                if (bigram in confirmWords) return true
            }
        }
        return false
    }

    /** Exact standalone cancel words. */
    private val cancelWords = setOf(
        "no", "nope", "nah", "cancel", "cancelled", "canceled", "stop", "abort", "halt",
        "don't", "dont", "never mind", "nevermind", "don't do it", "do not", "donot",
        "negative", "wait", "hold on", "forget it", "skip it", "no thanks", "no thank you"
    )

    /** Cancel detection with the same noise tolerance as [isConfirmWord]. */
    private fun isCancelWord(answer: String): Boolean {
        val a = answer.trim().trim('.', '!', ',', '?').lowercase(Locale.getDefault())
        if (a.isBlank()) return false
        if (cancelWords.contains(a) || cancelWords.any { a.startsWith("$it ") || a.startsWith("$it,") }) return true
        val words = a.split(Regex("[\\s,.!?]+")).filter { it.isNotBlank() }
        if (words.any { it in cancelWords }) return true
        if (words.size >= 2) {
            for (i in 0 until words.size - 1) {
                val bigram = "${words[i]} ${words[i + 1]}"
                if (bigram in cancelWords) return true
            }
        }
        return false
    }

    private fun isSensitiveRequest(request: String): Boolean {
        val r = request.lowercase(Locale.getDefault())
        val sensitiveVerbs = listOf(
            "delete", "remove", "uninstall", "uninstal", "format", "wipe", "erase",
            "send", "text", "txt", "message", "email", "mail", "share", "post",
            "ssh", "smb", "remote", "connect to", "login", "log in", "sign in",
            "kill", "shutdown", "shut down", "reboot", "restart", "install",
            "payment", "pay", "purchase", "buy", "transfer", "status"
        )
        return sensitiveVerbs.any { r.contains(it) }
    }

    // ------------------------------------------------------------- Timers

    private fun startConfirmTimeout() {
        cancelConfirmTimeout()
        val r = Runnable {
            confirmTimeoutRunnable = null
            // FIX: also time out AWAITING_CLARIFICATION. Previously only
            // AWAITING_CONFIRMATION was handled here, so a clarification question
            // ("Did you mean …?") left the service stuck forever when unanswered.
            if (state == State.AWAITING_CONFIRMATION || state == State.AWAITING_CLARIFICATION) {
                resetPending()
                state = State.LISTENING_REQUEST
                speak(randomVariant(R.string.wake_word_timeout_variants))
            }
        }
        confirmTimeoutRunnable = r
        mainHandler.postDelayed(r, CONFIRM_TIMEOUT_MS)
    }

    private fun cancelConfirmTimeout() {
        confirmTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        confirmTimeoutRunnable = null
    }

    private fun scheduleRequestEndTimer() {
        clearRequestEndTimer()
        val r = Runnable {
            requestEndRunnable = null
            if (pendingRequest.isNotBlank() && state == State.LISTENING_REQUEST) {
                finishRequest()
            }
        }
        requestEndRunnable = r
        mainHandler.postDelayed(r, REQUEST_END_SILENCE_MS)
    }

    private fun clearRequestEndTimer() {
        requestEndRunnable?.let { mainHandler.removeCallbacks(it) }
        requestEndRunnable = null
    }

    private fun scheduleFollowUpTimer() {
        cancelFollowUpTimer()
        val r = Runnable {
            followUpRunnable = null
            armedForRequest = false
        }
        followUpRunnable = r
        mainHandler.postDelayed(r, FOLLOWUP_TIMEOUT_MS)
    }

    private fun cancelFollowUpTimer() {
        followUpRunnable?.let { mainHandler.removeCallbacks(it) }
        followUpRunnable = null
    }

    // -------------------------------------------------------- Notification

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake word assistant", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when the always-on wake word listener is active."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun notifyMicMissing() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            MIC_MISSING_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.wake_word_notification_title))
                .setContentText(getString(R.string.wake_word_status_mic_missing))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }
}

private const val TAG = "WakeWordService"
