package com.example.volumeleveler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Animated audio-device style waveform: a few layered sine waves that scroll sideways,
 * taper to nothing at the left/right edges, and swell/shrink with the live room
 * loudness (State.levelDb). Purely decorative; not focusable.
 */
class WaveBackgroundView(context: Context) : View(context) {

    private class Layer(
        val color: Int,
        val alpha: Int,      // 0-255 - keep low so text on top stays readable
        val widthDp: Float,
        val cycles: Float,   // full sine cycles across the screen width
        val speed: Float,    // radians per second (sign = direction)
        val ampScale: Float, // relative height
        val phase0: Float
    )

    private val layers = listOf(
        Layer(Color.parseColor("#00BFFF"), 90, 2.5f, 1.6f, 1.1f, 1.00f, 0.0f),
        Layer(Color.parseColor("#00BFFF"), 60, 2.0f, 2.4f, -1.5f, 0.75f, 1.3f),
        Layer(Color.parseColor("#1E90FF"), 50, 2.0f, 3.3f, 1.9f, 0.55f, 2.6f),
        Layer(Color.parseColor("#7FDBFF"), 35, 1.5f, 4.6f, -2.4f, 0.40f, 4.0f)
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val density = resources.displayMetrics.density

    private var last = 0L
    private var time = 0f
    private var level = 0.25f // smoothed 0..1

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        time += dt

        // Room loudness (0-100% scale, 1% = 1 dB) -> 0..1, where ~50% and up is "full".
        val db = State.levelDb
        val target = if (db.isNaN()) 0.25f else (((db + 100f) / 100f) / 0.5f).coerceIn(0f, 1f)
        level += (target - level) * (1f - exp(-3f * dt)) // ease toward target

        val cy = h / 2f
        val maxAmp = h * 0.30f * (0.30f + 0.70f * level)
        val step = 6f * density

        for (l in layers) {
            paint.color = l.color
            paint.alpha = l.alpha
            paint.strokeWidth = l.widthDp * density
            val phase = l.phase0 + time * l.speed
            // Slow "breathing" so the wave never looks mechanical.
            val breathe = 1f + 0.25f * sin(time * 0.7f + l.phase0)

            path.reset()
            var x = 0f
            var first = true
            while (x <= w) {
                val t = x / w
                val env = sin(PI.toFloat() * t).let { it * it } // taper at both edges
                val y = cy + sin(2f * PI.toFloat() * l.cycles * t + phase) *
                    maxAmp * l.ampScale * env * breathe
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += step
            }
            canvas.drawPath(path, paint)
        }

        postInvalidateOnAnimation()
    }
}
