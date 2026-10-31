package com.brightmax.brightness

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.brightmax.data.AppPrefs
import com.brightmax.data.BrightnessBackup
import com.brightmax.safety.SafetyPolicy

/**
 * System-wide brightness through the official Settings.System API (needs the WRITE_SETTINGS special access).
 *
 * The original brightness + auto-brightness mode are saved to disk before the first change, so they can be
 * restored on boost-off, service stop, crash, shutdown or the next app start.
 */
class SystemBrightness(context: Context) {
    private val app = context.applicationContext
    private val prefs = AppPrefs.get(app)
    private val resolver get() = app.contentResolver

    fun canWrite(): Boolean = Settings.System.canWrite(app)

    /** Sets the system brightness to [level] (0..1). Returns false if not allowed. */
    @Synchronized
    fun apply(level: Float): Boolean {
        if (!canWrite()) return false
        return runCatching {
            if (prefs.brightnessBackup() == null) {
                prefs.saveBrightnessBackup(
                    BrightnessBackup(
                        brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128),
                        mode = Settings.System.getInt(
                            resolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        ),
                    ),
                )
            }
            // Adaptive brightness would fight the boost: switch to manual while boosting.
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, SafetyPolicy.toSystemBrightness(level))
        }.onFailure { Log.w(TAG, "apply failed", it) }.getOrDefault(false)
    }

    /** Puts back the saved brightness and mode. Safe to call any number of times. */
    @Synchronized
    fun restore(): Boolean {
        val backup = prefs.brightnessBackup() ?: return false
        val ok = canWrite() && runCatching {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, backup.brightness)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, backup.mode)
        }.onFailure { Log.w(TAG, "restore failed", it) }.isSuccess
        // Clear even when the permission was revoked: nothing more can be done, and a stale backup must
        // never overwrite a brightness the user picked later.
        prefs.clearBrightnessBackup()
        return ok
    }

    fun hasPendingRestore(): Boolean = prefs.brightnessBackup() != null

    companion object {
        private const val TAG = "SystemBrightness"
    }
}
