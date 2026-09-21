package com.example.volumeleveler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val updaters = mutableListOf<() -> Unit>()
    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var micBtn: Button
    private lateinit var calBtn: Button
    private var pendingStart = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Mic level (dBFS, always negative) shown on a 0-100 "loudness" scale: 1% = 1 dB. */
    private fun pct(dbfs: Float) = (dbfs + 100f).coerceIn(0f, 100f).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        root.addView(text("Volume Leveler", 30f))

        statusView = text("", 18f).apply { setPadding(0, dp(12), 0, dp(16)) }
        root.addView(statusView)

        toggleBtn = Button(this).apply {
            setOnClickListener { toggle() }
        }
        root.addView(toggleBtn)

        micBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener { cycleMic() }
        }
        root.addView(micBtn)

        root.addView(adjRow({ "Loudness target: ${pct(Prefs.target(this))}%" }) { d ->
            Prefs.setTarget(this, Prefs.target(this) + d)
        })
        root.addView(Button(this).apply {
            text = "Set target = current room loudness"
            isAllCaps = false
            setOnClickListener {
                if (!State.levelDb.isNaN()) Prefs.setTarget(this@MainActivity, State.levelDb)
                refresh()
            }
        })
        calBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (State.calStart != 0L) {
                    State.calStart = 0L
                } else if (!State.running) {
                    Toast.makeText(this@MainActivity, "Press Start leveling first", Toast.LENGTH_LONG).show()
                } else {
                    State.calStart = SystemClock.elapsedRealtime() + 30_000L
                }
                refresh()
            }
        }
        root.addView(calBtn)
        root.addView(adjRow({ "Tolerance: ±${Prefs.tolerance(this).roundToInt()}%" }) { d ->
            Prefs.setTolerance(this, Prefs.tolerance(this) + d)
        })
        root.addView(adjRow({ "Min volume: ${Prefs.minPct(this)}%" }) { d ->
            Prefs.setMinPct(this, Prefs.minPct(this) + d * 5)
        })
        root.addView(adjRow({ "Max volume: ${Prefs.maxPct(this)}%" }) { d ->
            Prefs.setMaxPct(this, Prefs.maxPct(this) + d * 5)
        })
        root.addView(adjRow({ "Boost quiet scenes: up to +${Prefs.boost(this)} levels" }) { d ->
            Prefs.setBoost(this, Prefs.boost(this) + d)
        })

        root.addView(text(
            "Loudness is what the mic hears in the room (0-100), not your TV volume. " +
                "To set the target while a show plays: press Start leveling, press " +
                "\"Calibrate target\", then go back to your show at a comfortable volume. " +
                "After 30 s the app listens for 20 s and sets the target (volume control " +
                "pauses meanwhile). Otherwise the app lowers volume right away when the " +
                "room gets louder than the target, and raises it when quieter, up to the " +
                "boost above your remote volume (never above Max volume).", 14f
        ).apply { setPadding(0, dp(16), 0, 0); setTextColor(Color.LTGRAY) })

        val panel = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2101418"))
            addView(root)
        }
        // Right half of the screen, full height; the left half stays see-through.
        val frame = FrameLayout(this)
        frame.addView(
            panel,
            FrameLayout.LayoutParams(
                resources.displayMetrics.widthPixels / 2,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )
        setContentView(frame)
        toggleBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 500)
            }
        })
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun adjRow(label: () -> String, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = text("", 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun change(d: Int) { onChange(d); reload(); refresh() }
        val minus = Button(this).apply { text = "−"; setOnClickListener { change(-1) } }
        val plus = Button(this).apply { text = "+"; setOnClickListener { change(+1) } }
        row.addView(tv); row.addView(minus); row.addView(plus)
        updaters.add { tv.text = label() }
        return row
    }

    private fun refresh() {
        updaters.forEach { it() }
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (State.levelDb.isNaN()) "-" else "${pct(State.levelDb)}%"
        statusView.text = "Status: ${State.status}\nMic in use: ${State.micName}\n" +
            "Room loudness: $level    Volume: $vol / $maxVol" +
            (if (State.running && State.baseVol >= 0)
                "\nYour volume: ${State.baseVol}    Ceiling: ${State.ceiling}" else "")
        toggleBtn.text = if (State.running) "Stop leveling" else "Start leveling"

        val saved = Prefs.mic(this)
        val label = if (saved.isEmpty()) "Auto (best available)"
        else MicSelector.list(this).firstOrNull { MicSelector.key(it) == saved }
            ?.let { MicSelector.label(it) } ?: "Auto (chosen mic not connected)"
        micBtn.text = "Mic: $label  (press to change)"
        calBtn.text = if (State.calStart != 0L) "Cancel calibration"
        else "Calibrate target in 30 s (while show plays)"
    }

    private fun cycleMic() {
        val devs = MicSelector.list(this)
        val keys = listOf("") + devs.map { MicSelector.key(it) }
        val i = keys.indexOf(Prefs.mic(this)).coerceAtLeast(0)
        Prefs.setMic(this, keys[(i + 1) % keys.size])
        reload()
        refresh()
    }

    /** Tell a running service to re-read settings / re-pick the mic. */
    private fun reload() {
        if (State.running) startService(Intent(this, LevelerService::class.java))
    }

    private fun toggle() {
        if (State.running) {
            stopService(Intent(this, LevelerService::class.java))
            State.running = false
            refresh()
        } else {
            requestPermsThenStart()
        }
    }

    private fun requestPermsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startLeveler()
        } else {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (pendingStart) {
            pendingStart = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startLeveler()
            } else {
                State.status = "Microphone permission denied"
                refresh()
            }
        }
    }

    private fun startLeveler() {
        startForegroundService(Intent(this, LevelerService::class.java))
        State.running = true
        State.status = "Starting…"
        refresh()
    }
}
