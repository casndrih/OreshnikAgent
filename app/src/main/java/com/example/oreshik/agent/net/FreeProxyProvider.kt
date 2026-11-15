package com.example.oreshik.agent.net

import com.example.oreshik.logging.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches free public proxy lists (best-effort, no guarantees) from
 * well-maintained GitHub sources and exposes them as uniform [ProxyEntry]s.
 *
 * Sources were chosen for size, active maintenance and per-entry protocol info:
 *  - TheSpeedX/PROXY-List (socks5.txt ~2500, http.txt ~2800 entries)
 *  - monosans/proxy-list (proto-prefixed lines for http/socks4/socks5)
 *
 * The list fetch itself always runs DIRECT (never through a proxy) — otherwise
 * a dead proxy would break list refresh too.
 */
object FreeProxyProvider {

    enum class Type { HTTP, SOCKS4, SOCKS5 }

    data class ProxyEntry(
        val type: Type,
        val host: String,
        val port: Int,
        /** Measured RTT through this proxy to the inference host; -1 = untested. */
        var rttMs: Long = -1,
    )

    private const val TAG = "FreeProxies"
    private const val FETCH_TIMEOUT_S = 20L

    private data class Source(val url: String, val type: Type, val protoPrefixed: Boolean)

    private val sources = listOf(
        Source("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", Type.SOCKS5, protoPrefixed = false),
        Source("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", Type.HTTP, protoPrefixed = false),
        Source("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", Type.HTTP, protoPrefixed = true),
        Source("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt", Type.SOCKS4, protoPrefixed = true),
        Source("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", Type.SOCKS5, protoPrefixed = true),
    )

    /** Fetches and parses all sources; returns a deduped candidate pool. */
    fun fetchAll(): List<ProxyEntry> {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val seen = HashSet<String>()
        val out = ArrayList<ProxyEntry>(2048)
        for (src in sources) {
            try {
                val body = client.newCall(Request.Builder().url(src.url).get().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                    }
                if (body.isBlank()) continue
                for (line in body.lineSequence()) {
                    val entry = parseLine(line.trim(), src) ?: continue
                    val key = "${entry.type.name}:${entry.host}:${entry.port}"
                    if (seen.add(key)) out.add(entry)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Proxy source failed: ${src.url} (${e.message})")
            }
        }
        AppLogger.i(TAG, "Fetched ${out.size} unique proxy candidates from ${sources.size} sources")
        return out
    }

    private fun parseLine(raw: String, src: Source): ProxyEntry? {
        if (raw.isEmpty() || raw.startsWith("#")) return null
        var line = raw
        var type = src.type
        if (src.protoPrefixed) {
            // monosans format: "proto://host:port" or proto://user:pass@host:port
            val idx = line.indexOf("://")
            if (idx <= 0) return null
            val proto = line.substring(0, idx).lowercase()
            line = line.substring(idx + 3).substringAfterLast('@')
            type = when (proto) {
                "http" -> Type.HTTP
                "socks4" -> Type.SOCKS4
                "socks5" -> Type.SOCKS5
                else -> return null
            }
        }
        val parts = line.split(":")
        if (parts.size != 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return ProxyEntry(type, host, port)
    }
}
