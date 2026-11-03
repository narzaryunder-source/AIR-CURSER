package com.aircursor.air

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.aircursor.R
import com.aircursor.cloudpc.CloudPcEngine
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Air's engine: one Gecko runtime for the whole app (created on first use, on the main thread). */
object AirEngine {
    const val START = "resource://android/assets/air/start.html"

    /** GeckoView ships for 64-bit ARM only here – the same phones that run AirCursor PC. */
    fun supported() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun runtime(c: Context): GeckoRuntime = GeckoRuntime.getDefault(c.applicationContext)
}

/**
 * Air – AirCursor's browser. Mozilla's Gecko engine running natively on the phone (GPU, hardware
 * video, fast JavaScript, Google sign-in works), with tabs, an address bar, downloads into the PC
 * and uploads from the PC or the phone.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class AirBrowser(
    private val activity: Activity,
    /** Files from the phone for a web page (the activity runs the system picker). */
    private val pickPhoneFiles: (multiple: Boolean, done: (List<Uri>) -> Unit) -> Unit,
    /** Window buttons in the tab strip: size, minimize, close – and full screen for videos. */
    private val window: Window,
) : LinearLayout(activity) {

    interface Window {
        fun toggleSize()
        fun minimize()
        fun closeAll()
        fun fullScreen(on: Boolean)
        fun openInPcBrowser(url: String)
    }

    private class Tab(val session: GeckoSession) {
        var title = "New tab"
        var url = ""
        var canBack = false
        var canForward = false
        var loading = false
        var progress = 0
    }

    private val dp = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).roundToInt()
    private val runtime = AirEngine.runtime(activity)
    private val prefs = activity.getSharedPreferences("air", Context.MODE_PRIVATE)

    private val tabs = ArrayList<Tab>()
    private var current = -1
    private val gecko = GeckoView(activity)
    private val tabRow = LinearLayout(activity)
    private val strip = LinearLayout(activity)
    private val toolbar = LinearLayout(activity)
    private val address = EditText(activity)
    private val progress = View(activity)
    private val downloads = TextView(activity)
    private var activeDownloads = 0
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var reloadBtn: ImageButton

    /** Drag the window by the empty part of the tab strip. */
    val dragHandle: View

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)

        // ---- tab strip: Air · tabs · + · window buttons ----
        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setBackgroundColor(STRIP)
        strip.setPadding(dp(8), 0, dp(2), 0)
        dragHandle = TextView(activity).apply {
            text = "Air"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(dp(4), 0, dp(10), 0)
        }
        strip.addView(dragHandle, LayoutParams(-2, -1))
        tabRow.orientation = HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        val tabScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, LayoutParams(-2, -1))
        }
        strip.addView(tabScroll, LayoutParams(0, -1, 1f))
        strip.addView(icon(R.drawable.ic_ui_plus, "New tab") { newTab(AirEngine.START) })
        strip.addView(icon(R.drawable.ic_ui_desktop, "Window size") { window.toggleSize() })
        strip.addView(icon(R.drawable.ic_ui_down, "Minimize (tabs stay open)") { window.minimize() })
        strip.addView(icon(R.drawable.ic_ui_close, "Close Air") { window.closeAll() })
        addView(strip, LayoutParams(-1, dp(38)))

        // ---- toolbar: back · forward · reload · address · menu ----
        toolbar.orientation = HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setBackgroundColor(BAR)
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4))
        backBtn = icon(R.drawable.ic_ui_left, "Back") { cur()?.session?.goBack() }
        fwdBtn = icon(R.drawable.ic_ui_right, "Forward") { cur()?.session?.goForward() }
        reloadBtn = icon(R.drawable.ic_ui_refresh, "Reload") {
            val t = cur() ?: return@icon
            if (t.loading) t.session.stop() else t.session.reload()
        }
        toolbar.addView(backBtn)
        toolbar.addView(fwdBtn)
        toolbar.addView(reloadBtn)
        address.apply {
            setSingleLine()
            hint = "Search or type a web address"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF0E1016.toInt())
            }
            setPadding(dp(14), 0, dp(14), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        toolbar.addView(address, LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        toolbar.addView(icon(R.drawable.ic_ui_menu, "Menu") { menu(it) })
        addView(toolbar, LayoutParams(-1, -2))

        progress.setBackgroundColor(ACCENT)
        progress.pivotX = 0f
        progress.scaleX = 0f
        addView(progress, LayoutParams(-1, dp(2)))

        addView(gecko, LayoutParams(-1, 0, 1f))
        downloads.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(BAR)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            visibility = GONE
        }
        addView(downloads, LayoutParams(-1, -2))
        refreshButtons()
    }

    private fun cur(): Tab? = tabs.getOrNull(current)

    // ---- public ----

    val hasTabs get() = tabs.isNotEmpty()

    /** Opens [url] (a link from the PC, or Air's start page) – in the blank start tab if there is one. */
    fun open(url: String) {
        val t = cur()
        if (t != null && (t.url.isEmpty() || t.url == AirEngine.START) && url != AirEngine.START) {
            t.session.loadUri(url)
        } else if (t == null || url != AirEngine.START || t.url != AirEngine.START) {
            newTab(url)
        }
    }

    /** Back inside Air (full screen video first, then page history). False: nothing to go back to. */
    fun back(): Boolean {
        val t = cur() ?: return false
        if (fullScreen) {
            t.session.exitFullScreen()
            return true
        }
        if (t.canBack) {
            t.session.goBack()
            return true
        }
        return false
    }

    fun setActive(active: Boolean) {
        cur()?.session?.setActive(active)
    }

    fun closeAll() {
        gecko.releaseSession()
        for (t in tabs) t.session.close()
        tabs.clear()
        current = -1
        renderTabs()
    }

    // ---- tabs ----

    private fun settings() = GeckoSessionSettings.Builder()
        .usePrivateMode(false)
        .useTrackingProtection(true)
        .userAgentMode(if (desktopSite()) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(if (desktopSite()) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .build()

    private fun desktopSite() = prefs.getBoolean("desktop_site", false)

    private fun makeTab(): Tab {
        val s = GeckoSession(settings())
        val t = Tab(s)
        s.navigationDelegate = navigation(t)
        s.progressDelegate = progressDelegate(t)
        s.contentDelegate = content(t)
        s.promptDelegate = prompts
        s.permissionDelegate = permissions
        return t
    }

    private fun newTab(url: String) {
        val t = makeTab()
        t.session.open(runtime)
        tabs += t
        select(tabs.size - 1)
        t.url = url
        t.session.loadUri(url)
    }

    private fun select(i: Int) {
        if (i !in tabs.indices) return
        cur()?.session?.setActive(false)
        gecko.releaseSession()
        current = i
        val t = tabs[i]
        gecko.setSession(t.session)
        t.session.setActive(true)
        address.setText(shownUrl(t.url))
        renderTabs()
        refreshButtons()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val wasCurrent = i == current
        if (wasCurrent) gecko.releaseSession()
        tabs[i].session.close()
        tabs.removeAt(i)
        if (tabs.isEmpty()) {
            current = -1
            renderTabs()
            window.minimize()
            return
        }
        if (wasCurrent) {
            current = -1
            select(i.coerceAtMost(tabs.size - 1))
        } else {
            if (i < current) current--
            renderTabs()
        }
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        for ((i, t) in tabs.withIndex()) {
            val chip = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(2), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(if (i == this@AirBrowser.current) BAR else 0x00000000)
                }
                setOnClickListener { select(i) }
            }
            chip.addView(TextView(activity).apply {
                text = t.title.ifBlank { "New tab" }
                setTextColor(if (i == current) Color.WHITE else 0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(150)
            })
            chip.addView(icon(R.drawable.ic_ui_close, "Close tab", small = true) { closeTab(i) })
            tabRow.addView(chip, LayoutParams(-2, dp(30)).apply { marginEnd = dp(4) })
        }
    }

    private fun refreshButtons() {
        val t = cur()
        backBtn.alpha = if (t?.canBack == true) 1f else 0.35f
        fwdBtn.alpha = if (t?.canForward == true) 1f else 0.35f
        reloadBtn.setImageResource(if (t?.loading == true) R.drawable.ic_ui_close else R.drawable.ic_ui_refresh)
        val p = if (t?.loading == true) t.progress.coerceAtLeast(5) else 0
        progress.animate().scaleX(p / 100f).setDuration(150).start()
    }

    private fun shownUrl(u: String) = if (u == AirEngine.START || u.startsWith("resource://")) "" else u

    /** Address bar: a web address, else a Google search. */
    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") || q.startsWith("about:") -> q
            !q.contains(' ') && (q.contains('.') || q.startsWith("localhost")) &&
                Regex("""^[\w.-]+(:\d+)?(/.*)?$""").matches(q) ->
                if (q.startsWith("localhost") || q.startsWith("127.")) "http://$q" else "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        address.clearFocus()
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
        val t = cur()
        if (t == null) newTab(url) else t.session.loadUri(url)
        gecko.requestFocus()
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(activity, anchor)
        m.menu.add(0, 1, 0, "New tab")
        m.menu.add(0, 2, 1, "Desktop site").apply { isCheckable = true; isChecked = desktopSite() }
        m.menu.add(0, 3, 2, "Open this page in the PC browser (Brave)")
        m.menu.add(0, 4, 3, "Clear browsing data")
        m.menu.add(0, 5, 4, "About Air")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(AirEngine.START)
                2 -> {
                    val desk = !desktopSite()
                    prefs.edit().putBoolean("desktop_site", desk).apply()
                    cur()?.session?.settings?.apply {
                        userAgentMode = if (desk) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                        viewportMode = if (desk) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                    }
                    cur()?.session?.reload()
                }
                3 -> cur()?.url?.takeIf { it.startsWith("http") }?.let { window.openInPcBrowser(it) }
                4 -> AlertDialog.Builder(activity)
                    .setTitle("Clear browsing data?")
                    .setMessage("Cookies, sign-ins, cache and site data in Air are deleted. Downloads in the PC stay.")
                    .setPositiveButton("Clear") { _, _ ->
                        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
                        toast("Browsing data cleared")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                5 -> AlertDialog.Builder(activity)
                    .setTitle("Air")
                    .setMessage(
                        "AirCursor's browser. It runs on the phone's own engine (Mozilla GeckoView), so pages, " +
                            "videos and AI apps are fast, and Google sign-in works.\n\nDownloads: the PC's Downloads folder.",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            true
        }
        m.show()
    }

    // ---- delegates ----

    private fun navigation(t: Tab) = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            t.url = url.orEmpty()
            if (t === cur() && !address.hasFocus()) address.setText(shownUrl(t.url))
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
            val scheme = Uri.parse(request.uri).scheme?.lowercase(Locale.ROOT) ?: return GeckoResult.allow()
            if (scheme in WEB_SCHEMES) return GeckoResult.allow()
            openExternal(request.uri)   // app links: mail, phone, WhatsApp, Play Store…
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // Links that open a new window / popups (sign-in windows too) become a new tab.
            val nt = makeTab()
            nt.url = uri
            tabs += nt
            select(tabs.size - 1)
            return GeckoResult.fromValue(nt.session)
        }
    }

    private fun progressDelegate(t: Tab) = object : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            t.loading = true
            t.progress = 5
            if (t === cur()) refreshButtons()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            t.loading = false
            if (t === cur()) refreshButtons()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            t.progress = progress
            if (t === cur()) refreshButtons()
        }
    }

    private var fullScreen = false

    private fun content(t: Tab) = object : ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            t.title = title.orEmpty()
            renderTabs()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            this@AirBrowser.fullScreen = fullScreen
            strip.visibility = if (fullScreen) GONE else VISIBLE
            toolbar.visibility = if (fullScreen) GONE else VISIBLE
            progress.visibility = if (fullScreen) GONE else VISIBLE
            window.fullScreen(fullScreen)
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tabs.indexOf(t))
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            download(response)
        }

        override fun onCrash(session: GeckoSession) = restart(t)

        override fun onKill(session: GeckoSession) = restart(t)
    }

    /** The page's process ended (memory): open the same page again in a fresh tab session. */
    private fun restart(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        val url = t.url.ifEmpty { AirEngine.START }
        if (i == current) gecko.releaseSession()
        t.session.close()
        val nt = makeTab()
        nt.session.open(runtime)
        nt.title = t.title
        tabs[i] = nt
        if (i == current) {
            current = -1
            select(i)
        }
        nt.session.loadUri(url)
        toast("The page was reloaded")
    }

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
                else -> false   // location, notifications, local network… not in Air (yet)
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
            callback.reject()   // camera / microphone: not in Air (yet)
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) {
            callback.reject()
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
                orientation = VERTICAL
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
            fun fromPhone() = pickPhoneFiles(multiple) { uris ->
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

    // ---- downloads go into the PC's Downloads folder ----

    private fun download(response: WebResponse) {
        val body = response.body
        if (body == null) {
            toast("This download is empty")
            return
        }
        // Big files over mobile data can pause for a while: do not give up after Gecko's short default.
        response.setReadTimeoutMillis(5 * 60 * 1000L)
        val headers = response.headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        val name = URLUtil.guessFileName(response.uri, headers["content-disposition"], headers["content-type"])
            .replace('/', '_').ifBlank { "download" }
        val total = headers["content-length"]?.toLongOrNull() ?: -1L
        val pcHome = File(CloudPcEngine.rootfs(activity), "root")
        val inPc = pcHome.isDirectory
        val dir = if (inPc) File(pcHome, "Downloads").apply { mkdirs() }
        else activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        activeDownloads++
        showDownload("Downloading $name…")
        Thread {
            var f = File(dir, name)
            var i = 1
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
            val part = File(dir, f.name + ".part")
            val ok = try {
                body.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400) {
                                last = now
                                val pct = if (total > 0) " – ${done * 100 / total}%" else ""
                                post { showDownload("Downloading $name$pct (${mb(done)})") }
                            }
                        }
                    }
                }
                part.renameTo(f)
            } catch (_: Exception) {
                part.delete()
                false
            }
            post {
                activeDownloads--
                showDownload(
                    when {
                        !ok -> "Download failed: $name – check the internet and try again"
                        inPc -> "Saved in the PC: Downloads/${f.name}"
                        else -> "Saved: ${f.name}"
                    },
                    hideAfter = 6000,
                )
            }
        }.start()
    }

    private fun mb(b: Long) = if (b >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", b / 1e6) else "${b / 1000} KB"

    private fun showDownload(text: String, hideAfter: Long = 0) {
        downloads.text = "⬇  $text"
        downloads.visibility = VISIBLE
        downloads.removeCallbacks(hideDownloads)
        if (hideAfter > 0) downloads.postDelayed(hideDownloads, hideAfter)
    }

    private val hideDownloads = Runnable { if (activeDownloads == 0) downloads.visibility = GONE }

    // ---- helpers ----

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
            toast("No app on the phone opens this link")
        } catch (_: Exception) {
        }
    }

    private fun field(value: String, type: Int) = EditText(activity).apply {
        setText(value)
        inputType = type
        setSingleLine()
    }

    private fun padded(v: View) = FrameLayout(activity).apply {
        setPadding(dp(22), dp(6), dp(22), 0)
        addView(v)
    }

    private fun icon(res: Int, description: String, small: Boolean = false, onClick: (View) -> Unit) = ImageButton(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = null
        contentDescription = description
        val p = if (small) dp(7) else dp(9)
        setPadding(p, p, p, p)
        layoutParams = LayoutParams(if (small) dp(26) else dp(38), if (small) dp(26) else dp(38))
        setOnClickListener { onClick(it) }
        setOnLongClickListener {
            toast(description)
            true
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val BG = 0xFF0B0D12.toInt()
        private const val STRIP = 0xFF0E1016.toInt()
        private const val BAR = 0xFF1C2029.toInt()
        private const val ACCENT = 0xFF6EA8FF.toInt()
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "resource", "javascript", "file", "moz-extension")
    }
}
