package com.example.oreshik.agent.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.oreshik.agent.accessibility.AccessibilityWatchdog
import com.example.oreshik.agent.capture.ScreenCaptureManager
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.notification.NotificationMonitorService

/** Applies the user's explicitly approved first-run device-access setup through su. */
object FirstRunRootSetup {

    fun apply(context: Context): String {
        val app = context.applicationContext
        val root = RootManager(app)
        val status = root.status(force = true)
        if (!status.available) {
            AgentConfig.allowRoot = false
            return "Root authorization was not granted. No elevated setup was applied."
        }

        // This flow is only entered after the in-app explanation and the root
        // manager's own authorization prompt have both been accepted.
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needsGrant = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(app, permission) != PackageManager.PERMISSION_GRANTED
        }
        val permissionReport = if (needsGrant.isEmpty()) {
            "All declared runtime permissions were already granted."
        } else {
            val command = needsGrant.joinToString("; ") { permission ->
                "pm grant '${app.packageName}' '$permission'"
            }
            root.execute(command, 30)
            val granted = needsGrant.filter { permission ->
                ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
            }
            val denied = needsGrant - granted.toSet()
            buildString {
                append("Runtime permissions granted: ")
                append(granted.map(::permissionLabel).ifEmpty { listOf("none") }.joinToString(", "))
                if (denied.isNotEmpty()) {
                    append("\nNot granted: ")
                    append(denied.map(::permissionLabel).joinToString(", "))
                }
            }
        }

        val notificationAccessReport = enableNotificationAccess(root, app)
        AgentConfig.accessibilityEnabled = true
        val miuiDevice = AccessibilityWatchdog.isXiaomiDevice()
        // Applies general app-specific background allowances on Android, then
        // only attempts PowerKeeper/autostart settings on Xiaomi-family ROMs.
        val accessibilityReport = AccessibilityRootFixer.applyAll(app)

        // This app's capture implementation uses the enabled accessibility
        // screenshot API and/or `screencap` via su; it does not keep a hidden
        // MediaProjection token or bypass Android's per-session consent UI.
        val capture = runCatching { ScreenCaptureManager.capture(app) }.getOrNull()
        val captureStatus = capture?.let { result ->
            val dimensions = result.bitmap?.let { "${it.width}x${it.height}" }
            result.bitmap?.recycle()
            if (dimensions != null) "Screen capture ready via ${result.strategy?.label} ($dimensions)."
            else "Screen capture setup needs attention: ${result.error ?: "capture unavailable"}"
        } ?: "Screen capture setup needs attention: capture check failed."

        return buildString {
            append("Root authorized as ").append(status.uid).append(".\n")
            append(permissionReport).append("\n")
            append(notificationAccessReport).append("\n")
            append("Accessibility/background setup:\n").append(accessibilityReport).append("\n")
            append(captureStatus)
            if (miuiDevice) {
                append("\nXiaomi/MIUI/HyperOS provider steps are included in the setup report above.")
            }
            append("\nRoot-only agent tools are enabled; turn off Allow root in Settings to revoke app use of su.")
        }
    }

    /** Merge this service into the user's listener list; never remove other listeners. */
    private fun enableNotificationAccess(root: RootManager, context: Context): String {
        if (NotificationMonitorService.isEnabled(context)) return "Notification access was already enabled."
        val component = "${context.packageName}/${NotificationMonitorService::class.java.name}"
        val (_, currentOutput) = root.execute("settings get secure enabled_notification_listeners", 10)
        val current = currentOutput.trim().takeUnless { it.isBlank() || it == "null" }.orEmpty()
        val listeners = current.split(':').filter(String::isNotBlank)
        val merged = if (listeners.any { it.equals(component, ignoreCase = true) }) current
            else (listeners + component).joinToString(":")
        root.execute("settings put secure enabled_notification_listeners ${shellQuote(merged)}", 10)
        val (_, verification) = root.execute("settings get secure enabled_notification_listeners", 10)
        return if (verification.contains(component, ignoreCase = true) && NotificationMonitorService.isEnabled(context)) {
            "Notification access enabled."
        } else {
            "Notification access could not be enabled automatically; review it in Android Notification access settings."
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun permissionLabel(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> "camera"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.READ_CONTACTS -> "contacts"
        Manifest.permission.SEND_SMS -> "SMS"
        Manifest.permission.POST_NOTIFICATIONS -> "notifications"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "shared storage"
        else -> permission.substringAfterLast('.')
    }
}
