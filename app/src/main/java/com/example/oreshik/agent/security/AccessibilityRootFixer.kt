package com.example.oreshik.agent.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.example.oreshik.agent.accessibility.AccessibilityWatchdog
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.logging.AppLogger

/**
 * Lifts the OS/MIUI restrictions that get the Oreshik Agent accessibility service
 * killed (MIUI Power Keeper / Battery Saver / autostart management), using the
 * app's own root bridge ([RootManager]) — no adb required. This mirrors
 * [WakeWordRootFixer] but targets the accessibility/computer-use path.
 *
 * Applied steps (each verified by reading the state back where possible):
 *
 *  1. `appops set PKG RUN_ANY_IN_BACKGROUND allow`  - no background execution block
 *  2. `appops set PKG RUN_IN_BACKGROUND allow`      - classic background op
 *  3. `appops set PKG ACCESS_RESTRICTED_SETTINGS allow` - MIUI hidden-restrictions op
 *  4. `dumpsys deviceidle whitelist +PKG`           - survive Doze/screen-off
 *  5. `am set-standby-bucket PKG active`            - never demoted to a restricted bucket
 *  6. MIUI powerkeeper "No restrictions"            - ConfigureProvider, sqlite fallback
 *  7. MIUI autostart                                - SecurityCenter providers (best effort)
 *  8. MIUI `POWER_HIDE_MODE_CHANGED` broadcast      - mark the app as battery-hidden
 *  9. Secure-settings grant of the accessibility service component (MERGED with the
 *     services other apps registered — never clobbered) + `accessibility_enabled 1`.
 *
 * Root is opt-in via `AgentConfig.allowRoot`; without it the fixer is a no-op and
 * reports manual steps instead. The package name is always derived from the
 * running context (the debug build is `com.example.oreshik.debug`).
 */
object AccessibilityRootFixer {

    private const val TAG = "AccessibilityRootFixer"
    private const val MIUI_AUTOSTART_VERIFIED_KEY = "miui_autostart_op_verified"

    /** True when the last pass applied all critical steps for this process lifetime. */
    @Volatile var appliedSuccessfully = false; private set

    /** Human-readable, line-per-step summary of the most recent pass (also logged). */
    @Volatile var lastReport: String = ""; private set

    data class Step(val ok: Boolean, val detail: String)

    /** "pkg/cls" component string of this app's accessibility service. */
    fun serviceComponent(context: Context): String =
        "${context.packageName}/${AgentAccessibilityService::class.java.name}"

    /** True when the system currently lists the service among enabled accessibility services. */
    fun isServiceRegistered(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    (info.resolveInfo.serviceInfo.packageName == context.packageName) &&
                        (info.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name)
                }
        }.getOrDefault(defaultValue = false)
    }

    fun applyAll(context: Context): String {
        val root = RootManager(context)
        if (!root.isEnabled()) {
            lastReport = "Root disabled in Agent Settings - MIUI restrictions not lifted."
            context.getSharedPreferences("device_access_audit", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("miui_background_fix_applied", false)
                .putBoolean(MIUI_AUTOSTART_VERIFIED_KEY, false)
                .apply()
            AppLogger.i(TAG, lastReport)
            return lastReport
        }
        if (!root.status().available) {
            lastReport = "Root (su) not available on this device - MIUI restrictions not lifted."
            context.getSharedPreferences("device_access_audit", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("miui_background_fix_applied", false)
                .putBoolean(MIUI_AUTOSTART_VERIFIED_KEY, false)
                .apply()
            AppLogger.i(TAG, lastReport)
            return lastReport
        }

        val pkg = context.packageName
        val steps = mutableListOf<Step>()

        // 1-3. AppOps: background execution + MIUI hidden restrictions.
        steps += fixOp(root, pkg, "RUN_ANY_IN_BACKGROUND", critical = true)
        steps += fixOp(root, pkg, "RUN_IN_BACKGROUND", critical = true)
        steps += fixOp(root, pkg, "ACCESS_RESTRICTED_SETTINGS", critical = false)

        // 4. Doze whitelist (persists until revoked).
        steps += fixDozeWhitelist(root, pkg)

        // 5. Standby bucket -> active.
        steps += fixStandbyBucket(root, pkg)

        // 6-8. MIUI specifics are only run on Xiaomi-family ROMs.
        val isXiaomi = AccessibilityWatchdog.isXiaomiDevice()
        if (isXiaomi) {
            steps += fixMiuiPowerKeeper(root, pkg)
            steps += fixMiuiAutostart(root, pkg)
            steps += fixMiuiPowerHideBroadcast(root, pkg)
        }

        // 9. Grant the accessibility service binding via secure settings.
        steps += grantAccessibilityService(root, context)

        val criticalCount = steps.count(Step::ok)
        val autostartStep = steps.firstOrNull { it.detail.startsWith("MIUI autostart") }
        val autostartVerified = !isXiaomi || autostartStep?.ok == true
        appliedSuccessfully = (criticalCount >= 6) && autostartVerified
        context.getSharedPreferences("device_access_audit", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("miui_background_fix_applied", appliedSuccessfully)
            .putBoolean(MIUI_AUTOSTART_VERIFIED_KEY, autostartVerified)
            .apply()
        val marked = ArrayList<String>(steps.size)
        for (i in steps.indices) {
            val mark = if (steps[i].ok) "✓ " else "✗ "
            marked += mark + steps[i].detail
        }
        lastReport = marked.joinToString("\n")
        AppLogger.i(TAG, "MIUI accessibility fix: $criticalCount/${steps.size} steps applied\n$lastReport")
        return lastReport
    }

    /** Persistent audit marker, combined with live service connectivity at app launch. */
    fun hasSuccessfulBackgroundFix(context: Context): Boolean {
        val prefs = context.getSharedPreferences("device_access_audit", Context.MODE_PRIVATE)
        return prefs.getBoolean("miui_background_fix_applied", false) &&
            (!AccessibilityWatchdog.isXiaomiDevice() || prefs.getBoolean(MIUI_AUTOSTART_VERIFIED_KEY, false))
    }

    /** Enable only this app's accessibility binding using an already authorized su grant. */
    fun enableAccessibilityService(context: Context): String {
        val root = RootManager(context)
        if (!root.canUseRoot()) return "Root unavailable; enable the service in Android Accessibility settings."
        val step = grantAccessibilityService(root, context)
        return if (step.ok) step.detail else "${step.detail}; enable it in Android Accessibility settings."
    }

    /**
     * Re-binds a service that is registered in secure settings but not connected
     * (the classic "MIUI killed it" state): remove the component, let the system
     * unbind, then re-add it so AccessibilityManagerService rebinds. Must be
     * called from a background thread; polls for the rebind for up to ~4 s.
     *
     * Only call this when `AgentConfig.accessibilityEnabled` is true — it
     * deliberately re-enables the binding (the root-user intent from the MIUI
     * bypass guide), overriding a manual system-settings toggle.
     */
    fun forceRebind(context: Context): String {
        val root = RootManager(context)
        if (!root.canUseRoot()) {
            return "Root unavailable — toggle 'Oreshik Agent Accessibility' in system Accessibility settings instead."
        }
        val component = serviceComponent(context)
        val (rc, current) = root.execute("settings get secure enabled_accessibility_services", 10)
        val existing = current.trim()
        if ((rc != 0) || (existing.isBlank()) || (existing == "null")) {
            return "Service not registered — run the MIUI fix first or enable it in Accessibility settings."
        }
        val without = existing.split(':')
            .asSequence()
            .filter { part -> part.isNotBlank() && !part.equals(component, ignoreCase = true) }
            .joinToString(":")
        root.execute("settings put secure enabled_accessibility_services \"$without\"", 10)
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        val restored = if (without.isBlank()) component else "$without:$component"
        root.execute("settings put secure enabled_accessibility_services \"$restored\"", 10)
        root.execute("settings put secure accessibility_enabled 1", 10)
        // Poll briefly for the system to bind the service.
        var attempts = 0
        while (attempts < 8) {
            attempts++
            if (isServiceRegistered(context)) {
                AppLogger.i(TAG, "Accessibility service re-bound via root")
                return "Accessibility service re-bound via root."
            }
            try { Thread.sleep(500) } catch (_: InterruptedException) { attempts = 8 }
        }
        return "Re-grant applied; the service did not re-bind within 4 s — check Accessibility settings."
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private fun fixOp(root: RootManager, pkg: String, op: String, critical: Boolean): Step {
        root.execute("appops set $pkg $op allow", 10)
        val (code, out) = root.execute("appops get $pkg $op", 10)
        // Some MIUI builds reject/ignore non-standard ops — treat verify failure of
        // optional ops as non-fatal.
        val ok = (code == 0) && ((out.contains("allow")) || (!critical))
        return Step(ok, if (ok) "Appop $op=allow" else "Appop $op not confirmed: ${out.trim().take(80)}")
    }

    private fun fixDozeWhitelist(root: RootManager, pkg: String): Step {
        root.execute("dumpsys deviceidle whitelist +$pkg", 10)
        val (code, out) = root.execute("dumpsys deviceidle whitelist", 15)
        val ok = (code == 0) && (out.contains(pkg))
        return Step(ok, if (ok) "Doze/screen-off whitelist: added" else "Doze whitelist not confirmed")
    }

    private fun fixStandbyBucket(root: RootManager, pkg: String): Step {
        root.execute("am set-standby-bucket $pkg active", 10)
        val (code, out) = root.execute("am get-standby-bucket $pkg", 10)
        // 5 = ACTIVE, 10 = WORKING_SET; both keep background work alive.
        val bucket = out.trim().toIntOrNull() ?: -1
        val ok = (code == 0) && ((bucket == 5) || (bucket == 10))
        return Step(ok, if (ok) "Standby bucket: active ($bucket)" else "Standby bucket not confirmed: ${out.trim()}")
    }

    private fun fixMiuiPowerKeeper(root: RootManager, pkg: String): Step {
        // Preferred: powerkeeper's ConfigureProvider (bgControl=none == "No restrictions").
        val (code, _) = root.execute(
            "content update --uri content://com.miui.powerkeeper.ConfigureProvider/user_configure " +
                "--bind pkgName:s:$pkg --bind bgControl:s:none --where \"pkgName='$pkg'\"",
            10,
        )
        if (code == 0) return Step(ok = true, "MIUI powerkeeper: battery restriction = none")
        // Fallback: direct DB write (sqlite3 may not exist on the device).
        val (code2, out2) = root.execute(
            "sqlite3 /data/data/com.miui.powerkeeper/databases/user_configure.db " +
                "\"INSERT OR REPLACE INTO userTable (userId, pkgName, lastConfigured, bgControl) " +
                "VALUES (0, '$pkg', strftime('%s','now')*1000, 'none');\"",
            10,
        )
        return if (code2 == 0) {
            Step(ok = true, "MIUI powerkeeper: battery restriction = none (db)")
        } else {
            Step(
                ok = false,
                "MIUI powerkeeper not writable - set 'No restrictions' manually " +
                    "in Settings > Apps > Battery (${out2.trim().take(60)})",
            )
        }
    }

    private fun fixMiuiAutostart(root: RootManager, pkg: String): Step {
        // MIUI gates listener-service binding on its vendor autostart app-op.
        // A successful provider insert alone is not proof that MIUI changed it.
        root.execute("appops set $pkg 10008 allow", 8)
        var verification = verifyMiuiAutostart(root, pkg)
        if (verification.second) {
            return Step(ok = true, "MIUI autostart: AppOp 10008=allow (verified)")
        }

        // Provider names differ across MIUI releases; retain these as fallbacks,
        // but always read AppOps back before reporting success.
        for (uri in listOf(
            "content://com.miui.securitycenter.autostart/autostart",
            "content://com.miui.securitycenter/autostart",
        )) {
            val (code, _) = root.execute(
                "content insert --uri $uri --bind pkg:s:$pkg --bind state:i:1",
                8,
            )
            if (code == 0) {
                verification = verifyMiuiAutostart(root, pkg)
                if (verification.second) {
                    return Step(ok = true, "MIUI autostart: enabled via provider; AppOp 10008 verified")
                }
            }
        }
        val detail = verification.first.trim().take(100).ifBlank { "AppOp 10008 was not reported" }
        return Step(ok = false, "MIUI autostart AppOp not allowed ($detail); enable manually in Security > Autostart")
    }

    private fun verifyMiuiAutostart(root: RootManager, pkg: String): Pair<String, Boolean> {
        val (code, output) = root.execute("appops get $pkg 10008", 8)
        val allowed = (code == 0) && Regex(
            "(?:MIUIOP\\s*\\(10008\\)|10008)\\s*:\\s*allow\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(output)
        return output to allowed
    }

    private fun fixMiuiPowerHideBroadcast(root: RootManager, pkg: String): Step {
        // Marks the app as hidden from MIUI power management (opportunistic; not verifiable).
        val (code, _) = root.execute(
            "am broadcast -a miui.intent.action.POWER_HIDE_MODE_CHANGED --ei mode 1 --es package $pkg",
            10,
        )
        return Step(
            code == 0,
            if (code == 0) "MIUI power-hide broadcast: sent"
            else "MIUI power-hide broadcast: not delivered on this ROM",
        )
    }

    private fun grantAccessibilityService(root: RootManager, context: Context): Step {
        val component = serviceComponent(context)
        val (_, current) = root.execute("settings get secure enabled_accessibility_services", 10)
        val existing = current.trim()
        val alreadyListed = existing.split(':').any { it.equals(component, ignoreCase = true) }
        val merged = when {
            (existing.isBlank()) || (existing == "null") -> component
            alreadyListed -> existing
            else -> "$existing:$component"
        }
        if (!alreadyListed) {
            // MERGE, never clobber: other apps may register accessibility services too.
            root.execute("settings put secure enabled_accessibility_services \"$merged\"", 10)
        }
        root.execute("settings put secure accessibility_enabled 1", 10)
        val (_, check) = root.execute("settings get secure enabled_accessibility_services", 10)
        val ok = check.contains(context.packageName)
        return Step(
            ok,
            if (ok) "Accessibility service: granted in secure settings"
            else "Secure-settings grant not confirmed: ${check.trim().take(80)}",
        )
    }
}
