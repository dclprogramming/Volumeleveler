package com.example.volumeleveler

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The settings UI. Used full-height on the right half of the screen, in the app and as an overlay. */
class SettingsPanel(ctx: Context, private val onToggle: () -> Unit) : ScrollView(ctx) {

    var onClose: (() -> Unit)? = null
    var onInteract: (() -> Unit)? = null

    private val updaters = mutableListOf<() -> Unit>()
    private val statusView: TextView
    private val toggleBtn: Button
    private val micBtn: Button
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun tv(s: String, size: Float) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun styleButton(b: Button): Button = b.apply {
        isAllCaps = false
        val states = arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        )
        val colors = intArrayOf(
            Color.parseColor("#444444"), // Focused / Hovered (Dark Gray)
            Color.parseColor("#444444"), // Pressed (Dark Gray)
            Color.TRANSPARENT            // Default
        )
        backgroundTintList = ColorStateList(states, colors)
    }

    init {
        setBackgroundColor(Color.parseColor("#F2101418"))
        isFillViewport = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        root.addView(tv("Volume Leveler", 26f))

        statusView = tv("", 16f).apply { setPadding(0, dp(8), 0, dp(12)) }
        root.addView(statusView)

        toggleBtn = styleButton(Button(context)).apply { 
            setOnClickListener { onToggle() } 
        }
        root.addView(toggleBtn)

        micBtn = styleButton(Button(context)).apply {
            setOnClickListener { cycleMic() }
        }
        root.addView(micBtn)

        root.addView(adjRow({ "Target: ${Prefs.target(context).roundToInt()} dB" }) { d ->
            Prefs.setTarget(context, Prefs.target(context) + d)
        })
        
        root.addView(styleButton(Button(context)).apply {
            text = "Set target = current room level"
            setOnClickListener {
                if (!State.levelDb.isNaN()) Prefs.setTarget(context, State.levelDb)
                refresh()
            }
        })
        
        root.addView(adjRow({ "Tolerance: ±${Prefs.tolerance(context).roundToInt()} dB" }) { d ->
            Prefs.setTolerance(context, Prefs.tolerance(context) + d)
        })
        
        root.addView(adjRow({ "Min volume: ${Prefs.minPct(context)}%" }) { d ->
            Prefs.setMinPct(context, Prefs.minPct(context) + d * 5)
        })
        
        root.addView(adjRow({ "Max volume: ${Prefs.maxPct(context)}%" }) { d ->
            Prefs.setMaxPct(context, Prefs.maxPct(context) + d * 5)
        })
        
        root.addView(styleButton(Button(context)).apply {
            text = "Close"
            setOnClickListener { onClose?.invoke() }
        })

        addView(root)
    }

    fun focusFirst() {
        post { toggleBtn.requestFocus() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        onInteract?.invoke()
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onClose?.invoke()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun adjRow(label: () -> String, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = tv("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minus = styleButton(Button(context)).apply { 
            text = "−" 
            setOnClickListener { onChange(-1); refresh() } 
        }
        val plus = styleButton(Button(context)).apply { 
            text = "+" 
            setOnClickListener { onChange(1); refresh() } 
        }
        row.addView(t); row.addView(minus); row.addView(plus)
        updaters.add { t.text = label() }
        return row
    }

    private fun refresh() {
        updaters.forEach { it() }
        val am = context.getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = if (State.levelDb.isNaN()) "-" else "${State.levelDb.roundToInt()} dB"
        statusView.text = "Status: ${State.status}\nMic: ${State.micName}\n" +
            "Room: $level    Volume: $vol / $maxVol"
        toggleBtn.text = if (State.running) "Stop leveling" else "Start leveling"

        val saved = Prefs.mic(context)
        val label = if (saved.isEmpty()) "Auto (best available)"
        else MicSelector.list(context).firstOrNull { MicSelector.key(it) == saved }
            ?.let { MicSelector.label(it) } ?: "Auto (chosen mic not connected)"
        micBtn.text = "Mic: $label"
    }

    private fun cycleMic() {
        val keys = listOf("") + MicSelector.list(context).map { MicSelector.key(it) }
        val i = keys.indexOf(Prefs.mic(context)).coerceAtLeast(0)
        Prefs.setMic(context, keys[(i + 1) % keys.size])
        State.micRev++
        refresh()
    }
}
