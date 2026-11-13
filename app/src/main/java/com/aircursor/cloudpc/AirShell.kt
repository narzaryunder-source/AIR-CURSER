package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aircursor.R
import com.aircursor.air.AirUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * AirCursor PC's Mac-like shell, drawn by the phone (its GPU, not the PC's CPU): a menu bar at the
 * top, a dock at the bottom and a full-screen apps grid with search. It shows when the PC uses the
 * "mac" style. The PC tells it its apps (shell-index.py) and open windows (/tmp/cloudpc-windows);
 * the shell starts and switches apps with small commands in the desktop session.
 * Our own names and logo (no Apple names or marks).
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class AirShell(
    private val activity: Activity,
    private val host: FrameLayout,
    private val actions: Actions,
) {
    interface Actions {
        fun runPc(cmd: String)
        fun openAir(url: String? = null)
        fun airWindows(): com.aircursor.air.AirWindows?
        fun desktopSize(): Pair<Int, Int>
        fun showToolbar()
        fun keyboard()
        fun lock()
        fun assistant()
        fun boostMenu()
        fun exit()
        fun changed()
        fun tour()
    }

    class App(
        val id: String, val name: String, val path: String, val icon: String,
        val wmClass: String, val categories: String, val keywords: String,
    )

    class PcWin(val id: String, val instance: String, val cls: String, val title: String)

    private val ui = AirUi(activity)
    private val root = CloudPcEngine.rootfs(activity)
    private val prefs = activity.getSharedPreferences("air_shell", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var enabled = false
        private set
    private var apps: List<App> = emptyList()
    private var appsStamp = 0L
    private var winsStamp = 0L
    private var styleStamp = 0L
    private var pcWins: List<PcWin> = emptyList()
    private var activeId = ""
    private val icons = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var indexAsked = 0L

    // ---- views ----
    private val bar = LinearLayout(activity)
    private val appName = ui.text("", 12.5, AirUi.TEXT, bold = true)
    private val clock = ui.text("", 12, AirUi.TEXT)
    private val battery = ui.text("", 12, AirUi.TEXT)
    private val dock = LinearLayout(activity)
    private val dockScroll = HorizontalScrollView(activity)
    private val dockBox = FrameLayout(activity)
    private val handle = View(activity)
    private var grid: FrameLayout? = null
    private var dockShownUntil = 0L
    private var lastMargins = ""

    private val barH get() = ui.u(24)
    private val dockH get() = ui.u(54)
    private val dockGap get() = ui.u(6)
    private val autoHide get() = prefs.getBoolean("dock_autohide", false)

    private lateinit var widgets: ShellWidgets

    private val tick = object : Runnable {
        override fun run() {
            try {
                poll()
            } catch (_: Exception) {
            }
            main.postDelayed(this, 1000)
        }
    }

    init {
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ui.u(10), 0, ui.u(10), 0)
        bar.background = GradientDrawable().apply { setColor(0x8C16181D.toInt()) }
        bar.elevation = ui.uf(4)
        val logo = ImageView(activity).apply {
            setImageResource(R.drawable.logo_pc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ui.u(3), ui.u(3), ui.u(3), ui.u(3))
            background = ui.ripple(null, 6)
            contentDescription = "AirCursor PC menu"
            setOnClickListener { logoMenu(it) }
        }
        bar.addView(logo, LinearLayout.LayoutParams(ui.u(26), ui.u(22)))
        appName.setPadding(ui.u(8), 0, ui.u(8), 0)
        appName.background = ui.ripple(null, 5)
        appName.gravity = Gravity.CENTER_VERTICAL
        appName.setOnClickListener { appMenu(it) }
        bar.addView(appName, LinearLayout.LayoutParams(-2, -1).apply { marginStart = ui.u(4) })
        for ((label, act) in listOf("Files" to { launchId("thunar.desktop", "thunar") },
            "Air" to { actions.openAir(null) }, "Store" to { actions.runPc("airstore") })) {
            bar.addView(ui.text(label, 12, 0xFFC9CCD4.toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.u(8), 0, ui.u(8), 0)
                background = ui.ripple(null, 5)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ui.icon(R.drawable.ic_ui_mic, "Voice assistant", box = 22, size = 13) { actions.assistant() })
        bar.addView(ui.icon(R.drawable.ic_ui_keyboard, "Keyboard", box = 22, size = 13) { actions.keyboard() })
        bar.addView(ui.icon(R.drawable.ic_ui_search, "Search apps", box = 22, size = 13) { openGrid(search = true) })
        battery.setPadding(ui.u(8), 0, ui.u(6), 0)
        bar.addView(battery)
        clock.setPadding(ui.u(6), 0, 0, 0)
        bar.addView(clock)

        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        dock.setPadding(ui.u(8), 0, ui.u(8), 0)
        dockScroll.isHorizontalScrollBarEnabled = false
        dockScroll.isFillViewport = false
        dockScroll.addView(dock, FrameLayout.LayoutParams(-2, -1))
        dockBox.background = GradientDrawable().apply {
            cornerRadius = ui.uf(17)
            setColor(0x47F2F4F8)
            setStroke(ui.u(1).coerceAtLeast(1), 0x59FFFFFF)
        }
        dockBox.elevation = ui.uf(8)
        dockBox.addView(dockScroll, FrameLayout.LayoutParams(-2, -1))
        handle.background = GradientDrawable().apply {
            cornerRadius = ui.uf(3)
            setColor(0x99FFFFFF.toInt())
        }
        handle.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) showDockFor(5000)
            true
        }
        bar.visibility = View.GONE
        dockBox.visibility = View.GONE
        handle.visibility = View.GONE
        host.addView(bar, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
        host.addView(dockBox, FrameLayout.LayoutParams(-2, dockH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dockGap
            leftMargin = ui.u(12)
            rightMargin = ui.u(12)
        })
        host.addView(handle, FrameLayout.LayoutParams(ui.u(90), ui.u(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.u(3)
        })
        widgets = ShellWidgets(activity, host, ui, barH)
        main.post(tick)
    }


    fun destroy() = main.removeCallbacks(tick)

    // ---- what the PC says ----

    private fun guest(rel: String) = File(root, rel.removePrefix("/"))

    private fun poll() {
        val style = guest("/root/.config/xfce4/aircursor-style")
        val sm = style.lastModified()
        if (sm != styleStamp) {
            styleStamp = sm
            setEnabled(try { style.readText().trim() == "mac" } catch (_: Exception) { false })
        }
        if (!enabled) return
        val af = guest("/root/.cache/cloudpc-shell/apps.tsv")
        val am = af.lastModified()
        if (am != appsStamp) {
            appsStamp = am
            apps = readApps(af)
            rebuildDock()
            (grid?.tag as? Runnable)?.run()
        }
        // New apps installed since the list was made: ask the PC for a new list (at most every 2 min).
        val appDir = max(guest("/usr/share/applications").lastModified(), guest("/usr/local/share/applications").lastModified())
        val now = System.currentTimeMillis()
        if ((am == 0L || appDir > am) && now - indexAsked > 120_000) {
            indexAsked = now
            actions.runPc("nice -n 10 python3 /usr/local/lib/cloudpc/shell-index.py >/dev/null 2>&1 &")
        }
        val wf = guest("/tmp/cloudpc-windows")
        val wm = wf.lastModified()
        if (wm != winsStamp) {
            winsStamp = wm
            readWindows(wf)
            rebuildDock()
        }
        updateBar()
        // Widgets on the empty desktop only (no PC window, no Air window, no apps grid).
        widgets.update(pcWins.isEmpty() && actions.airWindows()?.top() == null && grid == null)
        applyInsets()   // cheap; tells the PC only when the space changes (e.g. after an AirBoost resize)
        if (autoHide && dockBox.visibility == View.VISIBLE && grid == null && now > dockShownUntil) hideDock()
    }

    private fun readApps(f: File): List<App> = try {
        f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5 || p[1].isBlank()) null
            else App(p[0], p[1], p[2], p[3], p[4], p.getOrElse(5) { "" }, p.getOrElse(6) { "" })
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    } catch (_: Exception) {
        apps
    }

    private fun readWindows(f: File) {
        val list = ArrayList<PcWin>()
        var act = ""
        try {
            for (line in f.readLines()) {
                val p = line.split('\t')
                if (p[0] == "active") act = p.getOrElse(1) { "" }
                else if (p.size >= 3) list += PcWin(p[0], p[1], p[2], p.getOrElse(3) { "" })
            }
        } catch (_: Exception) {
        }
        pcWins = list
        activeId = act
    }

    private fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (on && !autoHide) dockBox.visibility = View.VISIBLE else dockBox.visibility = View.GONE
        handle.visibility = if (on && autoHide) View.VISIBLE else View.GONE
        if (!on) {
            closeGrid()
            widgets.update(false)
        }
        lastMargins = ""
        applyInsets()
        actions.changed()
    }

    // ---- matching windows to apps ----

    private fun appFor(w: PcWin): App? {
        val c = w.cls.lowercase(Locale.ROOT)
        val i = w.instance.lowercase(Locale.ROOT)
        return apps.firstOrNull { val k = it.wmClass.lowercase(Locale.ROOT); k.isNotEmpty() && (k == c || k == i) }
            ?: apps.firstOrNull { val id = it.id.removeSuffix(".desktop").lowercase(Locale.ROOT); id == c || id == i }
    }

    private fun windowsOf(a: App): List<PcWin> = pcWins.filter { appFor(it) === a }

    private fun nameOf(w: PcWin) = appFor(w)?.name ?: w.cls.ifBlank { w.title }

    // ---- menu bar ----

    private val clockFormat = SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault())

    private fun updateBar() {
        clock.text = clockFormat.format(Date())
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        battery.text = if (pct in 0..100) "$pct%" else ""
        val air = actions.airWindows()?.top()
        appName.text = when {
            air != null -> air.title.ifBlank { "Air" }.take(40)
            else -> pcWins.firstOrNull { it.id == activeId }?.let { nameOf(it) } ?: "Desktop"
        }
    }

    private fun popup(anchor: View, items: List<AirUi.Item>, width: Int = 240) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        ui.menu(anchor, loc[0], loc[1] + anchor.height + ui.u(2), items, width)
    }

    private fun logoMenu(anchor: View) {
        popup(anchor, listOf(
            AirUi.Item("About this PC", R.drawable.ic_ui_info) { about() },
            AirUi.Item("Settings", R.drawable.ic_ui_sliders) { launchId("xfce-settings-manager.desktop", "xfce4-settings-manager") },
            AirUi.Item("Air Store", R.drawable.ic_ui_store) { actions.runPc("airstore") },
            AirUi.Item("All apps", R.drawable.ic_ui_tabs) { openGrid(false) },
            AirUi.Item("AirBoost and PC style…", R.drawable.ic_ui_bolt, separatorBefore = true) { actions.boostMenu() },
            AirUi.Item("Hide the dock automatically", R.drawable.ic_ui_minimize, checked = autoHide) { setAutoHide(!autoHide) },
            AirUi.Item("Widgets on the desktop", R.drawable.ic_ui_sun, checked = widgets.on) { widgets.setOn(!widgets.on) },
            AirUi.Item("Toolbar (keys, files, hand control)", R.drawable.ic_ui_menu) { actions.showToolbar() },
            AirUi.Item("How to use (tour)", R.drawable.ic_ui_help, separatorBefore = true) { actions.tour() },
            AirUi.Item("Lock", R.drawable.ic_ui_lock) { actions.lock() },
            AirUi.Item("Turn off AirCursor PC", R.drawable.ic_ui_close) { actions.exit() },
        ), 270)
    }

    private fun appMenu(anchor: View) {
        val air = actions.airWindows()
        val top = air?.top()
        if (top != null) {
            popup(anchor, listOf(
                AirUi.Item("Minimise", R.drawable.ic_ui_minimize) { air.minimize(top) },
                AirUi.Item("Full size on / off", R.drawable.ic_ui_maximize) { air.toggleSize(top) },
                AirUi.Item("Close", R.drawable.ic_ui_close) { air.close(top) },
            ))
            return
        }
        val w = pcWins.firstOrNull { it.id == activeId } ?: return
        popup(anchor, listOf(
            AirUi.Item("Minimise", R.drawable.ic_ui_minimize) { actions.runPc("xdotool windowminimize ${w.id}") },
            AirUi.Item("Full size on / off", R.drawable.ic_ui_maximize, shortcut = "Alt+F10") {
                actions.runPc("xdotool windowactivate --sync ${w.id} key --clearmodifiers alt+F10")
            },
            AirUi.Item("Close", R.drawable.ic_ui_close, shortcut = "Alt+F4") {
                actions.runPc("xdotool windowactivate --sync ${w.id} key --clearmodifiers alt+F4")
            },
            AirUi.Item("Quit ${nameOf(w).take(24)}", R.drawable.ic_ui_stop, separatorBefore = true) {
                val ids = pcWins.filter { appFor(it) != null && appFor(it) === appFor(w) }.ifEmpty { listOf(w) }
                actions.runPc(ids.joinToString("; ") { "xdotool windowactivate --sync ${it.id} key --clearmodifiers alt+F4" })
            },
        ))
    }

    private fun about() {
        val (dw, dh) = actions.desktopSize()
        val ram = (activity.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.let {
            val mi = android.app.ActivityManager.MemoryInfo(); it.getMemoryInfo(mi); mi.totalMem
        } ?: 0L
        val free = root.usableSpace
        android.app.AlertDialog.Builder(activity)
            .setTitle("AirCursor PC")
            .setIcon(R.drawable.logo_pc)
            .setMessage(
                "Ubuntu desktop on your phone\n\n" +
                    "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Chip: ${Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 } ?: Build.HARDWARE}\n" +
                    "Memory: ${"%.1f".format(ram / 1e9)} GB\n" +
                    "Free storage: ${"%.1f".format(free / 1e9)} GB\n" +
                    "Desktop: $dw × $dh\n" +
                    "Apps: ${apps.size}",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- dock ----

    private class DockItem(val key: String, val label: String, val bitmap: Bitmap?, val res: Int?, val running: Boolean,
                           val onTap: () -> Unit, val onLong: ((View) -> Unit)?)

    private val defaultPins = listOf(
        "thunar.desktop", "cloudpc-air.desktop", "airstore.desktop", "ts-station.desktop", "cloudpc-youtube.desktop",
        "xfce4-terminal.desktop", "cloudpc-vscode.desktop", "xfce-settings-manager.desktop",
    )

    private fun pins(): List<String> =
        prefs.getString("dock_pins", null)?.split('\n')?.filter { it.isNotBlank() } ?: defaultPins

    private fun setPins(p: List<String>) = prefs.edit().putString("dock_pins", p.joinToString("\n")).apply()

    fun rebuildDock() {
        if (!enabled) return
        val items = ArrayList<DockItem>()
        items += DockItem("grid", "All apps", null, R.drawable.ic_ui_tabs, false, { openGrid(false) }, null)
        val byId = apps.associateBy { it.id }
        val airWins = actions.airWindows()?.windows.orEmpty()
        val shownApps = HashSet<App>()
        for (id in pins()) {
            val a = byId[id] ?: continue
            shownApps += a
            val running = if (id == "cloudpc-air.desktop") airWins.any { it.browser.app == null } else windowsOf(a).isNotEmpty()
            items += DockItem(id, a.name, icon(a), null, running, { tapApp(a) }, { v -> dockItemMenu(v, a, pinned = true) })
        }
        // Running PC apps that are not in the dock.
        for (w in pcWins) {
            val a = appFor(w)
            if (a != null && a in shownApps) continue
            if (a != null) {
                shownApps += a
                items += DockItem(a.id, a.name, icon(a), null, true, { tapApp(a) }, { v -> dockItemMenu(v, a, pinned = false) })
            } else {
                items += DockItem(w.id, w.title.ifBlank { w.cls }, null, R.drawable.ic_ui_desktop, true,
                    { actions.runPc("xdotool windowactivate ${w.id}") }, null)
            }
        }
        // Air app windows (YouTube, Air Store, web apps): drawn by the phone, also in the dock.
        val appWins = airWins.filter { it.browser.app != null }
        if (appWins.isNotEmpty()) items += DockItem("sep", "", null, null, false, {}, null)
        for (w in appWins) {
            val app = w.browser.app!!
            items += DockItem("air:" + w.key, app.title, null, app.iconRes, true, {
                val air = actions.airWindows() ?: return@DockItem
                if (air.top() === w) air.minimize(w) else air.show(w)
            }, null)
        }
        dock.removeAllViews()
        for (it in items) dock.addView(dockView(it))
    }

    private fun dockView(item: DockItem): View {
        if (item.key == "sep") {
            return View(activity).apply { setBackgroundColor(0x40FFFFFF) }.also {
                it.layoutParams = LinearLayout.LayoutParams(ui.u(1).coerceAtLeast(1), ui.u(36)).apply {
                    marginStart = ui.u(6); marginEnd = ui.u(6)
                }
            }
        }
        val box = FrameLayout(activity)
        val img = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            when {
                item.bitmap != null -> setImageBitmap(item.bitmap)
                item.res != null -> {
                    setImageResource(item.res)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    setPadding(ui.u(9), ui.u(9), ui.u(9), ui.u(9))
                    background = GradientDrawable().apply {
                        cornerRadius = ui.uf(10)
                        colors = intArrayOf(0xFF5B6BFF.toInt(), 0xFF8E5BFF.toInt())
                    }
                }
            }
            contentDescription = item.label
            tooltipText = item.label
        }
        box.addView(img, FrameLayout.LayoutParams(ui.u(40), ui.u(40), Gravity.CENTER))
        if (item.running) {
            box.addView(View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDDFFFFFF.toInt()) }
            }, FrameLayout.LayoutParams(ui.u(4), ui.u(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = ui.u(2)
            })
        }
        box.layoutParams = LinearLayout.LayoutParams(ui.u(47), -1)
        // Magnify a little under the finger (like a dock), then act.
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    img.animate().scaleX(1.28f).scaleY(1.28f).translationY(-ui.uf(6)).setDuration(110).start()
                    showDockFor(5000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    img.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(140).start()
                }
            }
            false
        }
        box.setOnClickListener { item.onTap() }
        if (item.onLong != null) box.setOnLongClickListener { item.onLong.invoke(it); true }
        return box
    }

    private fun dockItemMenu(v: View, a: App, pinned: Boolean) {
        val wins = windowsOf(a)
        val items = ArrayList<AirUi.Item>()
        for (w in wins.take(6)) items += AirUi.Item(w.title.ifBlank { a.name }.take(40), R.drawable.ic_ui_desktop) {
            actions.runPc("xdotool windowactivate ${w.id}")
        }
        items += AirUi.Item("New window", R.drawable.ic_ui_plus, separatorBefore = wins.isNotEmpty()) { launch(a) }
        items += if (pinned) AirUi.Item("Remove from the dock", R.drawable.ic_ui_trash) { setPins(pins() - a.id); rebuildDock() }
        else AirUi.Item("Keep in the dock", R.drawable.ic_ui_star) { setPins(pins() + a.id); rebuildDock() }
        if (wins.isNotEmpty()) items += AirUi.Item("Quit", R.drawable.ic_ui_stop) {
            actions.runPc(wins.joinToString("; ") { "xdotool windowactivate --sync ${it.id} key --clearmodifiers alt+F4" })
        }
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        ui.menu(v, loc[0], loc[1] - ui.u(34) * items.size - ui.u(14), items, 240)
    }

    private fun tapApp(a: App) {
        if (a.id == "cloudpc-air.desktop") {
            actions.openAir(null)
            return
        }
        val wins = windowsOf(a)
        when {
            wins.isEmpty() -> launch(a)
            wins.any { it.id == activeId } && actions.airWindows()?.top() == null ->
                actions.runPc("xdotool windowminimize $activeId")
            else -> {
                actions.airWindows()?.minimizeAll()
                actions.runPc("xdotool windowactivate ${wins.last().id}")
            }
        }
    }

    private fun launch(a: App) {
        actions.airWindows()?.minimizeAll()
        actions.runPc("setsid gio launch ${q(a.path)} >/dev/null 2>&1 &")
        ui.toast("Opening ${a.name}…")
    }

    private fun launchId(id: String, fallback: String) {
        val a = apps.firstOrNull { it.id == id }
        if (a != null) launch(a) else actions.runPc("setsid $fallback >/dev/null 2>&1 &")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun icon(a: App): Bitmap? {
        if (a.icon.isBlank()) return null
        icons.get(a.icon)?.let { return it }
        val f = guest(a.icon)
        val b = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } ?: return null
        icons.put(a.icon, b)
        return b
    }

    // ---- auto-hide ----

    private fun setAutoHide(on: Boolean) {
        prefs.edit().putBoolean("dock_autohide", on).apply()
        if (on) hideDock() else {
            dockBox.visibility = View.VISIBLE
            handle.visibility = View.GONE
            dockBox.translationY = 0f
        }
        lastMargins = ""
        applyInsets()
    }

    private fun showDockFor(ms: Long) {
        dockShownUntil = System.currentTimeMillis() + ms
        if (dockBox.visibility != View.VISIBLE) {
            dockBox.visibility = View.VISIBLE
            dockBox.translationY = dockH.toFloat()
            dockBox.animate().translationY(0f).setDuration(160).start()
            handle.visibility = View.GONE
            actions.changed()
        }
    }

    private fun hideDock() {
        if (!autoHide) return
        dockBox.animate().translationY(dockH.toFloat() + dockGap).setDuration(160).withEndAction {
            dockBox.visibility = View.GONE
            dockBox.translationY = 0f
            if (enabled) handle.visibility = View.VISIBLE
            actions.changed()
        }.start()
    }

    /** A tap on the PC picture: an auto-hidden dock goes away. */
    fun desktopTouched() {
        if (autoHide && dockBox.visibility == View.VISIBLE && grid == null) dockShownUntil = 0
    }

    // ---- space for the bar and the dock ----

    /** Maximized PC windows stop under the menu bar and above the dock; Air windows too. */
    fun applyInsets() {
        val hw = host.width
        val hh = host.height
        if (hw <= 0 || hh <= 0) {
            host.post { applyInsets() }
            return
        }
        val top = if (enabled) barH else 0
        val bottom = if (enabled && !autoHide) dockH + dockGap * 2 else 0
        actions.airWindows()?.setInsets(top, bottom)
        val (dw, dh) = actions.desktopSize()
        val scale = min(hw.toFloat() / dw, hh.toFloat() / dh)
        val off = (hh - dh * scale) / 2f
        val mt = max(0, ceil((top - off) / scale).toInt())
        val mb = max(0, ceil((bottom - off) / scale).toInt())
        val key = "$mt/$mb"
        if (key == lastMargins) return
        lastMargins = key
        actions.runPc(
            "xfconf-query -c xfwm4 -p /general/margin_top --create -t int -s $mt; " +
                "xfconf-query -c xfwm4 -p /general/margin_bottom --create -t int -s $mb",
        )
    }

    fun bringToFront() {
        if (!enabled) return
        widgets.view.bringToFront()
        bar.bringToFront()
        dockBox.bringToFront()
        handle.bringToFront()
        grid?.bringToFront()
    }

    // ---- apps grid ----

    val gridOpen get() = grid != null

    fun closeGrid(): Boolean {
        val g = grid ?: return false
        grid = null
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(g.windowToken, 0)
        g.animate().alpha(0f).setDuration(140).withEndAction { host.removeView(g) }.start()
        blurDesktop(false)
        actions.changed()
        return true
    }

    fun toggleGrid() = if (grid != null) closeGrid() else { openGrid(false); true }

    fun openGrid(search: Boolean) {
        if (grid != null) {
            closeGrid()
            return
        }
        val g = FrameLayout(activity)
        g.setBackgroundColor(0xB30B0C10.toInt())
        g.isClickable = true
        g.setOnClickListener { closeGrid() }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.u(24), barH + ui.u(14), ui.u(24), ui.u(10))
        }
        val field = EditText(activity).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { cornerRadius = ui.uf(10); setColor(0x33FFFFFF) }
            setPadding(ui.u(14), 0, ui.u(14), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_search, 0, 0, 0)
            compoundDrawablePadding = ui.u(8)
            compoundDrawableTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
        }
        col.addView(field, LinearLayout.LayoutParams(ui.u(320), ui.u(34)))
        val gv = GridView(activity).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = ui.u(92)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = ui.u(10)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        var shown: List<App> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size + if (field.text.isNotBlank()) 1 else 0
            override fun getItem(position: Int) = shown.getOrNull(position)
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cell = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ui.u(6), 0, ui.u(4))
                    addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(ui.u(54), ui.u(54)))
                    addView(TextView(activity).apply {
                        setTextColor(Color.WHITE)
                        textSize = 11.5f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
                    }, LinearLayout.LayoutParams(ui.u(88), -2).apply { topMargin = ui.u(5) })
                    background = ui.ripple(null, 12)
                }
                val iv = cell.getChildAt(0) as ImageView
                val tv = cell.getChildAt(1) as TextView
                val a = shown.getOrNull(position)
                iv.imageTintList = null
                iv.background = null
                iv.setPadding(0, 0, 0, 0)
                if (a != null) {
                    val b = icon(a)
                    if (b != null) iv.setImageBitmap(b) else iv.setImageResource(R.drawable.ic_ui_desktop)
                    tv.text = a.name
                } else {
                    iv.setImageResource(R.drawable.ic_ui_globe)
                    iv.imageTintList = ColorStateList.valueOf(Color.WHITE)
                    val p = ui.u(12)
                    iv.setPadding(p, p, p, p)
                    iv.background = GradientDrawable().apply { cornerRadius = ui.uf(14); setColor(0xFF4F5BD5.toInt()) }
                    tv.text = "Search the web"
                }
                return cell
            }
        }
        fun filter() {
            val qy = field.text.toString().trim().lowercase(Locale.ROOT)
            shown = if (qy.isEmpty()) apps else apps.filter {
                it.name.lowercase(Locale.ROOT).contains(qy) || it.keywords.lowercase(Locale.ROOT).contains(qy) ||
                    it.id.lowercase(Locale.ROOT).contains(qy) || it.categories.lowercase(Locale.ROOT).contains(qy)
            }.sortedBy { if (it.name.lowercase(Locale.ROOT).startsWith(qy)) 0 else 1 }
            adapter.notifyDataSetChanged()
        }
        g.tag = Runnable { filter() }
        gv.adapter = adapter
        fun open(position: Int) {
            val a = shown.getOrNull(position)
            if (a != null) {
                closeGrid()
                tapApp(a)
            } else if (field.text.isNotBlank()) {
                val text = field.text.toString().trim()
                closeGrid()
                actions.openAir("https://www.google.com/search?q=" + android.net.Uri.encode(text))
            }
        }
        gv.setOnItemClickListener { _, _, position, _ -> open(position) }
        gv.setOnItemLongClickListener { v, _, position, _ ->
            val a = shown.getOrNull(position) ?: return@setOnItemLongClickListener false
            val pinned = a.id in pins()
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            ui.menu(v, loc[0], loc[1] + v.height / 2, listOf(
                AirUi.Item("Open", R.drawable.ic_ui_external) { open(position) },
                if (pinned) AirUi.Item("Remove from the dock", R.drawable.ic_ui_trash) { setPins(pins() - a.id); rebuildDock() }
                else AirUi.Item("Keep in the dock", R.drawable.ic_ui_star) { setPins(pins() + a.id); rebuildDock() },
            ), 220)
            true
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter()
        })
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                open(0)
                true
            } else false
        }
        col.addView(gv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(18) })
        g.addView(col, FrameLayout.LayoutParams(-1, -1))
        filter()
        grid = g
        g.alpha = 0f
        host.addView(g, FrameLayout.LayoutParams(-1, -1))
        bar.bringToFront()
        g.animate().alpha(1f).setDuration(160).start()
        blurDesktop(true)
        actions.changed()
        if (search) {
            field.requestFocus()
            field.post {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** The desktop picture behind the grid gets soft (phone GPU, Android 12+). */
    private fun blurDesktop(on: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val pc = host.getChildAt(0) ?: return
        pc.setRenderEffect(if (on) RenderEffect.createBlurEffect(ui.uf(18), ui.uf(18), Shader.TileMode.CLAMP) else null)
    }
}
