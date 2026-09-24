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
}
