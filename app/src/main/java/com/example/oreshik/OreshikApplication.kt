package com.example.oreshik

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.oreshik.ai.ProviderManager
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OreshikApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Apply the persisted theme BEFORE super.onCreate(): AppCompat requires
        // the default night mode to be set before any Activity is created, or the
        // first screen can come up in the stale mode until it is re-created.
        AppCompatDelegate.setDefaultNightMode(ThemePrefs.get(this))
        super.onCreate()
        // Install the global crash handler FIRST so any init failure is captured.
        AppLogger.installCrashHandler()
        AppLogger.init(this, AppLogger.Level.DEBUG, true)
        AppLogger.i("OreshikApplication", "App starting (process ${android.os.Process.myPid()})")
        // Register all AI providers (NVIDIA, OpenRouter, BlueSmind, NaraRouter)
        // once at app start so every component (MainActivity, ProvidersActivity,
        // AgentExecutionService, WakeWordService) shares the same registry.
        runCatching { ProviderManager.initialize(this) }
            .onFailure { AppLogger.e("OreshikApplication", "Provider init failed: ${it.message}", it) }

        // Provision bundled toolpacks (APK assets → filesDir/runtimes) in the background.
        // Non-blocking: the UI is usable immediately; core packs (jdk/node/cli-tools)
        // are extracted first, heavy ones (jadx/ndk/ghidra) after.
        appScope.launch {
            val runtime = RuntimeManager(this@OreshikApplication)
            val ok = runtime.ensureProvisioned()
            if (ok) {
                AppLogger.i("OreshikApplication", "Toolpack provisioning complete")
            } else {
                AppLogger.w("OreshikApplication", "Toolpack provisioning finished with issues: ${runtime.lastProvisionError}")
            }
            // Seed the bundled professional skill catalog (curated core enabled).
            // Idempotent: existing skill rows are never modified.
            runCatching { com.example.oreshik.agent.skills.SkillCatalogSeeder.seedAll(this@OreshikApplication) }
                .onFailure { AppLogger.w("OreshikApplication", "Skill catalog seeding failed: ${it.message}") }
        }
    }
}
