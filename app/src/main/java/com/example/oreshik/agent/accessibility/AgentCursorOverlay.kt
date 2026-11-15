package com.example.oreshik.agent.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * A system-overlay cursor that visualizes the agent's computer-use actions in
 * real time — like a PC mouse pointer. The AccessibilityService moves it to
 * each tap/long-press/swipe target and animates presses with a ripple.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (available to enabled accessibility services,
 * no SYSTEM_ALERT_WINDOW prompt needed). Gracefully no-ops if unavailable.
 *
 * Holds only the application context and its own overlay views; views are
 * removed and nulled in [destroy] when the service is destroyed.
 */
@SuppressLint("StaticFieldLeak")
object AgentCursorOverlay {
    private const val CURSOR_SIZE = 48
    private const val CURSOR_GLOW_PADDING = 0
    private const val CURSOR_VIEW_SIZE = CURSOR_SIZE + CURSOR_GLOW_PADDING * 2
    private const val CURSOR_TAP_SIZE = 76
    private const val FADE_MS = 500L
    private const val IDLE_MS = 1500L

    private val MAIN = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var cursorView: ImageView? = null
    private var rippleView: View? = null
    private var added = false
    private var enabled = true

    /** Must be called once from the AccessibilityService (has the overlay permission). */
    fun init(context: Context) {
        synchronized(this) {
            appContext = context.applicationContext
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        ensureAdded()
    }

    /** Toggle overlay visibility (used by diagnostics/tests). */
    @Suppress("unused")
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) hide()
    }

    fun isEnabled(): Boolean = enabled

    /** Show the cursor at a screen position (like moving a mouse). */
    fun moveTo(x: Float, y: Float) {
        if (!enabled) return
        MAIN.post { ensureAddedInternal() }
        MAIN.post { updateCursor(x, y) }
    }

    /** Animate a tap/click ripple at the position, then show the cursor there. */
    fun animateTap(x: Float, y: Float) {
        if (!enabled) return
        moveTo(x, y)
        MAIN.post { runRipple(x, y) }
    }

    /** Animate a swipe path as a moving cursor trail. */
    fun animateSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (!enabled) return
        val steps = 12
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = x1 + ((x2 - x1) * t)
            val y = y1 + ((y2 - y1) * t)
            MAIN.postDelayed(
                {
                    updateCursor(x, y)
                    if (i == steps) runRipple(x2, y2)
                },
                (durationMs * t).toLong(),
            )
        }
    }

    fun hide() {
        MAIN.post {
            cursorView?.visibility = View.GONE
            rippleView?.visibility = View.GONE
        }
    }

    fun destroy() {
        MAIN.post {
            runCatching {
                cursorView?.let { windowManager?.removeView(it) }
                rippleView?.let { windowManager?.removeView(it) }
            }
            cursorView = null
            rippleView = null
            added = false
        }
    }

    private fun ensureAdded() {
        MAIN.post { ensureAddedInternal() }
    }

    @SuppressLint("InflateParams")
    private fun ensureAddedInternal() {
        if (added && (cursorView != null) && (rippleView != null)) return
        val wm = windowManager ?: return
        val ctx = appContext ?: return
        try {
            if (cursorView == null) {
                // PC-style arrow pointer drawn as a vector path
                val cursor = ImageView(ctx)
                cursor.setImageDrawable(ArrowCursorDrawable(ctx.resources.displayMetrics.density))
                cursor.alpha = 0f
                val cursorLp = WindowManager.LayoutParams(
                    (CURSOR_VIEW_SIZE * ctx.resources.displayMetrics.density).toInt(),
                    (CURSOR_VIEW_SIZE * ctx.resources.displayMetrics.density).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                cursorLp.gravity = Gravity.TOP or Gravity.START
                wm.addView(cursor, cursorLp)
                cursorView = cursor
            }
            if (rippleView == null) {
                val ripple = View(ctx)
                val ring = GradientDrawable()
                ring.shape = GradientDrawable.OVAL
                ring.setColor(0x3300B8D4)
                ring.setStroke((3 * ctx.resources.displayMetrics.density).toInt(), 0xFF00B8D4.toInt())
                ripple.background = ring
                ripple.alpha = 0f
                ripple.visibility = View.GONE
                val rippleLp = WindowManager.LayoutParams(
                    (CURSOR_TAP_SIZE * ctx.resources.displayMetrics.density).toInt(),
                    (CURSOR_TAP_SIZE * ctx.resources.displayMetrics.density).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                rippleLp.gravity = Gravity.TOP or Gravity.START
                wm.addView(ripple, rippleLp)
                rippleView = ripple
            }
            added = true
        } catch (_: Exception) {
            // TYPE_ACCESSIBILITY_OVERLAY requires an enabled service; fail silently
            added = false
        }
    }

    private fun updateCursor(x: Float, y: Float) {
        val cursor = cursorView ?: return
        val wm = windowManager ?: return
        try {
            val lp = (cursor.layoutParams as? WindowManager.LayoutParams) ?: return
            // The arrow is centered inside its padded glow view. Align its tip
            // (28% x / 12% y of the arrow) with the real screen coordinate.
            val density = cursor.resources.displayMetrics.density
            val arrowOrigin = CURSOR_GLOW_PADDING * density
            val arrowSize = CURSOR_SIZE * density
            lp.x = (x - arrowOrigin - arrowSize * 0.28f).toInt()
            lp.y = (y - arrowOrigin - arrowSize * 0.12f).toInt()
            wm.updateViewLayout(cursor, lp)
            cursor.animate().cancel()
            cursor.alpha = 1f
            cursor.visibility = View.VISIBLE
            // Idle fade-out
            MAIN.postDelayed(
                {
                    if (cursorView === cursor) {
                        cursor.animate().alpha(0f).setDuration(FADE_MS).start()
                    }
                },
                IDLE_MS,
            )
        } catch (_: Exception) {
            // Window may be gone; ignore
        }
    }

    private fun runRipple(x: Float, y: Float) {
        val ripple = rippleView ?: return
        val wm = windowManager ?: return
        try {
            val lp = (ripple.layoutParams as? WindowManager.LayoutParams) ?: return
            lp.x = (x - CURSOR_TAP_SIZE * 0.5f * ripple.resources.displayMetrics.density).toInt() + CURSOR_TAP_SIZE / 2
            lp.y = (y - CURSOR_TAP_SIZE * 0.5f * ripple.resources.displayMetrics.density).toInt() + CURSOR_TAP_SIZE / 2
            wm.updateViewLayout(ripple, lp)
            ripple.animate().cancel()
            ripple.alpha = 0.85f
            ripple.scaleX = 0.4f
            ripple.scaleY = 0.4f
            ripple.visibility = View.VISIBLE
            ripple.animate()
                .scaleX(1.6f).scaleY(1.6f)
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction { ripple.visibility = View.GONE }
                .start()
        } catch (_: Exception) {
            // Window may be gone; ignore
        }
    }

    /**
     * Flat cyan mouse arrow with a dark outline and white highlight for contrast.
     */
    private class ArrowCursorDrawable(private val density: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5FF.toInt()
            style = Paint.Style.FILL
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF003B49.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            // Draw a 48dp arrow centered in the larger halo view.
            val arrowSize = minOf(CURSOR_SIZE * density, w, h)
            val left = (w - arrowSize) * 0.5f
            val top = (h - arrowSize) * 0.5f
            val path = Path()
            path.moveTo(left + arrowSize * 0.28f, top + arrowSize * 0.12f)
            path.lineTo(left + arrowSize * 0.28f, top + arrowSize * 0.74f)
            path.lineTo(left + arrowSize * 0.42f, top + arrowSize * 0.60f)
            path.lineTo(left + arrowSize * 0.52f, top + arrowSize * 0.82f)
            path.lineTo(left + arrowSize * 0.62f, top + arrowSize * 0.78f)
            path.lineTo(left + arrowSize * 0.53f, top + arrowSize * 0.56f)
            path.lineTo(left + arrowSize * 0.72f, top + arrowSize * 0.56f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
            canvas.drawPath(path, highlight)
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            outline.alpha = alpha
            highlight.alpha = alpha
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            outline.colorFilter = colorFilter
            highlight.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
