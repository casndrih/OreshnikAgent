package com.example.oreshik.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The single live, shared in-app browser the user watches in real time.
 *
 * This is BOTH the user-facing browser (attached to a real window through
 * [LiveBrowserOverlayView], so the page renders continuously in the chat) AND
 * the browser the agent automates via the `web.browser` tool. Every click,
 * keystroke, and scroll the agent performs happens in this same WebView, so
 * the user sees the site being browsed live — no static screenshots.
 *
 * The same WebView instance is reused; overlay views detach/reattach it so
 * there is exactly one live rendering of the browsing session.
 *
 * Statics hold the app context (never an Activity) and are nulled in destroy
 * paths; the browser intentionally lives for the whole process.
 */
@SuppressLint("StaticFieldLeak")
object LiveBrowser {
    private const val TAG = "LiveBrowser"

    private val MAIN = Handler(Looper.getMainLooper())

    @Volatile private var hostView: LiveBrowserOverlayView? = null

    /** The single live WebView. Created lazily on the main thread. */
    @SuppressLint("StaticFieldLeak")
    @Volatile private var webView: WebView? = null

    /** Registered listeners notified on navigation/progress/title changes. */
    private val listeners = mutableListOf<(state: State) -> Unit>()

    /** Current-page load future: completed by onPageFinished (see navigate()). */
    private val loadFuture = AtomicReference<CompletableFuture<Boolean>?>(null)
    @Volatile private var navigatingUrl: String? = null

    /** Notify listeners after every visual automation step (cursor, typing…). */
    internal val cursor = Cursor()

    data class State(
        val url: String,
        val title: String,
        val progress: Int,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: Context): WebView {
        if (Looper.myLooper() == Looper.getMainLooper()) return acquireOnMain(context)
        val future = CompletableFuture<WebView>()
        if (!MAIN.post {
                runCatching { acquireOnMain(context) }
                    .onSuccess(future::complete)
                    .onFailure(future::completeExceptionally)
            }
        ) {
            throw IllegalStateException("Main thread is unavailable for WebView creation")
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    // This WebView is the app's browser surface; JavaScript is needed for modern sites.
    // File/content access stays disabled and mixed-content loading is blocked.
    @SuppressLint("SetJavaScriptEnabled")
    private fun acquireOnMain(context: Context): WebView {
        webView?.let { return it }
        synchronized(this) {
            webView?.let { return it }
            WebView.enableSlowWholeDocumentDraw()
            val wv = WebView(context.applicationContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (view.url == url) {
                            navigatingUrl = null
                            loadFuture.getAndSet(null)?.complete(true)
                        }
                        notifyState()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase()
                        return (scheme != "http") && (scheme != "https") && (uri.toString() != "about:blank")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: WebResourceError
                    ) {
                        // Only fail the navigation for main-frame errors.
                        // Subresource failures (favicon 404, blocked scripts…) are
                        // common and must NOT abort an otherwise-successful load.
                        if ((request != null) && request.isForMainFrame &&
                            ((request.url.toString() == view.url) || (request.url.toString() == navigatingUrl))
                        ) {
                            navigatingUrl = null
                            loadFuture.getAndSet(null)?.complete(false)
                        }
                    }

                    // Handle renderer process crashes (API 26+)
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail
                    ): Boolean {
                        Log.e(TAG, "WebView render process gone: didCrash=${detail.didCrash()}")
                        navigatingUrl = null
                        loadFuture.getAndSet(null)?.complete(false)
                        // Recreate a fresh renderer on next navigation.
                        try {
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                        } catch (_: Exception) {
                        }
                        synchronized(this@LiveBrowser) { webView = null }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        notifyState()
                    }

                    // Auto-handle JS dialogs so automation never blocks on them.
                    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        result.confirm(); return true
                    }

                    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        result.confirm(); return true
                    }

                    override fun onJsPrompt(
                        view: WebView, url: String, message: String, defaultValue: String,
                        result: android.webkit.JsPromptResult
                    ): Boolean {
                        result.confirm(defaultValue); return true
                    }

                    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                        // Web pages must never receive camera/microphone/device
                        // permissions without a user-facing consent flow.
                        request.deny()
                    }
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            webView = wv
            return wv
        }
    }

    // --------------------------------------------------------------------
    // Host (chat bubble) attachment
    // --------------------------------------------------------------------

    /** Attach the live WebView into the given overlay host (steals it from any previous host). */
    fun attach(host: LiveBrowserOverlayView) {
        MAIN.post {
            val wv = acquire(host.context)
            hostView?.removeWebView(wv)
            host.installWebView(wv)
            hostView = host
            notifyState()
        }
    }

    fun detach(host: LiveBrowserOverlayView) {
        MAIN.post {
            if (hostView === host) {
                val wv = webView ?: return@post
                host.removeWebView(wv)
                hostView = null
            }
        }
    }

    // --------------------------------------------------------------------
    // Agent-facing automation API (all WebView access is main-thread posted)
    // --------------------------------------------------------------------

    /** Navigate and wait (bounded) for the page to finish loading. */
    fun navigate(url: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "navigate must be called off the main thread")
            return false
        }
        val normalizedUrl = normalizeWebUrl(url) ?: return false
        if (webView == null) {
            // Never navigated before: create the browser eagerly so the bubble
            // exists before the first action completes.
            contextOnce?.let { acquire(it) } ?: return false
        }
        // Make the pointer visible while loading and place it on the new page
        // once loading completes. This is a cheap asynchronous viewport read.
        cursor.moveToViewportCenter()
        val future = CompletableFuture<Boolean>()
        val started = CompletableFuture<Boolean>()
        navigatingUrl = normalizedUrl
        loadFuture.set(future)
        if (!MAIN.post {
            if (loadFuture.get() !== future) {
                started.complete(false)
                return@post
            }
            val wv = webView
            if (wv == null) {
                future.complete(false)
                started.complete(false)
            } else {
                try {
                    wv.loadUrl(normalizedUrl)
                    notifyState()
                    started.complete(true)
                } catch (e: Exception) {
                    loadFuture.compareAndSet(future, null)
                    navigatingUrl = null
                    future.complete(false)
                    started.complete(false)
                    Log.e(TAG, "Could not start navigation to $normalizedUrl", e)
                }
            }
        }) {
            loadFuture.compareAndSet(future, null)
            navigatingUrl = null
            return false
        }
        if (!runCatching { started.get(5, TimeUnit.SECONDS) }.getOrDefault(false)) {
            loadFuture.compareAndSet(future, null)
            if (navigatingUrl == normalizedUrl) navigatingUrl = null
            return false
        }
        return try {
            val loaded = future[30, TimeUnit.SECONDS]
            if (loaded) cursor.moveToViewportCenter()
            loaded
        } catch (e: Exception) {
            loadFuture.compareAndSet(future, null)
            if (navigatingUrl == normalizedUrl) navigatingUrl = null
            Log.w(TAG, "navigate timed out for $normalizedUrl", e)
            false
        }
    }

    fun click(selector: String): Boolean {
        val script = """
            (function() {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return 'NOT_FOUND';
                var r = el.getBoundingClientRect();
                // Scroll the element into view first, like a real pointer click.
                el.scrollIntoView({block: 'center', behavior: 'instant'});
                var r2 = el.getBoundingClientRect();
                var cx = (r2.left + r2.width / 2 + window.scrollX);
                var cy = (r2.top + r2.height / 2 + window.scrollY);
                el.click();
                return 'CLICKED@' + Math.round(cx) + ',' + Math.round(cy);
            })()
        """.trimIndent()
        val result = evaluateScriptInternal(script)
        // Report the click point so the cursor can animate onto the element.
        parsePointResult(result)?.let { (x, y) -> cursor.animateTapAtPage(x, y) }
        return result.contains("CLICKED")
    }

    fun typeText(selector: String, text: String): Boolean {
        val script = """
            (function() {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return 'NOT_FOUND';
                el.scrollIntoView({block: 'center', behavior: 'instant'});
                var r = el.getBoundingClientRect();
                var cx = (r.left + r.width / 2 + window.scrollX);
                var cy = (r.top + r.height / 2 + window.scrollY);
                el.focus();
                el.value = ${jsString(text)};
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return 'TYPED@' + Math.round(cx) + ',' + Math.round(cy);
            })()
        """.trimIndent()
        val result = evaluateScriptInternal(script)
        parsePointResult(result)?.let { (x, y) -> cursor.animateTapAtPage(x, y) }
        return result.contains("TYPED")
    }

    fun scroll(x: Int, y: Int): Boolean {
        // Move the cursor to the viewport center and glide to the new offset,
        // like a PC user rolling the wheel with the pointer over the page.
        val viewport = currentViewportPoint()
        val vx = viewport.first
        val vy = viewport.second
        cursor.animateSwipeAtPage(vx, vy, vx, (vy + (y - currentScrollY())).coerceAtLeast(0f), 350)
        val script = "window.scrollTo($x, $y); 'SCROLLED'"
        val result = evaluateScriptInternal(script)
        return result.contains("SCROLLED")
    }

    fun evaluateScript(script: String): JsonElement {
        val result = evaluateScriptInternal(script)
        return try {
            JsonParser.parseString(result)
        } catch (_: Exception) {
            JsonObjectCompat.value(result)
        }
    }

    fun getHtml(): String {
        val result = evaluateScriptInternal("document.documentElement.outerHTML")
        return runCatching { JsonParser.parseString(result).asString }.getOrDefault(result)
    }

    fun goBack() { MAIN.post { webView?.let { if (it.canGoBack()) { it.goBack(); notifyState() } } } }
    fun goForward() { MAIN.post { webView?.let { if (it.canGoForward()) { it.goForward(); notifyState() } } } }
    fun reload() { MAIN.post { webView?.let { it.reload(); notifyState() } } }

    /** Current page URL. Reads the cached snapshot off the main thread, or the WebView directly on the main thread. */
    fun getCurrentUrl(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return webView?.url ?: cachedState?.url ?: ""
        }
        // WebView.getUrl() must be called on the thread that created the WebView (main).
        return cachedState?.url ?: callOnMain { webView?.url ?: "" } ?: ""
    }

    /** Current page title. Reads the cached snapshot off the main thread, or the WebView directly on the main thread. */
    fun getTitle(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return webView?.title ?: cachedState?.title ?: ""
        }
        // WebView.getTitle() must be called on the thread that created the WebView (main).
        return cachedState?.title ?: callOnMain { webView?.title ?: "" } ?: ""
    }

    fun currentState(): State? {
        val wv = webView ?: return null
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // WebView getters must be touched on the main thread; read the
            // cached snapshot instead of blocking on a future here.
            return cachedState
        }
        return State(
            url = wv.url ?: "",
            title = wv.title ?: "",
            progress = wv.progress,
            canGoBack = wv.canGoBack(),
            canGoForward = wv.canGoForward()
        )
    }

    fun addListener(listener: (State) -> Unit) { synchronized(this) { listeners.add(listener) } }
    fun removeListener(listener: (State) -> Unit) { synchronized(this) { listeners.remove(listener) } }

    // --------------------------------------------------------------------
    // Internal helpers
    // --------------------------------------------------------------------

    /** App context captured at first acquire() so navigate() can lazily create the WebView. */
    @Volatile internal var contextOnce: Context? = null

    @Volatile private var cachedState: State? = null

    private fun notifyState() {
        val state = currentState() ?: return
        cachedState = state
        val snapshot = synchronized(this) { listeners.toList() }
        MAIN.post {
            snapshot.forEach { runCatching { it(state) } }
        }
    }

    /**
     * Runs [action] on the main thread and returns its result. Returns null if the
     * action could not be posted or did not complete within the timeout. Used by
     * getters that must touch WebView state (url/title), which is only legal on
     * the thread that created the WebView.
     */
    private fun <T> callOnMain(action: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { action() }.getOrNull()
        }
        val future = CompletableFuture<T>()
        MAIN.post {
            try {
                future.complete(action())
            } catch (_: Exception) {
                future.completeExceptionally(java.util.concurrent.CancellationException("main-thread action failed"))
            }
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun evaluateScriptInternal(script: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "ERROR: JavaScript evaluation must run off the main thread"
        }
        val wv = webView ?: run {
            // Lazily spin up the browser so evaluate-first flows still work.
            contextOnce?.let { acquire(it) } ?: return "ERROR: no webview"
        }
        val future = CompletableFuture<String>()
        MAIN.post {
            try {
                wv.evaluateJavascript(script) { value -> future.complete(value) }
            } catch (e: Exception) {
                future.complete("ERROR: ${e.message}")
            }
        }
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun normalizeWebUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        val candidate = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) raw else "https://$raw"
        val uri = runCatching { candidate.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http") && (scheme != "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    /** Current viewport center in page coordinates (CSS px). */
    private fun currentViewportPoint(): Pair<Float, Float> {
        val result = evaluateScriptInternal(
            "'POINT@' + Math.round(window.scrollX + window.innerWidth/2) + ',' + Math.round(window.scrollY + window.innerHeight/2)"
        )
        return parsePointResult(result) ?: (0f to 0f)
    }

    private fun currentScrollY(): Float {
        // Return the raw number (unquoted) so toFloatOrNull works.
        val result = evaluateScriptInternal("String(Math.round(window.scrollY))")
        return result.trim().removeSuffix("\"").removePrefix("\"").toFloatOrNull() ?: 0f
    }

    /** Parses "ACTION@x,y" style results from click/type/viewport scripts. */
    private fun parsePointResult(result: String): Pair<Float, Float>? {
        val at = result.indexOf('@')
        if (at < 0) return null
        val parts = result.substring(at + 1).trim().trim('"').split(',')
        if (parts.size != 2) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        return x to y
    }

    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    // --------------------------------------------------------------------
    // In-bubble PC-style cursor
    // --------------------------------------------------------------------

    /**
     * Visual cursor for the live browser: a PC-style arrow that glides to each
     * element the agent is about to interact with and ripples on tap. Rendered
     * inside the host bubble by [LiveCursorView]; safe no-op when no host is
     * attached (agent still runs headless-visible elsewhere).
     */
    class Cursor {
        /** Glide the cursor to a page position without a tap animation. */
        @Suppress("unused")
        fun moveToPage(x: Float, y: Float) {
            MAIN.post { hostView?.cursorView?.moveToPage(x, y) }
        }

        /** Show the pointer at the visible page center without blocking navigation. */
        @Suppress("DEPRECATION")
        fun moveToViewportCenter() {
            MAIN.post {
                val wv = webView ?: return@post
                val scale = wv.resources.displayMetrics.density * wv.scale
                if (wv.width > 0 && wv.height > 0 && scale > 0f) {
                    hostView?.cursorView?.moveToPage(
                        (wv.scrollX + wv.width / 2f) / scale,
                        (wv.scrollY + wv.height / 2f) / scale,
                    )
                }
                runCatching {
                    wv.evaluateJavascript(
                        "'POINT@' + Math.round(window.scrollX + window.innerWidth/2) + ',' + Math.round(window.scrollY + window.innerHeight/2)",
                    ) { result ->
                        parsePointResult(result.orEmpty())?.let { (x, y) ->
                            hostView?.cursorView?.moveToPage(x, y)
                        }
                    }
                }
            }
        }

        fun animateTapAtPage(x: Float, y: Float) {
            MAIN.post {
                hostView?.cursorView?.let { cursor ->
                    cursor.moveToPage(x, y)
                    cursor.rippleAtPage(x, y)
                }
            }
        }

        fun animateSwipeAtPage(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
            MAIN.post {
                hostView?.cursorView?.animateSwipePage(x1, y1, x2, y2, durationMs)
            }
        }

        fun hide() {
            MAIN.post { hostView?.cursorView?.hide() }
        }
    }

    private object JsonObjectCompat {
        fun value(result: String): JsonElement = com.google.gson.JsonObject().apply {
            addProperty("value", result)
        }
    }
}
