package com.aircursor.air

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Outline
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.aircursor.R
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Air's windows over AirCursor PC: the Air browser and app windows (Air Store, TS Station,
 * YouTube, Spotify…). Each can be maximized or a movable, resizable window; minimize keeps it
 * running; opening the same app again brings its window back.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class AirWindows(
    private val activity: Activity,
    private val host: FrameLayout,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun pickPhoneFiles(multiple: Boolean, done: (List<Uri>) -> Unit)
        fun requestPermissions(perms: Array<String>, done: (Boolean) -> Unit)
        fun openInPcBrowser(url: String)
        fun openFileInPc(path: String)
        fun assistant()
        fun windowsChanged()
    }

    inner class Win(val key: String, val frame: WinFrame) {
        lateinit var browser: AirBrowser
        var maximized = true
        var minimized = false
        var videoFull = false
        var x = 0f
        var y = 0f
        var w = 0
        var h = 0
        val title get() = browser.title()
    }

    private val ui = AirUi(activity)
    private val wins = ArrayList<Win>()      // last = in front
    private val prefs = AirData.prefs(activity)

    val windows: List<Win> get() = wins

    /** Space kept free at the top and bottom for the phone-drawn menu bar and dock (Mac style). */
    private var insetTop = 0
    private var insetBottom = 0

    fun setInsets(top: Int, bottom: Int) {
        if (top == insetTop && bottom == insetBottom) return
        insetTop = top
        insetBottom = bottom
        for (w in wins) layout(w)
    }
    val anyShown get() = wins.any { !it.minimized }
    fun top(): Win? = wins.lastOrNull { !it.minimized }

    /** The Air browser (one window, many tabs). [url] null: just show it. */
    fun openBrowser(url: String?) {
        val w = wins.firstOrNull { it.key == BROWSER } ?: create(BROWSER, null, maximized = prefs.getBoolean("win_max_$BROWSER", true))
        show(w)
        w.browser.open(url)
    }

    /** An app window; [size] "max" or "win" is only the first-time size (then the last one is kept). */
    fun openApp(key: String, title: String, url: String, size: String) {
        val existing = wins.firstOrNull { it.key == key }
        if (existing != null) {
            show(existing)
            // The app's own server started again (Air Store, TS Station: a new port): load it there.
            val app = existing.browser.app
            if (app != null && origin(app.url) != origin(url)) {
                app.url = url
                existing.browser.load(url)
            }
            return
        }
        val icon = when (key) {
            "airstore" -> R.drawable.ic_ui_store
            "tsstation" -> R.drawable.ic_ui_gamepad
            "music", "spotify", "ytmusic", "jiosaavn", "gaana" -> R.drawable.ic_ui_music
            else -> R.drawable.ic_air_logo
        }
        val w = create(key, AirBrowser.App(key, title, url, icon), maximized = prefs.getBoolean("win_max_$key", size != "win"))
        show(w)
        w.browser.open(null)
    }

    private fun create(key: String, app: AirBrowser.App?, maximized: Boolean): Win {
        val frame = WinFrame()
        val win = Win(key, frame)
        frame.win = win
        win.maximized = maximized
        val b = AirBrowser(activity, object : AirBrowser.Host {
            override fun pickPhoneFiles(multiple: Boolean, done: (List<Uri>) -> Unit) = callbacks.pickPhoneFiles(multiple, done)
            override fun requestPermissions(perms: Array<String>, done: (Boolean) -> Unit) = callbacks.requestPermissions(perms, done)
            override fun openInPcBrowser(url: String) {
                minimize(win)
                callbacks.openInPcBrowser(url)
            }
            override fun openFileInPc(path: String) {
                minimize(win)
                callbacks.openFileInPc(path)
            }
            override fun openInBrowser(url: String) = openBrowser(url)
            override fun toggleSize(b: AirBrowser) = toggleSize(win)
            override fun isMaximized(b: AirBrowser) = win.maximized || win.videoFull
            override fun minimize(b: AirBrowser) = minimize(win)
            override fun close(b: AirBrowser) = close(win)
            override fun fullScreen(b: AirBrowser, on: Boolean) {
                win.videoFull = on
                layout(win)
            }
            override fun assistant() = callbacks.assistant()
            override fun changed(b: AirBrowser) = callbacks.windowsChanged()
        }, app)
        win.browser = b
        frame.addView(b, FrameLayout.LayoutParams(-1, -1))
        // Resize grip (bottom-right corner) for the window size.
        val grip = View(activity)
        frame.grip = grip
        frame.addView(grip, FrameLayout.LayoutParams(ui.u(22), ui.u(22), Gravity.END or Gravity.BOTTOM))
        host.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        wins += win
        restoreGeometry(win)
        attachDrag(win)
        layout(win)
        return win
    }

    fun show(w: Win) {
        w.minimized = false
        focus(w)
    }

    fun focus(w: Win) {
        if (wins.lastOrNull() !== w) {
            wins.remove(w)
            wins += w
        }
        w.frame.bringToFront()
        restack()
        callbacks.windowsChanged()
    }

    fun minimize(w: Win) {
        w.minimized = true
        restack()
        callbacks.windowsChanged()
    }

    fun close(w: Win) {
        saveGeometry(w)
        w.browser.closeAll()
        host.removeView(w.frame)
        wins.remove(w)
        restack()
        callbacks.windowsChanged()
    }

    /**
     * Windows fully under a maximized one are hidden and rest (their pages pause drawing): pages are
     * drawn on separate surfaces, and only the window in front may show through.
     */
    private fun restack() {
        var covered = false
        for (w in wins.asReversed()) {
            if (w.minimized) {
                w.frame.visibility = View.GONE
                w.browser.setActive(false)
                continue
            }
            w.frame.visibility = if (covered) View.INVISIBLE else View.VISIBLE
            w.browser.setActive(!covered)
            if (w.maximized || w.videoFull) covered = true
        }
    }

    fun toggleSize(w: Win) {
        if (w.videoFull) return
        w.maximized = !w.maximized
        prefs.edit().putBoolean("win_max_${w.key}", w.maximized).apply()
        layout(w)
    }

    /** A game starts from TS Station: every window steps aside. */
    fun minimizeAll() {
        for (w in wins) if (!w.minimized) minimize(w)
    }

    /** …and comes back after the game (the one in front). */
    fun showLast() {
        wins.lastOrNull()?.let { show(it) }
    }

    /** Back key: the window in front first (its pages); with nothing to go back to, it is minimized. */
    fun back(): Boolean {
        val w = top() ?: return false
        if (!w.browser.back()) minimize(w)
        return true
    }

    fun pause() {
        for (w in wins) w.browser.setActive(false)
    }

    fun resume() = restack()

    fun closeAll() {
        for (w in wins.toList()) close(w)
    }

    /** The window under a point of the screen (for the hand cursor), or null for the PC desktop. */
    fun windowAt(x: Float, y: Float): View? {
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        val px = x - loc[0]
        val py = y - loc[1]
        for (w in wins.asReversed()) {
            if (w.minimized || w.frame.visibility != View.VISIBLE) continue
            val f = w.frame
            if (px >= f.x && px < f.x + f.width && py >= f.y && py < f.y + f.height) return f
        }
        return null
    }

    /** Window list for the PC toolbar's Air button. */
    fun switcher(anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val items = ArrayList<AirUi.Item>()
        for (w in wins.asReversed()) {
            val icon = w.browser.app?.iconRes ?: R.drawable.ic_air_logo
            items += AirUi.Item(w.title + if (w.minimized) "  (minimized)" else "", icon) { show(w) }
        }
        items += AirUi.Item("New Air tab", R.drawable.ic_ui_plus, separatorBefore = items.isNotEmpty()) {
            openBrowser(null)
            wins.firstOrNull { it.key == BROWSER }?.browser?.openNewTab()
        }
        if (anyShown) items += AirUi.Item("Minimize all (show the PC)", R.drawable.ic_ui_minimize) { minimizeAll() }
        ui.menu(anchor, loc[0] - ui.u(280), loc[1], items, 280)
    }

    // ---- size and place ----

    private fun layout(w: Win) {
        val f = w.frame
        val hw = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val full = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        // A full-screen video covers everything; windows stay between the menu bar and the dock.
        val top = if (w.videoFull) 0 else insetTop
        val hh = if (w.videoFull) full else (full - insetTop - insetBottom).coerceAtLeast(full / 2)
        val whole = w.maximized || w.videoFull
        if (w.w <= 0 || w.h <= 0) {
            val app = w.browser.app
            val fw = if (app?.key == "airstore") 0.70f else 0.80f
            w.w = (hw * fw).roundToInt()
            w.h = (hh * 0.86f).roundToInt()
            val cascade = ui.u(26) * (wins.size - 1).coerceAtMost(4)
            w.x = ((hw - w.w) / 2f + cascade).coerceAtMost((hw - w.w).toFloat())
            w.y = ((hh - w.h) / 2f + cascade / 2f).coerceAtMost((hh - w.h).toFloat())
        }
        w.w = w.w.coerceIn(ui.u(420).coerceAtMost(hw), hw)
        w.h = w.h.coerceIn(ui.u(300).coerceAtMost(hh), hh)
        w.x = w.x.coerceIn(0f, (hw - w.w).toFloat().coerceAtLeast(0f))
        w.y = w.y.coerceIn(0f, (hh - w.h).toFloat().coerceAtLeast(0f))
        f.layoutParams = (f.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (whole) hw else w.w
            height = if (whole) hh else w.h
        }
        f.x = if (whole) 0f else w.x
        f.y = top + if (whole) 0f else w.y
        f.setRounded(!whole)
        f.grip?.visibility = if (whole) View.GONE else View.VISIBLE
        w.browser.setTextureBackend(!whole)
        f.requestLayout()
        w.browser.sizeChanged()
        restack()
    }

    private fun saveGeometry(w: Win) {
        if (w.w <= 0) return
        val o = JSONObject().put("x", w.x.toDouble()).put("y", w.y.toDouble()).put("w", w.w).put("h", w.h)
        prefs.edit().putString("win_geo_${w.key}", o.toString()).apply()
    }

    private fun restoreGeometry(w: Win) {
        try {
            val o = JSONObject(prefs.getString("win_geo_${w.key}", null) ?: return)
            w.x = o.getDouble("x").toFloat()
            w.y = o.getDouble("y").toFloat()
            w.w = o.getInt("w")
            w.h = o.getInt("h")
        } catch (_: Exception) {
        }
    }

    /** Move by the title bar / empty tab strip (double tap: maximize); resize by the corner. */
    private fun attachDrag(w: Win) {
        var dx = 0f
        var dy = 0f
        var lastTap = 0L
        var moved = false
        w.browser.dragArea.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focus(w)
                    dx = w.frame.x - e.rawX
                    dy = w.frame.y - e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> if (!w.maximized && !w.videoFull) {
                    val nx = e.rawX + dx
                    val ny = e.rawY + dy
                    if (moved || kotlin.math.abs(nx - w.frame.x) + kotlin.math.abs(ny - w.frame.y) > ui.u(4)) {
                        moved = true
                        w.x = nx.coerceIn(0f, (host.width - w.frame.width).toFloat().coerceAtLeast(0f))
                        w.y = (ny - insetTop).coerceIn(0f, (host.height - insetTop - insetBottom - w.frame.height).toFloat().coerceAtLeast(0f))
                        w.frame.x = w.x
                        w.frame.y = insetTop + w.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    if (!moved && now - lastTap < 350) {
                        toggleSize(w)
                        lastTap = 0
                    } else {
                        lastTap = if (moved) 0 else now
                    }
                    if (moved) saveGeometry(w)
                }
            }
            true
        }
        var sw = 0
        var sh = 0
        var sx = 0f
        var sy = 0f
        w.frame.grip?.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sw = w.frame.width
                    sh = w.frame.height
                    sx = e.rawX
                    sy = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    w.w = (sw + (e.rawX - sx)).roundToInt()
                    w.h = (sh + (e.rawY - sy)).roundToInt()
                    layout(w)
                }
                MotionEvent.ACTION_UP -> saveGeometry(w)
            }
            true
        }
    }

    /** A window: brings itself to the front when touched; rounded with a shadow when not maximized. */
    inner class WinFrame : FrameLayout(activity) {
        var win: Win? = null
        var grip: View? = null
        private var radius = 0f

        init {
            isClickable = true   // nothing reaches the PC desktop behind
            elevation = ui.uf(18)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }

        fun setRounded(on: Boolean) {
            radius = if (on) ui.uf(10) else 0f
            elevation = if (on) ui.uf(18) else 0f
            invalidateOutline()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) win?.let { if (wins.lastOrNull() !== it) focus(it) }
            return super.onInterceptTouchEvent(ev)
        }
    }

    private fun origin(u: String) = try {
        Uri.parse(u).let { "${it.scheme}://${it.authority}" }
    } catch (_: Exception) {
        u
    }

    companion object {
        const val BROWSER = "air"
    }
}
