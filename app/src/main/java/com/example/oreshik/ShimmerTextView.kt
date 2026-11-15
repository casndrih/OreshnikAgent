package com.example.oreshik

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/** A lightweight text-only shimmer that runs while the view is attached. */
class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val shimmerMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerOffset = 0f
    private var shimmerEnabled = false
    private var baseColor = currentTextColor
    private var highlightColor = currentTextColor

    fun setShimmerEnabled(enabled: Boolean, base: Int = baseColor, highlight: Int = highlightColor) {
        shimmerEnabled = enabled
        baseColor = base
        highlightColor = highlight
        if (enabled) {
            super.setTextColor(base)
            startShimmer()
        } else {
            stopShimmer()
            super.setTextColor(base)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopAnimator()
        shimmerShader = null
        startShimmer()
    }

    private fun startShimmer() {
        if (!shimmerEnabled || !isAttachedToWindow || width <= 0 || shimmerAnimator != null) return
        shimmerAnimator = ValueAnimator.ofFloat(-width * 1.5f, width * 1.5f).apply {
            duration = 1450L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                shimmerOffset = animation.animatedValue as Float
                updateShader()
            }
            start()
        }
    }

    private fun stopAnimator() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun stopShimmer() {
        stopAnimator()
        shimmerShader = null
        paint.shader = null
        invalidate()
    }

    private fun updateShader() {
        if (!shimmerEnabled || !isAttachedToWindow || width <= 0) return
        val span = width * 0.38f
        val shader = shimmerShader ?: LinearGradient(
            -span,
            0f,
            span,
            0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        ).also { shimmerShader = it }
        shimmerMatrix.setTranslate(shimmerOffset, 0f)
        shader.setLocalMatrix(shimmerMatrix)
        paint.shader = shader
        invalidate()
    }
}
