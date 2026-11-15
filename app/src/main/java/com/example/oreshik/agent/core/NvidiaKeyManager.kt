package com.example.oreshik.agent.core

import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multiple NVIDIA API key management with intelligent rotation.
 *
 * - Keys are stored in the existing Room settings table (no duplicate storage).
 * - Selection state is Mutex-protected so concurrent sessions never corrupt it.
 * - Round-robin across eligible keys; keys that hit rate limits (429), auth
 *   failures (401/403) or repeated 5xx enter a cooldown and are restored after
 *   the retry period.
 * - Rotation happens ONLY for retryable errors; invalid requests (400/422) and
 *   network outages do not trigger key rotation.
 * - Keys are never logged or exposed; display uses a masked form.
 * - Single-key configurations work without any rotation overhead.
 *
 * Note: rotation distributes load across the USER'S OWN configured keys only —
 * it does not and cannot circumvent NVIDIA's per-account quotas or terms.
 */
class NvidiaKeyManager(
    // Config indirection kept for future testability (allows injecting a fake config).
    @Suppress("unused") private val config: AgentConfig = AgentConfig,
) {

    data class KeyState(
        val key: String,
        /** Milliseconds since epoch when the key becomes eligible again (0 = now). */
        var cooldownUntil: Long = 0,
        var consecutiveFailures: Int = 0,
        var lastError: String = "",
    ) {
        /** Masked form for diagnostics UIs. */
        @Suppress("unused")
        val masked: String get() = mask(key)
        fun isEligible(now: Long = System.currentTimeMillis()) = now >= cooldownUntil
    }

    companion object {
        private const val KEYS_SETTING = "nvidia_keys"

        fun mask(key: String): String {
            if (key.length <= 12) return "••••"
            return key.take(9) + "••••" + key.takeLast(4)
        }

        /** Never log raw keys. */
        private fun safeTag(key: String) = mask(key)
    }

    private val gson = Gson()
    private val mutex = Mutex()
    private val states = LinkedHashMap<String, KeyState>()
    private var roundRobinCursor = 0

    // ------------------------------------------------------------------ storage

    fun loadKeys(): List<String> {
        val raw = AgentConfig.getRaw(KEYS_SETTING)
        if (raw.isBlank()) {
            // Back-compat: the legacy single-key setting belongs to NVIDIA only
            // while NVIDIA is active (or before any provider has been selected).
            // Otherwise it may contain a different provider's credential.
            val activeProviderId = AgentConfig.getRaw("active_provider_id")
            val single = if (activeProviderId.isBlank() || activeProviderId == "nvidia") AgentConfig.apiKey else ""
            return if (single.isBlank()) emptyList() else listOf(single)
        }
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun saveKeys(keys: List<String>) {
        val cleaned = keys.asSequence().map { key -> key.trim() }.filter { candidate -> candidate.isNotBlank() }.distinct().toList()
        mutex.tryLock()
        try {
            states.keys.retainAll(cleaned.toSet())
            cleaned.forEach { k -> states.getOrPut(k) { KeyState(k) } }
        } finally {
            if (mutex.isLocked) mutex.unlock()
        }
        AgentConfig.putRaw(KEYS_SETTING, if (cleaned.isEmpty()) "" else gson.toJson(cleaned))
        // Keep the legacy single-key setting in sync (used as fallback elsewhere).
        if (cleaned.isNotEmpty()) AgentConfig.apiKey = cleaned.first()
        AppLogger.i("NvidiaKeyManager", "Saved ${cleaned.size} key(s)")
    }

    fun addKey(key: String) = saveKeys(loadKeys() + key)
    fun removeKey(key: String) = saveKeys(loadKeys() - key)

    // ---------------------------------------------------------------- selection

    /**
     * Returns the next eligible key (round-robin across healthy keys). Returns
     * null when no keys are configured or all are cooling down.
     */
    suspend fun selectKey(): String? = mutex.withLock {
        val keys = loadKeys()
        if (keys.isEmpty()) return@withLock null
        refreshStates(keys)
        val now = System.currentTimeMillis()
        val eligible = states.values.filter { state -> state.isEligible(now) }
        if (eligible.isEmpty()) return@withLock null
        // Round-robin start position, wrapping.
        val start = roundRobinCursor % eligible.size
        val chosen = eligible[start]
        roundRobinCursor = (roundRobinCursor + 1) % eligible.size
        return chosen.key
    }

    /** Reports the outcome of a request so rotation state stays accurate. */
    suspend fun reportSuccess(key: String) = mutex.withLock {
        states[key]?.let { state ->
            state.consecutiveFailures = 0
            state.lastError = ""
            state.cooldownUntil = 0
        }
    }

    /**
     * Reports a retryable failure (429 rate limit, 401/403 auth, 5xx) and puts
     * the key into cooldown. Returns true when other keys remain eligible.
     */
    suspend fun reportFailure(key: String, httpCode: Int?, message: String): Boolean = mutex.withLock {
        val state = states[key] ?: return@withLock false
        state.consecutiveFailures++
        state.lastError = message.take(200)
        // Cooldown grows with consecutive failures: 30s, 2min, 10min (capped).
        val cooldownMs = when (httpCode) {
            429 -> 30_000L * state.consecutiveFailures
            401, 403 -> 10 * 60_000L // auth failures: long cooldown
            null -> (30_000L * state.consecutiveFailures).coerceAtMost(10 * 60_000L)
            else -> (30_000L * state.consecutiveFailures).coerceAtMost(10 * 60_000L)
        }
        state.cooldownUntil = System.currentTimeMillis() + cooldownMs
        AppLogger.w("NvidiaKeyManager", "Key ${safeTag(key)} failed (HTTP $httpCode); cooldown ${cooldownMs / 1000}s")
        states.values.any { it.isEligible() }
    }

    /** True when the given HTTP status should trigger key rotation. */
    fun shouldRotate(httpCode: Int?, @Suppress("UNUSED_PARAMETER") message: String): Boolean {
        // Network errors (null code): do NOT rotate (changing keys cannot fix connectivity).
        return (httpCode != null) && (((httpCode == 429)) || ((httpCode == 401)) || ((httpCode == 403)) || ((httpCode in 500..599)))
    }

    /** Parses "HTTP 429: ..." style error messages into the status code. */
    fun httpCodeFromMessage(message: String): Int? =
        Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()

    /** All tracked key states (for diagnostics UIs). */
    @Suppress("unused")
    fun allStates(): List<KeyState> = states.values.toList()

    private fun refreshStates(keys: List<String>) {
        states.keys.retainAll(keys.toSet())
        keys.forEach { k -> states.getOrPut(k) { KeyState(k) } }
    }
}
