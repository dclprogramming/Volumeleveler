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
    private lateinit var statusView: TextView
    private lateinit var overlayStatusView: TextView
    private lateinit var micStatusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var micBtn: Button
    private lateinit var overlayBtn: Button
    private var pendingStart = false
    private var pendingPreviewPermission = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Mic level (dBFS, always negative) shown on a 0-100 "loudness" scale: 1% = 1 dB. */
    private fun pct(dbfs: Float) = (dbfs + 100f).coerceIn(0f, 100f).roundToInt()

    private val ACCENT_COLOR = Color.parseColor("#00BFFF")

    /** Colors every "<number>%" and "<number>/<number>" occurrence in s light blue. */
    private fun withAccentColor(s: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(s)
        Regex("-?\\d+%|\\d+/\\d+").findAll(s).forEach { m ->
            sb.setSpan(ForegroundColorSpan(ACCENT_COLOR), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun statusColor(status: String): Int? = when (status) {
        "Stopped" -> Color.parseColor("#FF0000")
        "Listening" -> Color.parseColor("#00BFFF")
        else -> null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenW = resources.displayMetrics.widthPixels
        val panelBg = Color.parseColor("#101418")
        val rightColW = screenW * 66 / 100

        // ---- Right column (66%): controls ----
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(panelBg)
        }

        right.addView(text("Volume Leveler", 26f))

        // Shared width for every button on this screen: wide enough to comfortably fit
        // the longest label ("Follow avg: Off") with padding, plus 20% so they all read
        // comfortably from the couch. Computed from text metrics up front, so every
        // button (Start leveling, overlay, mic, Follow avg, Reset) can use it immediately
        // with no post-layout measuring pass needed.
        val scratchBtn = Button(this).apply { styleButton(this); isAllCaps = false }
        val btnWidth = ((maxOf(
            scratchBtn.paint.measureText("Follow avg: Off"),
            scratchBtn.paint.measureText("Follow avg: On")
        ).toInt() + dp(20) * 2) * 1.2f).toInt()

        /** A status text (left, expands) lined up with its button (right, fixed width). */
        fun pairedRow(tv: TextView, button: Button): LinearLayout {
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(tv); addView(button)
            }
        }

        // Row 1: Status + Your volume, lined up with Start/Stop leveling.
        statusView = text("", 16f)
        toggleBtn = ctrlBtn("Start leveling", btnWidth) { toggle() }
        right.addView(pairedRow(statusView, toggleBtn))

        // Row 2: Live overlay, lined up with the overlay toggle.
        overlayStatusView = text("", 16f)
        overlayBtn = ctrlBtn("Enable overlay", btnWidth) {
            Prefs.setOverlayOn(this, !Prefs.overlayOn(this))
            if (Prefs.overlayOn(this) && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                    "Allow \"Display over other apps\" on the next screen, then come back",
                    Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            reload()
            refresh()
        }
        right.addView(pairedRow(overlayStatusView, overlayBtn))

        // Row 3: Mic + Mic in use, lined up with the mic selector.
        micStatusView = text("", 16f)
        micBtn = ctrlBtn("Mic selector", btnWidth) { cycleMic() }
        right.addView(pairedRow(micStatusView, micBtn))

        // Room loudness + Loudness target (two lines) lined up with Follow avg. Doesn't resize between
        // On/Off since its width is the shared btnWidth computed above.
        val followBtn = ctrlBtn("Follow avg: Off", btnWidth) {
            Prefs.setFollowAvg(this, !Prefs.followAvg(this))
            reload(); refresh()
        }
        val targetTv = text("", 16f)
        right.addView(pairedRow(targetTv, followBtn))
       updaters.add {
    val level = if (State.levelDb.isNaN()) "-" else "${pct(State.levelDb)}%"
    val followed = Prefs.followAvg(this) && !State.statsAvg.isNaN()
    val t = (if (followed) State.statsAvg else Prefs.target(this)) + State.hvacTargetBonusDb
    val targetPct = "${pct(t)}%"

    val base = "Room loudness: $level\nLoudness target: $targetPct" +
        if (Prefs.followAvg(this)) " (following avg)" else ""

    val sb = withAccentColor(base)

    // Lime when following avg OR when HVAC-boosted
    if (followed || State.hvacBoosted) {
        val idx = base.indexOf(targetPct)
        if (idx >= 0) {
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#32CD32")),
                idx,
                idx + targetPct.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    targetTv.text = sb
    followBtn.text = if (Prefs.followAvg(this)) "Follow avg: On" else "Follow avg: Off"
} 

        right.addView(statsRow(btnWidth))

        // ---- Left column (34%): instructions ----
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(panelBg)
        }
        left.addView(text(
            "\n1. In a quiet room, manually set your remote volume where you like it. " +
            "Some movies will have a different comfort zone so you might need to adjust this " +
                "base volume setting later.\n\n" +
                "2. Press Start leveling, then start your movie. Max volume defaults to " +
                "the volume you set in step 1.\n\n" +
                "3. After playing movie for a few minutes you can press Back + Down for 5 " +
                "seconds to pull up the app and select Follow avg to use a dynamic Loudness " +
                "target if the avg stat isn't near the default setting of 26%. \n\n" +
                "4. If the HVAC kicks on and pushes the room's average loudness to " +
                "20% or more, the app automatically raises the volume by " +
                "4 to compensate, then automatically removes that boost once the " +
                "background noise drops back below 15%.", 16f
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

    /** "Loudness Statistics: High:#% Low:#% Avg:#%" with Reset (clears the gathered
     *  numbers, returns the Loudness target to 26%, and turns Follow avg off). */
    private fun statsRow(btnWidth: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val tv = text("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reset = ctrlBtn("Reset", btnWidth) {
            State.resetStats()
            Prefs.setTarget(this, -74f) // back to the 26% default
            Prefs.setFollowAvg(this, false)
            reload(); refresh()
        }
        row.addView(tv); row.addView(reset)
        fun fmt(v: Float) = if (v.isNaN()) "-" else "${pct(v)}%"
        updaters.add {
            tv.text = withAccentColor("Loudness Statistics: High:${fmt(State.statsHigh)}  Low:${fmt(State.statsLow)}  Avg:${fmt(State.statsAvg)}")
        }
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

    /** Every button on the settings page is built through here so they're all
     *  identical: same padding, same fixed width, same dp(6) top margin. That's what
     *  keeps the vertical rhythm between rows consistent throughout the page. */
    private fun ctrlBtn(label: String, width: Int, onClick: () -> Unit): Button = Button(this).apply {
        styleButton(this); isAllCaps = false; text = label
        val hPad = dp(20)
        setPadding(hPad, paddingTop, hPad, paddingBottom)
        layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        updaters.forEach { it() }
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Row 1: Status, then Your volume right under it.
        val yourVol = if (State.running && State.baseVol >= 0) State.baseVol else vol
        val yourVolText = "$yourVol/$maxVol"
        val statusStr = "Status: ${State.status}\nYour volume: $yourVolText"
        val statusSb = withAccentColor(statusStr)
        statusColor(State.status)?.let {
            val idx = statusStr.indexOf(State.status)
            if (idx >= 0) statusSb.setSpan(ForegroundColorSpan(it), idx, idx + State.status.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (State.hvacBoosted) {
            val idx = statusStr.indexOf(yourVolText)
            if (idx >= 0) {
                statusSb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#32CD32")),
                    idx, idx + yourVolText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        statusView.text = statusSb

        // Row 2: Live overlay, lined up with the overlay button.
        val overlayLabel = if (Prefs.overlayOn(this)) "Enabled" else "Disabled"
        overlayStatusView.text = withAccentColor("Live overlay: $overlayLabel")

        // Row 3: Mic in use + selected Mic, lined up with the mic selector button.
        val saved = Prefs.mic(this)
        val micLabel = if (saved.isEmpty()) "Auto"
        else MicSelector.list(this).firstOrNull { MicSelector.key(it) == saved }
            ?.let { MicSelector.label(it) } ?: "Auto (chosen mic not connected)"
        micStatusView.text = withAccentColor("Mic in use: ${State.micName}\nMic: $micLabel")

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
        LevelPreview.stop()
        startForegroundService(Intent(this, LevelerService::class.java))
        State.running = true
        State.status = "Starting…"
        refresh()
    }
}
