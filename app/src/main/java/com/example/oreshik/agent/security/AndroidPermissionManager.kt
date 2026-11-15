package com.example.oreshik.agent.security

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.oreshik.logging.AppLogger

/** Android runtime-permission status helper and standard system-dialog request path. */
object AndroidPermissionManager {
    const val REQUEST_RUNTIME = 7401

    private const val TAG = "OsPerms"

    data class Status(
        val camera: Boolean,
        val microphone: Boolean,
        val notifications: Boolean,
        val batteryUnrestricted: Boolean,
        val contacts: Boolean,
        val sms: Boolean,
        val sharedStorage: Boolean,
    ) {
        /** True when all runtime (dangerous) permissions are granted. */
        @Suppress("unused")
        val runtimeReady: Boolean get() = camera && microphone && notifications && contacts && sms && sharedStorage
    }

    fun status(context: Context): Status {
        val s = Status(
            camera = has(context, Manifest.permission.CAMERA),
            microphone = has(context, Manifest.permission.RECORD_AUDIO),
            notifications = (Build.VERSION.SDK_INT < 33) || has(context, Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = batteryUnrestricted(context),
            contacts = has(context, Manifest.permission.READ_CONTACTS),
            sms = has(context, Manifest.permission.SEND_SMS),
            sharedStorage = Build.VERSION.SDK_INT > 32 || has(context, Manifest.permission.READ_EXTERNAL_STORAGE),
        )
        AppLogger.d(TAG, "status: cam=${s.camera} mic=${s.microphone} contacts=${s.contacts} sms=${s.sms} storage=${s.sharedStorage} notif=${s.notifications} battery=${s.batteryUnrestricted}")
        return s
    }

    fun missingRuntimePermissions(context: Context): List<String> = runtimePermissions().filterNot { has(context, it) }

    fun requestRuntime(activity: Activity) {
        val permissions = missingRuntimePermissions(activity)
        if (permissions.isNotEmpty()) {
            AppLogger.i(TAG, "requestRuntime: requesting ${permissions.size} OS permission(s)")
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_RUNTIME)
        }
    }

    private fun runtimePermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun openAppDetails(context: Context) {
        AppLogger.i(TAG, "openAppDetails")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
    }

    fun batteryUnrestricted(context: Context): Boolean {
        // minSdk is 26; POWER_SERVICE save-mode checks exist on all supported versions.
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
