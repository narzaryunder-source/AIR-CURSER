package com.aircursor.air

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aircursor.R
import com.aircursor.cloudpc.CloudPcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/**
 * Air – AirCursor's browser, on the phone's own engine (Mozilla GeckoView): laptop-sized pages
 * like Brave in the PC, tabs with real site logos, Shields (trackers + ads), bookmarks, history,
 * downloads into the PC or the phone, and the voice assistant's mic.
 *
 * With [app] set it is an app window instead (Air Store, TS Station, YouTube, Spotify…):
 * no tabs or address bar, just a slim title bar.
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
class AirBrowser(
    internal val activity: Activity,
    internal val host: Host,
    val app: App? = null,
) : FrameLayout(activity) {

    /** An app window: [key] identifies it ("airstore", "youtube"…), [url] is its start page. */
    class App(val key: String, val title: String, val url: String, val iconRes: Int = R.drawable.ic_air_eagle)

    interface Host {
        fun pickPhoneFiles(multiple: Boolean, done: (List<Uri>) -> Unit)
        fun requestPermissions(perms: Array<String>, done: (Boolean) -> Unit)
        fun openInPcBrowser(url: String)
        fun openFileInPc(path: String)
        fun openInBrowser(url: String)
        fun toggleSize(b: AirBrowser)
        fun isMaximized(b: AirBrowser): Boolean
        fun minimize(b: AirBrowser)
        fun close(b: AirBrowser)
        fun fullScreen(b: AirBrowser, on: Boolean)
        fun assistant()
        fun changed(b: AirBrowser)
    }

    internal inner class Tab(val session: GeckoSession, val incognito: Boolean) {
        var title = "New tab"
        var url = ""
        var canBack = false
        var canForward = false
        var loading = false
        var progress = 0
        var secure = false
        var pending: String? = null
        var trackers = 0
        var adsCounted = 0
        var trackingPerm: PermissionDelegate.ContentPermission? = null
        var icon: Bitmap? = null
        var iconFor = ""
        var showWhenOpen = false
        var popupOf: Tab? = null
        val shieldsDown get() = trackingPerm?.value == PermissionDelegate.ContentPermission.VALUE_ALLOW
    }

    internal val ui = AirUi(activity)
    internal val runtime = AirEngine.runtime(activity)
    private val prefs = AirData.prefs(activity)
    internal val tabs = ArrayList<Tab>()
    internal var current = -1
    private val closedUrls = ArrayList<String>()
    private val gecko = GeckoView(activity)
    private val column = LinearLayout(activity)
    internal val content = FrameLayout(activity)
    private val progressLine = View(activity)
    private val panels = AirPanels(this)

    // browser chrome
    private var strip: LinearLayout? = null
    private var tabRow: LinearLayout? = null
    private var tabScroll: HorizontalScrollView? = null
    private var toolbar: LinearLayout? = null
    private var addressBox: LinearLayout? = null
    private var address: EditText? = null
    private var siteIcon: ImageView? = null
    private var star: ImageView? = null
    private var shieldIcon: ImageView? = null
    private var shieldBadge: TextView? = null
    internal var shieldButton: View? = null
    private var downloadsButton: View? = null
    private var backBtn: View? = null
    private var fwdBtn: View? = null
    private var reloadBtn: ImageView? = null
    private var barScroll: HorizontalScrollView? = null
    private var barRow: LinearLayout? = null
    private var maxButtons = ArrayList<ImageView>()
    private var suggestBox: LinearLayout? = null
    private var suggestToken = 0

    // app chrome
    private var appBar: LinearLayout? = null
    private var appBack: View? = null
    private var appTitle: TextView? = null

    // find in page
    private val findBar = LinearLayout(activity)
    private val findField = EditText(activity)
    private val findCount = TextView(activity)

    private var fullScreen = false
    private var pendingBlocked = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val mediaAllowed = HashSet<String>()
    private var visitedCache: HashSet<String>? = null
    private var closed = false

    /** Drag the window by this (the empty part of the tab strip, or the app title bar). */
    lateinit var dragArea: View
        private set

    private val dataListener: () -> Unit = {
        visitedCache = null
        if (!closed) {
            refreshBookmarksBar()
            refreshAddress()
            refreshDownloadsButton()
            panels.refresh()
        }
    }

    private val engineListener: () -> Unit = { if (!closed) countAds() }

    init {
        AirData.load(activity)
        setBackgroundColor(AirUi.TOOLBAR)
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(-1, -1))
        if (app == null) buildBrowserChrome() else buildAppBar(app)
        buildFindBar()
        content.setBackgroundColor(0xFF121318.toInt())
        content.addView(gecko, FrameLayout.LayoutParams(-1, -1))
        progressLine.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(AirUi.ACCENT, AirUi.ACCENT_2))
        progressLine.pivotX = 0f
        progressLine.scaleX = 0f
        content.addView(progressLine, FrameLayout.LayoutParams(-1, ui.u(2).coerceAtLeast(2), Gravity.TOP))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        AirData.listeners += dataListener
        AirEngine.listeners += engineListener
        AirDownloads.openInPc = { path -> host.openFileInPc(path) }
    }

    internal fun cur(): Tab? = tabs.getOrNull(current)

    // ======================================================================================
    // Chrome: tab strip, toolbar, bookmarks bar
    // ======================================================================================

    private fun buildBrowserChrome() {
        val s = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(AirUi.FRAME)
            setPadding(ui.u(8), 0, 0, 0)
        }
        strip = s
        val logo = ImageView(activity).apply {
            setImageResource(R.drawable.ic_air_eagle)
            contentDescription = "Air"
        }
        s.addView(logo, LinearLayout.LayoutParams(ui.u(20), ui.u(20)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = ui.u(8)
        })
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tabRow = row
        val sc = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, LayoutParams(-2, -1))
        }
        tabScroll = sc
        s.addView(sc, LinearLayout.LayoutParams(0, ui.u(31)))
        s.addView(ui.icon(R.drawable.ic_ui_plus, "New tab (Ctrl+T)", 28, 15, AirUi.MUTED) { newTab() }.apply {
            layoutParams = LinearLayout.LayoutParams(ui.u(28), ui.u(28)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = ui.u(2)
            }
        })
        val drag = View(activity)
        dragArea = drag
        drag.setOnClickListener { }
        s.addView(drag, LinearLayout.LayoutParams(0, -1, 1f))
        addWindowButtons(s)
        s.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) renderTabs() }
        column.addView(s, LinearLayout.LayoutParams(-1, ui.u(35)))

        // toolbar: ← → ⟳ [ 🔒 address ☆ ] Shields ⬇ 🎤 ≡
        val tb = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AirUi.TOOLBAR)
            setPadding(ui.u(6), ui.u(4), ui.u(6), ui.u(4))
        }
        toolbar = tb
        backBtn = ui.icon(R.drawable.ic_ui_left, "Back (Alt+←)") { goBack() }.also { tb.addView(it) }
        fwdBtn = ui.icon(R.drawable.ic_ui_right, "Forward (Alt+→)") { cur()?.session?.goForward() }.also { tb.addView(it) }
        reloadBtn = ui.icon(R.drawable.ic_ui_refresh, "Reload (Ctrl+R)") { reloadOrStop() }.also { tb.addView(it) }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(AirUi.FIELD, 16)
            setPadding(ui.u(10), 0, ui.u(3), 0)
        }
        addressBox = box
        val si = ImageView(activity).apply {
            setImageResource(R.drawable.ic_ui_search)
            imageTintList = ColorStateList.valueOf(AirUi.MUTED)
            setOnClickListener { cur()?.let { t -> if (t.url.startsWith("http")) panels.siteInfo(this, t) } }
        }
        siteIcon = si
        box.addView(si, LinearLayout.LayoutParams(ui.u(14), ui.u(14)).apply { marginEnd = ui.u(8) })
        val field = EditText(activity).apply {
            setSingleLine()
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Search ${AirData.engine(activity).name} or type a web address"
            setTextColor(AirUi.TEXT)
            setHintTextColor(AirUi.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(13.5))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString())
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, has ->
                box.background = ui.round(if (has) AirUi.FIELD_FOCUS else AirUi.FIELD, 16, if (has) AirUi.ACCENT else 0)
                if (has) {
                    val t = cur()
                    setText(t?.url?.takeIf { !isInternal(it) } ?: "")
                    selectAll()
                } else {
                    hideSuggestions()
                    refreshAddress()
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (hasFocus()) suggest(s?.toString().orEmpty())
                }
            })
        }
        address = field
        box.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        val st = ui.icon(R.drawable.ic_ui_star, "Bookmark this page (Ctrl+D)", 26, 15, AirUi.MUTED) { toggleBookmark() }
        star = st
        box.addView(st)
        tb.addView(box, LinearLayout.LayoutParams(0, ui.u(31), 1f).apply {
            marginStart = ui.u(6)
            marginEnd = ui.u(6)
        })

        // Shields with the number blocked on this page
        val shield = FrameLayout(activity).apply {
            background = ui.ripple(null, 15)
            contentDescription = "Shields: trackers and ads blocked"
            tooltipText = "Shields"
            setOnClickListener { panels.shields(this) }
        }
        val sic = ImageView(activity).apply {
            setImageResource(R.drawable.ic_ui_shield)
            imageTintList = ColorStateList.valueOf(AirUi.SHIELD)
        }
        shieldIcon = sic
        shield.addView(sic, FrameLayout.LayoutParams(ui.u(18), ui.u(18), Gravity.CENTER))
        val badge = TextView(activity).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(8.5))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ui.round(AirUi.SHIELD, 6)
            setPadding(ui.u(3), 0, ui.u(3), 0)
            includeFontPadding = false
            visibility = GONE
        }
        shieldBadge = badge
        shield.addView(badge, FrameLayout.LayoutParams(-2, ui.u(12), Gravity.TOP or Gravity.END))
        shieldButton = shield
        tb.addView(shield, LinearLayout.LayoutParams(ui.u(30), ui.u(30)))
        downloadsButton = ui.icon(R.drawable.ic_ui_download, "Downloads (Ctrl+J)") { panels.downloads() }.also {
            it.visibility = GONE
            tb.addView(it)
        }
        tb.addView(ui.icon(R.drawable.ic_ui_mic, "Voice assistant") { host.assistant() })
        tb.addView(ui.icon(R.drawable.ic_ui_menu, "Menu") { mainMenu(it) })
        column.addView(tb, LinearLayout.LayoutParams(-1, -2))

        // bookmarks bar
        val br = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.u(6), 0, ui.u(6), 0)
        }
        barRow = br
        val bs = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(AirUi.TOOLBAR)
            addView(br, LayoutParams(-2, -1))
            visibility = GONE
        }
        barScroll = bs
        column.addView(bs, LinearLayout.LayoutParams(-1, ui.u(28)))
        column.addView(View(activity).apply { setBackgroundColor(0xFF000000.toInt()) }, LinearLayout.LayoutParams(-1, ui.u(1).coerceAtLeast(1)))
        refreshButtons()
        refreshDownloadsButton()
    }

    private fun addWindowButtons(row: LinearLayout) {
        row.addView(ui.icon(R.drawable.ic_ui_minimize, "Minimize (tabs stay open)", 38, 14, AirUi.MUTED) { host.minimize(this) }.apply {
            background = ui.ripple(null, 0)
            layoutParams = LinearLayout.LayoutParams(ui.u(40), -1)
        })
        val max = ui.icon(R.drawable.ic_ui_maximize, "Maximize / window (F11)", 38, 13, AirUi.MUTED) { host.toggleSize(this) }.apply {
            background = ui.ripple(null, 0)
            layoutParams = LinearLayout.LayoutParams(ui.u(40), -1)
        }
        maxButtons += max
        row.addView(max)
        row.addView(ui.icon(R.drawable.ic_ui_close, if (app == null) "Close Air" else "Close", 38, 14, AirUi.MUTED) { host.close(this) }.apply {
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0xCCE81123.toInt()), null, ui.round(0xFFFFFFFF.toInt(), 0))
            layoutParams = LinearLayout.LayoutParams(ui.u(44), -1)
        })
    }

    private var textureBackend = false

    /**
     * Windowed: the page is drawn inside the window (TextureView) so overlapping windows stack
     * correctly. Maximized: a SurfaceView – the fastest way to show pages and videos.
     */
    fun setTextureBackend(on: Boolean) {
        if (on == textureBackend) return
        textureBackend = on
        gecko.setViewBackend(if (on) GeckoView.BACKEND_TEXTURE_VIEW else GeckoView.BACKEND_SURFACE_VIEW)
    }

    /** The window manager changed the size: show the right maximize/restore picture. */
    fun sizeChanged() {
        val maxed = host.isMaximized(this)
        for (b in maxButtons) b.setImageResource(if (maxed) R.drawable.ic_ui_restore else R.drawable.ic_ui_maximize)
        strip?.setPadding(ui.u(8), if (maxed) 0 else ui.u(2), 0, 0)
    }

    private fun buildAppBar(a: App) {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AirUi.FRAME)
            setPadding(ui.u(6), 0, 0, 0)
        }
        appBar = bar
        appBack = ui.icon(R.drawable.ic_ui_left, "Back", 28, 15, AirUi.MUTED) { goBack() }.also {
            it.visibility = GONE
            bar.addView(it)
        }
        val ic = ImageView(activity).apply { setImageResource(a.iconRes) }
        // Web apps (YouTube, Spotify…) show their real logo.
        if (a.iconRes == R.drawable.ic_air_eagle || a.iconRes == R.drawable.ic_ui_music) {
            AirIcons.get(activity, a.url) { bmp -> if (bmp != null) ic.setImageBitmap(bmp) }
        }
        bar.addView(ic, LinearLayout.LayoutParams(ui.u(17), ui.u(17)).apply {
            marginStart = ui.u(4)
            marginEnd = ui.u(9)
        })
        val title = ui.text(a.title, 12.5, AirUi.TEXT, bold = true)
        appTitle = title
        title.gravity = Gravity.CENTER_VERTICAL
        bar.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        dragArea = title
        bar.addView(ui.icon(R.drawable.ic_ui_refresh, "Reload", 30, 13, AirUi.MUTED) { reloadOrStop() })
        if (!a.url.contains("127.0.0.1") && !a.url.contains("localhost")) {
            bar.addView(ui.icon(R.drawable.ic_ui_external, "Open in the Air browser", 30, 13, AirUi.MUTED) {
                cur()?.url?.takeIf { it.startsWith("http") }?.let { u -> host.openInBrowser(u) }
            })
        }
        addWindowButtons(bar)
        column.addView(bar, LinearLayout.LayoutParams(-1, ui.u(32)))
    }

    private fun buildFindBar() {
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.setBackgroundColor(AirUi.TOOLBAR)
        findBar.setPadding(ui.u(10), ui.u(3), ui.u(6), ui.u(3))
        findBar.visibility = GONE
        findBar.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_ui_search)
            imageTintList = ColorStateList.valueOf(AirUi.MUTED)
        }, LinearLayout.LayoutParams(ui.u(14), ui.u(14)).apply { marginEnd = ui.u(8) })
        findField.apply {
            setSingleLine()
            hint = "Find in page"
            setTextColor(AirUi.TEXT)
            setHintTextColor(AirUi.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(13))
            background = ui.round(AirUi.FIELD, 6)
            setPadding(ui.u(8), 0, ui.u(8), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ ->
                find(text.toString(), backwards = false)
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = find(s?.toString().orEmpty(), backwards = false)
            })
        }
        findBar.addView(findField, LinearLayout.LayoutParams(0, ui.u(26), 1f))
        findCount.setTextColor(AirUi.MUTED)
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.uf(12))
        findCount.setPadding(ui.u(10), 0, ui.u(6), 0)
        findBar.addView(findCount)
        findBar.addView(ui.icon(R.drawable.ic_ui_up, "Previous", 28, 15) { find(findField.text.toString(), backwards = true) })
        findBar.addView(ui.icon(R.drawable.ic_ui_down, "Next", 28, 15) { find(findField.text.toString(), backwards = false) })
        findBar.addView(ui.icon(R.drawable.ic_ui_close, "Close (Esc)", 28, 13) { closeFind() })
        column.addView(findBar, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun renderTabs() {
        val row = tabRow ?: return
        val s = strip ?: return
        row.removeAllViews()
        val n = tabs.size.coerceAtLeast(1)
        val fixed = ui.u(8 + 28 + 28 + 2 + 40 + 40 + 44 + 48)
        val avail = (s.width - fixed).coerceAtLeast(ui.u(120))
        val w = (avail / n).coerceIn(ui.u(72), ui.u(230))
        for ((i, t) in tabs.withIndex()) row.addView(tabView(i, t, w), LinearLayout.LayoutParams(w, ui.u(31)))
        tabScroll?.layoutParams = (tabScroll?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = (w * tabs.size).coerceAtMost(avail)
        }
        tabScroll?.requestLayout()
        if (current >= 0) tabScroll?.post { tabScroll?.smoothScrollTo((current * w - avail / 2 + w / 2).coerceAtLeast(0), 0) }
    }

    private fun tabView(i: Int, t: Tab, w: Int): View {
        val active = i == current
        val r = ui.uf(9)
        val chip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.u(10), 0, ui.u(3), 0)
            background = if (active) {
                GradientDrawable().apply {
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(if (t.incognito) 0xFF2A2140.toInt() else AirUi.TOOLBAR)
                }
            } else {
                ui.ripple(null, 9)
            }
            setOnClickListener { select(i) }
            setOnLongClickListener {
                tabMenu(i)
                true
            }
        }
        val icon = ImageView(activity)
        when {
            t.incognito && isInternal(t.url) -> {
                icon.setImageResource(R.drawable.ic_ui_eye)
                icon.imageTintList = ColorStateList.valueOf(AirUi.ACCENT_2)
            }
            isInternal(t.url) || t.url.isEmpty() -> icon.setImageResource(R.drawable.ic_air_eagle)
            t.icon != null -> icon.setImageBitmap(t.icon)
            else -> {
                icon.setImageResource(R.drawable.ic_ui_globe)
                icon.imageTintList = ColorStateList.valueOf(AirUi.MUTED)
            }
        }
        if (t.loading) icon.alpha = 0.55f
        chip.addView(icon, LinearLayout.LayoutParams(ui.u(15), ui.u(15)).apply { marginEnd = ui.u(8) })
        chip.addView(ui.text(t.title.ifBlank { "New tab" }, 12, if (active) AirUi.TEXT else AirUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        if (active || w >= ui.u(118)) {
            chip.addView(ui.icon(R.drawable.ic_ui_close, "Close tab (Ctrl+W)", 22, 11, AirUi.MUTED) { closeTab(tabs.indexOf(t)) })
        }
        if (!active && i + 1 != current && i < tabs.size - 1) {
            val wrap = FrameLayout(activity)
            wrap.addView(chip, FrameLayout.LayoutParams(-1, -1))
            wrap.addView(View(activity).apply { setBackgroundColor(0x22FFFFFF) }, FrameLayout.LayoutParams(ui.u(1).coerceAtLeast(1), ui.u(16), Gravity.END or Gravity.CENTER_VERTICAL))
            return wrap
        }
        return chip
    }

    private fun refreshButtons() {
        val t = cur()
        backBtn?.alpha = if (t?.canBack == true || t?.popupOf != null) 1f else 0.35f
        fwdBtn?.alpha = if (t?.canForward == true) 1f else 0.35f
        reloadBtn?.setImageResource(if (t?.loading == true) R.drawable.ic_ui_close else R.drawable.ic_ui_refresh)
        appBack?.visibility = if (t?.canBack == true || t?.popupOf != null) VISIBLE else GONE
        val p = if (t?.loading == true) t.progress.coerceAtLeast(8) else 0
        progressLine.animate().cancel()
        if (p == 0) {
            progressLine.animate().alpha(0f).setDuration(220).withEndAction { progressLine.scaleX = 0f }.start()
        } else {
            progressLine.alpha = 1f
            progressLine.animate().scaleX(p / 100f).setDuration(160).start()
        }
    }

    internal fun refreshAddress() {
        val f = address ?: return
        val t = cur()
        val u = t?.url.orEmpty()
        val internal = u.isEmpty() || isInternal(u)
        if (!f.hasFocus()) f.setText(if (internal) "" else prettyUrl(u))
        siteIcon?.apply {
            when {
                internal -> {
                    setImageResource(R.drawable.ic_ui_search)
                    imageTintList = ColorStateList.valueOf(AirUi.MUTED)
                }
                t?.secure == true || u.startsWith("https://") -> {
                    setImageResource(R.drawable.ic_ui_lock)
                    imageTintList = ColorStateList.valueOf(AirUi.MUTED)
                }
                else -> {
                    setImageResource(R.drawable.ic_ui_alert)
                    imageTintList = ColorStateList.valueOf(0xFFFFB547.toInt())
                }
            }
        }
        star?.apply {
            visibility = if (internal) GONE else VISIBLE
            val on = AirData.isBookmarked(u)
            imageTintList = ColorStateList.valueOf(if (on) AirUi.ACCENT else AirUi.MUTED)
        }
        appTitle?.text = when {
            app == null -> ""
            t?.popupOf != null -> t.title.ifBlank { AirData.host(u) }
            else -> app.title
        }
        refreshShields()
    }

    private fun prettyUrl(u: String): CharSequence {
        val uri = Uri.parse(u)
        val h = uri.host ?: return u
        val auth = uri.authority ?: h
        val rest = u.substringAfter(auth, "").let { if (it == "/") "" else it }
        val b = SpannableStringBuilder(h.removePrefix("www.")).append(if (uri.port > 0) ":${uri.port}" else "")
        val start = b.length
        b.append(rest)
        b.setSpan(ForegroundColorSpan(AirUi.FAINT), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    internal fun refreshShields() {
        val t = cur()
        val n = (t?.trackers ?: 0) + (t?.let { AirEngine.adsBlocked[it.session] } ?: 0)
        shieldBadge?.apply {
            text = if (n > 99) "99+" else n.toString()
            visibility = if (n > 0 && t?.shieldsDown != true) VISIBLE else GONE
        }
        shieldIcon?.imageTintList = ColorStateList.valueOf(if (t?.shieldsDown == true) AirUi.FAINT else AirUi.SHIELD)
    }

    private fun refreshDownloadsButton() {
        downloadsButton?.visibility = if (AirData.downloads.any { System.currentTimeMillis() - it.time < 6 * 3600_000L }) VISIBLE else GONE
    }

    internal fun refreshBookmarksBar() {
        val bs = barScroll ?: return
        val row = barRow ?: return
        val mode = prefs.getString(AirData.KEY_BAR, "ntp")
        val t = cur()
        val show = AirData.bookmarks.isNotEmpty() && !fullScreen &&
            (mode == "always" || (mode == "ntp" && (t == null || isInternal(t.url) || t.url.isEmpty())))
        bs.visibility = if (show) VISIBLE else GONE
        if (!show) return
        row.removeAllViews()
        for (b in AirData.bookmarks) {
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.u(7), 0, ui.u(9), 0)
                background = ui.ripple(null, 6)
                setOnClickListener { load(b.url) }
                setOnLongClickListener {
                    panels.bookmarkMenu(this, b)
                    true
                }
            }
            val ic = ImageView(activity).apply { setImageResource(R.drawable.ic_ui_globe); imageTintList = ColorStateList.valueOf(AirUi.MUTED) }
            AirIcons.get(activity, b.url) { bmp ->
                if (bmp != null) {
                    ic.imageTintList = null
                    ic.setImageBitmap(bmp)
                }
            }
            chip.addView(ic, LinearLayout.LayoutParams(ui.u(14), ui.u(14)).apply { marginEnd = ui.u(6) })
            chip.addView(ui.text(b.title.ifBlank { AirData.host(b.url) }, 12, AirUi.TEXT).apply { maxWidth = ui.u(150) })
            row.addView(chip, LinearLayout.LayoutParams(-2, ui.u(24)).apply { marginEnd = ui.u(2) })
        }
    }

    private fun updateChrome() {
        renderTabs()
        refreshButtons()
        refreshAddress()
        refreshBookmarksBar()
        host.changed(this)
    }

    // ======================================================================================
    // Tabs
    // ======================================================================================

    val hasTabs get() = tabs.isNotEmpty()

    /** Window title for the switcher. */
    fun title(): String = app?.title ?: cur()?.title?.ifBlank { null } ?: "Air"

    fun currentUrl(): String? = cur()?.url?.takeIf { it.startsWith("http") }

    private fun desktopDefault() = prefs.getBoolean(AirData.KEY_DESKTOP, true)

    private fun sessionSettings(incognito: Boolean) = GeckoSessionSettings.Builder()
        .usePrivateMode(incognito)
        .useTrackingProtection(prefs.getBoolean(AirData.KEY_TRACKERS, true))
        .userAgentMode(if (desktopDefault()) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .build()

    private fun makeTab(incognito: Boolean): Tab {
        val s = GeckoSession(sessionSettings(incognito))
        val t = Tab(s, incognito)
        s.navigationDelegate = navigation(t)
        s.progressDelegate = progressDelegate(t)
        s.contentDelegate = contentDelegate(t)
        s.historyDelegate = historyDelegate(t)
        s.contentBlockingDelegate = blockingDelegate(t)
        s.promptDelegate = prompts
        s.permissionDelegate = permissions
        s.selectionActionDelegate = BasicSelectionActionDelegate(activity)
        return t
    }

    /** A new tab with the new tab page (the voice assistant, the window switcher). */
    fun openNewTab() {
        newTab()
    }

    /** Opens [url] (null: the new tab page) in a new tab. */
    internal fun newTab(url: String? = null, incognito: Boolean = false, background: Boolean = false, after: Tab? = null): Tab {
        val t = makeTab(incognito)
        t.session.open(runtime)
        val index = if (after != null && tabs.contains(after)) tabs.indexOf(after) + 1 else tabs.size
        tabs.add(index, t)
        val target = url ?: ntpUrl(incognito)
        t.url = target
        if (url != null) t.title = AirData.host(url).ifEmpty { "New tab" }
        if (background) {
            t.pending = target
            if (index <= current) current++
            renderTabs()
        } else {
            select(index)
            t.session.loadUri(target)
        }
        saveTabsSoon()
        // With a keyboard attached, type right away (like Brave); on touch only, no keyboard pops up.
        if (!background && url == null && resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS) {
            post { focusAddress(select = false) }
        }
        return t
    }

    internal fun select(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        val prev = cur()
        if (prev != null && prev !== t) {
            prev.session.setActive(false)
            try {
                runtime.webExtensionController.setTabActive(prev.session, false)
            } catch (_: Exception) {
            }
        }
        current = i
        if (gecko.session !== t.session) {
            gecko.releaseSession()
            gecko.setSession(t.session)
        }
        t.session.setActive(true)
        try {
            runtime.webExtensionController.setTabActive(t.session, true)
        } catch (_: Exception) {
        }
        t.pending?.let {
            t.pending = null
            t.session.loadUri(it)
        }
        closeFind()
        updateChrome()
        saveTabsSoon()
    }

    internal fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        val wasCurrent = i == current
        if (wasCurrent) gecko.releaseSession()
        AirEngine.adsBlocked.remove(t.session)
        t.session.close()
        tabs.removeAt(i)
        if (!t.incognito && t.url.startsWith("http")) closedUrls += t.url
        if (tabs.isEmpty()) {
            current = -1
            renderTabs()
            saveTabsSoon()
            host.close(this)
            return
        }
        if (wasCurrent) {
            current = -1
            val back = t.popupOf?.let { tabs.indexOf(it) }?.takeIf { it >= 0 }
            select(back ?: i.coerceAtMost(tabs.size - 1))
        } else {
            if (i < current) current--
            updateChrome()
        }
        saveTabsSoon()
    }

    /** A menu where the finger (or the hand cursor) last touched Air. */
    private fun menuAtTouch(items: List<AirUi.Item>) {
        val me = IntArray(2)
        getLocationInWindow(me)
        ui.menu(this, me[0] + lastTouchX.toInt(), me[1] + lastTouchY.toInt(), items)
    }

    private fun tabMenu(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        menuAtTouch(listOf(
            AirUi.Item("New tab to the right", R.drawable.ic_ui_plus) { newTab(after = t) },
            AirUi.Item("Reload", R.drawable.ic_ui_refresh) { t.session.reload() },
            AirUi.Item("Duplicate", R.drawable.ic_ui_copy) { if (t.url.startsWith("http")) newTab(t.url, after = t) },
            AirUi.Item("Bookmark this tab", R.drawable.ic_ui_star, enabled = t.url.startsWith("http")) {
                AirData.toggleBookmark(t.url, t.title)
            },
            AirUi.Item("Close tab", R.drawable.ic_ui_close, "Ctrl+W", separatorBefore = true) { closeTab(tabs.indexOf(t)) },
            AirUi.Item("Close other tabs", R.drawable.ic_ui_close, enabled = tabs.size > 1) {
                for (o in tabs.filter { it !== t }) closeTab(tabs.indexOf(o))
            },
            AirUi.Item("Reopen closed tab", R.drawable.ic_ui_history, "Ctrl+Shift+T", enabled = closedUrls.isNotEmpty()) { reopenClosed() },
        ))
    }

    private fun reopenClosed() {
        val u = closedUrls.removeLastOrNull() ?: return
        newTab(u)
    }

    // ======================================================================================
    // Public: windows manager, PC links, voice assistant
    // ======================================================================================

    /** Opens [url] (a link from the PC, the voice assistant) – or just shows Air with its tabs. */
    fun open(url: String?) {
        if (app != null) {
            if (tabs.isEmpty()) {
                val t = makeTab(false)
                t.session.open(runtime)
                tabs += t
                t.url = app.url
                select(0)
                t.session.loadUri(app.url)
            }
            return
        }
        if (tabs.isEmpty() && restoreTabs()) {
            if (url != null) newTab(url)
            return
        }
        when {
            url == null -> if (tabs.isEmpty()) newTab()
            else -> {
                val t = cur()
                if (t != null && (t.url.isEmpty() || isNtp(t.url)) && !t.loading) load(url) else newTab(url)
            }
        }
    }

    private fun restoreTabs(): Boolean {
        if (!prefs.getBoolean(AirData.KEY_RESTORE, true)) return false
        val (saved, sel) = AirData.savedTabs(activity)
        if (saved.isEmpty()) return false
        for ((u, title) in saved) {
            val t = makeTab(false)
            t.session.open(runtime)
            t.url = if (u == "air:ntp") ntpUrl() else u
            t.title = title.ifBlank { if (u == "air:ntp") "New tab" else AirData.host(u) }
            t.pending = t.url
            tabs += t
        }
        select(sel.coerceIn(0, tabs.size - 1))
        return true
    }

    private var saveQueued = false

    private fun saveTabsSoon() {
        if (app != null || saveQueued) return
        saveQueued = true
        postDelayed({
            saveQueued = false
            val list = tabs.filter { !it.incognito }.map { (if (isNtp(it.url) || it.url.isEmpty()) "air:ntp" else it.url) to it.title }
            val sel = tabs.filter { !it.incognito }.indexOf(cur()).coerceAtLeast(0)
            AirData.saveTabs(activity, list, sel)
        }, 800)
    }

    /** Loads [url] in the current tab. */
    fun load(url: String) {
        val t = cur()
        if (t == null) {
            newTab(url)
            return
        }
        t.url = url
        t.session.loadUri(url)
        refreshAddress()
    }

    fun search(q: String) = load(AirData.searchUrl(activity, q))

    fun goBack(): Boolean {
        val t = cur() ?: return false
        if (t.canBack) {
            t.session.goBack()
            return true
        }
        if (t.popupOf != null) {
            closeTab(tabs.indexOf(t))
            return true
        }
        return false
    }

    fun goForward() {
        cur()?.session?.goForward()
    }

    fun reloadOrStop() {
        val t = cur() ?: return
        if (t.loading) t.session.stop() else t.session.reload()
    }

    fun closeCurrentTab() = closeTab(current)

    fun nextTab(step: Int = 1) {
        if (tabs.size > 1) select(((current + step) % tabs.size + tabs.size) % tabs.size)
    }

    /** Voice: "scroll down" – one screen, like Page Down. */
    fun scrollPage(down: Boolean) {
        val code = if (down) KeyEvent.KEYCODE_PAGE_DOWN else KeyEvent.KEYCODE_PAGE_UP
        val t = SystemClock.uptimeMillis()
        gecko.requestFocus()
        gecko.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        gecko.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Voice typing: puts [text] into the box that has the focus on the page. */
    fun typeText(text: String) {
        gecko.requestFocus()
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray())
        if (events != null) {
            for (e in events) gecko.dispatchKeyEvent(e)
        } else {
            @Suppress("DEPRECATION")
            gecko.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    /** Back key / back gesture: inside Air first. False: nothing to go back to. */
    fun back(): Boolean {
        val t = cur() ?: return false
        when {
            fullScreen -> t.session.exitFullScreen()
            panels.close() -> Unit
            suggestBox?.visibility == VISIBLE -> hideSuggestions()
            findBar.visibility == VISIBLE -> closeFind()
            else -> return goBack()
        }
        return true
    }

    fun setActive(active: Boolean) {
        cur()?.session?.setActive(active)
    }

    /** Closes every tab (the window is going away). */
    fun closeAll() {
        closed = true
        AirData.listeners -= dataListener
        AirEngine.listeners -= engineListener
        flushBlocked()
        if (app == null) {
            val list = tabs.filter { !it.incognito }.map { (if (isNtp(it.url) || it.url.isEmpty()) "air:ntp" else it.url) to it.title }
            AirData.saveTabs(activity, list, tabs.filter { !it.incognito }.indexOf(cur()).coerceAtLeast(0))
        }
        gecko.releaseSession()
        for (t in tabs) {
            AirEngine.adsBlocked.remove(t.session)
            t.session.close()
        }
        tabs.clear()
        current = -1
    }

    fun focusAddress(select: Boolean = true) {
        val f = address ?: return
        f.requestFocus()
        if (select) f.selectAll()
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(f, 0)
    }

    // ======================================================================================
    // Address bar: go, suggestions
    // ======================================================================================

    private fun toUrl(input: String): String? {
        val q = input.trim()
        if (q.isEmpty()) return null
        val lower = q.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:") -> q
            !q.contains(' ') && Regex("""^(localhost|127\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+)(:\d+)?(/.*)?$""").matches(lower) -> "http://$q"
            !q.contains(' ') && q.contains('.') && Regex("""^[\w.-]+\.[a-z]{2,}(:\d+)?([/?#].*)?$""", RegexOption.IGNORE_CASE).matches(q) -> "https://$q"
            else -> AirData.searchUrl(activity, q)
        }
    }

    internal fun go(input: String, inNewTab: Boolean = false) {
        val url = toUrl(input) ?: return
        hideSuggestions()
        address?.clearFocus()
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        if (cur() == null || inNewTab) newTab(url) else load(url)
        gecko.requestFocus()
    }

    private fun suggest(q: String) {
        val token = ++suggestToken
        if (q.isBlank()) {
            hideSuggestions()
            return
        }
        val local = AirData.suggestions(q, 5)
        showSuggestions(q, local, emptyList())
        val engine = AirData.engine(activity)
        if (!prefs.getBoolean(AirData.KEY_SUGGEST, true) || engine.suggest == null || q.length > 80 || cur()?.incognito == true) return
        postDelayed({
            if (token != suggestToken) return@postDelayed
            Thread {
                val words = try {
                    val c = URL(engine.suggest.replace("%s", Uri.encode(q))).openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    c.disconnect()
                    val a = JSONArray(body)
                    val list = a.optJSONArray(1) ?: JSONArray()
                    (0 until list.length()).map { list.getString(it) }.take(5)
                } catch (_: Exception) {
                    emptyList()
                }
                post { if (token == suggestToken && address?.hasFocus() == true) showSuggestions(q, local, words) }
            }.start()
        }, 140)
    }

    private fun showSuggestions(q: String, sites: List<AirData.Site>, words: List<String>) {
        val box = suggestBox ?: LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(AirUi.PANEL, 10, 0x26FFFFFF)
            elevation = ui.uf(12)
            setPadding(0, ui.u(4), 0, ui.u(4))
            this@AirBrowser.addView(this, LayoutParams(-2, -2))
            suggestBox = this
        }
        box.removeAllViews()
        fun row(icon: Int, title: CharSequence, sub: String?, bmpUrl: String?, action: () -> Unit) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.u(12), 0, ui.u(12), 0)
                background = ui.ripple(null, 4)
                setOnClickListener { action() }
            }
            val ic = ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(AirUi.MUTED)
            }
            if (bmpUrl != null) {
                AirIcons.get(activity, bmpUrl) { b ->
                    if (b != null) {
                        ic.imageTintList = null
                        ic.setImageBitmap(b)
                    }
                }
            }
            r.addView(ic, LinearLayout.LayoutParams(ui.u(15), ui.u(15)).apply { marginEnd = ui.u(12) })
            r.addView(ui.text(title, 13, AirUi.TEXT), LinearLayout.LayoutParams(-2, -2))
            if (sub != null) r.addView(ui.text("  –  $sub", 12, AirUi.ACCENT), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(r, LinearLayout.LayoutParams(-1, ui.u(30)))
        }
        val engine = AirData.engine(activity)
        row(R.drawable.ic_ui_search, q, "${engine.name} Search", null) { go(q) }
        for (s in sites) {
            row(R.drawable.ic_ui_globe, s.title.ifBlank { AirData.host(s.url) }, s.url.removePrefix("https://").removePrefix("www.").take(60), s.url) {
                hideSuggestions()
                address?.clearFocus()
                load(s.url)
            }
        }
        for (w in words.filter { it != q }.take(5)) row(R.drawable.ic_ui_search, w, null, null) { go(w) }
        // Under the address bar, as wide as it.
        val ab = addressBox ?: return
        val loc = IntArray(2)
        val me = IntArray(2)
        ab.getLocationInWindow(loc)
        getLocationInWindow(me)
        box.layoutParams = (box.layoutParams as LayoutParams).apply {
            width = ab.width
            leftMargin = loc[0] - me[0]
            topMargin = loc[1] - me[1] + ab.height + ui.u(4)
        }
        box.visibility = VISIBLE
        box.bringToFront()
    }

    private fun hideSuggestions() {
        suggestToken++
        suggestBox?.visibility = GONE
    }

    // ======================================================================================
    // Bookmarks, find, menu
    // ======================================================================================

    private fun toggleBookmark() {
        val t = cur() ?: return
        if (!t.url.startsWith("http")) return
        val on = AirData.toggleBookmark(t.url, t.title)
        ui.toast(if (on) "Bookmarked – it's on the bookmarks bar" else "Bookmark removed")
        if (on && prefs.getString(AirData.KEY_BAR, "ntp") == "ntp" && AirData.bookmarks.size == 1) {
            prefs.edit().putString(AirData.KEY_BAR, "always").apply()
        }
        refreshBookmarksBar()
    }

    fun showFind() {
        findBar.visibility = VISIBLE
        findField.requestFocus()
        findField.selectAll()
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(findField, 0)
    }

    private fun closeFind() {
        if (findBar.visibility != VISIBLE) return
        findBar.visibility = GONE
        cur()?.session?.finder?.clear()
        findCount.text = ""
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(findField.windowToken, 0)
    }

    private fun find(text: String, backwards: Boolean) {
        val f = cur()?.session?.finder ?: return
        if (text.isEmpty()) {
            f.clear()
            findCount.text = ""
            return
        }
        f.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        f.find(text, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept({ r ->
            findCount.text = if (r == null || !r.found) "No results" else "${r.current} of ${r.total}"
        }, { _ -> })
    }

    private fun mainMenu(anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val t = cur()
        val page = t?.url?.takeIf { it.startsWith("http") }
        ui.menu(anchor, loc[0] + anchor.width - ui.u(270), loc[1] + anchor.height, listOf(
            AirUi.Item("New tab", R.drawable.ic_ui_plus, "Ctrl+T") { newTab() },
            AirUi.Item("New private tab", R.drawable.ic_ui_eye, "Ctrl+Shift+N") { newTab(incognito = true) },
            AirUi.Item("History", R.drawable.ic_ui_history, "Ctrl+H", separatorBefore = true) { panels.history() },
            AirUi.Item("Bookmarks", R.drawable.ic_ui_star, "Ctrl+Shift+O") { panels.bookmarks() },
            AirUi.Item("Downloads", R.drawable.ic_ui_download, "Ctrl+J") { panels.downloads() },
            AirUi.Item("Find in page", R.drawable.ic_ui_search, "Ctrl+F", separatorBefore = true, enabled = t != null) { showFind() },
            AirUi.Item("Save page as PDF", R.drawable.ic_ui_file, enabled = page != null) { savePdf() },
            AirUi.Item("Desktop site", R.drawable.ic_ui_desktop, checked = t?.session?.settings?.userAgentMode == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP) {
                toggleDesktop()
            },
            AirUi.Item("Open in the PC browser", R.drawable.ic_ui_external, enabled = page != null) { page?.let { host.openInPcBrowser(it) } },
            AirUi.Item("Settings", R.drawable.ic_ui_sliders, separatorBefore = true) { panels.settings() },
            AirUi.Item("About Air", R.drawable.ic_ui_info) { panels.about() },
        ), 270)
    }

    private fun toggleDesktop() {
        val t = cur() ?: return
        val desk = t.session.settings.userAgentMode != GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        t.session.settings.userAgentMode = if (desk) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.reload()
    }

    private fun savePdf() {
        val t = cur() ?: return
        t.session.saveAsPdf().accept({ input ->
            if (input != null) AirDownloads.stream(activity, input, (t.title.ifBlank { "page" }).take(80) + ".pdf", -1, "application/pdf")
        }, { _ -> ui.toast("This page can't be saved as PDF") })
    }

    // ======================================================================================
    // New tab page (assets/air/start.html) – data in the address after '#'
    // ======================================================================================

    internal fun isInternal(u: String) = u.startsWith(AirEngine.PAGES)

    private fun isNtp(u: String) = u.startsWith(AirEngine.START)

    internal fun ntpUrl(incognito: Boolean = false): String {
        val idx = prefs.getInt(AirData.KEY_NTP_PHOTO_INDEX, -1) + 1
        prefs.edit().putInt(AirData.KEY_NTP_PHOTO_INDEX, idx).apply()
        val e = AirData.engine(activity)
        val o = JSONObject()
            .put("photo", idx)
            .put("photos", prefs.getBoolean(AirData.KEY_NTP_PHOTOS, true))
            .put("sites", prefs.getBoolean(AirData.KEY_NTP_SITES, true))
            .put("stats", prefs.getBoolean(AirData.KEY_NTP_STATS, true))
            .put("blocked", AirData.blockedTotal(activity) + pendingBlocked)
            .put("engine", e.name)
            .put("search", e.search)
            .put("private", incognito)
        val top = JSONArray()
        for ((u, t) in AirData.topSites(activity)) top.put(JSONObject().put("u", u).put("t", t).put("h", AirData.host(u)))
        o.put("top", top)
        return AirEngine.START + "#" + Uri.encode(o.toString())
    }

    /** air://… links from Air's own pages (new tab page, error page). */
    private fun pageCommand(t: Tab, uri: String) {
        val u = Uri.parse(uri)
        when (u.host) {
            "hide" -> u.getQueryParameter("h")?.let {
                AirData.hideTopSite(activity, it)
                t.session.load(GeckoSession.Loader().uri(ntpUrl()).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
            }
            "set" -> {
                val k = u.getQueryParameter("k")
                val on = u.getQueryParameter("v") == "1"
                val key = when (k) {
                    "photos" -> AirData.KEY_NTP_PHOTOS
                    "sites" -> AirData.KEY_NTP_SITES
                    "stats" -> AirData.KEY_NTP_STATS
                    else -> null
                }
                if (key != null) prefs.edit().putBoolean(key, on).apply()
            }
            "voice" -> host.assistant()
            "settings" -> panels.settings()
            "history" -> panels.history()
            "bookmarks" -> panels.bookmarks()
            "downloads" -> panels.downloads()
        }
    }

    // ======================================================================================
    // Gecko delegates
    // ======================================================================================

    private fun navigation(t: Tab) = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            t.url = url.orEmpty()
            t.trackingPerm = perms.firstOrNull { it.permission == PermissionDelegate.PERMISSION_TRACKING }
            showIfPopup(t)
            if (isNtp(t.url)) t.title = if (t.incognito) "Private tab" else "New tab"
            loadIcon(t)
            if (t === cur()) {
                refreshAddress()
                refreshBookmarksBar()
            }
            saveTabsSoon()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            t.canBack = canGoBack
            if (t === cur()) refreshButtons()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            t.canForward = canGoForward
            if (t === cur()) refreshButtons()
        }

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            if (uri.startsWith("air://")) {
                // Only Air's own pages may send commands.
                if (isInternal(t.url) || request.triggerUri?.startsWith(AirEngine.PAGES) == true) post { pageCommand(t, uri) }
                return GeckoResult.deny()
            }
            val scheme = Uri.parse(uri).scheme?.lowercase(Locale.ROOT) ?: return GeckoResult.allow()
            if (scheme in WEB_SCHEMES) return GeckoResult.allow()
            openExternal(uri)   // app links: mail, phone, WhatsApp, Play Store…
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // Links that open a new window and sign-in popups: a new tab right after this one (in an
            // app window: shown over the app until it closes). Gecko opens the session itself.
            val nt = makeTab(t.incognito)
            nt.url = uri
            nt.title = AirData.host(uri).ifEmpty { "Loading…" }
            nt.showWhenOpen = true
            if (app != null) nt.popupOf = t
            tabs.add(tabs.indexOf(t) + 1, nt)
            renderTabs()
            postDelayed({ showIfPopup(nt) }, 60)
            postDelayed({ showIfPopup(nt) }, 400)
            return GeckoResult.fromValue(nt.session)
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            val kind = when {
                error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> "security"
                error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "unsafe"
                error.code == WebRequestError.ERROR_UNKNOWN_HOST -> "host"
                error.code == WebRequestError.ERROR_OFFLINE -> "offline"
                error.code == WebRequestError.ERROR_NET_TIMEOUT -> "timeout"
                error.code == WebRequestError.ERROR_CONNECTION_REFUSED -> "refused"
                else -> "other"
            }
            return GeckoResult.fromValue(AirEngine.PAGES + "error.html?e=$kind&u=" + Uri.encode(uri.orEmpty()))
        }
    }

    /** A popup/new-window tab becomes visible once Gecko has opened it. */
    private fun showIfPopup(t: Tab) {
        if (!t.showWhenOpen || !t.session.isOpen || closed) return
        t.showWhenOpen = false
        val i = tabs.indexOf(t)
        if (i >= 0) select(i)
    }

    private fun loadIcon(t: Tab) {
        val h = AirData.host(t.url)
        if (h.isEmpty() || h == t.iconFor) return
        t.iconFor = h
        t.icon = null
        AirIcons.get(activity, t.url) { b ->
            if (t.iconFor == h) {
                t.icon = b
                renderTabs()
            }
        }
    }

    private fun progressDelegate(t: Tab) = object : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            t.loading = true
            t.progress = 8
            t.trackers = 0
            t.adsCounted = 0
            AirEngine.adsBlocked[session] = 0
            showIfPopup(t)
            if (t === cur()) {
                refreshButtons()
                refreshShields()
            }
            renderTabsSoon()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            t.loading = false
            flushBlocked()
            if (t === cur()) refreshButtons()
            renderTabsSoon()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            t.progress = progress
            if (t === cur()) refreshButtons()
        }

        override fun onSecurityChange(session: GeckoSession, securityInfo: ProgressDelegate.SecurityInformation) {
            t.secure = securityInfo.isSecure
            if (t === cur()) refreshAddress()
        }
    }

    private var tabsQueued = false

    private fun renderTabsSoon() {
        if (tabsQueued) return
        tabsQueued = true
        postDelayed({
            tabsQueued = false
            renderTabs()
        }, 120)
    }

    private fun contentDelegate(t: Tab) = object : ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            t.title = if (isNtp(t.url)) (if (t.incognito) "Private tab" else "New tab") else title.orEmpty()
            if (!t.incognito) AirData.titled(t.url, t.title)
            renderTabsSoon()
            if (t === cur()) {
                refreshAddress()
                host.changed(this@AirBrowser)
            }
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            this@AirBrowser.fullScreen = fullScreen
            val v = if (fullScreen) GONE else VISIBLE
            strip?.visibility = v
            toolbar?.visibility = v
            appBar?.visibility = v
            if (fullScreen) barScroll?.visibility = GONE else refreshBookmarksBar()
            host.fullScreen(this@AirBrowser, fullScreen)
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tabs.indexOf(t))
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            AirDownloads.start(activity, response)
            post { refreshDownloadsButton() }
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
            contextMenu(t, element)
        }

        override fun onCrash(session: GeckoSession) = restart(t)

        override fun onKill(session: GeckoSession) = restart(t)
    }

    private fun historyDelegate(t: Tab) = object : GeckoSession.HistoryDelegate {
        override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean>? {
            if (t.incognito) return GeckoResult.fromValue(false)
            val top = flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL != 0
            val skip = GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or
                GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR
            if (top && flags and skip == 0) {
                AirData.visited(url, if (t.url == url) t.title.takeIf { it != "New tab" } else null)
                visitedCache?.add(url)
            }
            return GeckoResult.fromValue(true)
        }

        override fun getVisited(session: GeckoSession, urls: Array<out String>): GeckoResult<BooleanArray>? {
            val set = visitedCache ?: HashSet(AirData.history.map { it.url }).also { visitedCache = it }
            return GeckoResult.fromValue(BooleanArray(urls.size) { urls[it] in set })
        }
    }

    private fun blockingDelegate(t: Tab) = object : ContentBlocking.Delegate {
        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (!event.isBlocking) return
            t.trackers++
            pendingBlocked++
            if (t === cur()) refreshShields()
        }
    }

    /** uBlock Origin's count per tab changed: add the new ones to the all-time total. */
    private fun countAds() {
        for (t in tabs) {
            val n = AirEngine.adsBlocked[t.session] ?: continue
            if (n > t.adsCounted) {
                pendingBlocked += n - t.adsCounted
                t.adsCounted = n
            }
        }
        refreshShields()
    }

    private fun flushBlocked() {
        if (pendingBlocked > 0) {
            AirData.addBlocked(activity, pendingBlocked)
            pendingBlocked = 0
        }
    }

    /** The page's process ended (memory): open the same page again in a fresh session. */
    private fun restart(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0 || closed) return
        val url = t.url.ifEmpty { ntpUrl(t.incognito) }
        if (i == current) gecko.releaseSession()
        AirEngine.adsBlocked.remove(t.session)
        t.session.close()
        val nt = makeTab(t.incognito)
        nt.session.open(runtime)
        nt.title = t.title
        nt.url = url
        nt.popupOf = t.popupOf
        tabs[i] = nt
        if (i == current) {
            current = -1
            select(i)
            nt.session.loadUri(url)
            ui.toast("The page was reloaded")
        } else {
            nt.pending = url
        }
    }

    // ---- right click / long press ----

    private fun contextMenu(t: Tab, e: ContentDelegate.ContextElement) {
        val items = ArrayList<AirUi.Item>()
        val link = e.linkUri?.takeIf { it.startsWith("http") }
        val src = e.srcUri?.takeIf { it.startsWith("http") }
        if (link != null) {
            if (app == null) {
                items += AirUi.Item("Open link in new tab", R.drawable.ic_ui_plus) { newTab(link, t.incognito, background = true, after = t) }
                items += AirUi.Item("Open link in private tab", R.drawable.ic_ui_eye) { newTab(link, incognito = true) }
            } else {
                items += AirUi.Item("Open link in Air", R.drawable.ic_ui_external) { host.openInBrowser(link) }
            }
            items += AirUi.Item("Copy link address", R.drawable.ic_ui_copy) { copy(link) }
            items += AirUi.Item("Save link as…", R.drawable.ic_ui_download) { saveUrl(link) }
        }
        if (src != null) {
            val what = when (e.type) {
                ContentDelegate.ContextElement.TYPE_VIDEO -> "video"
                ContentDelegate.ContextElement.TYPE_AUDIO -> "audio"
                ContentDelegate.ContextElement.TYPE_IMAGE -> "image"
                else -> null
            }
            if (what != null) {
                val sep = items.isNotEmpty()
                if (app == null) items += AirUi.Item("Open $what in new tab", R.drawable.ic_ui_image, separatorBefore = sep) { newTab(src, t.incognito, after = t) }
                items += AirUi.Item("Save $what…", R.drawable.ic_ui_download, separatorBefore = sep && app != null) { saveUrl(src) }
                items += AirUi.Item("Copy $what address", R.drawable.ic_ui_copy) { copy(src) }
            }
        }
        if (items.isEmpty()) {
            items += AirUi.Item("Back", R.drawable.ic_ui_left, "Alt+←", enabled = t.canBack) { goBack() }
            items += AirUi.Item("Reload", R.drawable.ic_ui_refresh, "Ctrl+R") { t.session.reload() }
            if (app == null) items += AirUi.Item("Bookmark this page", R.drawable.ic_ui_star, "Ctrl+D", enabled = t.url.startsWith("http")) { toggleBookmark() }
            items += AirUi.Item("Save page as PDF", R.drawable.ic_ui_file, enabled = t.url.startsWith("http")) { savePdf() }
        }
        menuAtTouch(items)
    }

    private fun copy(text: String) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", text))
        ui.toast("Copied")
    }

    private fun saveUrl(url: String) {
        val ref = cur()?.url?.takeIf { it.startsWith("http") }
        val req = WebRequest.Builder(url).apply { if (ref != null) referrer(ref) }.build()
        GeckoWebExecutor(runtime).fetch(req).accept({ r ->
            if (r != null) AirDownloads.start(activity, r)
            refreshDownloadsButton()
        }, { _ -> ui.toast("Could not download this") })
    }

    // ---- permissions: autoplay/DRM yes; camera and mic after asking; the rest no ----

    private val permissions = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = when (perm.permission) {
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS, PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                PermissionDelegate.PERMISSION_STORAGE_ACCESS,
                -> true
                else -> false   // location, notifications, VR, local network: not in Air
            }
            return GeckoResult.fromValue(
                if (allow) PermissionDelegate.ContentPermission.VALUE_ALLOW else PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            val cam = video?.firstOrNull { it.source == PermissionDelegate.MediaSource.SOURCE_CAMERA }
            val mic = audio?.firstOrNull { it.source == PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
            if (cam == null && mic == null) {
                callback.reject()   // screen sharing and other sources: not from Air
                return
            }
            val site = Uri.parse(uri).host?.removePrefix("www.") ?: "This site"
            val what = listOfNotNull(cam?.let { "camera" }, mic?.let { "microphone" }).joinToString(" and ")
            val key = "$site|$what"
            var answered = false
            fun grant() {
                val need = ArrayList<String>()
                if (cam != null) need += Manifest.permission.CAMERA
                if (mic != null) need += Manifest.permission.RECORD_AUDIO
                val missing = need.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    callback.grant(cam, mic)
                } else {
                    host.requestPermissions(missing.toTypedArray()) { ok -> if (ok) callback.grant(cam, mic) else callback.reject() }
                }
            }
            if (key in mediaAllowed) {
                grant()
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("Use your $what?")
                .setMessage("$site wants to use the phone's $what.")
                .setPositiveButton("Allow") { _, _ ->
                    answered = true
                    mediaAllowed += key
                    grant()
                }
                .setNegativeButton("Block") { _, _ ->
                    answered = true
                    callback.reject()
                }
                .setOnDismissListener { if (!answered) callback.reject() }
                .show()
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) {
            val want = permissions?.filter { it == Manifest.permission.CAMERA || it == Manifest.permission.RECORD_AUDIO }.orEmpty()
            val missing = want.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
            when {
                permissions.isNullOrEmpty() || (want.isNotEmpty() && missing.isEmpty()) -> callback.grant()
                missing.isNotEmpty() -> host.requestPermissions(missing.toTypedArray()) { ok -> if (ok) callback.grant() else callback.reject() }
                else -> callback.reject()
            }
        }
    }

    // ---- prompts: alert, confirm, select lists, sign-in boxes, dates, files ----

    private val prompts = object : PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            AlertDialog.Builder(activity)
                .setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: host(session))
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ -> r.complete(prompt.dismiss()) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            AlertDialog.Builder(activity)
                .setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: host(session))
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton("Cancel") { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val f = field(prompt.defaultValue.orEmpty(), InputType.TYPE_CLASS_TEXT)
            AlertDialog.Builder(activity)
                .setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: host(session))
                .setMessage(prompt.message)
                .setView(padded(f))
                .setPositiveButton("OK") { _, _ -> r.complete(prompt.confirm(f.text.toString())) }
                .setNegativeButton("Cancel") { _, _ -> r.complete(prompt.dismiss()) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val user = field(prompt.authOptions.username.orEmpty(), InputType.TYPE_CLASS_TEXT).apply { hint = "User name" }
            val pass = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "Password" }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(user)
                addView(pass)
            }
            AlertDialog.Builder(activity)
                .setTitle("Sign in – ${host(session)}")
                .setMessage(prompt.message)
                .setView(padded(box))
                .setPositiveButton("Sign in") { _, _ -> r.complete(prompt.confirm(user.text.toString(), pass.text.toString())) }
                .setNegativeButton("Cancel") { _, _ -> r.complete(prompt.dismiss()) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val all = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun flat(list: Array<PromptDelegate.ChoicePrompt.Choice>?) {
                for (ch in list ?: return) {
                    if (ch.separator) continue
                    if (ch.items != null) flat(ch.items) else if (!ch.disabled) all += ch
                }
            }
            flat(prompt.choices)
            val labels = all.map { it.label }.toTypedArray()
            val b = AlertDialog.Builder(activity).setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: prompt.message)
            var answered = false
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = all.map { it.selected }.toBooleanArray()
                b.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("OK") { _, _ ->
                        answered = true
                        r.complete(prompt.confirm(all.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }
                    .setNegativeButton("Cancel") { _, _ -> answered = true; r.complete(prompt.dismiss()) }
            } else {
                b.setSingleChoiceItems(labels, all.indexOfFirst { it.selected }) { d, i ->
                    answered = true
                    r.complete(prompt.confirm(all[i]))
                    d.dismiss()
                }
            }
            b.setOnCancelListener { if (!answered) r.complete(prompt.dismiss()) }.show()
            return r
        }

        override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val now = Calendar.getInstance()
            when (prompt.type) {
                PromptDelegate.DateTimePrompt.Type.DATE -> {
                    val d = prompt.defaultValue?.split('-')?.mapNotNull { it.toIntOrNull() }
                    DatePickerDialog(activity, { _, y, m, day ->
                        r.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, day)))
                    }, d?.getOrNull(0) ?: now.get(Calendar.YEAR), (d?.getOrNull(1) ?: (now.get(Calendar.MONTH) + 1)) - 1,
                        d?.getOrNull(2) ?: now.get(Calendar.DAY_OF_MONTH)).apply {
                        setOnCancelListener { r.complete(prompt.dismiss()) }
                    }.show()
                }
                PromptDelegate.DateTimePrompt.Type.TIME -> {
                    val d = prompt.defaultValue?.split(':')?.mapNotNull { it.toIntOrNull() }
                    TimePickerDialog(activity, { _, h, min ->
                        r.complete(prompt.confirm(String.format(Locale.ROOT, "%02d:%02d", h, min)))
                    }, d?.getOrNull(0) ?: now.get(Calendar.HOUR_OF_DAY), d?.getOrNull(1) ?: now.get(Calendar.MINUTE), true).apply {
                        setOnCancelListener { r.complete(prompt.dismiss()) }
                    }.show()
                }
                else -> {
                    val f = field(prompt.defaultValue.orEmpty(), InputType.TYPE_CLASS_TEXT).apply {
                        hint = when (prompt.type) {
                            PromptDelegate.DateTimePrompt.Type.MONTH -> "YYYY-MM"
                            PromptDelegate.DateTimePrompt.Type.WEEK -> "YYYY-Www"
                            else -> "YYYY-MM-DDTHH:MM"
                        }
                    }
                    AlertDialog.Builder(activity).setTitle("Choose").setView(padded(f))
                        .setPositiveButton("OK") { _, _ -> r.complete(prompt.confirm(f.text.toString())) }
                        .setNegativeButton("Cancel") { _, _ -> r.complete(prompt.dismiss()) }
                        .setOnCancelListener { r.complete(prompt.dismiss()) }
                        .show()
                }
            }
            return r
        }

        override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
            val pcHome = File(CloudPcEngine.rootfs(activity), "root")
            fun fromPhone() = host.pickPhoneFiles(multiple) { uris ->
                r.complete(if (uris.isEmpty()) prompt.dismiss() else prompt.confirm(activity, uris.toTypedArray()))
            }
            if (!pcHome.isDirectory) {
                fromPhone()
                return r
            }
            AlertDialog.Builder(activity)
                .setTitle("Upload a file from")
                .setItems(arrayOf("The PC (Documents, Downloads, Projects…)", "The phone")) { _, which ->
                    if (which == 0) {
                        PcFilePicker(activity, pcHome) { f ->
                            r.complete(if (f == null) prompt.dismiss() else prompt.confirm(activity, Uri.fromFile(f)))
                        }.show()
                    } else {
                        fromPhone()
                    }
                }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    // ======================================================================================
    // Keyboard shortcuts (Brave / Chrome keys) and touch position for menus
    // ======================================================================================

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && shortcut(e)) return true
        return super.dispatchKeyEvent(e)
    }

    private fun shortcut(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val k = e.keyCode
        val browser = app == null
        when {
            k == KeyEvent.KEYCODE_ESCAPE -> when {
                suggestBox?.visibility == VISIBLE -> hideSuggestions()
                findBar.visibility == VISIBLE -> closeFind()
                panels.close() -> Unit
                cur()?.loading == true -> cur()?.session?.stop()
                else -> return false
            }
            k == KeyEvent.KEYCODE_F11 -> host.toggleSize(this)
            k == KeyEvent.KEYCODE_F5 || (ctrl && k == KeyEvent.KEYCODE_R) -> cur()?.session?.reload()
            alt && k == KeyEvent.KEYCODE_DPAD_LEFT -> goBack()
            alt && k == KeyEvent.KEYCODE_DPAD_RIGHT -> cur()?.session?.goForward()
            ctrl && k == KeyEvent.KEYCODE_F -> showFind()
            ctrl && k == KeyEvent.KEYCODE_W -> closeTab(current)
            !browser -> return false
            ctrl && shift && k == KeyEvent.KEYCODE_T -> reopenClosed()
            ctrl && shift && k == KeyEvent.KEYCODE_N -> newTab(incognito = true)
            ctrl && shift && k == KeyEvent.KEYCODE_O -> panels.bookmarks()
            ctrl && shift && k == KeyEvent.KEYCODE_B -> {
                val now = prefs.getString(AirData.KEY_BAR, "ntp")
                prefs.edit().putString(AirData.KEY_BAR, if (now == "always") "never" else "always").apply()
                refreshBookmarksBar()
            }
            ctrl && k == KeyEvent.KEYCODE_T -> newTab()
            ctrl && k == KeyEvent.KEYCODE_TAB -> nextTab(if (shift) -1 else 1)
            ctrl && k == KeyEvent.KEYCODE_PAGE_DOWN -> nextTab(1)
            ctrl && k == KeyEvent.KEYCODE_PAGE_UP -> nextTab(-1)
            ctrl && k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_8 -> select((k - KeyEvent.KEYCODE_1).coerceAtMost(tabs.size - 1))
            ctrl && k == KeyEvent.KEYCODE_9 -> select(tabs.size - 1)
            (ctrl && k == KeyEvent.KEYCODE_L) || (alt && k == KeyEvent.KEYCODE_D) || k == KeyEvent.KEYCODE_F6 -> focusAddress()
            ctrl && (k == KeyEvent.KEYCODE_K || k == KeyEvent.KEYCODE_E) -> focusAddress()
            ctrl && k == KeyEvent.KEYCODE_D -> toggleBookmark()
            ctrl && k == KeyEvent.KEYCODE_H -> panels.history()
            ctrl && k == KeyEvent.KEYCODE_J -> panels.downloads()
            ctrl && k == KeyEvent.KEYCODE_N -> newTab()
            else -> return false
        }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = ev.x
            lastTouchY = ev.y
            // A tap outside the address bar's suggestions closes them.
            val sb = suggestBox
            if (sb != null && sb.visibility == VISIBLE) {
                val inBox = ev.x >= sb.left && ev.x <= sb.right && ev.y >= sb.top && ev.y <= sb.bottom
                val ab = addressBox
                val loc = IntArray(2)
                val me = IntArray(2)
                ab?.getLocationInWindow(loc)
                getLocationInWindow(me)
                val inAddress = ab != null && ev.y + me[1] >= loc[1] && ev.y + me[1] <= loc[1] + ab.height
                if (!inBox && !inAddress) {
                    hideSuggestions()
                    address?.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private fun host(session: GeckoSession) = tabs.firstOrNull { it.session === session }?.url?.let { Uri.parse(it).host } ?: "Air"

    private fun openExternal(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:")) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    // Only what a web link may open: no component, no selector, browsable apps only.
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ui.toast("No app on the phone opens this link")
        } catch (_: Exception) {
        }
    }

    private fun field(value: String, type: Int) = EditText(activity).apply {
        setText(value)
        inputType = type
        setSingleLine()
    }

    private fun padded(v: View) = FrameLayout(activity).apply {
        val d = resources.displayMetrics.density
        setPadding((22 * d).toInt(), (6 * d).toInt(), (22 * d).toInt(), 0)
        addView(v)
    }

    /** Popups this browser opened (menus), closed when the window goes away. */
    internal val popups = ArrayList<PopupWindow>()

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "resource", "javascript", "moz-extension")
    }
}
