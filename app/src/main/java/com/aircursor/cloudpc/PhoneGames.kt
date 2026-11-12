package com.aircursor.cloudpc

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The phone's own games in TS Station: the app writes the list (name + icon) into the PC, and
 * TS Station asks the app to open one ("aircursor-launch:<package>"). The game then runs as a normal
 * phone app – its GPU, its touch controls. Only apps Android marks as games are listed or opened.
 */
object PhoneGames {
    private const val DIR = "root/.cache/ts-station/phone"

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo): Boolean =
        (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) ||
            (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun games(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .mapNotNull { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (ai.packageName == c.packageName || !isGame(ai)) return@mapNotNull null
                ai.packageName to ri.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Writes the list and 128 px icons into the PC (called when the desktop starts; off the main thread). */
    fun export(c: Context) {
        try {
            val dir = File(CloudPcEngine.rootfs(c), DIR)
            dir.mkdirs()
            val list = games(c)
            val keep = HashSet<String>()
            val arr = JSONArray()
            for ((pkg, name) in list) {
                arr.put(JSONObject().put("pkg", pkg).put("name", name))
                val icon = File(dir, "$pkg.png")
                keep += icon.name
                if (!icon.exists()) {
                    try {
                        val d = c.packageManager.getApplicationIcon(pkg)
                        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                        d.setBounds(0, 0, 128, 128)
                        d.draw(Canvas(b))
                        FileOutputStream(icon).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } catch (_: Exception) {
                    }
                }
            }
            // Games removed from the phone leave TS Station too.
            dir.listFiles()?.forEach { if (it.name.endsWith(".png") && it.name !in keep) it.delete() }
            val tmp = File(dir, "games.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, "games.json"))
        } catch (e: Exception) {
            Log.w("PhoneGames", "export failed", e)
        }
    }

    /** Opens a phone game TS Station asked for – only if it is an installed game. */
    fun launch(c: Context, pkg: String): Boolean {
        if (!Regex("[A-Za-z0-9_.]{3,120}").matches(pkg)) return false
        val ok = try {
            isGame(c.packageManager.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!ok) return false
        val intent = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
