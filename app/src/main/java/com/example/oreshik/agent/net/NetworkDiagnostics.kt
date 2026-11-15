package com.example.oreshik.agent.net

import com.example.oreshik.logging.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Lightweight network health probe for the Auto-VPN feature.
 *
 * Measures HEAD RTT to the inference host and a connectivity canary, plus DNS
 * resolution of the inference host. The result is cached for 60 s so bursty
 * callers (retry loops, settings screen) never hammer the network.
 *
 * "Slow" is deliberately conservative: RTT > 1500 ms on the inference host or
 * a first-attempt failure that a retry recovered. DNS is reported as broken
 * only when name resolution fails while the connectivity canary works.
 */
object NetworkDiagnostics {

    data class Result(
        /** True when the network looks too slow or flaky for direct inference. */
        val isSlow: Boolean,
        /** True when name resolution failed while raw connectivity works. */
        val isDnsIssue: Boolean,
        /** Best observed RTT in ms (lower is better); -1 when unreachable. */
        val rttMs: Long,
        /** Human-readable one-line summary for status lines. */
        val summary: String,
    )

    private const val TAG = "NetDiag"
    private const val HTTPS_SCHEME = "https:"
    private const val API_HOST = "integrate.api.nvidia.com"
    // Google's generate_204 canary: a zero-content endpoint ideal for RTT probes.
    private const val CANARY_HOST = "www.google.com"
    private const val CANARY_PATH = "/generate_204"
    private const val CACHE_MS = 60_000L
    /** Above this RTT (ms) the direct network is considered slow. */
    const val SLOW_RTT_MS = 1500L

    @Volatile private var cached: Result? = null
    @Volatile private var cachedAt = 0L

    fun last(): Result? = cached

    fun diagnose(): Result {
        cached?.let {
            if ((System.currentTimeMillis() - cachedAt) < CACHE_MS) return it
        }
        val direct = client()
        val apiRtt = rtt(direct, "$HTTPS_SCHEME//$API_HOST")
        val canaryRtt = rtt(direct, "$HTTPS_SCHEME//$CANARY_HOST$CANARY_PATH")
        val dnsOk = resolveDns()

        val failureRecovered = (apiRtt < 0) && (canaryRtt >= 0)
        val bestRtt = listOf(apiRtt, canaryRtt).asSequence().filter { rtt -> rtt > 0 }.minOrNull() ?: -1
        val isSlow = (bestRtt > SLOW_RTT_MS) || failureRecovered
        val isDnsIssue = !dnsOk && (canaryRtt > 0)

        val result = Result(
            isSlow = isSlow,
            isDnsIssue = isDnsIssue,
            rttMs = bestRtt,
            summary = buildString {
                append(if (isDnsIssue) "DNS broken" else if (isSlow) "SLOW" else "OK")
                append(" • RTT ")
                append(if (bestRtt > 0) "${bestRtt}ms" else "unreachable")
                if (isDnsIssue) append(" • API host unresolvable")
            },
        )
        AppLogger.i(TAG, "Network: ${result.summary}")
        cached = result
        cachedAt = System.currentTimeMillis()
        return result
    }

    /** Re-measure ignoring the cache. Used after a proxy is applied to verify gains. */
    @Suppress("unused")
    fun recheck(): Result {
        cached = null
        cachedAt = 0
        return diagnose()
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** HEAD round-trip in ms; -1 when the request failed. */
    fun rtt(client: OkHttpClient, url: String): Long {
        val start = System.currentTimeMillis()
        return try {
            client.newCall(
                Request.Builder().url(url).head().build(),
            ).execute().use { response ->
                // Any completed HTTP exchange counts; even 3xx/4xx proves the path works.
                val ok = response.code > 0
                if (ok) System.currentTimeMillis() - start else -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun resolveDns(): Boolean {
        var ok = false
        // All exceptions (including UnknownHostException) are captured INSIDE the
        // thread — an uncaught throwable in a raw thread crashes the whole app.
        val t = Thread {
            ok = try {
                // Non-null on success; a null return is impossible for a valid host string,
                // so resolution success/failure is expressed entirely by the try/catch.
                InetAddress.getByName(API_HOST)
                true
            } catch (_: Exception) {
                false
            }
        }
        t.isDaemon = true
        t.start()
        try { t.join(12_000) } catch (_: InterruptedException) {}
        return ok
    }
}
