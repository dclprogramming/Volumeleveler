package com.example.volumeleveler

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("leveler", Context.MODE_PRIVATE)

    /** "" = auto-select best available mic, otherwise MicSelector.key(device). */
    fun mic(c: Context): String = sp(c).getString("mic", "") ?: ""
    fun setMic(c: Context, v: String) = sp(c).edit().putString("mic", v).apply()

    /** Target room level in dBFS (uncalibrated, relative). Shown/edited as a 0-100 percent.
     *  Derived from the silence floor via a formula, or from Loudness Statistics if the
     *  user pressed Set there; kept in sync automatically either way. */
    fun target(c: Context): Float = sp(c).getFloat("target", -55f)
    fun setTarget(c: Context, v: Float) = sp(c).edit().putFloat("target", v.coerceIn(-80f, -5f)).apply()

    /** Whether the Loudness target is currently locked to the Loudness Statistics
     *  average (via its Set button) rather than the silence-floor formula. Clearing
     *  this (via Set on Silence floor) returns to the default formula. */
    fun targetFromStats(c: Context): Boolean = sp(c).getBoolean("targetFromStats", false)
    fun setTargetFromStats(c: Context, v: Boolean) = sp(c).edit().putBoolean("targetFromStats", v).apply()

    /** Dead-band in dB around the target where the volume is left alone. Hardwired to 1%. */
    fun tolerance(c: Context): Float = 1f

    /** Mic level (dBFS) of the quiet room with nothing playing; only meaningful once
     *  silenceLocked is true; until then, the UI shows a live value (current room
     *  loudness + 3%) instead of this stored number. */
    fun silence(c: Context): Float = sp(c).getFloat("silence", -65f)
    fun setSilence(c: Context, v: Float) = sp(c).edit().putFloat("silence", v.coerceIn(-100f, -10f)).apply()
    fun silenceLocked(c: Context): Boolean = sp(c).getBoolean("silenceLocked", false)
    fun setSilenceLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("silenceLocked", v).apply()

    /** Small on-screen readout (needs "Draw over other apps") while leveling runs. */
    fun overlayOn(c: Context): Boolean = sp(c).getBoolean("overlay", false)
    fun setOverlayOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()

    /** Whether we've already sent the user to Settings > Accessibility to set up the
     *  Back+Down shortcut. Only ever prompt once - the shortcut service's own
     *  enabled/disabled state flips on every use by design, so it can't be used to
     *  tell whether setup happened. */
    fun shortcutSetupPrompted(c: Context): Boolean = sp(c).getBoolean("shortcutSetupPrompted", false)
    fun setShortcutSetupPrompted(c: Context, v: Boolean) = sp(c).edit().putBoolean("shortcutSetupPrompted", v).apply()
}
