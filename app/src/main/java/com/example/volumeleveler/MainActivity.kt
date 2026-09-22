package com.example.volumeleveler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var topView: TextView
    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var micBtn: Button
    private lateinit var overlayBtn: Button
    private var pendingStart = false
    private var pendingPreviewPermission = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Mic level (dBFS, always negative) shown on a 0-100 "loudness" scale: 1% = 1 dB. */
    private fun pct(dbfs: Float) = (dbfs + 100f).coerceIn(0f, 100f).roundToInt()

    /** Your current volume, expressed on the same 0-100 loudness scale as the target. */
    private fun liveVolumePct(): Float {
        val am = getSystemService(AudioManager::class.java)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = if (State.running && State.baseVol >= 0) State.baseVol else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVol > 0) cur * 100f / maxVol else 0f
    }

    /** dBFS-equivalent of liveVolumePct(), i.e. what Prefs.target would be if locked right now. */
    private fun liveTargetDb(): Float = liveVolumePct() - 100f

    /** dBFS-equivalent silence floor if locked right now: current room loudness + 3%. */
    private fun liveSilenceDb(): Float = if (State.levelDb.isNaN()) -65f else State.levelDb + 3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        root.addView(text("Volume Leveler", 26f))

        topView = text("", 18f).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(topView)

        statusView = text("", 16f).apply { setPadding(0, dp(4), 0, dp(16)) }
        root.addView(statusView)

        toggleBtn = Button(this).apply {
            styleButton(this)
            setOnClickListener { toggle() }
        }
        root.addView(toggleBtn)

        micBtn = Button(this).apply {
            styleButton(this)
            isAllCaps = false
            setOnClickListener { cycleMic() }
        }
        root.addView(micBtn)

        root.addView(lockableRow(
            title = "Loudness target",
            display = {
                if (Prefs.targetLocked(this)) "${pct(Prefs.target(this))}%"
                else "${pct(liveTargetDb())}% (auto)"
            },
            onSet = { lockTarget(liveTargetDb()) },
            onAdjust = { d ->
                if (!Prefs.targetLocked(this)) lockTarget(liveTargetDb())
                Prefs.setTarget(this, Prefs.target(this) + d)
            }
        ))
        root.addView(adjRow({ "Loudness tolerance: ±${Prefs.tolerance(this).roundToInt()}%" }) { d ->
            Prefs.setTolerance(this, Prefs.tolerance(this) + d)
        })
        root.addView(adjRow({ "Max volume: ${Prefs.maxPct(this)}%" }) { d ->
            Prefs.setMaxPct(this, Prefs.maxPct(this) + d * 5)
        })
        root.addView(adjRow({ "Boost quiet scenes: up to +${Prefs.boost(this)} levels" }) { d ->
            Prefs.setBoost(this, Prefs.boost(this) + d)
        })
        root.addView(lockableRow(
            title = "Silence floor",
            display = {
                if (Prefs.silenceLocked(this)) "${pct(Prefs.silence(this))}%"
                else "${pct(liveSilenceDb())}% (auto)"
            },
            onSet = { lockSilence(liveSilenceDb()) },
            onAdjust = { d ->
                if (!Prefs.silenceLocked(this)) lockSilence(liveSilenceDb())
                Prefs.setSilence(this, Prefs.silence(this) + d)
            }
        ))
        overlayBtn = Button(this).apply {
            styleButton(this)
            isAllCaps = false
            setOnClickListener {
                Prefs.setOverlayOn(this@MainActivity, !Prefs.overlayOn(this@MainActivity))
                if (Prefs.overlayOn(this@MainActivity) && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity,
                        "Allow \"Display over other apps\" on the next screen, then come back",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                reload()
                refresh()
            }
        }
        root.addView(overlayBtn)

        root.addView(text(
            "Loudness is what the mic hears in the room (0-100), not your TV volume. " +
                "Loudness target and Silence floor start on \"auto\": target tracks your " +
                "current volume, and silence floor tracks room loudness + 3%. Press their " +
                "Set button to lock a value in, or press −/+ to lock and nudge at once. " +
                "The app lowers volume right away when the room gets louder than the target " +
                "(plus tolerance), and raises it when quieter, up to the boost above your " +
                "remote volume (never above Max volume), and only when the mic hears clearly " +
                "more than the silence floor. With the live overlay on, a small readout stays " +
                "in the corner of the screen over whatever you're watching.", 14f
        ).apply { setPadding(0, dp(16), 0, 0); setTextColor(Color.LTGRAY) })

        val panel = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2101418"))
            addView(root)
        }
        // Right side of the screen (66% width), full height.
        val frame = FrameLayout(this)
        frame.addView(
            panel,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 2 / 3),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )
        setContentView(frame)
        toggleBtn.requestFocus()
    }

    private fun lockTarget(liveDb: Float) {
        Prefs.setTarget(this, liveDb)
        Prefs.setTargetLocked(this, true)
    }

    private fun lockSilence(liveDb: Float) {
        Prefs.setSilence(this, liveDb)
        Prefs.setSilenceLocked(this, true)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            LevelPreview.start(this)
        } else if (!pendingPreviewPermission) {
            pendingPreviewPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        handler.post(object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 500)
            }
        })
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        if (!State.running) LevelPreview.stop()
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
        val tv = text("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun change(d: Int) { onChange(d); reload(); refresh() }
        val minus = Button(this).apply { styleButton(this); text = "−"; setOnClickListener { change(-1) } }
        val plus = Button(this).apply { styleButton(this); text = "+"; setOnClickListener { change(+1) } }
        row.addView(tv); row.addView(minus); row.addView(plus)
        updaters.add { tv.text = label() }
        return row
    }

    /** One line: "Title: value", then Set, then -, then +. Used for Loudness target / Silence floor. */
    private fun lockableRow(title: String, display: () -> String, onSet: () -> Unit, onAdjust: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = text("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val set = Button(this).apply {
            styleButton(this); text = "Set"; isAllCaps = false
            setOnClickListener { onSet(); reload(); refresh() }
        }
        val minus = Button(this).apply { styleButton(this); text = "−"; setOnClickListener { onAdjust(-1); reload(); refresh() } }
        val plus = Button(this).apply { styleButton(this); text = "+"; setOnClickListener { onAdjust(+1); reload(); refresh() } }
        row.addView(tv); row.addView(set); row.addView(minus); row.addView(plus)
        updaters.add { tv.text = "$title: ${display()}" }
        return row
    }

    /** Dark rounded button that gets a white border when focused (remote D-pad). */
    private fun styleButton(b: Button) {
        fun box(fill: Int, stroke: Int): Drawable {
            val g = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(3), stroke)
            }
            return InsetDrawable(g, dp(2))
        }
        val normal = Color.parseColor("#2A3441")
        val lit = Color.parseColor("#3A4757")
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(lit, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), box(lit, Color.WHITE))
            addState(intArrayOf(), box(normal, normal))
        }
        b.setTextColor(Color.WHITE)
        b.stateListAnimator = null
    }

    private fun refresh() {
        updaters.forEach { it() }
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (State.levelDb.isNaN()) "-" else "${pct(State.levelDb)}%"

        val yourVol = if (State.running && State.baseVol >= 0) State.baseVol else vol
        val ceiling = if (State.running && State.ceiling >= 0) State.ceiling else vol
        topView.text = "Your volume: $yourVol/$maxVol\nCeiling volume: $ceiling/$maxVol\nRoom loudness: $level"

        statusView.text = "Status: ${State.status}\nMic in use: ${State.micName}"
        toggleBtn.text = if (State.running) "Stop leveling" else "Start leveling"

        val saved = Prefs.mic(this)
        val label = if (saved.isEmpty()) "Auto (best available)"
        else MicSelector.list(this).firstOrNull { MicSelector.key(it) == saved }
            ?.let { MicSelector.label(it) } ?: "Auto (chosen mic not connected)"
        micBtn.text = "Mic: $label  (press to change)"

        val granted = Settings.canDrawOverlays(this)
        overlayBtn.text = when {
            !Prefs.overlayOn(this) -> "Live overlay: Off (press to enable)"
            granted -> "Live overlay: On (press to disable)"
            else -> "Live overlay: needs permission (press to grant)"
        }
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
            LevelPreview.start(this)
            refresh()
        } else {
            requestPermsThenStart()
        }
    }

    private fun requestPermsThenStart() {
        if (Prefs.overlayOn(this) && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Allow \"Display over other apps\" for the live overlay, then press Start leveling again",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
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
        if (code == 2) {
            pendingPreviewPermission = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                LevelPreview.start(this)
            }
            return
        }
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
        // Lock in whatever the auto values currently are, so the service always has a
        // real number to work from, even if Set was never pressed.
        if (!Prefs.targetLocked(this)) lockTarget(liveTargetDb())
        if (!Prefs.silenceLocked(this)) lockSilence(liveSilenceDb())
        LevelPreview.stop()
        startForegroundService(Intent(this, LevelerService::class.java))
        State.running = true
        State.status = "Starting…"
        refresh()
    }
}
