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
    // Volume ceiling: captured once when leveling starts and held fixed until the next
    // Stop/Start cycle. Manual remote changes while running are NOT applied to this.
    @Volatile private var baseVol = -1
    @Volatile private var hvacBoosted = false
    // Loudness Statistics: starts gathering once the room's active loudness has hit
    // STATS_START_DBFS at least once this session, and keeps gathering from then on
    // (does not re-gate on every sample dropping back below it).
    @Volatile private var statsStarted = false
    private var hvacAboveSince = 0L
    @Volatile private var hvacBoostDelta = 0
    // HVAC mute check: when loudness has held >=20% for the hold time, the TV is muted
    // for a short window to see whether the room stays loud without it (real background
    // noise) or drops (just a loud scene). A failed check uses a short back-off so it
    // can retry reasonably soon.
    @Volatile private var hvacTesting = false
    private var hvacTestStart = 0L
    private var hvacTestSum = 0.0
    private var hvacTestN = 0
    private var knownVol = -1        // volume as of our last look
    private var adjusted = false     // we just changed it ourselves
    private var settleUntil = 0L     // wait for our own change to show up before judging
    private var lastSync = 0L
    private var lastNotify = 0L
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
        }

        // These must run on EVERY Start so a new detection cycle can begin
        baseVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        knownVol = baseVol
        statsStarted = false
        hvacBoosted = false
        State.hvacBoosted = false
        hvacAboveSince = 0L
        hvacBoostDelta = 0
        hvacTesting = false

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
        State.hvacBoosted = false
        disableSco()
        removeOverlay()
        // Put the system volume back where it was when leveling started, undoing any
        // dynamic raise/lower drift from this session AND any still-active HVAC boost
        // (if HVAC never dropped back below 15% before you hit Stop, hvacBoostDelta is
        // still nonzero - restoring to raw baseVol would lock the boosted number in as
        // if it were your real baseline, causing a second boost to stack on top of it
        // next session).
        if (baseVol >= 0 && !am.isVolumeFixed) {
            val trueBase = baseVol - hvacBoostDelta
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val diff = trueBase - cur
            if (diff != 0) {
                val dir = if (diff > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(kotlin.math.abs(diff)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                }
            }
        }
        // Safety: never leave the stream muted if we were in the middle of a test
        if (hvacTesting) {
            hvacTesting = false
            try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        State.running = false
        State.status = "Stopped"
        // (Room loudness is left as-is; LevelPreview picks up mic listening again.)
        super.onDestroy()
    }

    // ---- on-screen overlay (works over Tubi/any app; needs "Draw over other apps") ----
    private fun addOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this) || !Prefs.overlayOn(this)) return
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val tv = TextView(this).apply {
                    setTextColor(android.graphics.Color.parseColor("#00BFFF"))
                    setBackgroundColor(android.graphics.Color.parseColor("#000000"))
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
                ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 24; y = 24 }
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

    private fun updateOverlay(text: String, volBoosted: Boolean, targetFollowing: Boolean) {
        main.post {
            if (volBoosted || targetFollowing) {
                val sb = android.text.SpannableString(text)
                val lime = android.graphics.Color.parseColor("#32CD32")
                if (targetFollowing) {
                    val tIdx = text.indexOf("Target ")
                    if (tIdx >= 0) {
                        val valStart = tIdx + 7 // length of "Target "
                        val valEnd = text.indexOf("  Vol", valStart).let { if (it >= 0) it else text.length }
                        sb.setSpan(
                            android.text.style.ForegroundColorSpan(lime),
                            valStart, valEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                if (volBoosted) {
                    val idx = text.indexOf("Vol ")
                    if (idx >= 0) {
                        sb.setSpan(
                            android.text.style.ForegroundColorSpan(lime),
                            idx + 4, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                overlay?.text = sb
            } else {
                overlay?.text = text
            }
        }
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
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
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
        val loudOverlay = if (State.levelDb.isNaN()) "-"
            else "%02d%%".format((State.levelDb + 100f).coerceIn(0f, 100f).toInt())
        val following = Prefs.followAvg(this) && !State.statsAvg.isNaN()
        val tgtDbfs = (if (following) State.statsAvg else Prefs.target(this)) + State.hvacTargetBonusDb
        val tgt = "${(tgtDbfs + 100f).coerceIn(0f, 100f).toInt()}%"
        val text = "Room loudness: $loud    Target: $tgt    Volume: $cur/$maxVol"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
        val volPct = if (maxVol > 0) (cur * 100f / maxVol).roundToInt() else 0
        updateOverlay("Loudness $loudOverlay  Target $tgt  Vol $cur", hvacBoosted, following)
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
            var preTestAvg = Float.NaN   // smoothed levels saved across the HVAC mute check
            var preTestSlow = Float.NaN

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

                // HVAC mute check in progress: the TV is muted on purpose, so freeze the
                // smoothed levels, stats, and volume leveling and just collect RAW
                // (unsmoothed) readings once the mute has had time to land.
                if (hvacTesting) {
                    val t = SystemClock.elapsedRealtime()
                    val elapsed = t - hvacTestStart

                    // Only accumulate after the mute has had time to land
                    if (elapsed >= HVAC_MUTE_SETTLE_MS) {
                        hvacTestSum += db
                        hvacTestN++
                    }

                    if (elapsed >= HVAC_MUTE_SETTLE_MS + HVAC_MUTE_MEASURE_MS) {
                        val meanDb = if (hvacTestN > 0) (hvacTestSum / hvacTestN).toFloat() else Float.NaN

                        // Always unmute
                        hvacTesting = false
                        try {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        } catch (_: Exception) {}

                        // Restore the smoothed levels so leveling continues smoothly
                        avg = preTestAvg
                        slow = preTestSlow
                        settleUntil = t + 1500
                        State.status = "Listening"

                        // Decision: room still loud while muted → real background noise
                        // (No longer requires isStreamMute() == true – that was the main
                        // source of intermittent failures on CEC/ARC devices.)
                        if (!meanDb.isNaN() && meanDb >= HVAC_MUTE_MAX_DBFS) {
                            try {
                                applyHvacBoost(raise = true)
                                hvacBoosted = true
                                State.hvacBoosted = true
                            } catch (e: Exception) {
                                State.status = "HVAC boost error: ${e.javaClass.simpleName}"
                            }
                        } else {
                            // Either it dropped (loud scene) or we got no usable samples.
                            // Back off only 20 s instead of a full extra minute.
                            hvacAboveSince = t - HVAC_HOLD_MS + HVAC_RETRY_BACKOFF_MS
                        }
                    }
                    continue
                }

                // Fast attack (loud sounds register within ~100 ms), slow release (~1.5 s).
                avg = if (avg.isNaN()) db else avg + (if (db > avg) ATTACK else RELEASE) * (db - avg)
                slow = if (slow.isNaN()) db else slow + (if (db > slow) SLOW_ATTACK else SLOW_RELEASE) * (db - slow)
                State.levelDb = avg

                val now = SystemClock.elapsedRealtime()
                syncVolume(now)
                updateNotification(now)

                // Auto HVAC boost/unboost
                try {
                    if (avg >= HVAC_ON_DBFS) {
                        if (hvacAboveSince == 0L) hvacAboveSince = now

                        if (!hvacBoosted && now - hvacAboveSince >= HVAC_HOLD_MS) {
                            if (am.isVolumeFixed) {
                                // Can't mute on this output – short back-off and try again later
                                hvacAboveSince = now - HVAC_HOLD_MS + HVAC_RETRY_BACKOFF_MS
                            } else {
                                // Start the mute test
                                preTestAvg = avg
                                preTestSlow = slow
                                try {
                                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                                } catch (_: Exception) { /* ignore */ }

                                hvacTestStart = now
                                hvacTestSum = 0.0
                                hvacTestN = 0
                                hvacTesting = true
                                State.status = "Checking background noise"
                            }
                        }
                    } else {
                        // Loudness dropped below the on-threshold
                        hvacAboveSince = 0L
                        if (hvacBoosted && avg < HVAC_OFF_DBFS) {
                            applyHvacBoost(raise = false)
                            hvacBoosted = false
                            State.hvacBoosted = false
                        }
                    }
                } catch (e: Exception) {
                    State.status = "HVAC boost error: ${e.javaClass.simpleName}"
                    // Make sure we never leave the stream muted
                    if (hvacTesting) {
                        hvacTesting = false
                        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
                    }
                }

                val silent = db < -85f
                if (silent) continue

                if (!statsStarted && avg >= STATS_START_DBFS) statsStarted = true
                if (statsStarted) State.recordStat(avg)

                val target = (if (Prefs.followAvg(this) && !State.statsAvg.isNaN()) State.statsAvg else Prefs.target(this)) +
                    State.hvacTargetBonusDb
                val tol = Prefs.tolerance(this)
                val loudBy = avg - (target + tol)
                if (now - dropWindowStart > DROP_WINDOW_MS) { dropWindowStart = now; dropInWindow = 0 }
                val dropRoom = MAX_DROP_LEVELS - dropInWindow
                when {
                    // Loud: react right away once we're clearly over
                    loudBy > LOWER_TRIGGER_MARGIN_DB && dropRoom > 0 && now - lastAdjust >= LOWER_COOLDOWN -> {
                        val slowBy = slow - (target + tol)
                        val levels = minOf(
                            floor(slowBy * CUT_DAMPING / DB_PER_LEVEL).toInt().coerceIn(LOWER_MIN_LEVELS, LOWER_MAX_LEVELS),
                            dropRoom
                        )
                        val moved = step(-1, levels)
                        dropInWindow += moved
                        avg -= moved * DB_PER_LEVEL
                        slow -= moved * DB_PER_LEVEL
                        lastAdjust = now
                    }
                    // Quiet: come back up toward your own volume
                    avg < target - tol && now - lastAdjust >= RAISE_COOLDOWN &&
                        am.getStreamVolume(AudioManager.STREAM_MUSIC) < baseVol -> {
                        val quietBy = (target - tol) - avg
                        val levels = if (quietBy >= 6f) 2 else 1
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
            if (hvacTesting) { // never leave the TV muted if we stop mid-check
                hvacTesting = false
                try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            }
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
        }
    }

    /** Highest volume level the app may raise to: your own baseline, never above it. */
    private fun ceilingLevels(): Int = baseVol

    /** Raises or lowers the actual volume (clamped to the device's min/max), and moves
     *  baseVol by that same real amount, so the ceiling never falls behind - and so
     *  reversing it later exactly undoes it, even if the original boost got clamped
     *  short (e.g. near the device's max volume). Unboosting reverses the EXACT
     *  amount recorded in hvacBoostDelta, rather than independently recomputing a
     *  fresh delta, so baseVol always returns to precisely its pre-boost value. */
    private fun applyHvacBoost(raise: Boolean) {
        if (am.isVolumeFixed) return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val delta = if (raise) (cur + HVAC_BOOST_LEVELS).coerceAtMost(maxVol) - cur else -hvacBoostDelta
        if (delta == 0) { hvacBoostDelta = 0; return }
        val target = (cur + delta).coerceIn(0, maxVol)
        val steps = target - cur
        if (steps == 0) { hvacBoostDelta = 0; return }
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
        baseVol += steps
        hvacBoostDelta = if (raise) steps else 0
    }

    private fun step(dir: Int, count: Int): Int {
        if (am.isVolumeFixed) {
            State.status = "Volume is fixed on this output (cannot adjust)"
            return 0
        }
        // Refuse to fire another burst while the previous one may still be landing on
        // the TV/receiver (HDMI-CEC/ARC has real round-trip lag).
        if (SystemClock.elapsedRealtime() < settleUntil) return 0

        val lo = maxOf(0, baseVol - 8)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val hi = ceilingLevels()

        val moves = if (dir > 0) minOf(cur + count, hi) - cur else cur - maxOf(cur - count, lo)
        if (moves <= 0) return 0

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
     * Tracks the current volume so our own adjustments (via step()) aren't mistaken for
     * manual ones. The baseline (baseVol) is intentionally NOT re-captured here.
     */
    private fun syncVolume(now: Long) {
        if (now < settleUntil || now - lastSync < 250) return
        lastSync = now
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (adjusted) {
            adjusted = false
        }
        knownVol = cur
        State.baseVol = baseVol
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
        private const val LOWER_MIN_LEVELS = 1
        private const val CUT_DAMPING = 0.8f
        private const val LOWER_MAX_LEVELS = 3
        private const val MAX_DROP_LEVELS = 3
        private const val DROP_WINDOW_MS = 2500L
        private const val SLOW_ATTACK = 0.20f
        private const val SLOW_RELEASE = 0.03f
        private const val DB_PER_LEVEL = 1.1f
        private const val ATTACK = 0.5f
        private const val RELEASE = 0.05f
        private const val LOWER_COOLDOWN = 400L
        private const val LOWER_TRIGGER_MARGIN_DB = 3f
        private const val RAISE_COOLDOWN = 400L
        private const val STATS_START_DBFS = -75f          // 25 %
        private const val HVAC_ON_DBFS = -80f              // 20 %
        private const val HVAC_OFF_DBFS = -85f             // 15 %
        private const val HVAC_HOLD_MS = 60_000L
        private const val HVAC_MUTE_SETTLE_MS = 1500L      // give CEC/ARC time to mute
        private const val HVAC_MUTE_MEASURE_MS = 500L
        private const val HVAC_MUTE_MAX_DBFS = -82f        // 18 %
        private const val HVAC_RETRY_BACKOFF_MS = 20_000L  // after failed test
        private const val HVAC_BOOST_LEVELS = 5
    }
}
