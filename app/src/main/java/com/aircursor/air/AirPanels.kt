package com.aircursor.air

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.aircursor.R
import org.mozilla.geckoview.BuildConfig
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.StorageController
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Air's side panels (history, bookmarks, downloads), the Shields popup, settings and about. */
@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
internal class AirPanels(private val b: AirBrowser) {
    private val ui get() = b.ui
    private val a get() = b.activity
    private var open: String? = null
    private var panel: LinearLayout? = null
    private var list: LinearLayout? = null
    private var query = ""

    init {
        AirEngine.showExtensionPopup = { s -> extensionPopup(s) }
    }

    // ---- side panel frame ----

    private fun show(kind: String, title: String, searchable: Boolean, action: Pair<String, () -> Unit>? = null) {
        close()
        open = kind
        query = ""
        val p = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(AirUi.PANEL, 0)
            elevation = ui.uf(16)
            isClickable = true
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.u(14), 0, ui.u(6), 0)
        }
        head.addView(ui.text(title, 15, AirUi.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            head.addView(ui.text(action.first, 12, AirUi.ACCENT).apply {
                setPadding(ui.u(10), ui.u(6), ui.u(10), ui.u(6))
                background = ui.ripple(null, 6)
                setOnClickListener { action.second() }
            })
        }
        head.addView(ui.icon(R.drawable.ic_ui_close, "Close", 30, 13, AirUi.MUTED) { close() })
        p.addView(head, LinearLayout.LayoutParams(-1, ui.u(42)))
        if (searchable) {
            val f = EditText(a).apply {
                setSingleLine()
                hint = "Search"
                setTextColor(AirUi.TEXT)
                setHintTextColor(AirUi.FAINT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(13))
                background = ui.round(AirUi.FIELD, 8)
                setPadding(ui.u(10), 0, ui.u(10), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        refresh()
                    }
                })
            }
            p.addView(f, LinearLayout.LayoutParams(-1, ui.u(30)).apply {
                leftMargin = ui.u(12); rightMargin = ui.u(12); bottomMargin = ui.u(8)
            })
        }
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        list = l
        p.addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(l)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val w = (b.content.width * 0.48f).roundToInt().coerceIn(ui.u(300), ui.u(400)).coerceAtMost(b.content.width)
        b.content.addView(p, FrameLayout.LayoutParams(w, -1, Gravity.END))
        panel = p
        refresh()
    }

    /** True when a panel was open (the back key closes it first). */
    fun close(): Boolean {
        val p = panel ?: return false
        b.content.removeView(p)
        panel = null
        list = null
        open = null
        return true
    }

    fun refresh() {
        when (open) {
            "history" -> fillHistory()
            "bookmarks" -> fillBookmarks()
            "downloads" -> fillDownloads()
        }
    }

    fun history() = show("history", "History", true, "Clear all" to {
        AlertDialog.Builder(a)
            .setTitle("Clear history?")
            .setMessage("All visited pages are removed from Air's history.")
            .setPositiveButton("Clear") { _, _ -> AirData.clearHistory() }
            .setNegativeButton("Cancel", null)
            .show()
    })

    fun bookmarks() = show("bookmarks", "Bookmarks", true)

    fun downloads() = show("downloads", "Downloads", false, "Settings" to { settings() })

    // ---- rows ----

    private fun siteRow(title: String, sub: String, url: String, trailing: String?, onRemove: (() -> Unit)?, onLong: ((View) -> Unit)? = null): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.u(14), 0, ui.u(4), 0)
            background = ui.ripple(null, 6)
            setOnClickListener {
                close()
                b.load(url)
            }
            if (onLong != null) setOnLongClickListener {
                onLong(this)
                true
            }
        }
        val ic = ImageView(a).apply {
            setImageResource(R.drawable.ic_ui_globe)
            imageTintList = ColorStateList.valueOf(AirUi.MUTED)
        }
        AirIcons.get(a, url) { bmp ->
            if (bmp != null) {
                ic.imageTintList = null
                ic.setImageBitmap(bmp)
            }
        }
        r.addView(ic, LinearLayout.LayoutParams(ui.u(16), ui.u(16)).apply { marginEnd = ui.u(12) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(title.ifBlank { AirData.host(url) }, 13, AirUi.TEXT))
        col.addView(ui.text(sub, 11, AirUi.FAINT).apply { setPadding(0, ui.u(2), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) r.addView(ui.text(trailing, 11, AirUi.FAINT).apply { setPadding(ui.u(8), 0, ui.u(4), 0) })
        if (onRemove != null) r.addView(ui.icon(R.drawable.ic_ui_close, "Remove", 26, 11, AirUi.FAINT) { onRemove() })
        return r
    }

    private fun header(t: String) = ui.text(t, 11.5, AirUi.MUTED, bold = true).apply {
        setPadding(ui.u(14), ui.u(12), ui.u(14), ui.u(6))
    }

    private fun empty(t: String) = ui.text(t, 12.5, AirUi.FAINT).apply {
        maxLines = 4
        setPadding(ui.u(16), ui.u(18), ui.u(16), ui.u(18))
    }

    private fun matches(s: AirData.Site): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        return q.isEmpty() || s.title.lowercase(Locale.ROOT).contains(q) || s.url.lowercase(Locale.ROOT).contains(q)
    }

    private fun fillHistory() {
        val l = list ?: return
        l.removeAllViews()
        val items = AirData.history.filter { matches(it) }.take(300)
        if (items.isEmpty()) {
            l.addView(empty(if (query.isBlank()) "Pages you visit show up here." else "Nothing found."))
            return
        }
        var lastDay = ""
        for (s in items) {
            val day = dayName(s.time)
            if (day != lastDay) {
                l.addView(header(day))
                lastDay = day
            }
            val time = DateUtils.formatDateTime(a, s.time, DateUtils.FORMAT_SHOW_TIME)
            l.addView(siteRow(s.title, AirData.host(s.url), s.url, time, { AirData.removeHistory(s) }), LinearLayout.LayoutParams(-1, ui.u(44)))
        }
    }

    private fun dayName(t: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today"
            sameYear && dayDiff == 1 -> "Yesterday"
            else -> DateUtils.formatDateTime(a, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
        }
    }

    private fun fillBookmarks() {
        val l = list ?: return
        l.removeAllViews()
        val items = AirData.bookmarks.filter { matches(it) }
        if (items.isEmpty()) {
            l.addView(empty(if (query.isBlank()) "Tap ☆ in the address bar (or Ctrl+D) to bookmark a page." else "Nothing found."))
            return
        }
        for (s in items.reversed()) {
            l.addView(
                siteRow(s.title, AirData.host(s.url), s.url, null, { AirData.removeBookmark(s) }) { v -> bookmarkMenu(v, s) },
                LinearLayout.LayoutParams(-1, ui.u(44)),
            )
        }
    }

    fun bookmarkMenu(anchor: View, s: AirData.Site) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        ui.menu(anchor, loc[0], loc[1] + anchor.height, listOf(
            AirUi.Item("Open", R.drawable.ic_ui_globe) { b.load(s.url) },
            AirUi.Item("Open in new tab", R.drawable.ic_ui_plus) { b.newTab(s.url) },
            AirUi.Item("Rename", R.drawable.ic_ui_sliders) { rename(s) },
            AirUi.Item("Delete", R.drawable.ic_ui_trash, separatorBefore = true) { AirData.removeBookmark(s) },
        ), 210)
    }

    private fun rename(s: AirData.Site) {
        val f = EditText(a).apply {
            setText(s.title)
            setSingleLine()
            selectAll()
        }
        val d = a.resources.displayMetrics.density
        AlertDialog.Builder(a)
            .setTitle("Bookmark name")
            .setView(FrameLayout(a).apply {
                setPadding((22 * d).toInt(), (6 * d).toInt(), (22 * d).toInt(), 0)
                addView(f)
            })
            .setPositiveButton("Save") { _, _ -> AirData.renameBookmark(s, f.text.toString().trim().ifEmpty { s.title }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fillDownloads() {
        val l = list ?: return
        l.removeAllViews()
        if (AirData.downloads.isEmpty()) {
            l.addView(empty("Files you download in Air show up here. Each one can go into the PC, onto the phone, or both."))
            return
        }
        for (d in AirData.downloads.take(100)) {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.u(14), ui.u(8), ui.u(4), ui.u(8))
            }
            r.addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_ui_file)
                imageTintList = ColorStateList.valueOf(if (d.state == "done") AirUi.ACCENT else AirUi.FAINT)
            }, LinearLayout.LayoutParams(ui.u(20), ui.u(20)).apply { marginEnd = ui.u(12) })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.text(d.name, 13, AirUi.TEXT))
            val status = when (d.state) {
                "running" -> {
                    val pct = if (d.size > 0) " · ${(d.done * 100 / d.size).coerceIn(0, 100)}%" else ""
                    "Downloading ${AirDownloads.size(d.done)}" + (if (d.size > 0) " of ${AirDownloads.size(d.size)}" else "") + pct
                }
                "done" -> AirDownloads.size(d.size) + " · " + listOfNotNull(d.pcPath?.let { "in the PC" }, d.phoneUri?.let { "on the phone" }).joinToString(" and ")
                "cancelled" -> "Cancelled"
                else -> "Failed – try again"
            }
            col.addView(ui.text(status, 11, if (d.state == "failed") AirUi.BAD else AirUi.FAINT).apply { setPadding(0, ui.u(2), 0, 0) })
            if (d.state == "running") {
                col.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = d.size <= 0
                    max = 1000
                    progress = if (d.size > 0) (d.done * 1000 / d.size).toInt() else 0
                    progressTintList = ColorStateList.valueOf(AirUi.ACCENT)
                }, LinearLayout.LayoutParams(-1, ui.u(6)).apply { topMargin = ui.u(4) })
            }
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (d.pcPath != null) r.addView(ui.icon(R.drawable.ic_ui_desktop, "Open in the PC", 28, 14, AirUi.MUTED) { AirDownloads.open(a, d, onPhone = false) })
            if (d.phoneUri != null) r.addView(ui.icon(R.drawable.ic_ui_phone, "Open on the phone", 28, 14, AirUi.MUTED) { AirDownloads.open(a, d, onPhone = true) })
            if (d.state == "running") {
                r.addView(ui.icon(R.drawable.ic_ui_close, "Cancel", 28, 12, AirUi.MUTED) { AirDownloads.cancel(d) })
            } else {
                r.addView(ui.icon(R.drawable.ic_ui_trash, "Remove from the list (the file stays)", 28, 13, AirUi.FAINT) {
                    AirData.downloads.remove(d)
                    AirData.saveDownloads()
                })
            }
            l.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---- Shields ----

    fun shields(anchor: View) {
        val t = b.cur() ?: return
        val site = AirData.host(t.url)
        val web = t.url.startsWith("http")
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.u(16), ui.u(14), ui.u(16), ui.u(12))
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_ui_shield)
            imageTintList = ColorStateList.valueOf(if (t.shieldsDown) AirUi.FAINT else AirUi.SHIELD)
        }, LinearLayout.LayoutParams(ui.u(30), ui.u(30)).apply { marginEnd = ui.u(12) })
        val htxt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        htxt.addView(ui.text(if (!web) "Shields" else if (t.shieldsDown) "Shields are DOWN" else "Shields are UP", 15, AirUi.TEXT, bold = true))
        htxt.addView(ui.text(if (web) "for $site" else "Air blocks trackers and ads on every site", 11.5, AirUi.MUTED).apply { setPadding(0, ui.u(2), 0, 0) })
        head.addView(htxt, LinearLayout.LayoutParams(0, -2, 1f))
        var pop: android.widget.PopupWindow? = null
        if (web && t.trackingPerm != null) {
            head.addView(Switch(a).apply {
                isChecked = !t.shieldsDown
                setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                    val perm = t.trackingPerm ?: return@setOnCheckedChangeListener
                    b.runtime.storageController.setPermission(
                        perm,
                        if (on) PermissionDelegate.ContentPermission.VALUE_DENY else PermissionDelegate.ContentPermission.VALUE_ALLOW,
                    )
                    pop?.dismiss()
                    t.session.reload()
                }
            })
        }
        col.addView(head)
        col.addView(View(a).apply { setBackgroundColor(AirUi.LINE) }, LinearLayout.LayoutParams(-1, ui.u(1).coerceAtLeast(1)).apply {
            topMargin = ui.u(12); bottomMargin = ui.u(8)
        })
        fun stat(label: String, n: String) {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(ui.text(label, 12.5, AirUi.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(ui.text(n, 13, AirUi.SHIELD, bold = true))
            col.addView(r, LinearLayout.LayoutParams(-1, ui.u(26)))
        }
        val ads = AirEngine.adsBlocked[t.session] ?: 0
        stat("Trackers blocked on this page", t.trackers.toString())
        if (AirEngine.adBlocker != null) {
            stat("Ads & trackers blocked by uBlock Origin", ads.toString())
        } else {
            col.addView(ui.text(
                if (AirData.prefs(a).getBoolean(AirData.KEY_ADS, true)) "Ad blocker (uBlock Origin) is getting ready…" else "Ad blocking is off (Settings)",
                11.5, AirUi.FAINT,
            ).apply { setPadding(0, ui.u(4), 0, ui.u(4)) })
        }
        val action = AirEngine.adBlockerAction
        if (web && action != null) {
            col.addView(ui.text("Ad blocker panel for this site  ›", 12.5, AirUi.ACCENT).apply {
                setPadding(0, ui.u(8), 0, ui.u(8))
                background = ui.ripple(null, 6)
                setOnClickListener {
                    pop?.dismiss()
                    action.click()
                }
            })
        }
        col.addView(View(a).apply { setBackgroundColor(AirUi.LINE) }, LinearLayout.LayoutParams(-1, ui.u(1).coerceAtLeast(1)).apply {
            topMargin = ui.u(8); bottomMargin = ui.u(8)
        })
        val total = AirData.blockedTotal(a)
        val minutes = (total * 50 / 60_000L)
        col.addView(ui.text(
            "Air has blocked ${NumberFormat.getIntegerInstance().format(total)} trackers & ads" +
                (if (minutes > 0) " · about $minutes min saved" else ""),
            11.5, AirUi.MUTED,
        ).apply { maxLines = 2 })
        col.addView(ui.text("Shields settings", 12, AirUi.ACCENT).apply {
            setPadding(0, ui.u(10), 0, 0)
            setOnClickListener {
                pop?.dismiss()
                settings()
            }
        })
        pop = ui.panel(anchor, col, 300)
    }

    fun siteInfo(anchor: View, t: AirBrowser.Tab) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.u(16), ui.u(14), ui.u(16), ui.u(12))
        }
        val secure = t.secure || t.url.startsWith("https://")
        col.addView(ui.text(AirData.host(t.url), 15, AirUi.TEXT, bold = true))
        col.addView(ui.text(
            if (secure) "🔒  Connection is secure – what you send stays private." else "⚠  Not secure – don't type passwords or card numbers here.",
            12, if (secure) AirUi.GOOD else 0xFFFFB547.toInt(),
        ).apply {
            maxLines = 3
            setPadding(0, ui.u(8), 0, ui.u(10))
        })
        var pop: android.widget.PopupWindow? = null
        col.addView(ui.text("Clear cookies and data for this site", 12.5, AirUi.ACCENT).apply {
            setPadding(0, ui.u(6), 0, ui.u(6))
            setOnClickListener {
                pop?.dismiss()
                b.runtime.storageController.clearDataFromBaseDomain(AirData.host(t.url), StorageController.ClearFlags.ALL)
                ui.toast("Data for ${AirData.host(t.url)} cleared")
                t.session.reload()
            }
        })
        pop = ui.panel(anchor, col, 300)
    }

    /** uBlock Origin's own panel (turn it off for one site, element picker…), in a small window. */
    private fun extensionPopup(s: GeckoSession) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = GeckoView(a)
        v.setSession(s)
        d.setContentView(v, ViewGroup.LayoutParams(ui.u(360).coerceAtMost(a.resources.displayMetrics.widthPixels), ui.u(520).coerceAtMost((a.resources.displayMetrics.heightPixels * 0.9f).toInt())))
        d.window?.setBackgroundDrawable(ColorDrawable(AirUi.PANEL))
        d.setOnDismissListener {
            v.releaseSession()
            s.close()
        }
        d.show()
    }

    // ---- settings ----

    fun settings() {
        val p = AirData.prefs(a)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val d = a.resources.displayMetrics.density
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        val dp = a.resources.displayMetrics.density
        fun section(t: String) = col.addView(TextView(a).apply {
            text = t
            setTextColor(AirUi.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun switch(label: String, on: Boolean, change: (Boolean) -> Unit) = col.addView(Switch(a).apply {
            text = label
            isChecked = on
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> change(v) }
        })
        fun choice(options: List<String>, selected: Int, change: (Int) -> Unit) = col.addView(RadioGroup(a).apply {
            orientation = RadioGroup.VERTICAL
            options.forEachIndexed { i, o ->
                addView(RadioButton(a).apply {
                    id = View.generateViewId()
                    text = o
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    isChecked = i == selected
                    setOnClickListener { change(i) }
                })
            }
        })

        section("Search engine")
        choice(AirData.ENGINES.map { it.name }, p.getInt(AirData.KEY_ENGINE, 0)) { p.edit().putInt(AirData.KEY_ENGINE, it).apply() }
        switch("Search suggestions while typing", p.getBoolean(AirData.KEY_SUGGEST, true)) { p.edit().putBoolean(AirData.KEY_SUGGEST, it).apply() }

        section("Shields")
        switch("Block trackers", p.getBoolean(AirData.KEY_TRACKERS, true)) { AirEngine.setTrackersBlocked(a, it) }
        switch("Block ads (uBlock Origin)", p.getBoolean(AirData.KEY_ADS, true)) { AirEngine.setAdsBlocked(a, it) }

        section("Pages")
        switch("Desktop sites (like a laptop)", p.getBoolean(AirData.KEY_DESKTOP, true)) { p.edit().putBoolean(AirData.KEY_DESKTOP, it).apply() }
        val themes = listOf(GeckoRuntimeSettings.COLOR_SCHEME_DARK, GeckoRuntimeSettings.COLOR_SCHEME_LIGHT, GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
        choice(listOf("Dark pages", "Light pages", "Like the phone"), themes.indexOf(AirData.colorScheme(p)).coerceAtLeast(0)) {
            AirEngine.setColorScheme(a, themes[it])
        }

        section("Downloads")
        val dl = listOf("", AirDownloads.PC, AirDownloads.PHONE, AirDownloads.BOTH)
        choice(listOf("Ask where to save each time", "Always in the PC", "Always on the phone", "Always both"),
            dl.indexOf(p.getString(AirData.KEY_DOWNLOAD_TO, "")).coerceAtLeast(0)) {
            p.edit().putString(AirData.KEY_DOWNLOAD_TO, dl[it]).apply()
        }

        section("Tabs and new tab page")
        switch("Open the tabs from last time", p.getBoolean(AirData.KEY_RESTORE, true)) { p.edit().putBoolean(AirData.KEY_RESTORE, it).apply() }
        val bars = listOf("ntp", "always", "never")
        col.addView(TextView(a).apply {
            text = "Bookmarks bar"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        choice(listOf("On the new tab page", "Always", "Never"), bars.indexOf(p.getString(AirData.KEY_BAR, "ntp")).coerceAtLeast(0)) {
            p.edit().putString(AirData.KEY_BAR, bars[it]).apply()
            b.refreshBookmarksBar()
        }
        switch("Photos of the Earth (NASA)", p.getBoolean(AirData.KEY_NTP_PHOTOS, true)) { p.edit().putBoolean(AirData.KEY_NTP_PHOTOS, it).apply() }
        switch("Shortcuts to top sites", p.getBoolean(AirData.KEY_NTP_SITES, true)) { p.edit().putBoolean(AirData.KEY_NTP_SITES, it).apply() }
        switch("Shields counter", p.getBoolean(AirData.KEY_NTP_STATS, true)) { p.edit().putBoolean(AirData.KEY_NTP_STATS, it).apply() }

        section("Privacy")
        col.addView(TextView(a).apply {
            text = "Clear browsing data…"
            setTextColor(AirUi.BAD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            setOnClickListener { clearData() }
        })

        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Air settings")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun clearData() {
        val what = arrayOf("History", "Cookies and site sign-ins", "Cached pictures and files")
        val checked = booleanArrayOf(true, false, true)
        AlertDialog.Builder(a)
            .setTitle("Clear browsing data")
            .setMultiChoiceItems(what, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Clear") { _, _ ->
                if (checked[0]) AirData.clearHistory()
                var flags = 0L
                if (checked[1]) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA or
                    StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.PERMISSIONS
                if (checked[2]) flags = flags or StorageController.ClearFlags.ALL_CACHES
                if (flags != 0L) b.runtime.storageController.clearData(flags)
                ui.toast("Cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun about() {
        AlertDialog.Builder(a)
            .setTitle("Air")
            .setMessage(
                "AirCursor's browser, running on the phone's own engine (Mozilla GeckoView ${BuildConfig.MOZILLA_VERSION}).\n\n" +
                    "• Pages are laid out like on a laptop – the same size as the PC.\n" +
                    "• Shields: Gecko blocks trackers; uBlock Origin (open source, by Raymond Hill) blocks ads.\n" +
                    "• Downloads go into the PC, onto the phone, or both.\n" +
                    "• New tab photos: NASA, public domain.\n\n" +
                    "Air keeps your history and bookmarks on this phone only.",
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
