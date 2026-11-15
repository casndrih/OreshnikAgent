package com.example.oreshik

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.oreshik.ai.ModelTester
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

/**
 * Small status dot with an animated "ripple of wave" ring around it, used next to
 * model names in the model-picker bottom sheets.
 *
 * States map to [ModelTester.ModelStatus]:
 *  - ONLINE  → green dot + green ripple   (probe responded)
 *  - OFFLINE → red dot + red ripple       (probe failed)
 *  - TIMEOUT → orange dot + orange ripple (timeout / rate-limit / transient)
 *  - PROBING → gray pulsing dot + gray ripple (still being checked)
 *
 * The ripple is an expanding, fading stroked circle looped with an infinite
 * ValueAnimator; the animator only runs while the view is attached to a window.
 */
class ModelStatusDot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var status: ModelTester.ModelStatus = ModelTester.ModelStatus.PROBING
        private set

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 0f..1f phase of the ripple loop, driven by the animator. */
    private var ripplePhase = 0f

    /** Smooth 0f..1f pulsing factor for the PROBING state. */
    private var pulse = 1f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = RIPPLE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            ripplePhase = t
            // Smooth cosine pulse (0..1) for the probing core.
            pulse = 0.5f + 0.5f * cos(2f * PI.toFloat() * t)
            invalidate()
        }
    }

    init {
        // Give the ripple a little breathing room even at the intrinsic size.
        minimumWidth = dip(MIN_SIZE_DP)
        minimumHeight = dip(MIN_SIZE_DP)
        setContentDescription(statusText(status))
        if (isAttachedToWindow) animator.start()
    }

    fun setStatus(newStatus: ModelTester.ModelStatus) {
        if (status == newStatus) return
        status = newStatus
        applyColors()
        setContentDescription(statusText(newStatus))
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyColors()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val coreR = maxR * CORE_FRACTION
        val color = colorFor(status)

        // Core dot (pulses while probing so "checking" reads as live).
        corePaint.color = color
        corePaint.alpha = if (status == ModelTester.ModelStatus.PROBING) ((0.45f + 0.55f * pulse) * 255f).toInt() else 255
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // Ripple ring: expands from the core edge to the view bounds while fading out.
        ripplePaint.color = color
        val ringR = coreR + (maxR - coreR) * ripplePhase
        ripplePaint.strokeWidth = (maxR - coreR) * RIPPLE_STROKE_FRACTION
        ripplePaint.alpha = (RIPPLE_START_ALPHA * (1f - ripplePhase)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, ringR, ripplePaint)

        // A second, softer trailing wave makes it read as a continuous "wave".
        val phase2 = (ripplePhase + 0.5f) % 1f
        val ringR2 = coreR + (maxR - coreR) * phase2
        ripplePaint.strokeWidth = (maxR - coreR) * RIPPLE_STROKE_FRACTION
        ripplePaint.alpha = (RIPPLE_START_ALPHA * 0.5f * (1f - phase2)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, ringR2, ripplePaint)
    }

    private fun applyColors() {
        // Re-resolve on theme changes (attach time) — colors come from resources
        // so night mode automatically gets the brighter variants.
        invalidate()
    }

    private fun colorFor(s: ModelTester.ModelStatus): Int = when (s) {
        ModelTester.ModelStatus.ONLINE -> ContextCompat.getColor(context, R.color.status_online)
        ModelTester.ModelStatus.OFFLINE -> ContextCompat.getColor(context, R.color.status_offline)
        ModelTester.ModelStatus.TIMEOUT -> ContextCompat.getColor(context, R.color.status_timeout)
        ModelTester.ModelStatus.PROBING -> ContextCompat.getColor(context, R.color.status_probing)
    }

    private fun statusText(s: ModelTester.ModelStatus): String = when (s) {
        ModelTester.ModelStatus.ONLINE -> context.getString(R.string.model_status_online)
        ModelTester.ModelStatus.OFFLINE -> context.getString(R.string.model_status_offline)
        ModelTester.ModelStatus.TIMEOUT -> context.getString(R.string.model_status_timeout)
        ModelTester.ModelStatus.PROBING -> context.getString(R.string.model_status_probing)
    }

    private fun dip(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val RIPPLE_PERIOD_MS = 1800L
        const val CORE_FRACTION = 0.28f
        const val RIPPLE_STROKE_FRACTION = 0.30f
        const val RIPPLE_START_ALPHA = 140
        const val MIN_SIZE_DP = 18
    }
}
