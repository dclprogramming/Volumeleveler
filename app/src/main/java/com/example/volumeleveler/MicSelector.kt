package com.example.volumeleveler

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

object MicSelector {

    /** Lower = preferred when auto-selecting. 9 = not a usable mic type. */
    fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 2
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 3
        else -> 9
    }

    fun list(ctx: Context): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { rank(it.type) < 9 }
            .sortedBy { rank(it.type) }
    }

    fun key(d: AudioDeviceInfo): String = "${d.type}|${d.productName}"

    fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TV"
            else -> "Other"
        }
        return "$kind - ${d.productName}"
    }

    /** The user's chosen mic if present, else the best available; null = system default. */
    fun pick(ctx: Context): AudioDeviceInfo? {
        val devs = list(ctx)
        val pref = Prefs.mic(ctx)
        if (pref.isNotEmpty()) devs.firstOrNull { key(it) == pref }?.let { return it }
        return devs.firstOrNull()
    }
}
