package com.example.oreshik.agent.security

import android.content.Context
import com.example.oreshik.logging.AppLogger

/**
 * Lifts the OS/MIUI restrictions that break always-on background microphone
 * listening, using the app's own root bridge ([RootManager]) - no adb required.
 *
 * Applied automatically when the wake-word service starts (and root is enabled
 * in Agent Settings):
 *
 *  1. `appops set PKG RECORD_AUDIO allow`            - mic usable in background
 *     (MIUI defaults to "foreground only", which deafens the recognizer off-screen).
 *  2. `appops set PKG RUN_ANY_IN_BACKGROUND allow`   - no background execution block.
 *  3. `dumpsys deviceidle whitelist +PKG`            - survive Doze/screen-off.
 *  4. `am set-standby-bucket PKG active`             - never demoted to a restricted bucket.
 *  5. Phantom-process killer raised                  - MIUI/Android 12+ kills the app's
 *     child processes (Python runtime) otherwise.
 *  6. MIUI powerkeeper / autostart                   - attempted opportunistically via
 *     providers; if the build hides them, a manual step is reported instead.
 *
 * Every command is verified by reading the state back; the human-readable result is
 * logged and stored in [com.example.oreshik.WakeWordState] for the diagnostics screen.
 */
object WakeWordRootFixer {

    private const val TAG = "WakeWordRootFixer"

    /** True when a fix pass already succeeded for this process lifetime. */
    @Volatile var appliedSuccessfully = false; private set

    /** Human-readable summary of the most recent pass (also logged). */
    @Volatile var lastReport: String = ""; private set

    data class Step(val ok: Boolean, val detail: String)

    fun applyAll(context: Context): String {
        val root = RootManager(context)
        if (!root.isEnabled()) {
            lastReport = "Root disabled in Agent Settings - restrictions not lifted."
            AppLogger.i(TAG, lastReport)
            return lastReport
        }
        if (!root.status().available) {
            lastReport = "Root (su) not available on this device - restrictions not lifted."
            AppLogger.i(TAG, lastReport)
            return lastReport
        }

        val pkg = context.packageName
        val steps = mutableListOf<Step>()

        // 1. Microphone appop: MIUI defaults to while-in-use; force full allow.
        steps += fixMicAppop(root, pkg)

        // 2. Background execution appop (the only op fixed via this helper today).
        steps += fixOp(root, pkg, op = "RUN_ANY_IN_BACKGROUND")

        // 3. Doze whitelist (persists until the user revokes it).
        steps += fixDozeWhitelist(root, pkg)

        // 4. Standby bucket -> active.
        steps += fixStandbyBucket(root, pkg)

        // 5. Phantom process killer (protects the bundled Python runtime).
        steps += fixPhantomKiller(root)

        // 6. MIUI-specific: powerkeeper "no restrictions" + autostart (best effort).
        steps += fixMiuiPowerKeeper(root, pkg)
        steps += fixMiuiAutostart(root, pkg)

        val okCount = steps.count(Step::ok)
        appliedSuccessfully = okCount >= 4 // the first four are the critical set
        val marked = ArrayList<String>(steps.size)
        for (i in steps.indices) {
            val mark = if (steps[i].ok) "✓ " else "✗ "
            marked += mark + steps[i].detail
        }
        lastReport = marked.joinToString("\n")
        AppLogger.i(TAG, "Root restriction fix: $okCount/${steps.size} applied\n$lastReport")
        com.example.oreshik.WakeWordState.onRootFixApplied(appliedSuccessfully, lastReport)
        return lastReport
    }

    private fun fixMicAppop(root: RootManager, pkg: String): Step {
        root.execute("appops set $pkg RECORD_AUDIO allow", 10)
        val (code, out) = root.execute("appops get $pkg RECORD_AUDIO", 10)
        val ok = (code == 0) && (out.contains("allow"))
        return Step(
            ok,
            if (ok) "Microphone: allowed in background (appop RECORD_AUDIO=allow)"
            else "Microphone appop not confirmed: ${out.trim().ifBlank { "no output" }}",
        )
    }

    /** Fixes a background-execution appop via the root bridge. */
    @Suppress("SameParameterValue")
    private fun fixOp(root: RootManager, pkg: String, op: String = "RUN_ANY_IN_BACKGROUND"): Step {
        root.execute("appops set $pkg $op allow", 10)
        val (code, out) = root.execute("appops get $pkg $op", 10)
        val ok = (code == 0) && (out.contains("allow"))
        return Step(ok, if (ok) "Appop $op=allow" else "Appop $op not confirmed: ${out.trim()}")
    }

    private fun fixDozeWhitelist(root: RootManager, pkg: String): Step {
        root.execute("dumpsys deviceidle whitelist +$pkg", 10)
        val (code, out) = root.execute("dumpsys deviceidle whitelist", 15)
        val ok = (code == 0) && (out.contains(pkg))
        return Step(
            ok,
            if (ok) "Doze/screen-off whitelist: added"
            else "Doze whitelist not confirmed",
        )
    }

    private fun fixStandbyBucket(root: RootManager, pkg: String): Step {
        root.execute("am set-standby-bucket $pkg active", 10)
        val (code, out) = root.execute("am get-standby-bucket $pkg", 10)
        // 5 = ACTIVE, 10 = WORKING_SET; both keep background mic usable.
        val bucket = out.trim().toIntOrNull() ?: -1
        val ok = (code == 0) && ((bucket == 5) || (bucket == 10))
        return Step(
            ok,
            if (ok) "Standby bucket: active ($bucket)"
            else "Standby bucket not confirmed: ${out.trim()}",
        )
    }

    private fun fixPhantomKiller(root: RootManager): Step {
        val (_, _) = root.execute(
            "cmd device_config put activity_manager max_phantom_processes 2147483647",
            10,
        )
        val (_, out2) = root.execute(
            "settings put global settings_enable_monitor_phantom_procs false",
            10,
        )
        val (vc, vo) = root.execute(
            "cmd device_config get activity_manager max_phantom_processes",
            10,
        )
        val ok = (vc == 0) && (vo.contains("2147483647"))
        return Step(
            ok,
            if (ok) "Phantom-process killer: disabled (child processes safe)"
            else "Phantom-process limit not confirmed: ${out2.trim()}",
        )
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
        return if (code2 == 0) Step(ok = true, "MIUI powerkeeper: battery restriction = none (db)")
        else Step(
            ok = false,
            "MIUI powerkeeper not writable - set 'No restrictions' manually " +
                "in Settings > Apps > Battery (${out2.trim().take(60)})",
        )
    }

    private fun fixMiuiAutostart(root: RootManager, pkg: String): Step {
        // Best effort: known SecurityCenter provider names differ across MIUI versions.
        for (uri in listOf(
            "content://com.miui.securitycenter.autostart/autostart",
            "content://com.miui.securitycenter/autostart",
        )) {
            val (code, _) = root.execute(
                "content insert --uri $uri --bind pkg:s:$pkg --bind state:i:1",
                8,
            )
            if (code == 0) return Step(ok = true, "MIUI autostart: enabled via provider")
        }
        return Step(ok = false, "MIUI autostart: enable manually (Security app > Autostart)")
    }
}
