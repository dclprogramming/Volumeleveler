package com.example.volumeleveler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout

class MainActivity : Activity() {

    private var pendingStart = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = SettingsPanel(this) { toggle() }
        panel.onClose = { finish() }
        val frame = FrameLayout(this)
        // Right half of the screen, full height.
        frame.addView(
            panel,
            FrameLayout.LayoutParams(
                resources.displayMetrics.widthPixels / 2,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )
        setContentView(frame)
        panel.focusFirst()
    }

    private fun toggle() {
        if (State.running) {
            stopService(Intent(this, LevelerService::class.java))
            State.running = false
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
            }
        }
    }

    private fun startLeveler() {
        startForegroundService(Intent(this, LevelerService::class.java))
        State.running = true
        State.status = "Starting…"
    }

    // ---- Reliable Remote Control Long-Press Handling ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        isLongPress = false
                        longPressHandler.postDelayed({
                            isLongPress = true
                            // Trigger toggle (or bring up/modify options) on long press hold (~800ms)
                            toggle()
                        }, 800)
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!isLongPress) {
                        // Short click passes through normally to focused UI elements
                        return super.dispatchKeyEvent(event)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
