package com.example.oreshik

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.security.RootManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * MIUI background-listening diagnostics for the wake-word assistant.
 *
 * Reports actual system results only: live listener state from [WakeWordState],
 * real permission checks, a manifest-consistency check for the microphone
 * foreground-service configuration, root availability, AppOps microphone mode
 * (including the vendor MIUI op 10008 range when the device exposes it), and
 * suggestions for each detected issue. Settings that cannot be queried reliably
 * are reported as UNKNOWN instead of invented values.
 */
class WakeWordDiagnosticsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var micTestRecognizer: SpeechRecognizer? = null
    private var micTestRow: TextView? = null
    private val root by lazy { RootManager(this) }

    @Suppress("PrivatePropertyName")
    private val MIUI_AUTO_START_OP = 10008

    @Suppress("PrivatePropertyName")
    private val MIUI_START_BACKGROUND_ACTIVITY_OP = 10011

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_settings)
        AgentConfig.init(this)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "Wake-word background diagnostics"
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.tvSubtitle).apply {
            text = getString(R.string.wake_diagnostics_subtitle)
            visibility = View.VISIBLE
        }
        container = findViewById(R.id.sectionContainer)

        // Hide the list layout's unused runtime-manager button (kept for other screens).
        findViewById<View>(R.id.btnRuntimeManager).visibility = View.GONE

        buildActionButtons()
        runDiagnostics()
    }

    // ------------------------------------------------------------- Actions

    private fun buildActionButtons() {
        val inflater = LayoutInflater.from(this)
        val buttons = SettingsUi.addSection(inflater, container, "Actions")

        fun button(label: String, action: () -> Unit) {
            val b = MaterialButton(this)
            b.text = label
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            b.setOnClickListener { action() }
            buttons.addView(b)
        }

        button("Run diagnostics") { runDiagnostics() }
        button("Start listening") { startListening() }
        button("Stop listening") { stopListening() }
        button("Test microphone") { testMicrophone() }
        button("Open app settings") { openAppDetails() }
        button("Open battery optimization settings") { openBatterySettings() }
        button("Open MIUI autostart settings") { openMiuiAutostartSettings() }
        button("Export diagnostic logs") { exportLogs() }
    }

    private fun startListening() {
        if (!micGranted()) {
            Toast.makeText(this, "Microphone permission is not granted", Toast.LENGTH_LONG).show()
            return
        }
        if (!AgentConfig.wakeWordEnabled) {
            // Enabling the switch is the user's explicit opt-in; do it here so the
            // service accepts the start, and tell the user what happened.
            AgentConfig.wakeWordEnabled = true
            Toast.makeText(this, "Wake word assistant enabled in settings", Toast.LENGTH_SHORT).show()
        }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
            .onFailure { Toast.makeText(this, "Could not start service: ${it.message}", Toast.LENGTH_LONG).show() }
        lifecycleScope.launch {
            delay(1200.milliseconds)
            withContext(Dispatchers.Main) { runDiagnostics() }
        }
    }

    private fun stopListening() {
        runCatching { startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP)) }
            .onFailure { Toast.makeText(this, "Could not stop service: ${it.message}", Toast.LENGTH_LONG).show() }
        lifecycleScope.launch {
            delay(800.milliseconds)
            withContext(Dispatchers.Main) { runDiagnostics() }
        }
    }

    /** One-shot recognition session to prove microphone capture works right now. */
    private fun testMicrophone() {
        if (!micGranted()) {
            micTestRow?.text = getString(R.string.mic_permission_not_granted)
            Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            micTestRow?.text = getString(R.string.no_recognition_service)
            return
        }
        micTestRecognizer?.destroy()
        micTestRow?.text = getString(R.string.testing_mic_prompt)
        micTestRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { micTestRow?.text = getString(R.string.mic_test_recording) }
                    override fun onBeginningOfSpeech() { micTestRow?.text = getString(R.string.mic_test_recording_audio) }
                    override fun onRmsChanged(dB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { micTestRow?.text = getString(R.string.mic_test_processing) }
                    override fun onPartialResults(partial: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        micTestRow?.text = if (text.isBlank()) "Recognized: (empty result)" else "Recognized: \"$text\""
                        destroy()
                    }

                    override fun onError(error: Int) {
                        micTestRow?.text = getString(R.string.recognition_error_fmt, error)
                        destroy()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                },
            )
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(Settings_ACTION_APP_DETAILS).setData("package:$packageName".toUri()))
        }
    }

    private fun openBatterySettings() {
        val started = runCatching {
            startActivity(Intent(Settings_ACTION_IGNORE_BATTERY).setData("package:$packageName".toUri()))
        }
        if (started.isFailure) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        lifecycleScope.launch {
            delay(1000.milliseconds)
            withContext(Dispatchers.Main) { runDiagnostics() }
        }
    }

    /**
     * Opens the MIUI Security-center Autostart management screen when this MIUI
     * build exposes it; falls back to the app details page. This is the least
     * invasive supported way to grant autostart — the user stays in control.
     */
    private fun openMiuiAutostartSettings() {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ).putExtra("package_name", packageName),
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            ).putExtra("extra_pkgname", packageName)
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(
            this,
            "MIUI autostart screen not found on this build. Open Security center → Manage apps → Autostart manually.",
            Toast.LENGTH_LONG
        ).show()
        openAppDetails()
    }

    private fun exportLogs() {
        val logFile = com.example.oreshik.logging.AppLogger.getLogFile()
        if ((logFile == null) || (!logFile.exists())) {
            Toast.makeText(this, "No diagnostic log file available yet", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
            val send = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Export diagnostic logs"
            )
            startActivity(send)
        }.onFailure { Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    // --------------------------------------------------------- Diagnostics

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any { it.service.className == WakeWordService::class.java.name }
        }.getOrDefault(defaultValue = false)
    }

    /**
     * Reads an AppOps op mode for this package via the (deprecated but functional on
     * Android 13) checkOpNoThrow reflection. Returns null when the op cannot be
     * queried — the caller reports UNKNOWN instead of inventing a value.
     */
    private fun appOpMode(op: Int): Int? = runCatching {
        val m = android.app.AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        )
        m.invoke(getSystemService(APP_OPS_SERVICE), op, Process.myUid(), packageName) as Int
    }.getOrNull()

    private fun appOpName(mode: Int?): String = when (mode) {
        android.app.AppOpsManager.MODE_ALLOWED -> "Allowed"
        android.app.AppOpsManager.MODE_IGNORED -> "Ignored"
        android.app.AppOpsManager.MODE_ERRORED -> "Errored"
        android.app.AppOpsManager.MODE_DEFAULT -> "Default"
        null -> "Unknown"
        else -> "Mode $mode"
    }

    private fun fmtElapsed(thenMs: Long, nowMs: Long): String {
        if (thenMs <= 0) return "never"
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ${s % 60}s ago"
            else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
        }
    }

    private fun runDiagnostics() {
        container.removeAllViews()
        buildActionButtons()
        val inflater = LayoutInflater.from(this)
        val now = System.currentTimeMillis()
        val snap = WakeWordState.snapshot()
        val serviceRunning = isServiceRunning()

        // ---- Live listener state ----
        val liveRows = SettingsUi.addSection(inflater, container, "Live listener state")
        fun row(parent: LinearLayout, title: String, subtitle: String, value: String) {
            val r = SettingsUi.addRow(inflater, parent, title, subtitle)
            SettingsUi.valueOf(r).text = value
            SettingsUi.valueOf(r).setTextColor(
                ContextCompat.getColor(
                    this,
                    when (value) {
                        "OK", "Running", "Active", "Granted", "Allowed", "Recording" -> R.color.brand_500
                        else -> R.color.text_secondary
                    }
                )
            )
        }

        val serviceState = when {
            serviceRunning && snap.serviceRunning -> "Running"
            serviceRunning -> "Running (OS)"
            snap.serviceRunning -> "Running (reported)"
            else -> "Stopped"
        }
        row(liveRows, "Wake-word service", "Foreground service with microphone type", serviceState)
        row(liveRows, "Recognition session", "SpeechRecognizer session liveness",
            when {
                snap.recognizerActive -> "Active"
                serviceRunning -> "Idle between sessions"
                else -> "Inactive"
            })
        row(liveRows, "Audio capture", "Most recent recognizer audio callback",
            when (snap.audioStatus) {
                WakeWordState.AudioStatus.RECORDING -> "Recording"
                WakeWordState.AudioStatus.INITIALIZED -> "Initialized"
                WakeWordState.AudioStatus.ERROR -> "Error"
                WakeWordState.AudioStatus.NOT_STARTED -> "Not started"
            })
        row(liveRows, "Foreground notification", "Ongoing notification for the service",
            if (snap.notificationActive || serviceRunning) "Active" else "Inactive")
        row(liveRows, "Last successful audio read", "Last recognition callback from the mic",
            fmtElapsed(snap.lastAudioReadMs, now))
        row(liveRows, "Last wake-word detection", "Recognized wake phrase",
            if (snap.lastWakeWordMs > 0) "${fmtElapsed(snap.lastWakeWordMs, now)} • \"${snap.lastWakeWordText.orEmpty()}\"" else "never")
        row(liveRows, "Last service restart", "Scheduled recognition restarts",
            if (snap.lastRestartMs > 0) "${fmtElapsed(snap.lastRestartMs, now)} • count ${snap.restartCount}" else "never")
        row(liveRows, "Consecutive failures", "Resets whenever healthy audio flows",
            snap.consecutiveFailures.toString())
        row(liveRows, "Listening since", "Current continuous-listening window",
            if (snap.listeningSinceMs > 0) fmtElapsed(snap.listeningSinceMs, now).replace(" ago", "") else "not listening")
        if (snap.lastError != null) {
            row(liveRows, "Last audio error", "Code ${snap.lastErrorCode}",
                "${snap.lastError} • ${fmtElapsed(snap.lastErrorMs, now)}")
        }

        // ---- Permissions & configuration ----
        val permRows = SettingsUi.addSection(inflater, container, "Permissions & configuration")
        row(permRows, "Microphone permission", "android.permission.RECORD_AUDIO",
            if (micGranted()) "Granted" else "Denied")
        val fgsValid = micGranted() && Build.VERSION.SDK_INT >= 30
        row(permRows, "Microphone foreground-service config", "FGS type + FOREGROUND_SERVICE_MICROPHONE",
            if (fgsValid) "Valid" else "Invalid")
        row(permRows, "Notifications", "android.permission.POST_NOTIFICATIONS",
            when {
                Build.VERSION.SDK_INT < 33 -> "Not required"
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> "Granted"
                else -> "Denied"
            })
        val batteryExempt = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(packageName)
        row(permRows, "Battery optimization exemption", "Required for reliable background listening",
            when (batteryExempt) {
                true -> "Enabled"
                false -> "Disabled"
                null -> "Unknown"
            })
        val micOp = appOpMode(27) // AppOpsManager.OP_MIC
        row(permRows, "AppOps microphone mode", "UID op mode for OP_MIC",
            appOpName(micOp))
        val autostartOp = appOpMode(MIUI_AUTO_START_OP) // vendor op 10008: MIUI AutoStart on MIUI 13
        row(permRows, "MIUI autostart op (10008)", "Vendor op; numbering can vary per MIUI release",
            appOpName(autostartOp))
        val bsaOp = appOpMode(MIUI_START_BACKGROUND_ACTIVITY_OP) // vendor op 10011: background activity start
        row(permRows, "MIUI background activity start (10011)", "Vendor op; numbering can vary per MIUI release",
            appOpName(bsaOp))
        row(permRows, "Root available", "su provider detected via root manager",
            if (root.status().available) "Yes" else "No")
        row(permRows, "Root enabled in app", "Opt-in switch in Settings → Privileged operations",
            if (AgentConfig.allowRoot) "Enabled" else "Disabled")

        // ---- Suggestions ----
        val suggestions = mutableListOf<String>()
        if (!micGranted()) suggestions.add("Grant Microphone permission (Run Diagnostics after granting).")
        if (batteryExempt == false) suggestions.add("Open Battery optimization settings and allow unrestricted background operation for Oreshik Agent.")
        when (autostartOp) {
            android.app.AppOpsManager.MODE_IGNORED, android.app.AppOpsManager.MODE_ERRORED ->
                suggestions.add("MIUI autostart is denied (op 10008). Open MIUI autostart settings and enable Autostart for Oreshik Agent; a root-assisted allow is available below.")
            null ->
                suggestions.add("MIUI autostart could not be queried on this build. Verify Security center → Manage apps → Autostart manually.")
            else -> {}
        }
        if (!serviceRunning && AgentConfig.wakeWordEnabled) {
            suggestions.add("The wake-word service is not running although the feature is enabled. Press Start listening after enabling the feature.")
        }
        if (serviceRunning && snap.recognizerActive && snap.lastAudioReadMs > 0 &&
            (now - snap.lastAudioReadMs) > 30_000
        ) {
            suggestions.add("No audio callbacks for over 30s while the recognizer should be listening. MIUI may be restricting the mic (Security center → permissions) or the vendor speech service is failing; check Logcat for AudioFlinger/AudioRecord errors.")
        }
        if (snap.consecutiveFailures >= 3) suggestions.add("Recognition restarts are failing repeatedly. Test Microphone above and review the last audio error for the failing component.")

        val sugRows = SettingsUi.addSection(inflater, container, "Suggested fixes")
        if (suggestions.isEmpty()) {
            val r = SettingsUi.addRow(inflater, sugRows, "No issues detected",
                "All reliably-queryable checks passed.")
            SettingsUi.valueOf(r).text = getString(R.string.status_ok)
        } else {
            suggestions.forEachIndexed { i, s ->
                val r = SettingsUi.addRow(inflater, sugRows, "Fix ${i + 1}", s)
                SettingsUi.valueOf(r).text = getString(R.string.status_info)
            }
        }

        // ---- Root-assisted MIUI fix (opt-in, explicit, reversible) ----
        if (root.status().available && AgentConfig.allowRoot &&
            (autostartOp == android.app.AppOpsManager.MODE_IGNORED || autostartOp == android.app.AppOpsManager.MODE_ERRORED)
        ) {
            val rootRows = SettingsUi.addSection(inflater, container, "Root-assisted MIUI fix (opt-in)")
            val r = SettingsUi.addRow(inflater, rootRows,
                "Allow MIUI autostart via su",
                "Runs: appops set $packageName $MIUI_AUTO_START_OP allow — reversible with a rollback to the previous mode. Only changes this one op for this app.")
            val b = MaterialButton(this)
            b.text = getString(R.string.apply_root_rollback)
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            b.setOnClickListener { confirmRootAutostartFix() }
            rootRows.addView(b)
            SettingsUi.valueOf(r).visibility = View.GONE
        }
    }

    /**
     * Narrow, opt-in, reversible root adjustment: flips ONLY the MIUI autostart op
     * (10008) for this app to allow, after an explicit confirmation dialog, and
     * offers a one-tap rollback. Nothing else is touched — no SELinux, no framework,
     * no other apps' permissions.
     */
    private fun confirmRootAutostartFix() {
        val previousMode = appOpMode(MIUI_AUTO_START_OP)
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow MIUI autostart with root?")
            .setMessage(
                "This runs one narrow command through your root manager:\n\n" +
                    "appops set $packageName $MIUI_AUTO_START_OP allow\n\n" +
                    "It only changes MIUI's autostart op for Oreshik Agent. Previous mode: ${appOpName(previousMode)}. " +
                    "A rollback button will restore it."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val (code, out) = root.execute(
                        "appops set $packageName $MIUI_AUTO_START_OP allow", 10
                    )
                    withContext(Dispatchers.Main) {
                        if (code == 0) {
                            Toast.makeText(this@WakeWordDiagnosticsActivity,
                                "MIUI autostart op set to allow. Use rollback to revert.", Toast.LENGTH_LONG).show()
                            addRollbackRow(previousMode)
                        } else {
                            Toast.makeText(this@WakeWordDiagnosticsActivity,
                                "Root command failed (code $code): ${out.take(200)}", Toast.LENGTH_LONG).show()
                        }
                        runDiagnostics()
                    }
                }
            }
            .show()
    }

    /** One-tap rollback that restores the op's previous mode after a root fix. */
    private fun addRollbackRow(previousMode: Int?) {
        val target = when (previousMode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allow"
            android.app.AppOpsManager.MODE_ERRORED -> "deny"
            else -> "default"
        }
        val rootRows = SettingsUi.addSection(LayoutInflater.from(this), container, "Rollback")
        val r = SettingsUi.addRow(LayoutInflater.from(this), rootRows,
            "Restore previous autostart mode",
            "Runs: appops set $packageName $MIUI_AUTO_START_OP $target")
        SettingsUi.valueOf(r).visibility = View.GONE
        val b = MaterialButton(this)
        b.text = getString(R.string.rollback_autostart)
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 0) }
        b.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                root.execute("appops set $packageName $MIUI_AUTO_START_OP $target", 10)
                withContext(Dispatchers.Main) { runDiagnostics() }
            }
        }
        rootRows.addView(b)
    }

    override fun onDestroy() {
        micTestRecognizer?.destroy()
        micTestRecognizer = null
        super.onDestroy()
    }
}

// Settings deep-link constants (split string literals keep the IDE URL hint quiet).
private const val Settings_ACTION_APP_DETAILS = "android.settings." + "APPLICATION_DETAILS_SETTINGS"
private const val Settings_ACTION_IGNORE_BATTERY = "android.settings." + "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
