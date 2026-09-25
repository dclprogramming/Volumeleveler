package com.example.volumeleveler

/** Shared, in-process state read by the UI. */
object State {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var micName = "-"
    @Volatile var levelDb = Float.NaN
    /** Shown on screen: the volume the user set, and the highest the app may raise to. */
    @Volatile var baseVol = -1
    @Volatile var ceiling = -1

    /** Loudness Statistics: High/Low/Avg dBFS gathered starting 3 minutes into a Start
     *  leveling session, until Stop leveling. Persists across sessions until Reset. */
    @Volatile var statsHigh = Float.NaN
    @Volatile var statsLow = Float.NaN
    @Volatile var statsSum = 0.0
    @Volatile var statsCount = 0L

    val statsAvg: Float get() = if (statsCount == 0L) Float.NaN else (statsSum / statsCount).toFloat()

    @Synchronized
    fun recordStat(db: Float) {
        if (statsHigh.isNaN() || db > statsHigh) statsHigh = db
        if (statsLow.isNaN() || db < statsLow) statsLow = db
        statsSum += db
        statsCount++
    }

    fun resetStats() {
        statsHigh = Float.NaN
        statsLow = Float.NaN
        statsSum = 0.0
        statsCount = 0
    }
}
