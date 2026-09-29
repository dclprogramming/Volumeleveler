package com.example.volumeleveler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Decorative audio-style animated backgrounds, all driven by the live room loudness
 * (State.levelDb). MODE_STATIC draws nothing (the plain solid background behind this
 * view shows through) and does not animate at all. Not focusable.
 */
class WaveBackgroundView(context: Context) : View(context) {

    companion object {
        const val MODE_STATIC = 0
        const val MODE_WAVES = 1
        const val MODE_EQ = 2
        const val MODE_TRACE = 3
        const val MODE_RINGS = 4
        val NAMES = listOf("Static", "Waves", "Equalizer", "Loudness trace", "Pulse rings")
        private val ACCENT = Color.parseColor("#00BFFF")
    }

    var mode: Int = MODE_STATIC
        set(v) { field = v; last = 0L; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private var last = 0L
    private var time = 0f
    private var level = 0.25f // smoothed room loudness, 0..1

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == MODE_STATIC) return // nothing to draw, and no animation loop
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        time += dt

        // Room loudness (0-100% scale, 1% = 1 dB) -> 0..1, where ~50% and up is "full".
        // Quick attack, slower release, like a real meter.
        val db = State.levelDb
        val target = if (db.isNaN()) 0.25f else (((db + 100f) / 100f) / 0.5f).coerceIn(0f, 1f)
        val rate = if (target > level) 8f else 2.5f
        level += (target - level) * (1f - exp(-rate * dt))

        when (mode) {
            MODE_WAVES -> drawWaves(canvas, w, h)
            MODE_EQ -> drawEq(canvas, w, h, dt)
            MODE_TRACE -> drawTrace(canvas, w, h, dt)
            MODE_RINGS -> drawRings(canvas, w, h, dt)
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ Waves

    private class Layer(
        val color: Int, val alpha: Int, val widthDp: Float,
        val cycles: Float, val speed: Float, val ampScale: Float, val phase0: Float
    )

    private val layers = listOf(
        Layer(Color.parseColor("#00BFFF"), 90, 2.5f, 1.6f, 1.1f, 1.00f, 0.0f),
        Layer(Color.parseColor("#00BFFF"), 60, 2.0f, 2.4f, -1.5f, 0.75f, 1.3f),
        Layer(Color.parseColor("#1E90FF"), 50, 2.0f, 3.3f, 1.9f, 0.55f, 2.6f),
        Layer(Color.parseColor("#7FDBFF"), 35, 1.5f, 4.6f, -2.4f, 0.40f, 4.0f)
    )

    private fun drawWaves(c: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        val cy = h / 2f
        val maxAmp = h * 0.30f * (0.30f + 0.70f * level)
        val step = 6f * density
        for (l in layers) {
            paint.color = l.color
            paint.alpha = l.alpha
            paint.strokeWidth = l.widthDp * density
            val phase = l.phase0 + time * l.speed
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
            c.drawPath(path, paint)
        }
    }

    // -------------------------------------------------------------- Equalizer

    private val BARS = 40
    private val barH = FloatArray(BARS)
    private val peak = FloatArray(BARS)

    /** LED-style segmented bars with falling peak caps. Overall height follows the room
     *  loudness; the per-bar motion is synthetic (the app only has one loudness value,
     *  not a real frequency spectrum). */
    private fun drawEq(c: Canvas, w: Float, h: Float, dt: Float) {
        paint.style = Paint.Style.FILL
        paint.color = ACCENT
        val cell = w / BARS
        val barW = cell * 0.62f
        val seg = 9f * density
        val gap = 3f * density
        val maxH = h * 0.62f
        for (i in 0 until BARS) {
            val f = i / (BARS - 1f)
            val shape = 1f - 0.5f * f // a little heavier on the "bass" (left) side
            val a = sin(time * (2.1f + i * 0.37f) + i * 1.7f)
            val b = sin(time * (3.3f + i * 0.21f) + i * 0.9f)
            val wob = 0.5f + 0.25f * (a + b) // 0..1
            val tgt = (level * shape * (0.25f + 0.75f * wob)).coerceIn(0f, 1f)
            val k = if (tgt > barH[i]) 18f else 5f
            barH[i] += (tgt - barH[i]) * (1f - exp(-k * dt))
            peak[i] = if (barH[i] > peak[i]) barH[i] else max(barH[i], peak[i] - 0.3f * dt)

            val x = i * cell + (cell - barW) / 2f
            val bh = barH[i] * maxH
            paint.alpha = 85
            var y = h
            while (y - seg >= h - bh) {
                rect.set(x, y - seg, x + barW, y)
                c.drawRect(rect, paint)
                y -= seg + gap
            }
            if (peak[i] > 0.02f) {
                val py = h - peak[i] * maxH
                paint.alpha = 140
                rect.set(x, py - seg * 0.4f, x + barW, py)
                c.drawRect(rect, paint)
            }
        }
    }

    // ---------------------------------------------------------- Loudness trace

    private val N = 200
    private val SAMPLE = 0.05f // seconds per bar -> 10 second window
    private val hist = FloatArray(N)
    private var histAcc = 0f

    /** Mirrored recorder-style waveform: the actual room loudness over the last ~10 s,
     *  scrolling right-to-left with the newest reading on the right edge. */
    private fun drawTrace(c: Canvas, w: Float, h: Float, dt: Float) {
        histAcc += dt
        while (histAcc >= SAMPLE) {
            System.arraycopy(hist, 1, hist, 0, N - 1)
            hist[N - 1] = (level * (0.85f + 0.3f * Random.nextFloat())).coerceIn(0f, 1f)
            histAcc -= SAMPLE
        }
        val frac = histAcc / SAMPLE
        val cell = w / (N - 1)
        val cy = h / 2f
        paint.style = Paint.Style.STROKE
        paint.color = ACCENT
        paint.strokeWidth = cell * 0.6f
        for (i in 0 until N) {
            val x = (i - frac) * cell
            val half = max(2f * density, hist[i] * h * 0.42f)
            paint.alpha = 20 + 110 * i / (N - 1) // older = fainter
            c.drawLine(x, cy - half, x, cy + half, paint)
        }
    }

    // ------------------------------------------------------------- Pulse rings

    private var ringPhase = 0f

    /** Concentric rings radiating out from the center; louder room = faster, brighter. */
    private fun drawRings(c: Canvas, w: Float, h: Float, dt: Float) {
        ringPhase = (ringPhase + dt * (0.10f + 0.30f * level)) % 1f
        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) * 0.75f
        paint.color = ACCENT
        paint.style = Paint.Style.STROKE
        for (i in 0 until 5) {
            val p = (ringPhase + i / 5f) % 1f
            val fade = 1f - p
            paint.strokeWidth = (1.5f + 3f * fade) * density
            paint.alpha = (fade * fade * (50f + 130f * level)).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, maxR * (0.08f + 0.92f * p), paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 60
        c.drawCircle(cx, cy, min(w, h) * (0.05f + 0.07f * level), paint)
    }
}
