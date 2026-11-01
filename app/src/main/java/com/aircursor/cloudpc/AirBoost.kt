package com.aircursor.cloudpc

import android.content.Context

/**
 * AirBoost for the built-in Cloud PC ("DLSS-lite"): the desktop is drawn with fewer pixels
 * (faster on the phone) and the phone upscales it with an FSR-style sharpening filter
 * (Android 13+), so it still looks crisp. Light mode stops background jobs.
 */
enum class AirBoost(val label: String, val width: Int, val sharpen: Float, val light: Boolean, val about: String) {
    PERFORMANCE("⚡ Performance", 1024, 0.85f, true, "Sabse tez · 1024 px + strong sharpening + light mode"),
    BALANCED("⚖️ Balanced", 1280, 0.55f, true, "Roz ke liye · 1280 px + sharpening + light mode"),
    QUALITY("💎 Quality", 1600, 0.25f, false, "Sabse saaf · 1600 px, sab kuch ON"),
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
    }
}
