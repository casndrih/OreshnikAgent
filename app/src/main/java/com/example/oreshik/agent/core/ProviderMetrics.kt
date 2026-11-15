package com.example.oreshik.agent.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.net.URI

/** Persistent app-private request telemetry. It stores model/host/usage only, never prompts or credentials. */
object ProviderMetrics {
    data class Snapshot(
        val host: String,
        val model: String,
        val requests: Long,
        val successes: Long,
        val failures: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val usageReports: Long,
        val totalLatencyMs: Long,
        val lastLatencyMs: Long,
        val updatedAt: Long,
    ) {
        val averageLatencyMs: Long get() = if (requests == 0L) 0 else totalLatencyMs / requests
    }

    private data class MutableStats(
        val host: String,
        val model: String,
        var requests: Long = 0,
        var successes: Long = 0,
        var failures: Long = 0,
        var promptTokens: Long = 0,
        var completionTokens: Long = 0,
        var usageReports: Long = 0,
        var totalLatencyMs: Long = 0,
        var lastLatencyMs: Long = 0,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    private val stats = LinkedHashMap<String, MutableStats>()
    private val gson = Gson()
    @Volatile private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            preferences = prefs
            val saved = prefs.getString(KEY_STATS, null).orEmpty()
            if (saved.isNotBlank()) runCatching {
                gson.fromJson(saved, Array<MutableStats>::class.java).orEmpty().forEach { stats["${it.host}|${it.model}"] = it }
            }
        }
    }

    private fun persistLocked() {
        preferences?.edit()?.putString(KEY_STATS, gson.toJson(stats.values.toList()))?.apply()
    }

    fun record(baseUrl: String, model: String, latencyMs: Long, success: Boolean, promptTokens: Long, completionTokens: Long, usageReported: Boolean = promptTokens > 0 || completionTokens > 0) {
        val host = runCatching { URI(baseUrl).host?.lowercase().orEmpty() }.getOrDefault("")
            .ifBlank { "unknown-provider" }.take(120)
        val safeModel = model.take(160)
        val key = "$host|$safeModel"
        synchronized(lock) {
            val item = stats.getOrPut(key) { MutableStats(host, safeModel) }
            item.requests++
            if (success) item.successes++ else item.failures++
            item.promptTokens += promptTokens.coerceAtLeast(0)
            item.completionTokens += completionTokens.coerceAtLeast(0)
            if (usageReported) item.usageReports++
            item.lastLatencyMs = latencyMs.coerceAtLeast(0)
            item.totalLatencyMs += item.lastLatencyMs
            item.updatedAt = System.currentTimeMillis()
            while (stats.size > 100) stats.remove(stats.keys.first())
            persistLocked()
        }
    }

    fun snapshot(): List<Snapshot> = synchronized(lock) {
        stats.values.map {
            Snapshot(it.host, it.model, it.requests, it.successes, it.failures, it.promptTokens,
                it.completionTokens, it.usageReports, it.totalLatencyMs, it.lastLatencyMs, it.updatedAt)
        }.sortedByDescending { it.updatedAt }
    }

    fun reset() = synchronized(lock) { stats.clear(); persistLocked() }

    fun setPricing(context: Context, host: String, model: String, inputUsdPerMillion: Double, outputUsdPerMillion: Double) {
        require(inputUsdPerMillion.isFinite() && outputUsdPerMillion.isFinite() &&
            inputUsdPerMillion in 0.0..100_000.0 && outputUsdPerMillion in 0.0..100_000.0
        ) { "Pricing must be finite and between 0 and 100000 USD per million tokens." }
        val key = pricingKey(host, model)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("$key.input", java.lang.Double.doubleToRawLongBits(inputUsdPerMillion))
            .putLong("$key.output", java.lang.Double.doubleToRawLongBits(outputUsdPerMillion))
            .apply()
    }

    fun estimatedCostUsd(context: Context, item: Snapshot): Double? {
        if (item.usageReports == 0L) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = pricingKey(item.host, item.model)
        val input = prefs.getLong("$key.input", Long.MIN_VALUE)
        val output = prefs.getLong("$key.output", Long.MIN_VALUE)
        if (input == Long.MIN_VALUE || output == Long.MIN_VALUE) return null
        val inRate = java.lang.Double.longBitsToDouble(input)
        val outRate = java.lang.Double.longBitsToDouble(output)
        return (item.promptTokens * inRate + item.completionTokens * outRate) / 1_000_000.0
    }

    private fun pricingKey(host: String, model: String): String {
        val safeHost = host.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(120)
        val safeModel = model.filter { it.isLetterOrDigit() || it in "._-" }.take(160)
        return "price:$safeHost:$safeModel"
    }

    private const val PREFS = "provider_metrics"
    private const val KEY_STATS = "request_stats"
}
