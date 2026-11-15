package com.example.oreshik

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.accessibility.AgentAccessibilityService
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.agent.security.AndroidPermissionManager
import com.example.oreshik.data.AgentDatabase
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.ParsePosition

class SettingsActivity : AppCompatActivity() {

    /**
     * SAF "create file" launcher for the config backup. The picker is opened with a
     * suggested file name; on confirmation the JSON is written on a worker thread.
     */
    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                writeBackupTo(uri)
            }
        }

    /** SAF "open file" launcher for restoring a previously saved config backup. */
    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestoreFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); AgentConfig.init(this); setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        // Connection (endpoint/API key) management now lives in the Providers
        // screen (nav drawer); Settings only shows a read-only status line.
        val active = findViewById<TextView>(R.id.tvActiveModel)
        val activeProviderName = if (AgentConfig.isOfflineMode) "Offline GGUF" else
            runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider().displayName }.getOrDefault("NVIDIA NIM")
        active.text = buildString {
            append("Active provider: ").append(activeProviderName).append('\n')
            append(when {
                AgentConfig.isOfflineMode -> "Local model: ${AgentConfig.offlineModelName.removeSuffix(".gguf").ifBlank { "No GGUF selected" }}\nRuns on this device; web tools and screenshot analysis require an online provider."
                AgentConfig.model.isBlank() -> "No model selected. Choose one from Models & thinking."
                else -> "Active model: ${AgentConfig.model}\nThinking: ${AgentConfig.reasoningEffort}"
            })
        }
        val crossSessionSwitch = findViewById<SwitchMaterial>(R.id.swCrossSessionContext)
        bindSwitch(R.id.swMemory, AgentConfig.memoryEnabled) {
            AgentConfig.memoryEnabled = it
            crossSessionSwitch.isEnabled = it
        }
        bindSwitch(R.id.swCrossSessionContext, AgentConfig.crossSessionContextEnabled) {
            AgentConfig.crossSessionContextEnabled = it
        }
        crossSessionSwitch.isEnabled = AgentConfig.memoryEnabled
        bindSwitch(R.id.swAutoLearn, AgentConfig.memoryAutoLearn) { AgentConfig.memoryAutoLearn=it }
        bindSwitch(R.id.swHistory, AgentConfig.saveHistory) { AgentConfig.saveHistory=it }
        bindSwitch(R.id.swCompression, AgentConfig.contextCompression) { AgentConfig.contextCompression=it }
        val compressionThresholdInput = findViewById<EditText>(R.id.etCompressionThreshold)
        compressionThresholdInput.setText(formatLocalizedInteger(AgentConfig.compressionThresholdChars))
        compressionThresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val parsed = parseLocalizedInteger(compressionThresholdInput.text.toString())
                AgentConfig.compressionThresholdChars = parsed ?: 70000
                if (parsed == null) {
                    compressionThresholdInput.setText(formatLocalizedInteger(AgentConfig.compressionThresholdChars))
                }
            }
        }
        bindSwitch(R.id.swRead, AgentConfig.autoApproveReads) { AgentConfig.autoApproveReads=it }
        bindSwitch(R.id.swWrite, AgentConfig.askBeforeWrite) { AgentConfig.askBeforeWrite=it }
        bindSwitch(R.id.swProcess, AgentConfig.allowProcess) { AgentConfig.allowProcess=it }
        bindSwitch(R.id.swRoot, AgentConfig.allowRoot) { AgentConfig.allowRoot=it; updateRoot() }
        bindSwitch(R.id.swNetwork, AgentConfig.allowNetwork) { AgentConfig.allowNetwork=it }
        bindSwitch(R.id.swAccessibility, AgentConfig.accessibilityEnabled) { AgentConfig.accessibilityEnabled=it; updateAccessibility() }
        // Toggling this switch now applies immediately: OFF stops the wake-word
        // speech/mic foreground service (mic + notification released at once),
        // ON starts it right away instead of waiting for the next app launch.
        bindSwitch(R.id.swWakeWord, AgentConfig.wakeWordEnabled) { AgentConfig.wakeWordEnabled=it; syncWakeWordService(); updateWakeWord() }
        bindSwitch(R.id.swWakeWordBoost, AgentConfig.wakeWordVolumeBoost) { AgentConfig.wakeWordVolumeBoost=it }
        bindSwitch(R.id.swSpeechClarification, AgentConfig.speechClarificationEnabled) { AgentConfig.speechClarificationEnabled=it }
        bindSwitch(R.id.swAutoVpn, AgentConfig.autoVpnEnabled) { AgentConfig.autoVpnEnabled = it; updateAutoVpnStatus() }
        bindSwitch(R.id.swVision, AgentConfig.visionAutoDescribe) { AgentConfig.visionAutoDescribe = it; updateVisionStatus() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestProxy).setOnClickListener {
            // Force a diagnosis + proxy search pass on a worker thread; the result
            // (including honest failures) is shown in the status line.
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestProxy).isEnabled = false
            Thread {
                val report = runCatching { com.example.oreshik.agent.net.ProxyAutoTuner.ensureTunnel(force = true) }
                    .getOrElse { "Proxy test failed: ${it.message}" }
                runOnUiThread {
                    findViewById<TextView>(R.id.tvAutoVpnStatus).text = report
                    findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestProxy).isEnabled = true
                }
            }.start()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAndroidPermissions).setOnClickListener {
            AndroidPermissionManager.requestRuntime(this)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAppPermissions).setOnClickListener {
            AndroidPermissionManager.openAppDetails(this)
        }
        // Maximum agent steps: adjustable 1..5000 with a live value readout.
        // Persisted on every change; the engine reads the value per run.
        val maxStepsSlider = findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaxSteps)
        val maxStepsValue = findViewById<TextView>(R.id.tvMaxStepsValue)
        fun renderMaxStepsValue(steps: Int) {
            maxStepsValue.text = resources.getQuantityString(R.plurals.max_agent_steps_value_fmt, steps, steps)
        }
        maxStepsSlider.value = AgentConfig.maxAgentSteps.toFloat()
        renderMaxStepsValue(AgentConfig.maxAgentSteps)
        maxStepsSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val steps = value.toInt()
                AgentConfig.maxAgentSteps = steps
                renderMaxStepsValue(steps)
            }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetMaxSteps).setOnClickListener {
            AgentConfig.maxAgentSteps = 24
            maxStepsSlider.value = 24f
            renderMaxStepsValue(24)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMiuiFix).setOnClickListener {
            // Runs the full verified MIUI bypass (appops, Doze, PowerKeeper,
            // autostart, standby bucket, secure-settings grant) on a worker thread.
            // Recovery/rebind is included when the service is registered-but-dead.
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMiuiFix).isEnabled = false
            Thread {
                val msg = runCatching {
                    com.example.oreshik.agent.accessibility.AccessibilityWatchdog.attemptRecovery(this)
                }.getOrElse { "MIUI fix failed: ${it.message}" }
                runOnUiThread {
                    findViewById<TextView>(R.id.tvMiuiFixStatus).text = msg
                    findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMiuiFix).isEnabled = true
                    updateAccessibility()
                }
            }.start()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAccessibility).setOnClickListener {
            // Recovery first: with root this re-grants the binding directly; otherwise
            // it deep-links to the accessibility settings (same destination either way).
            val msg = com.example.oreshik.agent.accessibility.AccessibilityWatchdog.attemptRecovery(this)
            Toast.makeText(this, msg.take(120), Toast.LENGTH_LONG).show()
            updateAccessibility()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnScreenCapture).setOnClickListener {
            // 1) Apply every grant the capture paths need (root, opt-in per step).
            // 2) Run a LIVE capture test and report which strategy worked.
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnScreenCapture).isEnabled = false
            Thread {
                val setup = runCatching { enableScreenCaptureGrants() }.getOrElse { "grant step failed: ${it.message}" }
                val result = runCatching {
                    com.example.oreshik.agent.capture.ScreenCaptureManager.capture(this)
                }.getOrElse {
                    com.example.oreshik.agent.capture.ScreenCaptureManager.CaptureResult(null, null, "capture threw: ${it.message}")
                }
                // Read width/height BEFORE recycling: calling getWidth() on a
                // recycle()'d bitmap is undefined behavior (logcat warning) and
                // can crash on some devices.
                val dims = result.bitmap?.let { "${it.width}x${it.height}" }
                result.bitmap?.recycle()
                val summary = buildString {
                    append(setup)
                    append("\n")
                    val passed = dims?.let { size ->
                        "Capture TEST: PASS via ${result.strategy?.label} ($size)"
                    }
                    append(passed ?: "Capture TEST: FAIL — ${result.error}")
                }
                runOnUiThread {
                    findViewById<TextView>(R.id.tvScreenCaptureStatus).text = summary
                    findViewById<com.google.android.material.button.MaterialButton>(R.id.btnScreenCapture).isEnabled = true
                }
            }.start()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBatteryOptimization).setOnClickListener {
            requestBatteryExemption()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelinux).setOnClickListener {
            // setSelinuxPermissive shells out via su — run it on a worker thread;
            // only the Toast post needs the main thread.
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelinux).isEnabled = false
            Thread {
                val root = RootManager(this)
                val currentMode = root.getSelinuxMode()
                val targetPermissive = currentMode == "Enforcing"
                val (_, msg) = root.setSelinuxPermissive(targetPermissive)
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    updateSelinux()
                    findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelinux).isEnabled = true
                }
            }.start()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTheme).setOnClickListener {
            val modes = listOf(
                getString(R.string.theme_mode_system) to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                getString(R.string.theme_mode_light) to AppCompatDelegate.MODE_NIGHT_NO,
                getString(R.string.theme_mode_dark) to AppCompatDelegate.MODE_NIGHT_YES,
            )
            val current = ThemePrefs.get(this)
            val labels = modes.map { (label, mode) -> if (mode == current) "● $label" else label }.toTypedArray()
            // Use the AppCompat-hosted dialog: MaterialComponents-hosted dialogs
            // are not reliably recreated when setDefaultNightMode() applies, so
            // the picker could stay in the previous mode. The AppCompat dialog
            // re-themes the whole screen (including this dialog) immediately.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.change_theme))
                .setItems(labels) { _, which ->
                    val (label, mode) = modes[which]
                    ThemePrefs.set(this, mode)
                    AppCompatDelegate.setDefaultNightMode(mode)
                    updateThemeStatus()
                    Toast.makeText(this, "Theme: $label", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBackupConfig).setOnClickListener {
            backupLauncher.launch(ConfigBackup.suggestedFileName())
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreConfig).setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        // Live inventory line so the user sees WHAT a backup will contain before
        // tapping the button (row counts per table + active key/model presence).
        Thread {
            val line = runCatching {
                ConfigBackup.buildSummaryLine(AgentDatabase.get(applicationContext))
            }.getOrDefault("")
            if (line.isNotBlank()) {
                runOnUiThread { findViewById<TextView>(R.id.tvBackupStatus).text = line }
            }
        }.start()

        updateAccessibility()
        updateRootAsync()
        updateSelinuxAsync()
        updateAndroidAccess()
        updateWakeWord()
        updateThemeStatus()
    }

    private fun formatLocalizedInteger(value: Int): String = NumberFormat.getIntegerInstance().apply {
        isGroupingUsed = false
    }.format(value)

    private fun parseLocalizedInteger(value: String): Int? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val position = ParsePosition(0)
        val parsed = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.parse(text, position)
            ?: return null
        if (position.index != text.length) return null
        val longValue = parsed.toLong()
        return longValue.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    /** Writes the full config backup JSON to the chosen SAF uri (worker thread). */
    private fun writeBackupTo(uri: Uri) {
        val status = findViewById<TextView>(R.id.tvBackupStatus)
        status.text = getString(R.string.backup_preparing_status)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ConfigBackup.writeBackup(this@SettingsActivity, uri) }
            result.onSuccess { msg ->
                status.text = msg
                Toast.makeText(this@SettingsActivity, "Backup saved", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                status.text = getString(R.string.backup_write_failed_fmt, e.message ?: "unknown error")
                Toast.makeText(this@SettingsActivity, "Backup failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Asks for explicit confirmation — restore REPLACES the current config/data. */
    private fun confirmAndRestoreFrom(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_confirm_overwrite_title)
            .setMessage(R.string.backup_confirm_overwrite)
            .setPositiveButton(R.string.restore_button) { _, _ -> restoreFrom(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Restores the full config from the chosen backup file (worker thread). */
    private fun restoreFrom(uri: Uri) {
        val status = findViewById<TextView>(R.id.tvBackupStatus)
        status.text = getString(R.string.backup_restoring_status)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ConfigBackup.restore(this@SettingsActivity, uri) }
            result.onSuccess { msg ->
                status.text = msg
                Toast.makeText(this@SettingsActivity, "Restore complete", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                status.text = getString(R.string.backup_restore_failed_fmt, e.message ?: "unknown error")
                Toast.makeText(this@SettingsActivity, "Restore failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun bindSwitch(id:Int, checked:Boolean, action:(Boolean)->Unit){ findViewById<SwitchMaterial>(id).apply { isChecked=checked; setOnCheckedChangeListener{_,v->action(v)} } }
    private fun updateAccessibility(){
        val manager = getSystemService(AccessibilityManager::class.java)
        val serviceEnabled = manager?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                (info.resolveInfo.serviceInfo.packageName == packageName) &&
                    (info.resolveInfo.serviceInfo.name == AgentAccessibilityService::class.java.name)
            } == true
        val appEnabled = AgentConfig.accessibilityEnabled
        findViewById<TextView>(R.id.tvAccessibilityStatus).text = when {
            !appEnabled -> "Accessibility tools: DISABLED in app settings"
            serviceEnabled -> "Accessibility service: ENABLED & Connected"
            else -> {
                // Extend the plain status with the watchdog diagnosis so the user
                // learns WHY the service isn't running (e.g. killed by MIUI).
                val d = com.example.oreshik.agent.accessibility.AccessibilityWatchdog.diagnose(this)
                when (d.problem) {
                    com.example.oreshik.agent.accessibility.AccessibilityWatchdog.Problem.KILLED_BY_SYSTEM ->
                        "Accessibility service: registered but KILLED by the system (" +
                            d.details.substringAfter("likely ").substringBefore(" — ") + "). Tap 'Open Accessibility settings' to re-enable."
                    else -> "Accessibility service: disabled in Android settings (Tap 'Open Accessibility settings' above to enable)"
                }
            }
        }
    }
    @Suppress("SameParameterValue")
    private fun updateRoot(){ val s=RootManager(this).status(force = true); findViewById<TextView>(R.id.tvRootStatus).text=when { !AgentConfig.allowRoot -> getString(R.string.root_access_disabled); s.available -> getString(R.string.root_access_enabled_uid, s.uid); else -> getString(R.string.root_access_unavailable) } }

    /**
     * Async variant for onCreate/onResume: [RootManager.status] spawns an `su -c id`
     * subprocess (observed taking ~25s on this device while su prompts resolved),
     * so it must never run on the main thread. Renders a provisional line first.
     */
    private fun updateRootAsync() {
        val statusView = findViewById<TextView>(R.id.tvRootStatus)
        if (!AgentConfig.allowRoot) {
            statusView.text = getString(R.string.root_access_disabled)
            return
        }
        statusView.text = getString(R.string.root_access_checking)
        Thread {
            val s = runCatching { RootManager(this).status(force = true) }.getOrElse { RootManager.RootStatus(available = false) }
            runOnUiThread {
                statusView.text = when {
                    s.available -> getString(R.string.root_access_enabled_uid, s.uid)
                    else -> getString(R.string.root_access_unavailable)
                }
            }
        }.start()
    }

    private fun updateWakeWord() {
        val micGranted = AndroidPermissionManager.status(this).microphone
        val snap = WakeWordState.snapshot()
        val serviceState = when {
            snap.serviceRunning && snap.recognizerActive -> "running • recognition session active"
            snap.serviceRunning -> "service running • recognition idle between sessions"
            else -> "stopped"
        }
        findViewById<TextView>(R.id.tvWakeWordStatus).text = when {
            !AgentConfig.wakeWordEnabled -> getString(R.string.wake_word_status_disabled)
            !micGranted -> getString(R.string.wake_word_status_mic_missing)
            snap.serviceRunning && snap.recognizerActive -> getString(R.string.wake_word_status_listening)
            snap.serviceRunning -> "Wake word assistant: service running, recognition idle between sessions"
            else -> "Wake word assistant: enabled, service not running — open the wake-word diagnostics screen for details"
        }
        findViewById<TextView>(R.id.tvWakeWordStatus).append("\nService state: $serviceState")
    }

    /**
     * Applies the wake-word setting to the always-on speech/mic service now:
     * ON starts the foreground listener (mic capture begins immediately), OFF
     * sends ACTION_STOP so the service tears down its recognizer, releases the
     * microphone and removes the persistent notification. Mirrors
     * [MainActivity.syncWakeWordService] so the toggle takes effect without
     * reopening the chat screen.
     */
    private fun syncWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        if (AgentConfig.wakeWordEnabled && AndroidPermissionManager.status(this).microphone) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(this, intent) }
        } else {
            // Stopping also covers the "enabled but mic not granted" case: the
            // service must never stay up when the switch is off or unusable.
            runCatching { startService(intent.setAction(WakeWordService.ACTION_STOP)) }
        }
    }

    private fun updateThemeStatus() {
        val status = when (ThemePrefs.get(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_status_light)
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_status_dark)
            else -> getString(R.string.theme_status_follow_system)
        }
        findViewById<TextView>(R.id.tvThemeStatus).text = status
    }

    private fun updateSelinux() {
        val mode = RootManager(this).getSelinuxMode()
        findViewById<TextView>(R.id.tvSelinuxStatus).text = getString(R.string.selinux_mode_fmt, mode)
    }

    /**
     * Async variant for onCreate/onResume: [RootManager.getSelinuxMode] shells out
     * to `su -c cat`/`getenforce` (subprocess, not binder) and must not block the
     * main thread. Renders a provisional line first.
     */
    private fun updateSelinuxAsync() {
        val statusView = findViewById<TextView>(R.id.tvSelinuxStatus)
        statusView.text = getString(R.string.selinux_mode_fmt, "…")
        Thread {
            val mode = runCatching { RootManager(this).getSelinuxMode() }.getOrDefault("Unknown")
            runOnUiThread {
                statusView.text = getString(R.string.selinux_mode_fmt, mode)
            }
        }.start()
    }

    // Intentional battery-optimization request (wake-word + background agent);
    // the Play-policy lint is acknowledged (sideloaded app).
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:$packageName".toUri() }) }
    }

    private fun updateAndroidAccess() {
        val s = AndroidPermissionManager.status(this)
        findViewById<TextView>(R.id.tvSandboxStatus).text =
            "Private app workspace: ${AgentConfig.workspaceRoot(this).absolutePath}"
        findViewById<TextView>(R.id.tvAndroidPermissionStatus).text = getString(
            R.string.android_permissions_status_fmt,
            if (s.camera) "GRANTED" else "DENIED",
            if (s.microphone) "GRANTED" else "DENIED",
            if (s.notifications) "GRANTED" else "DENIED",
            if (s.batteryUnrestricted) "YES" else "NO",
        )
    }

    override fun onResume(){ super.onResume(); updateAccessibility(); updateMiuiFixStatus(); updateRootAsync(); updateSelinuxAsync(); updateAndroidAccess(); updateWakeWord(); updateThemeStatus(); updateAutoVpnStatus(); updateVisionStatus(); updateScreenCaptureStatus() }

    /** Live MIUI bypass status: last verified fixer report, or root-off guidance. */
    private fun updateMiuiFixStatus() {
        val fixer = com.example.oreshik.agent.security.AccessibilityRootFixer
        val status = when {
            !AgentConfig.allowRoot -> "MIUI bypass: root disabled in Agent settings — enable 'Allow root (su) operations', then tap 'Fix MIUI background restrictions'."
            fixer.lastReport.isBlank() -> "MIUI bypass: not applied yet — tap 'Fix MIUI background restrictions'."
            else -> (if (fixer.appliedSuccessfully) "MIUI bypass: APPLIED\n" else "MIUI bypass: PARTIAL\n") + fixer.lastReport
        }
        findViewById<TextView>(R.id.tvMiuiFixStatus).text = status
    }

    /**
     * Applies every grant the screen-capture paths need. Each step is root-gated
     * and best-effort; returns a human-readable per-step report.
     */
    private fun enableScreenCaptureGrants(): String {
        val root = RootManager(this)
        val steps = mutableListOf<String>()
        if (root.canUseRoot()) {
            // Overlay (needed by some capture stacks) + background run.
            steps += root.execute("appops set $packageName SYSTEM_ALERT_WINDOW allow", 10)
                .let { (c, _) -> if (c == 0) "✓ appop SYSTEM_ALERT_WINDOW=allow" else "✗ appop SYSTEM_ALERT_WINDOW" }
            steps += root.execute("appops set $packageName RUN_ANY_IN_BACKGROUND allow", 10)
                .let { (c, _) -> if (c == 0) "✓ appop RUN_ANY_IN_BACKGROUND=allow" else "✗ appop RUN_ANY_IN_BACKGROUND" }
            // MIUI wipes the a11y binding on reinstalls — re-assert it.
            val svc = "$packageName/${AgentAccessibilityService::class.java.name}"
            steps += root.execute("settings put secure enabled_accessibility_services \"$svc\"", 10)
                .let { (c, _) -> if (c == 0) "✓ accessibility service re-granted" else "✗ accessibility re-grant" }
            steps += root.execute("settings put secure accessibility_enabled 1", 10)
                .let { (c, _) -> if (c == 0) "✓ accessibility_enabled=1" else "✗ accessibility_enabled" }
        } else {
            steps += "root off — skipping grants (accessibility API may still work on non-MIUI devices)"
        }
        return steps.joinToString("\n")
    }

    /** Screen-capture status line: last test result + live capability summary. */
    private fun updateScreenCaptureStatus() {
        findViewById<TextView>(R.id.tvScreenCaptureStatus).text =
            com.example.oreshik.agent.capture.ScreenCaptureManager.statusLine(this)
    }

    /** Auto-VPN status: route summary + last tuner report. */
    private fun updateAutoVpnStatus() {
        val rot = com.example.oreshik.agent.net.ProxyRotator
        val tuner = com.example.oreshik.agent.net.ProxyAutoTuner
        val route = if (rot.current == null) "Direct connection" else "Via proxy: " + rot.routeSummary()
        val status = buildString {
            append("Route: ").append(route)
            if (!AgentConfig.autoVpnEnabled) append("\nAuto-VPN is OFF — enable the switch to fail over automatically on slow networks.")
            if (tuner.lastReport.isNotBlank() && tuner.lastReport != "Auto-VPN idle") append("\n").append(tuner.lastReport)
        }
        findViewById<TextView>(R.id.tvAutoVpnStatus).text = status
    }

    /** Vision computer-use status: enabled state + active/selected model. */
    private fun updateVisionStatus() {
        val activeName = runCatching { com.example.oreshik.ai.ProviderManager.getActiveProvider().displayName }.getOrDefault("the active provider")
        val status = when {
            !AgentConfig.visionAutoDescribe && AgentConfig.visionModel.isBlank() ->
                "Vision analysis: OFF — enable the switch or set a model."
            AgentConfig.visionModel.isNotBlank() -> "Vision model: ${AgentConfig.visionModel} via $activeName (fixed in settings)"
            else -> "Vision model: auto (picked from the $activeName catalog on first use; other providers tried as fallback)"
        }
        findViewById<TextView>(R.id.tvVisionStatus).text = status
    }
}
