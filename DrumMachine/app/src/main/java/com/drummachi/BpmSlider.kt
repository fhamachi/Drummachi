package com.drummachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * BpmSlider - slider CONTÍNUO de BPM (v4.4, pedido do Felipe).
 *
 * Sem travas visuais nem paradas discretas: o dedo desliza livremente e o
 * knob pode "passar" das bordas do trilho (o trilho é mais curto que a view;
 * o padding lateral = raio do knob). A faixa é 40..240 BPM — o clamp só
 * acontece no valor final (e no engine). Sem dependência de Material.
 *
 * Implementação própria com gesto horizontal (ACTION_DOWN/MOVE): posição x
 * (float, sub-pixel) -> BPM contínuo; sem snap. Notifica [onBpmChange] a cada
 * mudança durante o arrasto.
 */
class BpmSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var minBpm = 40
    var maxBpm = 240

    var bpm = 120
        set(value) {
            field = value.coerceIn(minBpm, maxBpm)
            invalidate()
        }

    /** Notificado a cada mudança durante o arrasto (main thread). */
    var onBpmChange: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_track)
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_fill)
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_knob)
    }
    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_knob_border)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val knobRadius = dp(9f)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun trackLeft(): Float = paddingLeft.toFloat() + knobRadius
    private fun trackRight(): Float = (width - paddingRight).toFloat() - knobRadius
    private fun trackWidth(): Float = (trackRight() - trackLeft()).coerceAtLeast(1f)

    private fun bpmToX(bpmValue: Int): Float {
        val t = (bpmValue - minBpm).toFloat() / (maxBpm - minBpm).toFloat()
        return trackLeft() + t * trackWidth()
    }

    private fun xToBpm(x: Float): Int {
        val t = ((x - trackLeft()) / trackWidth()).coerceIn(0f, 1f)
        return (minBpm + t * (maxBpm - minBpm)).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = trackLeft()
        val right = trackRight()
        val cy = height / 2f
        // trilho (sem ticks — contínuo)
        canvas.drawLine(left, cy, right, cy, trackPaint)
        // preenchimento até o knob
        val knobX = bpmToX(bpm)
        if (knobX > left) canvas.drawLine(left, cy, knobX, cy, fillPaint)
        // knob
        canvas.drawCircle(knobX, cy, knobRadius, knobPaint)
        canvas.drawCircle(knobX, cy, knobRadius, knobBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromX(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float) {
        val newBpm = xToBpm(x)
        if (newBpm != bpm) {
            bpm = newBpm
            onBpmChange?.invoke(bpm)
        }
    }
}
