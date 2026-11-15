package com.example.oreshik.browser

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.example.oreshik.R

/**
 * A chat-embeddable container that hosts the single live [LiveBrowser] WebView
 * so the user can watch the agent's browsing session in real time, navigate
 * with back/forward/reload arrows, and see the URL + progress.
 *
 * A [LiveCursorView] is stacked on top of the WebView and renders the PC-style
 * arrow cursor the agent animates while browsing (glides, ripples, scroll
 * drags), so the user sees the agent's pointer working inside the page.
 *
 * Exactly one instance shows the live WebView at a time (the WebView is moved
 * between hosts on attach). Other instances show the URL bar with a "Reattach"
 * button so the user can bring the live view to that bubble.
 */
class LiveBrowserOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val container: FrameLayout
    private val urlBar: TextView
    private val progressBar: ProgressBar
    private val btnBack: ImageButton
    private val btnForward: ImageButton
    private val btnReload: ImageButton
    private val btnReattach: TextView

    /** PC-style cursor drawn over the WebView while the agent browses. */
    internal val cursorView: LiveCursorView = LiveCursorView(context)

    private var listener: ((LiveBrowser.State) -> Unit)? = null

    init {
        inflate(context, R.layout.view_live_browser, this)
        container = findViewById(R.id.liveBrowserContainer)
        urlBar = findViewById(R.id.tvLiveUrl)
        progressBar = findViewById(R.id.liveProgress)
        btnBack = findViewById(R.id.btnLiveBack)
        btnForward = findViewById(R.id.btnLiveForward)
        btnReload = findViewById(R.id.btnLiveReload)
        btnReattach = findViewById(R.id.btnLiveReattach)

        btnBack.setOnClickListener { LiveBrowser.goBack() }
        btnForward.setOnClickListener { LiveBrowser.goForward() }
        btnReload.setOnClickListener { LiveBrowser.reload() }
        btnReattach.setOnClickListener { LiveBrowser.attach(this) }

        // Cursor floats above the WebView inside the container.
        cursorView.visibility = VISIBLE
        container.addView(
            cursorView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        listener = { state -> onStateChanged(state) }
        LiveBrowser.addListener(listener!!)

        // Attach to the live browser when this view enters the layout.
        // This makes the most recently visible bubble the live one.
        post { LiveBrowser.attach(this) }
    }

    private fun onStateChanged(state: LiveBrowser.State) {
        urlBar.text = state.url.ifBlank { "about:" + "blank" }
        progressBar.progress = state.progress
        progressBar.visibility = if (state.progress < 100) VISIBLE else GONE
        btnBack.isEnabled = state.canGoBack
        btnForward.isEnabled = state.canGoForward
        btnBack.alpha = if (state.canGoBack) 1f else 0.4f
        btnForward.alpha = if (state.canGoForward) 1f else 0.4f
    }

    internal fun installWebView(webView: android.webkit.WebView) {
        container.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.visibility = VISIBLE
        container.addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // Re-stack the cursor above the freshly attached WebView.
        container.addView(
            cursorView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        btnReattach.visibility = GONE
    }

    internal fun removeWebView(webView: android.webkit.WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.removeView(cursorView) // avoid leaking the cursor into a detached container
        btnReattach.visibility = VISIBLE
    }

    override fun onDetachedFromWindow() {
        listener?.let { LiveBrowser.removeListener(it) }
        LiveBrowser.detach(this)
        super.onDetachedFromWindow()
    }
}
