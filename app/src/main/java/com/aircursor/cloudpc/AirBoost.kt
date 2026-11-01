package com.aircursor.cloudpc

import android.content.Context

/**
 * AirBoost for AirCursor PC: the desktop is drawn with fewer pixels (much less work for the
 * phone, more FPS) and the phone upscales it with an FSR-style sharpening filter (Android 13+),
 * so it still looks crisp. Light mode stops background jobs a phone does not need.
 */
enum class AirBoost(val label: String, val width: Int, val sharpen: Float, val light: Boolean, val about: String) {
    GAME("Game", 960, 0.9f, true, "Maximum FPS for games and video · 960 px · strong sharpening"),
    PERFORMANCE("Performance", 1120, 0.75f, true, "Fast and smooth · 1120 px · sharpening"),
    BALANCED("Balanced", 1280, 0.55f, true, "Recommended for daily use · 1280 px"),
    QUALITY("Quality", 1600, 0.25f, false, "Sharpest text · 1600 px · all background services on"),
    ;

    companion object {
        private const val PREFS = "cloudpc_engine"
        private const val KEY = "airboost"

        fun current(c: Context): AirBoost {
            val name = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, BALANCED.name)
            return entries.firstOrNull { it.name == name } ?: BALANCED
        }

        fun set(c: Context, b: AirBoost) =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, b.name).apply()

        /**
         * Saves the profile and, when the desktop is running, applies it at once: new desktop size
         * (no restart) and light mode. Sharpening follows when the desktop screen is shown again.
         */
        fun applyNow(c: Context, b: AirBoost): Boolean {
            set(c, b)
            if (!CloudPcBus.state.value.desktopRunning) return false
            val (w, h) = CloudPcDesktopActivity.desktopSize(c)
            val light = if (b.light) "cloudpc-light 0; " else ""
            CloudPcService.send(c, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession("${light}cloudpc-resize $w $h"))
            return true
        }
    }
}
