package com.example.oreshik.agent.net

import com.example.oreshik.logging.AppLogger
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures real-world latency of proxy candidates against the actual inference
 * endpoint (not a generic ping target), so "works" means "the app's traffic
 * would flow". Verified entries are cached for 1 h (free proxies churn fast).
 *
 * Testing runs in parallel with a bounded worker pool and a hard cap on
 * candidates per round to keep battery/data usage sane.
 */
object ProxyTester {

    private const val TAG = "ProxyTester"
    private const val HTTPS_SCHEME = "https:"
    private const val TARGET = "$HTTPS_SCHEME//integrate.api.nvidia.com"
    /** Connect timeout per probe. */
    private const val CONNECT_TIMEOUT_MS = 6L
    /** Total call timeout per probe. */
    private const val CALL_TIMEOUT_MS = 10L
    /** A proxy faster than this (ms RTT) is good enough to use. */
    private const val MAX_ACCEPT_RTT_MS = 2500L
    /** Parallel test workers. */
    private const val WORKERS = 4
    /** Max candidates tested in one round (cheapest-first ordering happens upstream). */
    private const val MAX_PER_ROUND = 40
    /** Verified-proxy cache lifetime. */
    private const val CACHE_MS = 60 * 60_000L

    /** Verified proxies (type+host+port -> rtt), newest verification time alongside. */
    private data class Verified(val entry: FreeProxyProvider.ProxyEntry, val verifiedAt: Long)
    private val cache = LinkedHashMap<String, Verified>()

    fun cachedVerified(): List<FreeProxyProvider.ProxyEntry> = synchronized(cache) {
        pruneCache()
        val entries = cache.values.toList()
        entries.asSequence().map(Verified::entry).sortedBy(FreeProxyProvider.ProxyEntry::rttMs).toList()
    }

    /** Clears the verified-proxy cache. */
    @Suppress("unused")
    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * Tests up to [MAX_PER_ROUND] candidates; returns the verified pool sorted by
     * RTT. Never throws.
     */
    fun testRound(candidates: List<FreeProxyProvider.ProxyEntry>): List<FreeProxyProvider.ProxyEntry> {
        val batch = candidates.take(MAX_PER_ROUND)
        if (batch.isEmpty()) return cachedVerified()
        val index = AtomicInteger(0)
        val done = CountDownLatch(minOf(WORKERS, batch.size))
        repeat(minOf(WORKERS, batch.size)) {
            Thread {
                try {
                    while (true) {
                        val i = index.getAndIncrement()
                        if (i >= batch.size) break
                        val entry = batch[i]
                        val rtt = probe(entry)
                        if ((rtt >= 1) && (rtt <= MAX_ACCEPT_RTT_MS)) {
                            entry.rttMs = rtt
                            synchronized(cache) {
                                cache[keyOf(entry)] = Verified(entry, System.currentTimeMillis())
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        try { done.await(90, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        val result = cachedVerified()
        AppLogger.i(TAG, "Test round done: ${batch.size} probed, ${result.size} verified in pool")
        return result
    }

    /** Single probe: HEAD to the inference host through the proxy; returns RTT or -1. */
    fun probe(entry: FreeProxyProvider.ProxyEntry): Long {
        val address = InetSocketAddress(entry.host, entry.port)
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy(entry.type.toJavaType(), address))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_MS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.SECONDS)
            .build()
        return NetworkDiagnostics.rtt(client, TARGET)
    }

    private fun keyOf(e: FreeProxyProvider.ProxyEntry) = "${e.type.name}:${e.host}:${e.port}"

    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - CACHE_MS
        cache.entries.removeIf { (_, verified) -> (verified.verifiedAt < cutoff) }
    }

    private fun FreeProxyProvider.Type.toJavaType(): java.net.Proxy.Type = when (this) {
        FreeProxyProvider.Type.HTTP -> java.net.Proxy.Type.HTTP
        FreeProxyProvider.Type.SOCKS4, FreeProxyProvider.Type.SOCKS5 -> java.net.Proxy.Type.SOCKS
    }
}
