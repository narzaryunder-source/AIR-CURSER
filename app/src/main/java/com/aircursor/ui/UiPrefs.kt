package com.aircursor.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App look: light / dark / follow the phone, and which info cards the person closed. */
object UiPrefs {
    private const val FILE = "ui"
    private const val KEY_THEME = "theme"

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context) {
        val name = prefs(c).getString(KEY_THEME, ThemeMode.SYSTEM.name)
        _theme.value = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
    }

    fun setTheme(c: Context, mode: ThemeMode) {
        _theme.value = mode
        prefs(c).edit().putString(KEY_THEME, mode.name).apply()
    }

    fun closed(c: Context, card: String) = prefs(c).getBoolean("card_$card", false)

    fun close(c: Context, card: String) = prefs(c).edit().putBoolean("card_$card", true).apply()
}
