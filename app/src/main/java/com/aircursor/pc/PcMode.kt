package com.aircursor.pc

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import com.aircursor.data.Prefs

/** An installed app that can be started from PC mode. */
data class AppEntry(val label: String, val packageName: String, val activity: String) {
    val key: String get() = "$packageName/$activity"
    val component: ComponentName get() = ComponentName(packageName, activity)
}

/** PC mode settings and shared state between the desktop, the overlay taskbar and the service. */
object PcMode {
    private const val KEY_ENABLED = "pc_mode"
    private const val KEY_PINNED = "pc_pinned"
    private const val KEY_WALLPAPER = "pc_wallpaper"
    private const val KEY_FREEFORM = "pc_freeform"
    private const val KEY_KEY_CURSOR = "pc_key_cursor"

    /** Listeners for "desktop is in front" changes (main thread). */
    private val listeners = LinkedHashSet<() -> Unit>()

    /** True while the PC desktop activity is visible (then the overlay taskbar hides). */
    var desktopVisible = false
        set(value) {
            if (field == value) return
            field = value
            listeners.toList().forEach { it() }
        }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun isEnabled(c: Context) = Prefs(c).sp.getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = Prefs(c).sp.edit().putBoolean(KEY_ENABLED, on).apply()

    fun freeform(c: Context) = Prefs(c).sp.getBoolean(KEY_FREEFORM, true)
    fun setFreeform(c: Context, on: Boolean) = Prefs(c).sp.edit().putBoolean(KEY_FREEFORM, on).apply()

    /** Alt + arrow keys move the cursor, Alt + Enter clicks. */
    fun keyboardCursor(c: Context) = Prefs(c).sp.getBoolean(KEY_KEY_CURSOR, true)
    fun setKeyboardCursor(c: Context, on: Boolean) = Prefs(c).sp.edit().putBoolean(KEY_KEY_CURSOR, on).apply()

    fun wallpaper(c: Context) = Prefs(c).sp.getInt(KEY_WALLPAPER, 0)
    fun setWallpaper(c: Context, i: Int) = Prefs(c).sp.edit().putInt(KEY_WALLPAPER, i).apply()

    fun pinned(c: Context): List<String> =
        Prefs(c).sp.getString(KEY_PINNED, null)?.split('|')?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setPinned(c: Context, keys: List<String>) =
        Prefs(c).sp.edit().putString(KEY_PINNED, keys.distinct().joinToString("|")).apply()

    /** All launchable apps, sorted by name (our own app excluded). */
    fun loadApps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, 0)
        return list.asSequence()
            .filter { it.activityInfo.packageName != c.packageName }
            .map {
                AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name)
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private val iconCache = HashMap<String, Bitmap>()

    fun icon(c: Context, app: AppEntry, sizePx: Int): Bitmap? = synchronized(iconCache) {
        iconCache[app.key]?.let { return it }
        return try {
            val d = c.packageManager.getActivityIcon(app.component)
            d.toBitmap(sizePx, sizePx).also { iconCache[app.key] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Starts an app. In window mode a launch size is requested: phones with "freeform windows"
     * (Developer options) open it as a floating window, others simply open it full screen.
     */
    fun launch(c: Context, app: AppEntry, window: Boolean = freeform(c)) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            if (window) {
                val dm = c.resources.displayMetrics
                val w = (dm.widthPixels * 0.6f).toInt()
                val h = (dm.heightPixels * 0.75f).toInt()
                val left = (dm.widthPixels - w) / 2
                val top = (dm.heightPixels - h) / 3
                val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
                c.startActivity(intent, opts.toBundle())
            } else {
                c.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w("PcMode", "launch failed", e)
            Toast.makeText(c, "Could not open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun appInfo(c: Context, app: AppEntry) {
        try {
            c.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /** Opens the PC desktop (optionally with the start menu open). */
    fun openDesktop(c: Context, startMenu: Boolean = false) {
        c.startActivity(
            Intent(c, PcActivity::class.java)
                .putExtra(PcActivity.EXTRA_START_MENU, startMenu)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }
}
