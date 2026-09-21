package com.example.volumeleveler

/** Shared, in-process state read by the UI. */
object State {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var micName = "-"
    @Volatile var levelDb = Float.NaN
    /** Bumped when the mic choice changes so the capture loop restarts. */
    @Volatile var micRev = 0
}
