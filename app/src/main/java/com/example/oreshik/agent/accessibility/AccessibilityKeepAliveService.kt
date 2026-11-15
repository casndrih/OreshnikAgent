package com.example.oreshik.agent.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.oreshik.MainActivity
import com.example.oreshik.R
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.security.AccessibilityRootFixer
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * METHOD 4 of the MIUI accessibility bypass: a quiet foreground service that
 * keeps the computer-use stack alive on aggressive ROMs.
 *
 *  - Persistent MIN-importance notification (no sound, no heads-up).
 *  - Renewing [PowerManager.PARTIAL_WAKE_LOCK] (30-minute pattern, same as
 *    [com.example.oreshik.AgentExecutionService]) so the CPU keeps running with
 *    the screen off.
 *  - 60 s watchdog loop:
 *      * every 5 min, re-applies [AccessibilityRootFixer] (root, opt-in) because
 *        MIUI PowerKeeper has been observed to reset appops/state over time;
 *      * if the accessibility service is registered-but-dead and root is on,
 *        force-rebinds it via secure settings.
 *
 * START_STICKY + [com.example.oreshik.BootCompletedReceiver] make it survive both
 * process death and reboots. The service self-stops when the app-level
 * "Accessibility tools" switch is turned off.
 */
class AccessibilityKeepAliveService : Service() {

    companion object {
        const val ACTION_START = "com.example.oreshik.action.START_ACCESSIBILITY_KEEP_ALIVE"
        const val ACTION_STOP = "com.example.oreshik.action.STOP_ACCESSIBILITY_KEEP_ALIVE"
        private const val CHANNEL = "accessibility_keep_alive"
        private const val FOREGROUND_ID = 1337
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val ROOT_REFRESH_INTERVAL_MS = 5 * 60_000L
        private const val WAKELOCK_MS = 30 * 60 * 1000L
        private const val TAG = "KeepAlive"

        @Volatile private var running = false

        /** True while the keep-alive foreground service is running. */
        @Suppress("unused")
        fun isRunning(): Boolean = running

        /** Foreground-safe start (call from an Activity or the accessibility service). */
        fun start(context: Context) {
            if (!AgentConfig.accessibilityEnabled) return
            val intent = Intent(context, AccessibilityKeepAliveService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
                .onFailure {
                    // Pre-O devices have no startForegroundService.
                    runCatching { context.startService(intent) }
                }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.startService(Intent(context, AccessibilityKeepAliveService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AgentConfig.init(this)
        running = true
        createChannel()
        startForegroundCompat()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AccessibilityKeepAlive")
            ?.apply { setReferenceCounted(false) }
        runCatching { if (wakeLock?.isHeld != true) wakeLock?.acquire(WAKELOCK_MS) }
        scope.launch { watchdogLoop() }
        AppLogger.i(TAG, "Accessibility keep-alive service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = when (intent?.action) {
        ACTION_STOP -> {
            AppLogger.i(TAG, "Stop intent received")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
        else -> {
            // Refresh the wakelock window whenever we are (re)started.
            runCatching { if (wakeLock?.isHeld != true) wakeLock?.acquire(WAKELOCK_MS) }
            START_STICKY
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        AppLogger.i(TAG, "Accessibility keep-alive service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // Watchdog
    // ------------------------------------------------------------------

    private suspend fun watchdogLoop() {
        var lastRootPass = 0L
        while (scope.isActive) {
            delay(WATCHDOG_INTERVAL_MS.milliseconds)
            try {
                if (!AgentConfig.accessibilityEnabled) {
                    // App-level kill switch: user turned accessibility tools off.
                    AppLogger.i(TAG, "Accessibility tools disabled in app settings — stopping keep-alive")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                // Keep the CPU alive beyond a single 30-min wakelock window.
                runCatching { if (wakeLock?.isHeld != true) wakeLock?.acquire(WAKELOCK_MS) }

                val now = System.currentTimeMillis()
                if ((AgentConfig.allowRoot) && ((now - lastRootPass) >= ROOT_REFRESH_INTERVAL_MS)) {
                    lastRootPass = now
                    runCatching { AccessibilityRootFixer.applyAll(this@AccessibilityKeepAliveService) }
                        .onFailure { AppLogger.w(TAG, "Periodic root fix pass failed: ${it.message}") }
                }

                // Registered-but-dead service (the classic MIUI kill signature):
                // re-bind it via root; otherwise the next agent run would fail.
                val dead = !AgentAccessibilityService.isBound() &&
                    AccessibilityRootFixer.isServiceRegistered(this)
                if (dead && AgentConfig.allowRoot && AgentConfig.accessibilityEnabled) {
                    AppLogger.w(TAG, "Accessibility service registered but not connected — force re-binding")
                    val msg = runCatching { AccessibilityRootFixer.forceRebind(this) }
                        .getOrDefault("force rebind threw")
                    AppLogger.i(TAG, "Force rebind result: $msg")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Keep-alive watchdog iteration failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Accessibility keep-alive",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps the Oreshik Agent accessibility service alive on aggressive battery managers (MIUI/HyperOS)."
                setShowBadge(false)
            },
        )
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }
}
