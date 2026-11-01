package com.aircursor.cloudpc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Android 12 and newer stop an app's extra programs when it runs more than 32 of them (the
 * "phantom process" limit). The desktop alone needs about 20; VS Code, Firefox with tabs or a
 * game push it over, and Android then closes them at once. The limit can be lifted once in
 * Developer options; this helper explains how and checks whether it is already done.
 */
object ChildLimit {
    const val LIMIT = 32
    private const val KEY = "settings_enable_monitor_phantom_procs"
    private const val PREFS = "cloudpc_engine"
    private const val HIDDEN = "child_limit_hidden"

    fun applies(): Boolean = Build.VERSION.SDK_INT >= 31

    /** true = the limit is off, false = it is on, null = this phone does not tell. */
    fun limitOff(c: Context): Boolean? {
        if (!applies()) return true
        val global = try {
            Settings.Global.getString(c.contentResolver, KEY)
        } catch (_: Exception) {
            null
        }
        if (!global.isNullOrBlank()) return global.equals("false", ignoreCase = true)
        val prop = getprop("persist.sys.fflag.override.$KEY")
        if (prop.isNotBlank()) return prop.equals("false", ignoreCase = true)
        return null
    }

    private fun getprop(name: String): String = try {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor()
        out
    } catch (_: Exception) {
        ""
    }

    /** Programs this app runs right now (Android only lets an app see its own). */
    fun runningPrograms(): Int = File("/proc").list()?.count { n -> n.isNotEmpty() && n.all { it.isDigit() } } ?: 0

    fun hidden(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HIDDEN, false)

    fun hide(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HIDDEN, true).apply()

    /** Should the app show the "let big apps run" steps? */
    fun shouldShow(c: Context) = applies() && !hidden(c) && limitOff(c) != true

    fun steps(): List<String> {
        val devOptions = "Turn on Developer options: Settings → About phone → tap \"Build number\" 7 times."
        return when {
            Build.VERSION.SDK_INT >= 34 -> listOf(
                devOptions,
                "Open Settings → System → Developer options (on some phones: Settings → Developer options).",
                "Turn on \"Disable child process restrictions\".",
                "Restart the phone once, then open AirCursor PC again.",
            )
            Build.VERSION.SDK_INT >= 32 -> listOf(
                devOptions,
                "Open Developer options → Feature flags.",
                "Turn OFF \"settings_enable_monitor_phantom_procs\".",
                "No Feature flags page? Then it needs a computer once: " +
                    "adb shell settings put global $KEY false",
            )
            else -> listOf(
                "Android 12 can only lift this limit with a computer (once):",
                "adb shell device_config put activity_manager max_phantom_processes 2147483647",
                "Until then: close Firefox tabs and other apps before opening VS Code or games.",
            )
        }
    }

    /** Opens Developer options, or "About phone" when they are not turned on yet. */
    fun openSettings(c: Context) {
        val devOn = try {
            Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        val first = if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        for (action in listOf(first, Settings.ACTION_SETTINGS)) {
            try {
                c.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }
}
