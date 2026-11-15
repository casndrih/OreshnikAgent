package com.example.oreshik.agent.net

import com.example.oreshik.logging.AppLogger
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies a chosen proxy to every registered app HTTP client (inference API,
 * model catalog, web tools) and notifies listeners on change.
 *
 * Clients register their [OkHttpClient.Builder] + a rebuild function; when the
 * proxy changes, each client is rebuilt with the new proxy (or without one).
 * Rebuilds are synchronized so in-flight calls finish on the old client while
 * new calls use the new one — no torn state.
 *
 * This is per-client proxying, NOT a system VPN: no VpnService permission, no
 * root, nothing global changes. Unregistered clients keep going direct.
 */
object ProxyRotator {

    private data class Registered(
        val name: String,
        val build: (Proxy?) -> OkHttpClient,
    )

    private val clients = ConcurrentHashMap<String, Registered>()
    private val listeners = ConcurrentHashMap<String, (FreeProxyProvider.ProxyEntry?) -> Unit>()

    /** The currently applied proxy, null when traffic goes direct. */
    @Volatile var current: FreeProxyProvider.ProxyEntry? = null; private set

    /** Human-readable summary of the last change, for the Settings status line. */
    @Volatile var lastReport: String = "Direct connection (no proxy)"; private set

    /** Registers a rebuildable client. Replace = update the build function. */
    fun register(name: String, build: (Proxy?) -> OkHttpClient) {
        clients[name] = Registered(name, build)
        AppLogger.d("ProxyRotator", "Client registered: $name")
    }

    /** Removes a registered client and its listener. */
    @Suppress("unused")
    fun unregister(name: String) {
        clients.remove(name)
        listeners.remove(name)
    }

    fun onProxyChanged(name: String, listener: (FreeProxyProvider.ProxyEntry?) -> Unit) {
        listeners[name] = listener
    }

    /** Builds (or rebuilds) the client for [name] with the current proxy state. */
    fun client(name: String): OkHttpClient? {
        val reg = clients[name] ?: return null
        return synchronized(this) { reg.build(toJavaProxy(current)) }
    }

    /** Applies [entry] to all registered clients. Blocking; returns a report. */
    @Synchronized
    fun setProxy(entry: FreeProxyProvider.ProxyEntry?): String {
        current = entry
        lastReport = if (entry == null) {
            "Direct connection (no proxy)"
        } else {
            "${entry.type.name} proxy ${entry.host}:${entry.port}" +
                (if (entry.rttMs > 0) " (RTT ${entry.rttMs}ms)" else "")
        }
        AppLogger.i("ProxyRotator", "Proxy changed: $lastReport")
        for (l in listeners.values) {
            runCatching { l(entry) }
        }
        return lastReport
    }

    /** Convenience: report the current route for status lines. */
    fun routeSummary(): String = lastReport

    /** Shared converter: ProxyEntry → OkHttp proxy (null-safe). */
    fun toJavaProxy(entry: FreeProxyProvider.ProxyEntry?): Proxy? {
        if (entry == null) return null
        val type = when (entry.type) {
            FreeProxyProvider.Type.HTTP -> Proxy.Type.HTTP
            FreeProxyProvider.Type.SOCKS4, FreeProxyProvider.Type.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress(entry.host, entry.port))
    }
}
