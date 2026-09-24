package com.example.volumeleveler

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Rides Google TV's built-in "Accessibility Shortcut" instead of requesting global
 * key-event filtering. Set this up once under Settings > Accessibility:
 *   1. Turn on "Enable accessibility shortcut"
 *   2. Set "Shortcut service" to "Volume Leveler shortcut" (this service)
 * After that, holding Back + Down together for 3 seconds - from any app - toggles
 * this service's enabled state. We launch MainActivity on EITHER transition (on or
 * off), so every hold of the shortcut jumps you here, regardless of which way it flips.
 *
 * Unlike key-event filtering (FLAG_REQUEST_FILTER_KEY_EVENTS), this costs nothing
 * while idle: the shortcut gesture is recognized by the system itself, not by routing
 * every key press on the device through our process. That's what was causing the
 * system-wide input lag with the previous approach.
 */
class KeyInterceptService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        launchApp() // the shortcut just turned this service ON
    }

    override fun onUnbind(intent: Intent?): Boolean {
        launchApp() // the shortcut just turned this service back OFF
        return super.onUnbind(intent)
    }

    private fun launchApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
