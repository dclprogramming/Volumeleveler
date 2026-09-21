package com.example.volumeleveler

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Watches the remote's OK button system-wide. Holding it ~0.7 s opens the settings
 * panel as an overlay on the right half of the screen, over whatever app is playing.
 */
class KeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var panel: SettingsPanel? = null
    private val showRunnable = Runnable { showPanel() }
    private val idleRunnable = Runnable { hidePanel() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 && panel == null) handler.postDelayed(showRunnable, HOLD_MS)
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(showRunnable)
            }
        }
        return false // never swallow keys; normal OK presses still reach the app
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hidePanel()
        return super.onUnbind(intent)
    }

    private fun showPanel() {
        if (panel != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val p = SettingsPanel(ContextThemeWrapper(this, android.R.style.Theme_Material)) { toggleLeveler() }
        p.onClose = { hidePanel() }
        p.onInteract = {
            handler.removeCallbacks(idleRunnable)
            handler.postDelayed(idleRunnable, IDLE_MS)
        }
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels / 2,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.TOP }
        try {
            wm.addView(p, lp)
        } catch (e: Exception) {
            return
        }
        panel = p
        p.focusFirst()
        handler.postDelayed(idleRunnable, IDLE_MS)
    }

    private fun hidePanel() {
        handler.removeCallbacks(idleRunnable)
        panel?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
        }
        panel = null
    }

    private fun toggleLeveler() {
        try {
            if (State.running) {
                stopService(Intent(this, LevelerService::class.java))
                State.running = false
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                State.status = "Open the app once to grant mic permission"
            } else {
                startForegroundService(Intent(this, LevelerService::class.java))
                State.running = true
                State.status = "Starting…"
            }
        } catch (e: Exception) {
            State.status = "Open the app to start"
        }
    }

    companion object {
        private const val HOLD_MS = 700L
        private const val IDLE_MS = 15_000L
    }
}
