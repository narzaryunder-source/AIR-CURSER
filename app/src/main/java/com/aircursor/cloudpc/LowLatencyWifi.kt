package com.aircursor.cloudpc

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps Wi-Fi out of its power-saving naps while the two phones work together. In power saving
 * Wi-Fi delivers packets in bursts (every ~100 ms), which makes the remote pointer jump; the
 * low-latency mode sends them at once. Android ends it by itself when the screen goes off.
 */
class LowLatencyWifi(context: Context) {
    private val lock: WifiManager.WifiLock? = try {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm?.createWifiLock(mode, "aircursor:remote-input")?.apply { setReferenceCounted(false) }
    } catch (_: Exception) {
        null
    }

    fun hold(on: Boolean) {
        try {
            val l = lock ?: return
            if (on && !l.isHeld) l.acquire()
            if (!on && l.isHeld) l.release()
        } catch (_: Exception) {
        }
    }
}
