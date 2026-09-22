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
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
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
    // Volume ceiling: starts at the volume when leveling began, then follows any manual change.
    @Volatile private var baseVol = -1
    private var knownVol = -1        // volume as of our last look
    private var adjusted = false     // we just changed it ourselves
    private var settleUntil = 0L     // wait for our own change to show up before judging
    private var lastSync = 0L
    private val main = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null

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
        if (Prefs.overlayOn(this)) addOverlay() else removeOverlay()
        startCapture() // also used as "reload settings" when the UI pings the service
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        worker?.join(1500)
        if (registered) am.unregisterAudioDeviceCallback(deviceCb)
        registered = false
        disableSco()
        removeOverlay()
        State.running = false
        State.status = "Stopped"
        State.levelDb = Float.NaN
        super.onDestroy()
    }

    // ---- on-screen overlay (works over Tubi/any app; needs "Draw over other apps") ----

    private fun addOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this) || !Prefs.overlayOn(this)) return
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val tv = TextView(this).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#B0000000"))
                    setPadding(18, 10, 18, 10)
                    textSize = 13f
                    text = "Volume Leveler"
                }
                val type = if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 24 }
                wm.addView(tv, lp)
                overlay = tv
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlay() {
        main.post {
            overlay?.let {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
            }
            overlay = null
        }
    }

    private fun updateOverlay(text: String) {
        main.post { overlay?.text = text }
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
        val n = buildNotification("Listening to the room and adjusting volume")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Volume Leveler running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Lets you check the room reading from the notification shade without leaving your show. */
    private fun updateNotification(now: Long) {
        if (now - lastNotify < NOTIFY_MS) return
        lastNotify = now
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val loud = if (State.levelDb.isNaN()) "-" else "${(State.levelDb + 100f).coerceIn(0f, 100f).toInt()}%"
        val text = "Room loudness: $loud    Volume: $cur/$maxVol    Ceiling: ${State.ceiling}/$maxVol"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
        updateOverlay("Loud $loud  Vol $cur/$maxVol  Ceil ${State.ceiling}/$maxVol")
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
            var aboveSince = 0L   // when the mic started hearing more than the silent room
            var lastAdjust = 0L
            var silentSince = 0L
            var lastNotify = 0L

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
                updateNotification(now)
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

                val target = Prefs.target(this)
                val tol = Prefs.tolerance(this)
                val loudBy = avg - (target + tol)
                if (now - dropWindowStart > DROP_WINDOW_MS) { dropWindowStart = now; dropInWindow = 0 }
                val dropRoom = MAX_DROP_LEVELS - dropInWindow
                // "Content present" = the mic has heard clearly more than the silent room.
                if (avg > Prefs.silence(this) + SILENCE_MARGIN_DB) {
                    if (aboveSince == 0L) aboveSince = now
                } else {
                    aboveSince = 0L
                }
                val contentPresent = aboveSince != 0L && now - aboveSince >= CONTENT_HOLD_MS
                when {
                    // Loud: react right away, sized to how far over we are (at least 3 levels).
                    loudBy > 0 && dropRoom > 0 && now - lastAdjust >= LOWER_COOLDOWN -> {
                        // Size the cut from the steadier level, so a one-off spike in an
                        // explosion doesn't cause a huge drop; and cap the total cut per window.
                        val slowBy = slow - (target + tol)
                        val levels = minOf(
                            floor(slowBy * CUT_DAMPING / DB_PER_LEVEL).toInt().coerceIn(LOWER_MIN_LEVELS, LOWER_MAX_LEVELS),
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
                    // Quiet: come back up. Recovering toward your own volume (after the app's
                    // own cut) always proceeds - we caused the drop, so content is present.
                    // Going ABOVE your volume (the boost) still needs contentPresent, so a
                    // silent room never gets boosted.
                    avg < target - tol && now - lastAdjust >= RAISE_COOLDOWN &&
                        (am.getStreamVolume(AudioManager.STREAM_MUSIC) < baseVol || contentPresent) -> {
                        // The quieter the scene, the more levels it gets back (1-3), and
                        // recovery moves faster than boosting above your own volume.
                        val quietBy = (target - tol) - avg
                        val levels = if (quietBy >= 8f) 3 else if (quietBy >= 4f) 2 else 1
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
     * level the ceiling, whether the user turned it up or down. This also moves the
     * loudness target, since the target always equals your volume.
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
        private const val NOTIFY_MS = 2000L
        private const val SILENCE_MARGIN_DB = 4f  // must be this far above the silence floor to count as content
        private const val LOWER_MIN_LEVELS = 3
        private const val CUT_DAMPING = 0.8f    // slightly undershoot rather than overcorrect
        private const val LOWER_MAX_LEVELS = 5
        private const val MAX_DROP_LEVELS = 4      // most levels cut within one window (~4-5 dB)
        private const val DROP_WINDOW_MS = 2500L
        private const val SLOW_ATTACK = 0.20f      // per 50 ms chunk (~0.25 s) - faster reaction
        private const val SLOW_RELEASE = 0.03f
        private const val CONTENT_HOLD_MS = 2000L
        private const val DB_PER_LEVEL = 1.1f   // roughly what one volume level changes, in dB
        private const val ATTACK = 0.5f         // per 50 ms chunk
        private const val RELEASE = 0.03f
        private const val LOWER_COOLDOWN = 400L
        private const val RAISE_COOLDOWN = 900L
    }
}
