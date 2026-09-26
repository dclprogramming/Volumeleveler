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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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

    private val PCT_COLOR = Color.parseColor("#00BFFF")

    /** Colors every "<number>%" occurrence in s light blue, leaving the rest as-is. */
    private fun withPctColor(s: String): CharSequence {
        val sb = SpannableStringBuilder(s)
        Regex("-?\\d+%").findAll(s).forEach { m ->
            sb.setSpan(ForegroundColorSpan(PCT_COLOR), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun statusColor(status: String): Int? = when (status) {
        "Stopped" -> Color.parseColor("#FF0000")
        "Listening" -> Color.parseColor("#00BFFF")
        else -> null
    }

    /** dBFS-equivalent silence floor if locked right now: current room loudness + 3%. */
    private fun liveSilenceDb(): Float = if (State.levelDb.isNaN()) -65f else State.levelDb + 3f

    /** Loudness target, derived from the silence floor (not from volume): a gentle
     *  offset above it, clamped to a sane range. */
    private fun computedTargetDb(silenceDbfs: Float): Float {
        val silencePct = pct(silenceDbfs).toFloat()
        val targetPct = (0.6f * silencePct + 18.2f).coerceIn(18f, 45f)
        return targetPct - 100f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenW = resources.displayMetrics.widthPixels
        val panelBg = Color.parseColor("#101418")
        val rightColW = screenW * 66 / 100
        // Percentage of the right column's own width, not a fixed dp value, so the
        // three action buttons stay proportionally sized and everything fits at
        // 1080p, 1440p, and 4K without any resolution-specific tuning.
        val actionBtnWidth = (rightColW * 0.55f).toInt()

        // ---- Right column (66%): controls ----
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(panelBg)
        }

        right.addView(text("Volume Leveler", 26f))

        // Start leveling / Live overlay / Mic — stacked, right-locked. Width gets set to
        // match the combined width of the Silence floor row's Set/-/+ buttons once that
        // row has been laid out (see the post{} block near the end of this function).
        val actionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(8))
        }
        fun actionBtn(): Button = Button(this).apply {
            styleButton(this)
            layoutParams = LinearLayout.LayoutParams(actionBtnWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        toggleBtn = actionBtn().apply { text = "Start leveling"; setOnClickListener { toggle() } }
        actionButtons.addView(toggleBtn)
        overlayBtn = actionBtn().apply {
            text = "Enable overlay"
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
        actionButtons.addView(overlayBtn)
        micBtn = actionBtn().apply { text = "Mic selector"; setOnClickListener { cycleMic() } }
        actionButtons.addView(micBtn)

        // Readouts (left) + the 3 action buttons (right), side by side.
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val readouts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusView = text("", 16f).apply { setPadding(0, dp(8), 0, dp(4)) }
        readouts.addView(statusView)
        topView = text("", 16f).apply { setPadding(0, dp(4), 0, dp(16)) }
        readouts.addView(topView)
        topRow.addView(readouts)
        topRow.addView(actionButtons)
        right.addView(topRow)

        val silenceRowButtons = mutableListOf<Button>()
        right.addView(lockableRow(
            title = "Silence floor",
            display = {
                if (Prefs.silenceLocked(this)) {
                    val now = if (State.levelDb.isNaN()) "-" else "${pct(State.levelDb)}%"
                    "${pct(Prefs.silence(this))}% (set)   now: $now"
                } else "${pct(liveSilenceDb())}% (auto)"
            },
            onSet = { lockSilence(liveSilenceDb()) },
            onAdjust = { d ->
                if (!Prefs.silenceLocked(this)) lockSilence(liveSilenceDb())
                Prefs.setSilence(this, Prefs.silence(this) + d)
                syncTargetFromSilence()
            },
            captureButtons = { silenceRowButtons.addAll(it) }
        ))
        right.addView(adjRow({
            val source = if (Prefs.targetFromStats(this)) "from stats" else "from silence floor"
            "Loudness target ($source): ${pct(Prefs.target(this))}%"
        }) { d ->
            Prefs.setTarget(this, Prefs.target(this) + d)
        })
        right.addView(statsRow())

        // ---- Left column (34%): instructions ----
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(panelBg)
        }
        left.addView(text(
            "\n1. In a quiet room, manually set your remote volume where you like it.\n\n" +
                "2. Press Set on Silence floor to capture the current room's Loudness level " +
                "(the app adds a +3% buffer) — this also sets your Loudness target automatically.\n\n" +
                "3. Press Start leveling, then start your movie. Max volume defaults to the " +
                "volume you set in step 1 — the app never raises above it.\n\n" +
                "4. About 3 minutes into playback, Loudness Statistics starts gathering " +
                "High/Low/Avg readings until you press Stop leveling. Press Set next to it to " +
                "lock your Loudness target to the measured average instead of the silence-floor " +
                "formula, or Reset to clear the gathered numbers. Pressing Set on Silence floor " +
                "always goes back to the default formula.\n\n" +
                "If sustained background noise (like HVAC) pushes the room's loudness to " +
                "20% or more for a full minute, the app automatically raises the volume by " +
                "4 to compensate, then automatically removes that boost again once the " +
                "background noise drops back below 15% — no need to reopen the app or " +
                "recalibrate.", 16f
        ).apply { setTextColor(Color.parseColor("#FFFFFF")) })

        // ---- Full-width row: 34% instructions | 66% controls ----
        val rowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panelBg)
        }
        rowContainer.addView(left, LinearLayout.LayoutParams(screenW * 34 / 100, LinearLayout.LayoutParams.MATCH_PARENT))
        rowContainer.addView(right, LinearLayout.LayoutParams(rightColW, LinearLayout.LayoutParams.MATCH_PARENT))

        setContentView(rowContainer)
        toggleBtn.requestFocus()

        // Guide the user to Accessibility Settings to set up the Back+Down shortcut -
        // but only ever once, ever. The service's own enabled/disabled state flips on
        // every use of the shortcut by design, so (unlike before) we can't use that to
        // detect whether setup is done; a persisted flag is the only reliable signal.
        if (!Prefs.shortcutSetupPrompted(this)) {
            Prefs.setShortcutSetupPrompted(this, true)
            Toast.makeText(this,
                "One-time setup: turn on \"Enable accessibility shortcut,\" then set " +
                    "Shortcut service to \"Volume Leveler shortcut.\" After that, hold " +
                    "Back + Down for 3 seconds from any app to jump here.",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Match the 3 action buttons' width to the Silence floor row's Set/-/+ combined
        // width, once that row has actually been measured (post{} runs after layout).
        rowContainer.post {
            val combined = silenceRowButtons.sumOf { it.width }
            if (combined > 0) {
                listOf(toggleBtn, overlayBtn, micBtn).forEach { b ->
                    b.layoutParams = LinearLayout.LayoutParams(combined, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    }
                }
                actionButtons.requestLayout()
            }
        }
    }

    private fun lockSilence(liveDb: Float) {
        Prefs.setSilence(this, liveDb)
        Prefs.setSilenceLocked(this, true)
        Prefs.setTargetFromStats(this, false)
        syncTargetFromSilence()
    }

    private fun syncTargetFromSilence() {
        Prefs.setTarget(this, computedTargetDb(Prefs.silence(this)))
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
        updaters.add { tv.text = withPctColor(label()) }
        return row
    }

    /** "Loudness Statistics: High:#% Low:#% Avg:#%" with Set (locks the Loudness target
     *  to the measured average) and Reset (clears the gathered numbers). */
    private fun statsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = text("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val set = Button(this).apply {
            styleButton(this); text = "Set"; isAllCaps = false
            setOnClickListener {
                if (!State.statsAvg.isNaN()) {
                    Prefs.setTarget(this@MainActivity, State.statsAvg)
                    Prefs.setTargetFromStats(this@MainActivity, true)
                }
                reload(); refresh()
            }
        }
        val reset = Button(this).apply {
            styleButton(this); text = "Reset"; isAllCaps = false
            setOnClickListener { State.resetStats(); reload(); refresh() }
        }
        row.addView(tv); row.addView(set); row.addView(reset)
        fun fmt(v: Float) = if (v.isNaN()) "-" else "${pct(v)}%"
        updaters.add {
            tv.text = withPctColor("Loudness Statistics: High:${fmt(State.statsHigh)}  Low:${fmt(State.statsLow)}  Avg:${fmt(State.statsAvg)}")
        }
        return row
    }

    /** One line: "Title: value", then Set, then -, then +. Used for Loudness target / Silence floor. */
    private fun lockableRow(
        title: String, display: () -> String, onSet: () -> Unit, onAdjust: (Int) -> Unit,
        captureButtons: ((List<Button>) -> Unit)? = null
    ): LinearLayout {
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
        updaters.add { tv.text = withPctColor("$title: ${display()}") }
        captureButtons?.invoke(listOf(set, minus, plus))
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
        if (!Prefs.silenceLocked(this) && !Prefs.targetFromStats(this)) {
            Prefs.setTarget(this, computedTargetDb(liveSilenceDb()))
        }
        updaters.forEach { it() }
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (State.levelDb.isNaN()) "-" else "${pct(State.levelDb)}%"

        val yourVol = if (State.running && State.baseVol >= 0) State.baseVol else vol
        // Max volume defaults to your own baseline - the app never raises above it.
        val ceiling = if (State.running && State.ceiling >= 0) State.ceiling else vol
        topView.text = withPctColor("Your volume: $yourVol/$maxVol\nCeiling volume: $ceiling/$maxVol\nRoom loudness: $level")

        val saved = Prefs.mic(this)
        val micLabel = if (saved.isEmpty()) "Auto"
        else MicSelector.list(this).firstOrNull { MicSelector.key(it) == saved }
            ?.let { MicSelector.label(it) } ?: "Auto (chosen mic not connected)"
        val overlayLabel = if (Prefs.overlayOn(this)) "Enabled" else "Disabled"

        val statusText = SpannableStringBuilder("Status: ")
        val statusStart = statusText.length
        statusText.append(State.status)
        statusColor(State.status)?.let {
            statusText.setSpan(ForegroundColorSpan(it), statusStart, statusText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        statusText.append("\nMic in use: ${State.micName}\nMic: $micLabel \nLive overlay: $overlayLabel")
        statusView.text = statusText
        toggleBtn.text = if (State.running) "Stop leveling" else "Start leveling"
        overlayBtn.text = if (Prefs.overlayOn(this)) "Disable overlay" else "Enable overlay"
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
        // Lock in the auto silence floor (and its derived target) if Set was never pressed,
        // so the service always has real numbers to work from.
        if (!Prefs.silenceLocked(this)) lockSilence(liveSilenceDb())
        LevelPreview.stop()
        startForegroundService(Intent(this, LevelerService::class.java))
        State.running = true
        State.status = "Starting…"
        refresh()
    }
}
