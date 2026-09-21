package com.example.volumeleveler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LevelerService : Service() {

    private lateinit var am: AudioManager
    private val generation = AtomicInteger(0)
    private var worker: Thread? = null
    private var registered = false
    private var lastSig = ""
    private val main = Handler(Looper.getMainLooper())
    // Volume ceiling: starts at the volume when leveling began, then follows any manual change.
    @Volatile private var baseVol = -1
    private var knownVol = -1        // volume as of our last look
    private var adjusted = false     // we just changed it ourselves
    private var settleUntil = 0L     // wait for our own change to show up before judging
    private var lastSync = 0L

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged()
    }

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!registered) {
            lastSig = signature()
            am.registerAudioDeviceCallback(deviceCb, Handler(Looper.getMainLooper()))
            registered = true
            baseVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            knownVol = baseVol
        }
        State.running = true
        startCapture() // also used as "reload settings" when the UI pings the service
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        worker?.join(1500)
        if (registered) am.unregisterAudioDeviceCallback(deviceCb)
        registered = false
        disableSco()
        State.running = false
        State.calStart = 0L
        State.status = "Stopped"
        State.levelDb = Float.NaN
        super.onDestroy()
    }

    // ---- device hot-plug ----

    private fun signature() = MicSelector.list(this).joinToString(",") { MicSelector.key(it) }

    private fun onDevicesChanged() {
        val sig = signature()
        if (sig != lastSig) {
            lastSig = sig
            startCapture()
        }
    }

    // ---- foreground notification ----

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Volume Leveler", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Volume Leveler running")
            .setContentText("Listening to the room and adjusting volume")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---- capture + leveling ----

    private fun startCapture() {
        generation.incrementAndGet()
        worker?.join(1500)
        val gen = generation.get()
        val t = Thread({ captureLoop(gen) }, "leveler-capture")
        worker = t
        t.start()
    }

    private fun captureLoop(gen: Int) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            State.status = "Mic init failed"
            return
        }
        var rec: AudioRecord? = null
        try {
            val dev = MicSelector.pick(this)
            if (dev != null && dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) enableSco()

            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                State.status = "Could not open mic (permission or in use)"
                return
            }
            if (dev != null) rec.setPreferredDevice(dev)
            rec.startRecording()
            State.micName = dev?.let { MicSelector.label(it) } ?: "System default"
            State.status = "Listening"

            val buf = ShortArray(rate / 20) // 50 ms chunks
            var avg = Float.NaN   // fast: decides WHEN it is loud
            var slow = Float.NaN  // steadier: decides HOW MUCH to cut
            var dropWindowStart = 0L
            var dropInWindow = 0
            var lastAdjust = 0L
            var silentSince = 0L
            var calSum = 0.0
            var calCount = 0
            var calSeen = 0L
            var calMeasuring = false

            while (gen == generation.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    State.status = "Mic read error ($n)"
                    break
                }
                if (n == 0) continue

                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i] / 32768.0
                    sum += s * s
                }
                val db = (20.0 * log10(max(sqrt(sum / n), 1e-7))).toFloat()
                // Fast attack (loud sounds register within ~100 ms), slow release (~1.5 s).
                avg = if (avg.isNaN()) db else avg + (if (db > avg) ATTACK else RELEASE) * (db - avg)
                slow = if (slow.isNaN()) db else slow + (if (db > slow) SLOW_ATTACK else SLOW_RELEASE) * (db - slow)
                State.levelDb = avg

                val now = SystemClock.elapsedRealtime()
                syncVolume(now)
                val silent = db < -85f

                // Timed calibration: measure the room while the show plays, then set the target.
                val cs = State.calStart
                if (cs != 0L) {
                    if (cs != calSeen) { calSeen = cs; calSum = 0.0; calCount = 0; calMeasuring = false }
                    if (now < cs) {
                        State.status = "Calibrating: measuring starts in ${(cs - now) / 1000 + 1}s. Go back to your show."
                    } else {
                        if (!calMeasuring) {
                            calMeasuring = true
                            toast("Measuring room loudness for 20 s…")
                        }
                        State.status = "Calibrating: measuring room loudness…"
                        if (!silent) { calSum += avg; calCount++ }
                        if (now >= cs + CAL_MEASURE_MS) finishCalibration(calSum, calCount)
                    }
                }
                if (silent) {
                    if (silentSince == 0L) silentSince = now
                    if (now - silentSince > 10_000) {
                        State.status = "Mic is silent (may be reserved by assistant/another app)"
                    }
                    continue
                }
                silentSince = 0L
                if (State.status.startsWith("Mic is silent")) State.status = "Listening"
                if (State.calStart != 0L) continue // volume control is paused while calibrating

                val target = Prefs.target(this)
                val tol = Prefs.tolerance(this)
                val loudBy = avg - (target + tol)
                if (now - dropWindowStart > DROP_WINDOW_MS) { dropWindowStart = now; dropInWindow = 0 }
                val dropRoom = MAX_DROP_LEVELS - dropInWindow
                when {
                    // Loud: react right away, sized to how far over we are (at least 3 levels).
                    loudBy > 0 && dropRoom > 0 && now - lastAdjust >= LOWER_COOLDOWN -> {
                        // Size the cut from the steadier level, so a one-off spike in an
                        // explosion doesn't cause a huge drop; and cap the total cut per window.
                        val slowBy = slow - (target + tol)
                        val levels = minOf(
                            ceil(slowBy / DB_PER_LEVEL).toInt().coerceIn(LOWER_MIN_LEVELS, LOWER_MAX_LEVELS),
                            dropRoom
                        )
                        val moved = step(-1, levels)
                        dropInWindow += moved
                        // The mic can't hear the change yet, so assume it will and
                        // don't keep lowering for the same loud moment.
                        avg -= moved * DB_PER_LEVEL
                        slow -= moved * DB_PER_LEVEL
                        lastAdjust = now
                    }
                    // Quiet: come back up gently. Only if there is plausibly content
                    // playing; a very quiet room (paused video) must not ramp volume up.
                    avg < target - tol && avg > target - QUIET_FLOOR_DB && now - lastAdjust >= RAISE_COOLDOWN -> {
                        // The quieter the scene, the more levels it gets back (1-3).
                        val quietBy = (target - tol) - avg
                        val levels = if (quietBy >= 10f) 3 else if (quietBy >= 5f) 2 else 1
                        val moved = step(+1, levels)
                        avg += moved * DB_PER_LEVEL
                        slow += moved * DB_PER_LEVEL
                        lastAdjust = now
                    }
                }
            }
        } catch (e: SecurityException) {
            State.status = "Microphone permission denied"
        } catch (e: Exception) {
            State.status = "Error: ${e.javaClass.simpleName}"
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
        }
    }

    /** Highest volume level the app may raise to: your volume + boost, and never above Max volume. */
    private fun ceilingLevels(): Int {
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return minOf(floor(maxVol * Prefs.maxPct(this) / 100.0).toInt(), baseVol + Prefs.boost(this))
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun finishCalibration(sum: Double, count: Int) {
        State.calStart = 0L
        if (count < 100) { // under ~5 s of actual sound
            State.status = "Calibration failed: no sound heard"
            toast("Calibration failed: no sound heard. Try again with the show playing.")
            return
        }
        Prefs.setTarget(this, (sum / count).toFloat())
        State.status = "Listening"
        toast("Target set to ${(Prefs.target(this) + 100f).roundToInt()}%")
    }

    private fun step(dir: Int, count: Int): Int {
        if (am.isVolumeFixed) {
            State.status = "Volume is fixed on this output (cannot adjust)"
            return 0
        }
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val lo = ceil(maxVol * Prefs.minPct(this) / 100.0).toInt()
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val hi = ceilingLevels()

        // Never move against the requested direction, and never past the limits.
        val moves = if (dir > 0) minOf(cur + count, hi) - cur else cur - maxOf(cur - count, lo)
        if (moves <= 0) return 0

        // adjustStreamVolume follows the same path as remote volume keys,
        // which is what HDMI-CEC / ARC output usually needs.
        repeat(moves) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
        }
        adjusted = true
        settleUntil = SystemClock.elapsedRealtime() + 1500
        return moves
    }

    /**
     * Notices volume changes we didn't make (remote volume keys) and makes the new
     * level the ceiling, whether the user turned it up or down.
     */
    private fun syncVolume(now: Long) {
        if (now < settleUntil || now - lastSync < 250) return
        lastSync = now
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (adjusted) {
            adjusted = false // just learn where our own change landed
        } else if (cur != knownVol) {
            baseVol = cur
        }
        knownVol = cur
        State.baseVol = baseVol
        State.ceiling = ceilingLevels()
    }

    // ---- Bluetooth mic routing (best effort) ----

    @Suppress("DEPRECATION")
    private fun enableSco() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun disableSco() {
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
        } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL = "leveler"
        private const val LOWER_MIN_LEVELS = 3
        private const val LOWER_MAX_LEVELS = 6
        private const val MAX_DROP_LEVELS = 8      // most levels cut within one window (~9 dB)
        private const val DROP_WINDOW_MS = 2500L
        private const val SLOW_ATTACK = 0.12f      // per 50 ms chunk (~0.4 s)
        private const val SLOW_RELEASE = 0.03f
        private const val QUIET_FLOOR_DB = 35f // below this (relative to target) we assume nothing is playing
        private const val DB_PER_LEVEL = 1.1f   // roughly what one volume level changes, in dB
        private const val ATTACK = 0.5f         // per 50 ms chunk
        private const val RELEASE = 0.03f
        private const val LOWER_COOLDOWN = 600L
        private const val CAL_MEASURE_MS = 20_000L
        private const val RAISE_COOLDOWN = 1200L
    }
}
