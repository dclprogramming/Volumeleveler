package com.example.volumeleveler

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight mic listener used only to show live numbers (Room loudness, and the
 * auto Target/Silence floor values) while the app is open but not actually leveling.
 * Never adjusts volume. Stops itself the instant real leveling starts, so only one
 * of this or LevelerService ever holds the mic at a time.
 */
object LevelPreview {
    @Volatile private var thread: Thread? = null
    @Volatile private var stop = false

    fun start(ctx: Context) {
        if (thread != null || State.running) return
        stop = false
        val t = Thread({ run(ctx.applicationContext) }, "leveler-preview")
        thread = t
        t.start()
    }

    fun stop() {
        stop = true
        thread?.join(500)
        thread = null
    }

    private fun run(ctx: Context) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        var rec: AudioRecord? = null
        try {
            val dev = MicSelector.pick(ctx)
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) return
            if (dev != null) rec.setPreferredDevice(dev)
            rec.startRecording()
            if (State.micName == "-") State.micName = dev?.let { MicSelector.label(it) } ?: "System default"

            val buf = ShortArray(rate / 20) // 50 ms chunks
            var avg = Float.NaN
            while (!stop && !State.running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i] / 32768.0
                    sum += s * s
                }
                val db = (20.0 * log10(max(sqrt(sum / n), 1e-7))).toFloat()
                avg = if (avg.isNaN()) db else avg + (if (db > avg) 0.5f else 0.03f) * (db - avg)
                State.levelDb = avg
            }
        } catch (_: Exception) {
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
        }
    }
}
