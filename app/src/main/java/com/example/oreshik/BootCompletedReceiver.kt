package com.example.oreshik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.oreshik.agent.accessibility.AccessibilityKeepAliveService
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.security.AccessibilityRootFixer
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * METHOD 5/6 persistence for the MIUI accessibility bypass: after a reboot,
 * MIUI re-applies its default battery restrictions. This receiver re-applies
 * the root fixes ([AccessibilityRootFixer]) and restarts the keep-alive
 * foreground service so the accessibility/computer-use stack comes back on its
 * own instead of requiring the user to reopen the app.
 *
 * Enabled only when the app-level "Accessibility tools" switch is on. The
 * direct boot phase is intentionally skipped — secure settings are not readable
 * there and the fixer would fail silently.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        /** MIUI/HyperOS quick-boot variants of ACTION_BOOT_COMPLETED. */
        private val MIUI_BOOT_ACTIONS = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "miui.intent.action.BOOT_COMPLETED"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in MIUI_BOOT_ACTIONS) return
        AgentConfig.init(context)
        if (!AgentConfig.accessibilityEnabled) {
            AppLogger.i(TAG, "Accessibility tools disabled — skipping boot restore")
            return
        }

        val appContext = context.applicationContext
        AppLogger.i(TAG, "Boot completed — restoring MIUI accessibility bypass")
        val pending = goAsync()
        scope.launch {
            try {
                // MIUI re-arms PowerKeeper/battery defaults on boot: re-apply all
                // appops/whitelists/powerkeeper settings before anything else.
                runCatching { AccessibilityRootFixer.applyAll(appContext) }
                    .onFailure { AppLogger.w(TAG, "Boot root fix failed: ${it.message}") }
                // Restart the keep-alive foreground service (watchdog + wakelock).
                AccessibilityKeepAliveService.start(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
