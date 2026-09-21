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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class LevelerService : Service() {

    private lateinit var am: AudioManager
    private val generation = AtomicInteger(0)
    private var worker: Thread? = null
    private var registered = false
    private var lastSig = ""
    private val main = Handler(Looper.getMainLooper())

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
        val rev = State.micRev
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

            val buf = ShortArray(rate / 10) // 100 ms chunks
            var avg = Float.NaN
            var lastAdjust = 0L
            var silentSince = 0L

            while (gen == generation.get()) {
                if (State.micRev != rev) { // mic choice changed in the UI
                    main.post { startCapture() }
                    return
                }
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
                // Smooth over roughly 1 s so short bursts don't cause jumps.
                avg = if (avg.isNaN()) db else avg + 0.1f * (db - avg)
                State.levelDb = avg

                val now = SystemClock.elapsedRealtime()
                val silent = db < -85f
                if (silent) {
                    if (silentSince == 0L) silentSince = now
                    if (now - silentSince > 10_000) {
                        State.status = "Mic is silent (may be reserved by assistant/another app)"
                    }
                    continue
                }
                silentSince = 0L
                if (State.status.startsWith("Mic is silent")) State.status = "Listening"

                if (now - lastAdjust < 1500) continue
                val target = Prefs.target(this)
                val tol = Prefs.tolerance(this)
                val err = avg - target
                val vl = STEP_LEVELS // volume levels moved per adjustment
                when {
                    err > tol -> { step(-1, vl); lastAdjust = now }
                    // Only raise if there is plausibly content playing; a very quiet
                    // room (paused video) must not ramp volume up to the max.
                    err < -tol && avg > target - 20f -> { step(+1, vl); lastAdjust = now }
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

    private fun step(dir: Int, count: Int) {
        if (am.isVolumeFixed) {
            State.status = "Volume is fixed on this output (cannot adjust)"
            return
        }
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val lo = ceil(maxVol * Prefs.minPct(this) / 100.0).toInt()
        val hi = floor(maxVol * Prefs.maxPct(this) / 100.0).toInt().coerceAtLeast(lo)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + dir * count).coerceIn(lo, hi)
        // adjustStreamVolume follows the same path as remote volume keys,
        // which is what HDMI-CEC / ARC output usually needs.
        repeat(abs(next - cur)) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
        }
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
        private const val STEP_LEVELS = 3
    }
}
