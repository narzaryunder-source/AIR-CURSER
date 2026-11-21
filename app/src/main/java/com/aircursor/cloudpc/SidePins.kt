package com.aircursor.cloudpc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Apps pinned to the toolbar at the side of the PC screen: the person's important apps, one tap
 * away. Any app in the PC's app list can be pinned – desktop apps, web apps (YouTube, ChatGPT…),
 * Air Store, OpenCode – because they all have a menu entry. Kept on the phone (settings).
 */
internal object SidePins {
    /** [path]: the app's menu entry in the PC; [icon]: its picture in the PC (may be empty). */
    data class Pin(val path: String, val name: String, val icon: String)

    const val MAX = 8

    /** The PC's app list (written by shell-index.py): id, name, menu entry, icon, … per line. */
    const val INDEX = "root/.cache/cloudpc-shell/apps.tsv"

    fun decode(json: String?): List<Pin> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path")
            if (path.isBlank()) null else Pin(path, o.optString("name").ifBlank { "App" }, o.optString("icon"))
        }.distinctBy { it.path }.take(MAX)
    } catch (_: Exception) {
        emptyList()
    }

    fun encode(pins: List<Pin>): String = JSONArray().apply {
        for (p in pins) put(JSONObject().put("path", p.path).put("name", p.name).put("icon", p.icon))
    }.toString()

    /** [pin] at the end (moved there if it was pinned already); the oldest goes when full. */
    fun add(pins: List<Pin>, pin: Pin): List<Pin> = (pins.filter { it.path != pin.path } + pin).takeLast(MAX)

    fun remove(pins: List<Pin>, path: String): List<Pin> = pins.filter { it.path != path }

    /** The apps the PC knows, by name (empty when the list is not made yet). */
    fun readIndex(rootfs: File): List<Pin> = try {
        File(rootfs, INDEX).readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4 || p[1].isBlank() || p[2].isBlank()) null else Pin(p[2], p[1], p[3])
        }.distinctBy { it.path }.sortedBy { it.name.lowercase(Locale.ROOT) }
    } catch (_: Exception) {
        emptyList()
    }

    /** The shell command that opens [pin] inside the desktop session. */
    fun launchCommand(pin: Pin): String = "setsid gio launch " + quote(pin.path) + " >/dev/null 2>&1 &"

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
