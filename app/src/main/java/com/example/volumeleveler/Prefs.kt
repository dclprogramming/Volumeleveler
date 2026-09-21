package com.example.volumeleveler

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("leveler", Context.MODE_PRIVATE)

    /** "" = auto-select best available mic, otherwise MicSelector.key(device). */
    fun mic(c: Context): String = sp(c).getString("mic", "") ?: ""
    fun setMic(c: Context, v: String) = sp(c).edit().putString("mic", v).apply()

    /** Target room level in dBFS (uncalibrated, relative). */
    fun target(c: Context): Float = sp(c).getFloat("target", -40f)
    fun setTarget(c: Context, v: Float) = sp(c).edit().putFloat("target", v.coerceIn(-80f, -5f)).apply()

    /** Dead-band in dB around the target where the volume is left alone. */
    fun tolerance(c: Context): Float = sp(c).getFloat("tol", 3f)
    fun setTolerance(c: Context, v: Float) = sp(c).edit().putFloat("tol", v.coerceIn(1f, 15f)).apply()

    fun minPct(c: Context): Int = sp(c).getInt("minPct", 10)
    fun setMinPct(c: Context, v: Int) = sp(c).edit().putInt("minPct", v.coerceIn(0, 100)).apply()

    fun maxPct(c: Context): Int = sp(c).getInt("maxPct", 60)
    fun setMaxPct(c: Context, v: Int) = sp(c).edit().putInt("maxPct", v.coerceIn(5, 100)).apply()

    /** How many volume levels above your own volume quiet scenes may be boosted. */
    fun boost(c: Context): Int = sp(c).getInt("boost", 5)
    fun setBoost(c: Context, v: Int) = sp(c).edit().putInt("boost", v.coerceIn(0, 30)).apply()
}
