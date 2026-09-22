package com.example.volumeleveler

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("leveler", Context.MODE_PRIVATE)

    /** "" = auto-select best available mic, otherwise MicSelector.key(device). */
    fun mic(c: Context): String = sp(c).getString("mic", "") ?: ""
    fun setMic(c: Context, v: String) = sp(c).edit().putString("mic", v).apply()

    /** Target room level in dBFS (uncalibrated, relative). Shown/edited as a 0-100 percent.
     *  Only meaningful once targetLocked is true; until then, the UI shows a live value
     *  (your current volume, as a loudness percent) instead of this stored number. */
    fun target(c: Context): Float = sp(c).getFloat("target", -55f)
    fun setTarget(c: Context, v: Float) = sp(c).edit().putFloat("target", v.coerceIn(-80f, -5f)).apply()
    fun targetLocked(c: Context): Boolean = sp(c).getBoolean("targetLocked", false)
    fun setTargetLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("targetLocked", v).apply()

    /** Dead-band in dB around the target where the volume is left alone. */
    fun tolerance(c: Context): Float = sp(c).getFloat("tol", 1f)
    fun setTolerance(c: Context, v: Float) = sp(c).edit().putFloat("tol", v.coerceIn(1f, 15f)).apply()

    fun maxPct(c: Context): Int = sp(c).getInt("maxPct", 50)
    fun setMaxPct(c: Context, v: Int) = sp(c).edit().putInt("maxPct", v.coerceIn(5, 100)).apply()

    /** How many volume levels above your own volume quiet scenes may be boosted. */
    fun boost(c: Context): Int = sp(c).getInt("boost", 3)
    fun setBoost(c: Context, v: Int) = sp(c).edit().putInt("boost", v.coerceIn(0, 30)).apply()

    /** Mic level (dBFS) of the quiet room with nothing playing; quiet-scene boost stays off near it.
     *  Only meaningful once silenceLocked is true; until then, the UI shows a live value
     *  (current room loudness + 3%) instead of this stored number. */
    fun silence(c: Context): Float = sp(c).getFloat("silence", -65f)
    fun setSilence(c: Context, v: Float) = sp(c).edit().putFloat("silence", v.coerceIn(-100f, -10f)).apply()
    fun silenceLocked(c: Context): Boolean = sp(c).getBoolean("silenceLocked", false)
    fun setSilenceLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("silenceLocked", v).apply()

    /** Small on-screen readout (needs "Draw over other apps") while leveling runs. */
    fun overlayOn(c: Context): Boolean = sp(c).getBoolean("overlay", true)
    fun setOverlayOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("overlay", v).apply()
}
