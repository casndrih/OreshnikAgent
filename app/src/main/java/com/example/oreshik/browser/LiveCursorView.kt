package com.example.oreshik.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.withTranslation

/**
 * PC-style arrow cursor drawn ON TOP of the live WebView inside its chat
 * bubble (no WindowManager overlay needed — it is a sibling View in the
 * bubble's FrameLayout).
 *
 * The agent's browser automation ([LiveBrowser]) glides this pointer to each
 * element it is about to click/type, ripples on tap, and drags along scroll
 * gestures — so the user watches a PC-style pointer doing the work in real
 * time instead of staring at screenshots.
 *
 * Cursor positions are given in PAGE coordinates (CSS px, matching what the
 * JS automation code reports); they are converted to view coordinates using
 * the sibling WebView's scroll + scale at draw time.
 */
@SuppressLint("DrawAllocation")
class LiveCursorView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cursorSize = 48f * density
    private val idleFadeMs = 5000L

    // Arrow paints (same look as the system-wide AgentCursorOverlay arrow).
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF073B46.toInt() // dark teal for contrast on bright pages
        style = Paint.Style.FILL
    }
    private val arrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        // Canvas scaling also scales stroke width; keep this in normalized
        // path units so the final outline remains about 2dp wide.
        strokeWidth = 2f / 48f
    }

    // Ripple paints.
    private val rippleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3300B8D4
        style = Paint.Style.FILL
    }
    private val rippleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B8D4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    // Arrow path in normalized 0..1 space (classic mouse arrow, 24x24 box).
    private val arrowPath = Path().apply {
        moveTo(0.28f, 0.12f)
        lineTo(0.28f, 0.74f)
        lineTo(0.42f, 0.60f)
        lineTo(0.52f, 0.82f)
        lineTo(0.62f, 0.78f)
        lineTo(0.53f, 0.56f)
        lineTo(0.72f, 0.56f)
        close()
    }

    // Arrow state (page coordinates).
    private var pageX = 0f
    private var pageY = 0f
    private var hasPosition = false

    // Ripple state.
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleProgress = 0f
    private var rippleActive = false

    // Glide (animated move) state.
    private var glideFromX = 0f
    private var glideFromY = 0f
    private var glideToX = 0f
    private var glideToY = 0f
    private var glideStart = 0L
    private var glideDuration = 0L
    private var gliding = false
    private var glideOnArrive: (() -> Unit)? = null

    private var lastActivityAt = 0L
    private var fadeAlpha = 1f

    private val ticker = Runnable { onTick() }

    // ------------------------------------------------------------------
    // Public API (page coordinates)
    // ------------------------------------------------------------------

    /** Instantly place the arrow at the given page coordinates. */
    fun moveToPage(x: Float, y: Float) {
        pageX = x
        pageY = y
        hasPosition = true
        gliding = false
        glideOnArrive = null
        lastActivityAt = SystemClock.uptimeMillis()
        fadeAlpha = 1f
        invalidate()
        startTicking()
    }

    /** Glide the arrow to (x, y) then play a tap ripple at the target. */
    @Suppress("unused")
    fun tapAtPage(x: Float, y: Float, glideMs: Long = 260L) {
        glideTo(x, y, glideMs) { rippleAtPage(x, y) }
    }

    /** Play a tap ripple at the given page point. */
    fun rippleAtPage(x: Float, y: Float) {
        rippleX = x
        rippleY = y
        rippleProgress = 0f
        rippleActive = true
        lastActivityAt = SystemClock.uptimeMillis()
        fadeAlpha = 1f
        startTicking()
    }

    /** Drag the cursor along a scroll/swipe path (page coordinates). */
    fun animateSwipePage(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        moveToPage(x1, y1)
        glideTo(x2, y2, durationMs.coerceIn(120L, 2000L))
    }

    fun hide() {
        hasPosition = false
        rippleActive = false
        gliding = false
        glideOnArrive = null
        fadeAlpha = 0f
        invalidate()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPosition && !rippleActive) return

        val wv = findWebView(parent as? ViewGroup) ?: return
        @Suppress("DEPRECATION")
        val scale = wv.resources.displayMetrics.density * wv.scale
        val viewX = (pageX * scale) - wv.scrollX
        val viewY = (pageY * scale) - wv.scrollY

        val idle = SystemClock.uptimeMillis() - lastActivityAt
        fadeAlpha = if (idle > idleFadeMs) {
            (1f - (idle - idleFadeMs) / 500f).coerceIn(0f, 1f)
        } else 1f

        if (rippleActive) {
            drawRipple(
                canvas,
                (rippleX * scale - wv.scrollX),
                (rippleY * scale - wv.scrollY),
            )
        }
        if (hasPosition && fadeAlpha > 0f) {
            drawArrow(canvas, viewX, viewY)
        }
        if (gliding || rippleActive || fadeAlpha in 0f..1f && hasPosition && fadeAlpha < 1f) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float) {
        // Align the arrow TIP (path point 0.28, 0.12) with the target point.
        canvas.withTranslation(x - 0.28f * cursorSize, y - 0.12f * cursorSize) {
            canvas.scale(cursorSize, cursorSize)
            arrowFill.alpha = (fadeAlpha * 255).toInt()
            arrowOutline.alpha = (fadeAlpha * 255).toInt()
            canvas.drawPath(arrowPath, arrowFill)
            canvas.drawPath(arrowPath, arrowOutline)
        }
    }

    private fun drawRipple(canvas: Canvas, x: Float, y: Float) {
        val radius = cursorSize * (0.4f + rippleProgress * 1.2f)
        rippleFill.alpha = ((1f - rippleProgress) * 0.85f * 255).toInt()
        rippleStroke.alpha = ((1f - rippleProgress) * 255).toInt()
        canvas.drawCircle(x, y, radius, rippleFill)
        canvas.drawCircle(x, y, radius, rippleStroke)
    }

    // ------------------------------------------------------------------
    // Animation loop
    // ------------------------------------------------------------------

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        if (gliding) {
            val t = if (glideDuration <= 0L) 1f
            else ((now - glideStart) / glideDuration.toFloat()).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) // ease-out
            pageX = glideFromX + (glideToX - glideFromX) * eased
            pageY = glideFromY + (glideToY - glideFromY) * eased
            if (t >= 1f) {
                gliding = false
                val arrive = glideOnArrive
                glideOnArrive = null
                arrive?.invoke()
            }
        }
        if (rippleActive) {
            rippleProgress += 0.06f
            if (rippleProgress >= 1f) rippleActive = false
        }
        invalidate()
        if (gliding || rippleActive) {
            // Keep animating: re-post the ticker every frame (~16ms).
            postDelayed(ticker, 16)
        } else {
            // Keep ticking while the idle fade is in progress.
            val idle = now - lastActivityAt
            if (hasPosition && idle > idleFadeMs && idle < idleFadeMs + 600L) {
                postDelayed(ticker, 16)
            }
        }
    }

    private fun glideTo(x: Float, y: Float, durationMs: Long, onArrive: (() -> Unit)? = null) {
        if (!hasPosition) {
            // First appearance: don't glide from (0,0); just appear there.
            pageX = x
            pageY = y
            hasPosition = true
        }
        glideFromX = pageX
        glideFromY = pageY
        glideToX = x
        glideToY = y
        glideStart = SystemClock.uptimeMillis()
        glideDuration = durationMs
        glideOnArrive = onArrive
        gliding = true
        lastActivityAt = glideStart
        fadeAlpha = 1f
        startTicking()
    }

    /** Runs [onTick] every ~16ms until glide/ripple/fade animations settle. */
    private fun startTicking() {
        removeCallbacks(ticker)
        post(ticker)
    }

    /** Depth-first search for the sibling WebView inside the bubble. */
    private fun findWebView(root: ViewGroup?): android.webkit.WebView? {
        var current = root
        var depth = 0
        while (current != null && depth < 8) {
            for (i in 0 until current.childCount) {
                val child = current.getChildAt(i)
                if (child is android.webkit.WebView) {
                    return child
                }
                if (child is ViewGroup) {
                    findWebView(child)?.let { found -> return found }
                }
            }
            current = current.parent as? ViewGroup
            depth++
        }
        return null
    }
}
