package com.example.volumeleveler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Lets you long-press Back, from inside ANY app (Tubi, etc.), to jump straight to
 * Volume Leveler's MainActivity. Requires the user to turn this service on once under
 * Settings > Accessibility - a normal app has no way to grab a key system-wide otherwise.
 *
 * A single tap of Back still behaves completely normally (we only swallow the event
 * once the hold has crossed LONG_PRESS_MS; everything before that passes straight
 * through untouched).
 */
class KeyInterceptService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        launchApp()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyEvent(event)

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Only the initial press starts the timer; ignore any auto-repeat downs.
                if (event.repeatCount == 0) {
                    longPressFired = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                // Before the hold threshold, let this through as a normal Back down.
                longPressFired
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val fired = longPressFired
                longPressFired = false
                // If we already launched on the long-press, swallow the matching
                // key-up too so it doesn't ALSO register as a normal Back tap.
                fired
            }
            else -> super.onKeyEvent(event)
        }
    }

    private fun launchApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
