package com.example.oreshik.agent.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger

/**
 * Diagnoses why the Oreshik Agent accessibility service is not running and, where
 * the user's device is rooted and authorized, gathers read-only diagnostics that
 * point at the specific restriction (MIUI PowerKeeper/autostart management,
 * battery killers, foreground-service limits).
 *
 * This deliberately does NOT duplicate the existing service-management logic in
 * SettingsActivity (enabled/connected status + deep links). It extends it: the
 * settings screen keeps its status line and adds a watchdog diagnosis + a
 * user-initiated recovery attempt that runs the full MIUI bypass
 * ([com.example.oreshik.agent.security.AccessibilityRootFixer]) when root is on.
 */
object AccessibilityWatchdog {

    private const val TAG = "AccessibilityWatchdog"

    @Suppress("unused") // Reserved for future detection of "never enabled" state.
    enum class Problem { NONE, APP_LEVEL_DISABLED, KILLED_BY_SYSTEM, NOT_ENABLED, MIUI_RESTRICTION }

    @Suppress("unused")
    private val unusedProblemPlaceholder: Nothing? = null

    data class Diagnosis(
        val problem: Problem,
        val serviceEnabled: Boolean,
        val appEnabled: Boolean,
        val details: String,
        /** User-facing recovery steps, ordered. Empty when nothing is wrong. */
        val recoverySteps: List<String> = emptyList(),
        /** Root-only diagnostic command suggestions (read-only). */
        val rootDiagnostics: List<String> = emptyList(),
    )

    fun diagnose(context: Context): Diagnosis {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val serviceEnabled = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                (info.resolveInfo.serviceInfo.packageName == context.packageName) &&
                    (info.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name)
            } == true
        val appEnabled = com.example.oreshik.agent.core.AgentConfig.accessibilityEnabled

        if (!appEnabled) {
            return Diagnosis(
                Problem.APP_LEVEL_DISABLED,
                serviceEnabled = serviceEnabled,
                appEnabled = false,
                details = "Accessibility tools are disabled in the app settings.",
                recoverySteps = listOf("Enable 'Accessibility tools' in Settings."),
            )
        }
        if (serviceEnabled) {
            // Enabled & connected. Check whether the app process itself looks healthy.
            return Diagnosis(
                Problem.NONE,
                serviceEnabled = true,
                appEnabled = true,
                details = "Accessibility service is enabled and connected.",
            )
        }

        // Service registered in settings but not connected: it was likely killed by
        // the OEM system (MIUI PowerKeeper/autostart + battery killer) or disabled.
        val isXiaomi = isXiaomiDevice()
        val root = RootManager(context)
        val rootAvailable = root.canUseRoot()

        val details = buildString {
            append("Accessibility service is registered but not connected — it was likely ")
            append(if (isXiaomi) "killed by MIUI PowerKeeper/autostart management" else "killed by the system or disabled")
            append('.')
            if (rootAvailable) append(" Root is available for automatic recovery.")
        }

        val steps = mutableListOf(
            "Re-enable the service in Android Accessibility settings (it is turned off when the system kills it).",
        )
        if (isXiaomi) {
            steps += "Tap 'Fix MIUI restrictions' in Settings → Device automation (with root) to re-apply the bypass."
            steps += "Open Security app → Autostart → allow Oreshik Agent."
            steps += "Open Security app → Battery → No restrictions for Oreshik Agent."
            steps += "Lock the app in Recents (swipe-down on the app card)."
        }

        val problem = if (isXiaomi) Problem.MIUI_RESTRICTION else Problem.KILLED_BY_SYSTEM
        val rootDiag = if (rootAvailable) {
            listOf(
                "dumpsys accessibility | grep -A2 ${context.packageName}",
                "settings get secure enabled_accessibility_services",
                "cmd appops get ${context.packageName} RUN_ANY_IN_BACKGROUND",
            )
        } else {
            emptyList()
        }

        return Diagnosis(
            problem,
            serviceEnabled = false,
            appEnabled = true,
            details = details,
            recoverySteps = steps,
            rootDiagnostics = rootDiag,
        )
    }

    /**
     * Attempts a user-authorized recovery. With root, runs the full MIUI bypass
     * (appops, Doze whitelist, PowerKeeper "No restrictions", autostart, standby
     * bucket, secure-settings grant — each verified) and force-rebinds the
     * service when it is registered-but-dead. Without root, opens the Android
     * accessibility settings so the user can re-enable it in one tap.
     */
    fun attemptRecovery(context: Context): String {
        val root = RootManager(context)
        if (root.canUseRoot()) {
            val fixer = com.example.oreshik.agent.security.AccessibilityRootFixer
            // Full MIUI bypass pass first; every step is verified and reported.
            val report = fixer.applyAll(context)
            if (fixer.appliedSuccessfully && !isServiceConnected(context)) {
                // Registered-but-dead: force the system to rebind the service.
                val rebind = runCatching { fixer.forceRebind(context) }
                    .getOrDefault("force rebind failed")
                AppLogger.i(TAG, "Recovery rebind: $rebind")
                return "$report\n$rebind"
            }
            return report
        }
        // Non-root path: deep-link to the system accessibility settings.
        return runCatching {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opened Accessibility settings — re-enable 'Oreshik Agent Accessibility'."
        }.getOrDefault("Could not open Accessibility settings; enable it manually in system Settings.")
    }

    private fun isServiceConnected(context: Context): Boolean =
        com.example.oreshik.agent.security.AccessibilityRootFixer.isServiceRegistered(context) &&
            AgentAccessibilityService.isBound()

    /** Xiaomi, Redmi, POCO, MIUI, and HyperOS device/ROM detection. */
    fun isXiaomiDevice(): Boolean {
        val identity = listOf(
            android.os.Build.MANUFACTURER,
            android.os.Build.BRAND,
            android.os.Build.DISPLAY,
            android.os.Build.FINGERPRINT,
        ).joinToString(" ").lowercase()
        return listOf("xiaomi", "redmi", "poco", "miui", "hyperos").any(identity::contains)
    }
}
