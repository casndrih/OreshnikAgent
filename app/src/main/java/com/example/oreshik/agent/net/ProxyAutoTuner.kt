package com.example.oreshik.agent.net

import com.example.oreshik.logging.AppLogger
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when a proxy is needed, finds a fast working one, applies it, and
 * verifies it actually improved the route — then reports honestly.
 *
 * Policy:
 *  1. Diagnose the direct network (cached 60 s).
 *  2. If healthy and not forced, do nothing (drop a stale proxy if direct
 *     recovered on its own).
 *  3. If slow or DNS-broken: pick the best cached verified proxy, else fetch +
 *     test a fresh round; apply via [ProxyRotator]; re-measure THROUGH the
 *     proxied client and keep it only if the route meaningfully improved
 *     (direct unreachable → any success, or new RTT < 75% of direct RTT).
 *  4. If nothing worked, revert to direct and say so.
 *
 * One pass is bounded (~45–90 s worst case) and runs on a background thread;
 * concurrent callers are folded into the running pass (single-flight).
 */
object ProxyAutoTuner {

    private const val TAG = "ProxyAutoTuner"
    /** New RTT must be below this fraction of the direct RTT to be kept. */
    private const val IMPROVE_FACTOR = 0.75
    private const val HTTPS_SCHEME = "https:"
    private const val API_HOST_PROBE = "//integrate.api.nvidia.com"
    private const val CANARY_PROBE = "//www.google.com/generate_204"

    @Volatile var lastReport: String = "Auto-VPN idle"; private set
    private val running = AtomicBoolean(false)

    /**
     * Runs one tuner pass. Safe from any thread; concurrent calls return the
     * current report instead of starting a second pass.
     *
     * @param force test and apply even when the direct network looks healthy.
     */
    fun ensureTunnel(force: Boolean = false): String {
        if (!running.compareAndSet(false, true)) return lastReport
        val result = try {
            doRun(force)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tuner pass failed: ${e.message}")
            lastReport = "Auto-VPN pass failed: ${e.message?.take(120)}"
            lastReport
        } finally {
            running.set(false)
        }
        return result
    }

    private fun doRun(force: Boolean): String {
        val (isSlow, isDnsIssue, directRtt, summary) = NetworkDiagnostics.diagnose()
        val sb = StringBuilder("Network: ").append(summary)

        if ((!isSlow) && (!isDnsIssue) && (!force)) {
            if (ProxyRotator.current != null) {
                ProxyRotator.setProxy(null)
                sb.append(" • direct OK again, proxy dropped")
            }
            lastReport = sb.toString()
            AppLogger.i(TAG, lastReport)
            return lastReport
        }

        sb.append(" • searching proxy…")
        lastReport = sb.toString()

        // 1) Cheap path: best cached verified proxy.
        var applied = tryApplyBest(ProxyTester.cachedVerified(), directRtt)

        // 2) Fresh candidates when the cache had nothing usable.
        if (!applied) {
            val candidates = FreeProxyProvider.fetchAll()
            if (candidates.isEmpty()) {
                ProxyRotator.setProxy(null)
                sb.append(" • proxy lists unreachable; staying direct")
                lastReport = sb.toString()
                AppLogger.w(TAG, lastReport)
                return lastReport
            }
            applied = tryApplyBest(ProxyTester.testRound(candidates), directRtt)
        }

        if (!applied) {
            ProxyRotator.setProxy(null)
            sb.append(" • no fast proxy found; staying direct")
            lastReport = sb.toString()
            AppLogger.w(TAG, lastReport)
            return lastReport
        }

        // lastReport was finalized inside tryApplyBest on success.
        return lastReport
    }

    /** Applies the best proxy from [pool] and verifies the improvement; true when kept. */
    private fun tryApplyBest(pool: List<FreeProxyProvider.ProxyEntry>, directRtt: Long): Boolean {
        val stale = ProxyRotator.current
        for (entry in pool) {
            ProxyRotator.setProxy(entry)
            val proxiedRtt = proxiedRtt(entry)
            val improved = when {
                (directRtt < 0) && (proxiedRtt > 0) -> true
                (directRtt > 0) && (proxiedRtt > 0) -> proxiedRtt <= (directRtt * IMPROVE_FACTOR).toLong()
                else -> false
            }
            if (improved) {
                lastReport = buildString {
                    append("Network slow • proxy APPLIED: ")
                    append(entry.type.name).append(' ').append(entry.host).append(':').append(entry.port)
                    append(" • via-proxy RTT ").append(proxiedRtt).append("ms")
                    append(" vs direct ").append(if (directRtt > 0) "${directRtt}ms" else "unreachable")
                }
                AppLogger.i(TAG, lastReport)
                return true
            }
            // Didn't help: revert and try the next candidate.
            ProxyRotator.setProxy(stale)
        }
        return false
    }

    /** Measures RTT to the inference host THROUGH the candidate proxy. -1 when dead. */
    private fun proxiedRtt(entry: FreeProxyProvider.ProxyEntry): Long {
        val type = when (entry.type) {
            FreeProxyProvider.Type.HTTP -> Proxy.Type.HTTP
            FreeProxyProvider.Type.SOCKS4, FreeProxyProvider.Type.SOCKS5 -> Proxy.Type.SOCKS
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy(type, InetSocketAddress(entry.host, entry.port)))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val api = NetworkDiagnostics.rtt(client, "$HTTPS_SCHEME$API_HOST_PROBE")
        if (api > 0) return api
        return NetworkDiagnostics.rtt(client, "$HTTPS_SCHEME$CANARY_PROBE")
    }
}
