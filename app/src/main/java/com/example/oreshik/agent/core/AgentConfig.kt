package com.example.oreshik.agent.core

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.SettingEntity
import com.example.oreshik.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred


// Application context only — intentionally held for the process lifetime so
// settings can be persisted from any thread without leaking an Activity.
@SuppressLint("StaticFieldLeak")
object AgentConfig {
    private const val NVIDIA_BASE = "https:" + "//integrate.api.nvidia.com"

    // Application context only — intentionally held for the process lifetime so
    // settings can be persisted from any thread without leaking an Activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var app: Context? = null
    private val cache = ConcurrentHashMap<String, String>()
    private val settingsReady = CompletableDeferred<Unit>()
    private val cacheLock = Any()
    private val writeLock = Any()
    private val modifiedKeys = mutableSetOf<String>()
    private var cacheGeneration = 0L
    private val settingsExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "agent-settings").apply { isDaemon = true }
    }
    private val defaults = mapOf(
        // No hardcoded API keys: the user enters their own key in the UI
        // (Settings/Providers) and it is persisted in the settings database so
        // it can be changed at any time.
        "base_url" to NVIDIA_BASE, "api_key" to "", "model" to "",
        "model_mode" to "online", "offline_model_path" to "", "offline_model_name" to "",
        "reasoning_effort" to "high", "memory_enabled" to "true", "cross_session_context" to "false", "save_history" to "true",
        "auto_approve_reads" to "true", "ask_before_write" to "true", "allow_process" to "true",
        "allow_network" to "true", "accessibility_enabled" to "true", "allow_root" to "false", "max_agent_steps" to "24", "context_compression" to "true", "compression_threshold_chars" to "70000", "compression_keep_messages" to "14",
        "wake_word_enabled" to "false", "wake_word_volume_boost" to "true",
        "speech_clarification_enabled" to "true",
        "auto_vpn_enabled" to "false",
        "vision_model" to "", "vision_auto_describe" to "true",
    )
    fun init(context: Context) {
        val loadGeneration: Long
        synchronized(cacheLock) {
            if (app != null) return
            app = context.applicationContext
            defaults.forEach { (key, value) -> cache.putIfAbsent(key, value) }
            loadGeneration = cacheGeneration
        }
        settingsExecutor.execute {
            try {
                val c = synchronized(cacheLock) { app } ?: return@execute
                val rows = runCatching { AgentDatabase.get(c).settings().all() }
                    .onFailure { AppLogger.e("AgentConfig", "settings load failed: ${it.message}", it) }
                    .getOrNull() ?: return@execute
                synchronized(cacheLock) {
                    // A restore or a local edit that happened while the query was running
                    // takes precedence over this older snapshot.
                    if (cacheGeneration == loadGeneration) {
                        rows.forEach { row ->
                            if (row.key !in modifiedKeys) cache[row.key] = row.value
                        }
                    }
                }
            } finally {
                settingsReady.complete(Unit)
            }
        }
    }

    /** True after the persisted settings snapshot has been applied to the cache. */
    val isReady: Boolean get() = settingsReady.isCompleted

    /** Waits without blocking the main thread until the initial DB settings read finishes. */
    suspend fun awaitReady() = settingsReady.await()
    private fun get(key: String): String = synchronized(cacheLock) {
        cache[key] ?: defaults[key].orEmpty()
    }
    private fun put(key: String, value: String) {
        synchronized(writeLock) {
            synchronized(cacheLock) {
                cache[key] = value
                modifiedKeys += key
            }
            val c = app ?: return
            settingsExecutor.execute {
                runCatching { AgentDatabase.get(c).settings().put(SettingEntity(key, value)) }
                    .onFailure { AppLogger.e("AgentConfig", "setting write failed for $key: ${it.message}", it) }
            }
        }
    }

    /** Raw access for composite settings (e.g. the multi-key JSON list). */
    fun getRaw(key: String): String = get(key)
    fun putRaw(key: String, value: String) = put(key, value)

    /**
     * Reloads every persisted setting row into the in-memory cache. Used after a
     * config restore so the RUNNING process immediately sees the restored values
     * (API key, model, toggles, …) without an app restart.
     */
    fun refreshCache(context: Context) {
        val c = context.applicationContext
        synchronized(writeLock) {
            awaitPendingWrites()
            app = c
            val rows = AgentDatabase.get(c).settings().all()
            synchronized(cacheLock) {
                cacheGeneration++
                cache.clear()
                cache.putAll(defaults)
                rows.forEach { cache[it.key] = it.value }
                modifiedKeys.clear()
            }
            AppLogger.i("AgentConfig", "settings cache refreshed (${rows.size} keys)")
        }
    }

    /** Waits for already queued setting writes before an authoritative DB restore. */
    fun awaitPendingWrites() {
        if (Thread.currentThread().name == "agent-settings") return
        settingsExecutor.submit(Runnable {}).get(30, TimeUnit.SECONDS)
    }

    /**
     * Prevents new setting writes from being queued while an authoritative restore
     * replaces the settings table and refreshes the in-memory cache.
     */
    fun <T> withWritesPaused(action: () -> T): T = synchronized(writeLock) {
        awaitPendingWrites()
        action()
    }
    var baseUrl: String get() = get("base_url"); set(v) { put("base_url", v.trimEnd('/')) }
    var apiKey: String get() = get("api_key"); set(v) { put("api_key", v) }
    var model: String get() = get("model"); set(v) { put("model", v) }
    /** Inference route selected in the chat model picker. */
    var modelMode: String
        get() = if (get("model_mode").equals("offline", ignoreCase = true)) "offline" else "online"
        set(value) { put("model_mode", if (value.equals("offline", ignoreCase = true)) "offline" else "online") }
    var offlineModelPath: String get() = get("offline_model_path"); set(v) { put("offline_model_path", v.trim()) }
    var offlineModelName: String get() = get("offline_model_name"); set(v) { put("offline_model_name", v.trim()) }
    val isOfflineMode: Boolean get() = modelMode == "offline"
    /** Stable label used for run notifications and message metadata. */
    val activeModelName: String
        get() = if (isOfflineMode) offlineModelName.removeSuffix(".gguf").ifBlank { "Offline GGUF" } else model
    val sessionModelName: String
        get() = if (isOfflineMode) "offline:$offlineModelName" else model
    var reasoningEffort: String get() = get("reasoning_effort"); set(v) { put("reasoning_effort", v) }
    var memoryEnabled: Boolean get() = get("memory_enabled").toBoolean(); set(v) { put("memory_enabled", v.toString()) }
    /** Whether prompts and memory tools may recall content originating in other chat sessions. */
    var crossSessionContextEnabled: Boolean get() = get("cross_session_context").toBoolean(); set(v) { put("cross_session_context", v.toString()) }
    var saveHistory: Boolean get() = get("save_history").toBoolean(); set(v) { put("save_history", v.toString()) }
    var autoApproveReads: Boolean get() = get("auto_approve_reads").toBoolean(); set(v) { put("auto_approve_reads", v.toString()) }
    var askBeforeWrite: Boolean get() = get("ask_before_write").toBoolean(); set(v) { put("ask_before_write", v.toString()) }
    var allowProcess: Boolean get() = get("allow_process").toBoolean(); set(v) { put("allow_process", v.toString()) }
    var allowNetwork: Boolean get() = get("allow_network").toBoolean(); set(v) { put("allow_network", v.toString()) }
    var accessibilityEnabled: Boolean get() = get("accessibility_enabled").toBoolean(); set(v) { put("accessibility_enabled", v.toString()) }
    var allowRoot: Boolean get() = get("allow_root").toBoolean(); set(v) { put("allow_root", v.toString()) }
    var maxAgentSteps: Int get() = get("max_agent_steps").toIntOrNull()?.coerceIn(1, 5000) ?: 24; set(v) { put("max_agent_steps", v.coerceIn(1,5000).toString()) }
    var contextCompression: Boolean get() = get("context_compression").toBoolean(); set(v) { put("context_compression", v.toString()) }
    var compressionThresholdChars: Int get() = get("compression_threshold_chars").toIntOrNull()?.coerceIn(20000, 250000) ?: 70000; set(v) { put("compression_threshold_chars", v.coerceIn(20000,250000).toString()) }
    var compressionKeepMessages: Int get() = get("compression_keep_messages").toIntOrNull()?.coerceIn(6, 40) ?: 14; set(v) { put("compression_keep_messages", v.coerceIn(6,40).toString()) }
    /** Always-on wake-word voice assistant ("Hey Oreshik", "Hey Casper", …). */
    var wakeWordEnabled: Boolean get() = get("wake_word_enabled").toBoolean(); set(v) { put("wake_word_enabled", v.toString()) }

    /** Boost media volume during TTS talkback so confirmations are clearly audible. */
    var wakeWordVolumeBoost: Boolean get() = get("wake_word_volume_boost").toBoolean(); set(v) { put("wake_word_volume_boost", v.toString()) }

    /** Intelligent speech clarification (mishear/ambiguity detection before execution). */
    var speechClarificationEnabled: Boolean get() = get("speech_clarification_enabled").toBoolean(); set(v) { put("speech_clarification_enabled", v.toString()) }

    /** Auto-VPN: use free proxies when the direct network is slow/DNS-broken. */
    var autoVpnEnabled: Boolean get() = get("auto_vpn_enabled").toBoolean(); set(v) { put("auto_vpn_enabled", v.toString()) }

    /** Vision computer-use: NVIDIA VL model override (blank = auto-pick from catalog). */
    var visionModel: String get() = get("vision_model"); set(v) { put("vision_model", v.trim()) }

    /** Vision computer-use: append VLM screen descriptions to accessibility reads. */
    var visionAutoDescribe: Boolean get() = get("vision_auto_describe").toBoolean(); set(v) { put("vision_auto_describe", v.toString()) }

    /**
     * Autonomous long-term learning: after agent runs, distill durable user/project facts from
     * the exchange into permanent vector memory (and capture explicit "remember that…" requests
     * immediately). Disabling this stops all automatic memory writes; explicit memory.save tool
     * calls and the manual Memory screen keep working.
     */
    var memoryAutoLearn: Boolean get() = get("memory_auto_learn").toBoolean(); set(v) { put("memory_auto_learn", v.toString()) }

    /** How many exchanges between automatic LLM distillation passes (clamped 1..10). */
    @Suppress("unused") // Public settings API; consumed by the autonomous learner when enabled.
    var memoryLearnEvery: Int get() = get("memory_learn_every").toIntOrNull()?.coerceIn(1, 10) ?: 3; set(v) { put("memory_learn_every", v.coerceIn(1, 10).toString()) }

    /** The agent has one canonical workspace in private app storage. */
    fun workspaceRoot(context: Context): File {
        val app = context.applicationContext
        val primary = File(app.filesDir, "workspace")
        if (!primary.exists() && !primary.mkdirs() && !primary.isDirectory) {
            throw IllegalStateException("Unable to create the canonical app workspace at ${primary.absolutePath}")
        }
        if (!hasWorkspaceAccess(primary)) {
            throw IllegalStateException(
                "The canonical app workspace is inaccessible at ${primary.absolutePath}; refusing to split files into another directory.",
            )
        }
        return primary
    }

    private fun hasWorkspaceAccess(directory: File): Boolean = runCatching {
        directory.isDirectory && directory.canRead() && directory.canWrite() && directory.canExecute() &&
            directory.listFiles() != null
    }.getOrDefault(false)

    /** User-selected chat media lives in a private app folder, independent of workspace permissions. */
    fun chatAttachmentsRoot(context: Context): File {
        val app = context.applicationContext
        return File(app.filesDir, "chat_attachments").apply { mkdirs() }
    }

    /** Resolve a stored attachment name while preventing paths from escaping its private folder. */
    fun chatAttachmentFile(context: Context, relativePath: String): File? = runCatching {
        val root = chatAttachmentsRoot(context).canonicalFile
        require(relativePath.isNotBlank() && !relativePath.contains('/') && !relativePath.contains('\\')) {
            "Invalid chat attachment path."
        }
        File(root, relativePath).canonicalFile.also { file ->
            require(file.parentFile == root) { "Chat attachment path is outside its private folder." }
        }
    }.getOrNull()
    fun modelsUrl(): String {
        // FIX (double-/v1): base URLs already include /v1 — don't append it twice.
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1") || base.endsWith("/v1/")) "$base/models"
               else "$base/v1/models"
    }
}
