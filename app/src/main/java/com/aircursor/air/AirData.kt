package com.aircursor.air

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Air's own data on the phone: bookmarks, history, the download list, open tabs and settings.
 * Small JSON files in the app's private storage, read once and written in the background.
 * Everything here is used on the main thread.
 */
object AirData {
    const val KEY_DESKTOP = "desktop_site_v2"
    const val KEY_TRACKERS = "block_trackers"
    const val KEY_ADS = "block_ads"
    const val KEY_THEME = "page_theme"
    const val KEY_ENGINE = "search_engine"
    const val KEY_SUGGEST = "search_suggestions"
    const val KEY_RESTORE = "restore_tabs"
    const val KEY_DOWNLOAD_TO = "download_to"      // "" ask, "pc", "phone", "both"
    const val KEY_BAR = "bookmarks_bar"            // "ntp" (new tab only), "always", "never"
    const val KEY_NTP_PHOTOS = "ntp_photos"
    const val KEY_NTP_SITES = "ntp_sites"
    const val KEY_NTP_STATS = "ntp_stats"
    const val KEY_NTP_PHOTO_INDEX = "ntp_photo_index"
    const val KEY_BLOCKED_TOTAL = "blocked_total"
    const val KEY_HIDDEN_SITES = "hidden_sites"
    const val KEY_TABS = "open_tabs"

    class Site(val url: String, var title: String, var time: Long, var visits: Int = 1)

    class Download(
        val id: Long,
        val name: String,
        var size: Long,
        var done: Long = 0,
        /** "running", "done", "failed", "cancelled" */
        var state: String = "running",
        var pcPath: String? = null,
        var phoneUri: String? = null,
        val time: Long = System.currentTimeMillis(),
    )

    data class Engine(val name: String, val search: String, val suggest: String?)

    val ENGINES = listOf(
        Engine("Google", "https://www.google.com/search?q=%s", "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"),
        Engine("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?type=list&q=%s"),
        Engine("Bing", "https://www.bing.com/search?q=%s", null),
    )

    /** Shortcuts on the new tab page before there is any history. */
    val DEFAULT_SITES = listOf(
        "https://www.youtube.com/" to "YouTube",
        "https://www.google.com/" to "Google",
        "https://claude.ai/" to "Claude",
        "https://chatgpt.com/" to "ChatGPT",
        "https://gemini.google.com/" to "Gemini",
        "https://github.com/" to "GitHub",
        "https://mail.google.com/" to "Gmail",
        "https://www.wikipedia.org/" to "Wikipedia",
    )

    private val io = Executors.newSingleThreadExecutor()
    private var dir: File? = null
    private var loaded = false

    val bookmarks = ArrayList<Site>()
    val history = ArrayList<Site>()          // newest first
    val downloads = ArrayList<Download>()    // newest first
    val listeners = ArrayList<() -> Unit>()

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("air", Context.MODE_PRIVATE)

    fun colorScheme(p: SharedPreferences) = p.getInt(KEY_THEME, GeckoRuntimeSettings.COLOR_SCHEME_DARK)

    fun engine(c: Context) = ENGINES.getOrElse(prefs(c).getInt(KEY_ENGINE, 0)) { ENGINES[0] }

    fun searchUrl(c: Context, q: String) = engine(c).search.replace("%s", Uri.encode(q))

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val d = File(c.applicationContext.filesDir, "air").apply { mkdirs() }
        dir = d
        readSites(File(d, "bookmarks.json"), bookmarks)
        readSites(File(d, "history.json"), history)
        try {
            val a = JSONArray(File(d, "downloads.json").readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                downloads += Download(
                    o.getLong("id"), o.getString("name"), o.optLong("size", -1), o.optLong("done", 0),
                    o.optString("state", "done").let { if (it == "running") "failed" else it },
                    o.optString("pc").ifEmpty { null }, o.optString("phone").ifEmpty { null }, o.optLong("time"),
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun readSites(f: File, into: ArrayList<Site>) {
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                into += Site(o.getString("u"), o.optString("t"), o.optLong("w"), o.optInt("v", 1))
            }
        } catch (_: Exception) {
        }
    }

    private fun sitesJson(list: List<Site>) = JSONArray().apply {
        for (s in list) put(JSONObject().put("u", s.url).put("t", s.title).put("w", s.time).put("v", s.visits))
    }.toString()

    private fun write(name: String, text: String) {
        val d = dir ?: return
        io.execute {
            try {
                val tmp = File(d, "$name.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(d, name))
            } catch (_: Exception) {
            }
        }
    }

    private fun changed() {
        for (l in listeners.toList()) l()
    }

    // ---- bookmarks ----

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        val i = bookmarks.indexOfFirst { it.url == url }
        val on = if (i >= 0) {
            bookmarks.removeAt(i)
            false
        } else {
            bookmarks += Site(url, title.ifBlank { host(url) }, System.currentTimeMillis())
            true
        }
        write("bookmarks.json", sitesJson(bookmarks))
        changed()
        return on
    }

    fun removeBookmark(s: Site) {
        bookmarks.remove(s)
        write("bookmarks.json", sitesJson(bookmarks))
        changed()
    }

    fun renameBookmark(s: Site, title: String) {
        s.title = title
        write("bookmarks.json", sitesJson(bookmarks))
        changed()
    }

    // ---- history ----

    private var historyDirty = false

    fun visited(url: String, title: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val now = System.currentTimeMillis()
        val old = history.indexOfFirst { it.url == url }
        if (old >= 0) {
            val s = history.removeAt(old)
            s.time = now
            s.visits++
            if (!title.isNullOrBlank()) s.title = title
            history.add(0, s)
        } else {
            history.add(0, Site(url, title.orEmpty(), now))
            while (history.size > 3000) history.removeAt(history.size - 1)
        }
        saveHistorySoon()
    }

    fun titled(url: String, title: String) {
        val s = history.firstOrNull { it.url == url } ?: return
        if (title.isNotBlank() && s.title != title) {
            s.title = title
            saveHistorySoon()
        }
    }

    private fun saveHistorySoon() {
        if (historyDirty) return
        historyDirty = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            historyDirty = false
            write("history.json", sitesJson(history))
        }, 3000)
    }

    fun removeHistory(s: Site) {
        history.remove(s)
        write("history.json", sitesJson(history))
        changed()
    }

    fun clearHistory() {
        history.clear()
        write("history.json", "[]")
        changed()
    }

    /** Address-bar suggestions: bookmarks first, then the most visited pages that match. */
    fun suggestions(q: String, max: Int = 6): List<Site> {
        val t = q.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return emptyList()
        fun hit(s: Site) = s.url.lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").startsWith(t) || s.title.lowercase(Locale.ROOT).contains(t) ||
            s.url.lowercase(Locale.ROOT).contains(t)
        val out = LinkedHashMap<String, Site>()
        for (s in bookmarks) if (hit(s)) out.putIfAbsent(s.url, s)
        for (s in history.filter { hit(it) }.sortedByDescending { it.visits * 4 + (it.time / 3_600_000L) }) {
            if (out.size >= max) break
            out.putIfAbsent(s.url, s)
        }
        return out.values.take(max)
    }

    // ---- new tab page: most visited sites ----

    fun topSites(c: Context, max: Int = 8): List<Pair<String, String>> {
        val hidden = prefs(c).getStringSet(KEY_HIDDEN_SITES, emptySet()) ?: emptySet()
        val byHost = LinkedHashMap<String, Triple<String, String, Int>>()
        for (s in history) {
            val h = host(s.url)
            if (h.isEmpty() || h in hidden || h == "127.0.0.1" || h == "localhost") continue
            val prev = byHost[h]
            val origin = Uri.parse(s.url).let { "${it.scheme}://${it.authority}/" }
            byHost[h] = if (prev == null) Triple(origin, siteName(h, s.title), s.visits)
            else Triple(prev.first, prev.second, prev.third + s.visits)
        }
        val list = byHost.entries.filter { it.value.third >= 2 }.sortedByDescending { it.value.third }
            .map { it.value.first to it.value.second }.toMutableList()
        for ((u, t) in DEFAULT_SITES) {
            if (list.size >= max) break
            val h = host(u)
            if (h in hidden || list.any { host(it.first) == h }) continue
            list += u to t
        }
        return list.take(max)
    }

    fun hideTopSite(c: Context, host: String) {
        val p = prefs(c)
        val set = HashSet(p.getStringSet(KEY_HIDDEN_SITES, emptySet()) ?: emptySet())
        set += host
        p.edit().putStringSet(KEY_HIDDEN_SITES, set).apply()
    }

    /** "youtube.com" → "YouTube": a short name for a shortcut from the page title or the host. */
    private fun siteName(host: String, title: String): String {
        DEFAULT_SITES.firstOrNull { host(it.first) == host }?.let { return it.second }
        val base = host.split('.').let { if (it.size >= 2) it[it.size - 2] else host }
        val t = title.split(" - ", " | ", " – ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
        val fromTitle = t.lastOrNull { it.length in 2..18 && it.lowercase(Locale.ROOT).replace(" ", "").contains(base.lowercase(Locale.ROOT)) }
        return fromTitle ?: base.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    fun host(url: String): String = try {
        (Uri.parse(url).host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    // ---- downloads ----

    fun saveDownloads() {
        val a = JSONArray()
        for (d in downloads.take(200)) {
            a.put(
                JSONObject().put("id", d.id).put("name", d.name).put("size", d.size).put("done", d.done)
                    .put("state", d.state).put("pc", d.pcPath ?: "").put("phone", d.phoneUri ?: "").put("time", d.time),
            )
        }
        write("downloads.json", a.toString())
        changed()
    }

    private var notifyPosted = false

    /** Progress of a running download: tell the panels, at most a few times a second. */
    fun saveDownloadsLater() {
        if (notifyPosted) return
        notifyPosted = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            notifyPosted = false
            changed()
        }, 300)
    }

    // ---- Shields counter (trackers and ads blocked, all time) ----

    fun addBlocked(c: Context, n: Int) {
        if (n <= 0) return
        val p = prefs(c)
        p.edit().putLong(KEY_BLOCKED_TOTAL, p.getLong(KEY_BLOCKED_TOTAL, 0) + n).apply()
    }

    fun blockedTotal(c: Context) = prefs(c).getLong(KEY_BLOCKED_TOTAL, 0)

    // ---- open tabs (to open again next time) ----

    fun saveTabs(c: Context, urls: List<Pair<String, String>>, selected: Int) {
        val a = JSONArray()
        for ((u, t) in urls.take(30)) a.put(JSONObject().put("u", u).put("t", t))
        prefs(c).edit().putString(KEY_TABS, JSONObject().put("tabs", a).put("sel", selected).toString()).apply()
    }

    fun savedTabs(c: Context): Pair<List<Pair<String, String>>, Int> = try {
        val o = JSONObject(prefs(c).getString(KEY_TABS, null) ?: "{}")
        val a = o.optJSONArray("tabs") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).let { t -> t.getString("u") to t.optString("t") } } to o.optInt("sel", 0)
    } catch (_: Exception) {
        emptyList<Pair<String, String>>() to 0
    }
}
