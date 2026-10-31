package com.brightmax.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brightmax.safety.SafetyPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings, exposed as StateFlows so the service and the UI react to changes. */
data class UserSettings(
    val level: Float = SafetyPolicy.MAX_LEVEL,
    val timerMinutes: Int = SafetyPolicy.DEFAULT_TIMER_MIN,
    val hdrEnabled: Boolean = true,
    /** Use HDR mode even though the display reports no HDR (Android 14+). */
    val hdrTryAnyway: Boolean = false,
    val overlayEnabled: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)

/** The brightness values that were active before the boost (kept on disk so a crash can still restore them). */
data class BrightnessBackup(val brightness: Int, val mode: Int)

class AppPrefs private constructor(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("brightmax", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun read() = UserSettings(
        level = SafetyPolicy.clampLevel(sp.getFloat(K_LEVEL, SafetyPolicy.MAX_LEVEL)),
        timerMinutes = sp.getInt(K_TIMER, SafetyPolicy.DEFAULT_TIMER_MIN),
        hdrEnabled = sp.getBoolean(K_HDR, true),
        hdrTryAnyway = sp.getBoolean(K_HDR_TRY, false),
        overlayEnabled = sp.getBoolean(K_OVERLAY, true),
        disclaimerAccepted = sp.getBoolean(K_DISCLAIMER, false),
    )

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit(commit = true, action = block)
        _settings.value = read()
    }

    fun setLevel(level: Float) = update { putFloat(K_LEVEL, SafetyPolicy.clampLevel(level)) }
    fun setTimerMinutes(minutes: Int) = update { putInt(K_TIMER, minutes) }
    fun setHdrEnabled(enabled: Boolean) = update { putBoolean(K_HDR, enabled) }
    fun setHdrTryAnyway(enabled: Boolean) = update { putBoolean(K_HDR_TRY, enabled) }
    fun setOverlayEnabled(enabled: Boolean) = update { putBoolean(K_OVERLAY, enabled) }
    fun acceptDisclaimer() = update { putBoolean(K_DISCLAIMER, true) }

    // --- Brightness backup (written synchronously: it must survive a crash right after) ---

    fun brightnessBackup(): BrightnessBackup? =
        if (sp.getBoolean(K_HAS_BACKUP, false)) {
            BrightnessBackup(sp.getInt(K_BACKUP_BRIGHTNESS, 128), sp.getInt(K_BACKUP_MODE, 0))
        } else {
            null
        }

    fun saveBrightnessBackup(backup: BrightnessBackup) = sp.edit(commit = true) {
        putBoolean(K_HAS_BACKUP, true)
        putInt(K_BACKUP_BRIGHTNESS, backup.brightness)
        putInt(K_BACKUP_MODE, backup.mode)
    }

    fun clearBrightnessBackup() = sp.edit(commit = true) { putBoolean(K_HAS_BACKUP, false) }

    companion object {
        private const val K_LEVEL = "level"
        private const val K_TIMER = "timer_minutes"
        private const val K_HDR = "hdr_enabled"
        private const val K_HDR_TRY = "hdr_try_anyway"
        private const val K_OVERLAY = "overlay_enabled"
        private const val K_DISCLAIMER = "disclaimer_accepted"
        private const val K_HAS_BACKUP = "has_backup"
        private const val K_BACKUP_BRIGHTNESS = "backup_brightness"
        private const val K_BACKUP_MODE = "backup_mode"

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) { instance ?: AppPrefs(context).also { instance = it } }
    }
}
