package com.example.oreshik.ai

import com.example.oreshik.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cross-session API-key rotation for a provider.
 *
 * When a provider has multiple keys, every request (and therefore every
 * concurrent session) takes the next key round-robin — spreading load across
 * all keys instead of hammering the first one. A 429 (rate limit), 401/403
 * (auth) or 5xx puts the key into a cooldown so it is skipped until it likely
 * recovers, and the session transparently retries on the next key.
 *
 * Rotation state is process-wide (shared by all concurrent sessions) and
 * per-process only — it does not need to survive a restart.
 */
object ProviderKeyRotator {

    private data class Entry(
        val key: String,
        @Volatile var cooldownUntil: Long = 0L,
        @Volatile var consecutiveFailures: Int = 0,
    )

    private val cursors = ConcurrentHashMap<String, AtomicInteger>()
    private val entries = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()

    /** Returns the next key to use for [providerId], round-robin across eligible keys. */
    fun nextKey(providerId: String, keys: List<String>): String? {
        val usable = keys.filter { it.isNotBlank() }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return usable[0]
        val now = System.currentTimeMillis()
        val eligible = usable.filter { (entries[providerId]?.get(it)?.cooldownUntil ?: 0) <= now }
        val pool = eligible.ifEmpty { usable } // all cooling down → still try rather than fail
        val cursor = cursors.getOrPut(providerId) { AtomicInteger(0) }
        val idx = Math.floorMod(cursor.getAndIncrement(), pool.size)
        return pool[idx]
    }

    /** Clears cooldown state after a successful call. */
    fun reportSuccess(providerId: String, key: String) {
        entries[providerId]?.get(key)?.let {
            it.cooldownUntil = 0L
            it.consecutiveFailures = 0
        }
    }

    /**
     * Reports a failure; 429/401/403/5xx put the key into a growing cooldown.
     * Returns true when at least one key is (still) eligible.
     */
    fun reportFailure(providerId: String, key: String, message: String): Boolean {
        val code = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val codeMatches = (code == 429) || (code == 401) || (code == 403)
        val serverError = (code != null) && ((code >= 500) && (code <= 599))
        val retryable = codeMatches || serverError
        if (!retryable) return false
        val map = entries.getOrPut(providerId) { ConcurrentHashMap() }
        val e = map.getOrPut(key) { Entry(key) }
        e.consecutiveFailures++
        val cooldownMs = when (code) {
            429 -> 30_000L * e.consecutiveFailures
            401, 403 -> 10 * 60_000L
            else -> (30_000L * e.consecutiveFailures).coerceAtMost(10 * 60_000L)
        }
        e.cooldownUntil = System.currentTimeMillis() + cooldownMs
        AppLogger.w("KeyRotator", "key ${mask(key)} of $providerId failed (HTTP $code); cooldown ${cooldownMs / 1000}s")
        val now = System.currentTimeMillis()
        val providerEntries = entries[providerId]
        val anyEligible = if (providerEntries == null) false else {
            val values = providerEntries.values.toList()
            var found = false
            for (i in values.indices) {
                if (values[i].cooldownUntil <= now) {
                    found = true
                    break
                }
            }
            found
        }
        return anyEligible
    }

    /** True when [message] looks like a retryable key-level failure (429/401/403/5xx). */
    fun isRetryableFailure(message: String): Boolean {
        val code = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val codeMatches = (code == 429) || (code == 401) || (code == 403)
        val serverError = (code != null) && ((code >= 500) && (code <= 599))
        return codeMatches || serverError
    }

    fun mask(key: String): String {
        if (key.length <= 12) return "••••"
        return key.take(9) + "••••" + key.takeLast(4)
    }
}
