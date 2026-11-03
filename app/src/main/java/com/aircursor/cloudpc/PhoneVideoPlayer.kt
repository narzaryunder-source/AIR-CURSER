package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.aircursor.R
import java.io.File
import kotlin.math.roundToInt

/**
 * Smooth video on the phone's own video chip, in a small window floating over AirCursor PC.
 * The PC draws everything with the CPU, so videos there stutter; here Android decodes them in
 * hardware. YouTube plays through YouTube's official embedded player; video files from the PC
 * play straight from the PC's folder; other video sites open in the phone's web view.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class PhoneVideoPlayer(
    private val activity: Activity,
    private val host: FrameLayout,
    /** Web pages ask for files to upload: the phone's file picker. */
    private val pickFiles: ((ValueCallback<Array<Uri>>) -> Unit)? = null,
    /** "Open in the PC browser" from the fast window. */
    private val openInPc: ((String) -> Unit)? = null,
) {
    private val dp = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).roundToInt()

    private var panel: FrameLayout? = null
    private var web: WebView? = null
    private var video: VideoView? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var size = 1              // 0 small, 1 medium, 2 whole screen
    private var youtubeUrl: String? = null
    private var webMode = false
    private var titleView: TextView? = null

    val isOpen get() = panel != null

    /** [marker] = "<start seconds>|<link or file:///path>" as sent by cloudpc-phone-video. */
    fun play(marker: String) {
        val start = marker.substringBefore('|').toIntOrNull()?.coerceAtLeast(0) ?: 0
        val target = marker.substringAfter('|', marker).trim()
        val yt = youtubeId(target)
        when {
            yt != null -> open("YouTube") { youtube(it, yt, start) }.also {
                youtubeUrl = "https://www.youtube.com/watch?v=$yt" + if (start > 0) "&t=${start}s" else ""
            }
            target.startsWith("file://") -> {
                val f = guestFile(target.removePrefix("file://"))
                if (f == null) {
                    Toast.makeText(activity, "That video file could not be found", Toast.LENGTH_SHORT).show()
                    return
                }
                open(f.name) { file(it, f, start) }
            }
            target.startsWith("https://") || target.startsWith("http://") -> open(Uri.parse(target).host ?: "Video") { page(it, target) }
            else -> Toast.makeText(activity, "No video found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Fast web window: a website (YouTube, AI chats…) runs on the phone's own web engine – GPU,
     * hardware video and fast JavaScript – in a window over the PC. Downloads go to the PC's
     * Downloads folder.
     */
    fun browse(url: String) {
        val host = Uri.parse(url).host?.removePrefix("www.")?.removePrefix("m.") ?: "Web"
        open(host, web = true) { page(it, url) }
    }

    /** Back inside the web window; false when there is nothing to go back to. */
    fun back(): Boolean {
        val w = web ?: return false
        if (fullView != null) {
            exitFull()
            return true
        }
        if (webMode && w.canGoBack()) {
            w.goBack()
            return true
        }
        return false
    }

    fun pause() {
        web?.onPause()
        video?.pause()
    }

    fun resume() {
        web?.onResume()
    }

    fun close() {
        exitFull()
        web?.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        web = null
        video?.stopPlayback()
        video = null
        panel?.let { host.removeView(it) }
        panel = null
        youtubeUrl = null
        titleView = null
        webMode = false
    }

    // ---- the floating window ----

    private fun open(title: String, web: Boolean = false, fill: (FrameLayout) -> Unit) {
        close()
        webMode = web
        val p = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x40FFFFFF)
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
            isClickable = true   // touches stay in the player, never reach the desktop behind
        }
        val content = FrameLayout(activity)
        // Web pages start below the bar; videos use the whole window (the bar floats over them).
        p.addView(content, FrameLayout.LayoutParams(-1, -1).apply { if (web) topMargin = dp(40) })
        p.addView(bar(title, p), FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP))
        panel = p
        host.addView(p, FrameLayout.LayoutParams(1, 1))
        size = if (web) 2 else 1
        layout(firstTime = true)
        fill(content)
    }

    private fun bar(title: String, p: FrameLayout): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            background = if (webMode) GradientDrawable().apply { setColor(0xFF15171E.toInt()) }
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        if (webMode) bar.addView(icon(R.drawable.ic_ui_back, "Back") { if (!back()) close() })
        bar.addView(TextView(activity).apply {
            titleView = this
            text = if (webMode) "⚡  $title · fast (phone engine)" else "▶  $title · phone video"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(icon(R.drawable.ic_ui_phone, "Open in the YouTube app") { openYoutubeApp() }.apply {
            visibility = View.GONE
            tag = "yt"
        })
        if (webMode && openInPc != null) bar.addView(icon(R.drawable.ic_ui_globe, "Open in the PC browser") {
            val u = web?.url
            if (u != null && (u.startsWith("https://") || u.startsWith("http://"))) {
                close()
                openInPc.invoke(u)
            }
        })
        bar.addView(icon(R.drawable.ic_ui_desktop, "Bigger / smaller") {
            size = (size + 1) % 3
            layout(firstTime = false)
        })
        bar.addView(icon(R.drawable.ic_ui_close, "Close") { close() })
        // Drag the window by its top bar.
        var dx = 0f
        var dy = 0f
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = p.x - e.rawX; dy = p.y - e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    if (size != 2) {
                        p.x = (e.rawX + dx).coerceIn(0f, (host.width - p.width).toFloat().coerceAtLeast(0f))
                        p.y = (e.rawY + dy).coerceIn(0f, (host.height - p.height).toFloat().coerceAtLeast(0f))
                    }
                    true
                }
                else -> true
            }
        }
        return bar
    }

    private fun icon(res: Int, description: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = null
        contentDescription = description
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        setOnClickListener { onClick() }
    }

    private fun layout(firstTime: Boolean) {
        val p = panel ?: return
        val hw = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val hh = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val (w, h) = when (size) {
            2 -> hw to hh
            0 -> (hw * 0.34f).roundToInt().let { it to it * 9 / 16 }
            else -> (hw * 0.5f).roundToInt().coerceAtMost(hh * 16 / 9).let { it to it * 9 / 16 }
        }
        p.layoutParams = (p.layoutParams as FrameLayout.LayoutParams).apply {
            width = w
            height = h
            gravity = Gravity.TOP or Gravity.START
        }
        p.radius(if (size == 2) 0 else 14)
        if (size == 2) {
            p.x = 0f
            p.y = 0f
        } else if (firstTime || p.x + w > hw || p.y + h > hh || p.x == 0f && p.y == 0f) {
            p.x = (hw - w - dp(16)).toFloat().coerceAtLeast(0f)   // bottom right, like a mini player
            p.y = (hh - h - dp(16)).toFloat().coerceAtLeast(0f)
        }
        p.requestLayout()
    }

    private fun FrameLayout.radius(r: Int) {
        (background as? GradientDrawable)?.cornerRadius = dp(r).toFloat()
    }

    // ---- what plays inside ----

    private fun webView(): WebView = WebView(activity).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (webMode && !title.isNullOrBlank()) titleView?.text = "⚡  ${title.take(60)} · fast"
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                val pick = pickFiles ?: return false
                pick(callback)
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFull()
                fullView = view
                fullCallback = callback
                view.setBackgroundColor(Color.BLACK)
                host.addView(view, FrameLayout.LayoutParams(-1, -1))
                panel?.visibility = View.INVISIBLE
            }

            override fun onHideCustomView() = exitFull()
        }
        web = this
    }

    private fun exitFull() {
        fullView?.let { host.removeView(it) }
        fullView = null
        fullCallback?.onCustomViewHidden()
        fullCallback = null
        panel?.visibility = View.VISIBLE
    }

    private fun youtube(content: FrameLayout, id: String, start: Int) {
        panel?.findViewWithTag<View>("yt")?.visibility = View.VISIBLE
        val w = webView()
        // Links inside the player (title, logo, "watch on YouTube") open in the YouTube app.
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (request.isForMainFrame && !u.startsWith("https://com.aircursor")) {
                    openExternal(u)
                    return true
                }
                return false
            }
        }
        content.addView(w, FrameLayout.LayoutParams(-1, -1))
        // YouTube's embedded player wants to know which app shows it (Referer = the app's id).
        val src = "https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&start=$start"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
            <body><iframe src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
            allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>
        """.trimIndent()
        w.loadDataWithBaseURL("https://com.aircursor/", html, "text/html", "utf-8", null)
    }

    private fun page(content: FrameLayout, url: String) {
        val w = webView()
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false   // stays in the window
                openExternal(request.url.toString())                     // app links, mail, phone…
                return true
            }
        }
        content.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.loadUrl(url)
    }

    private fun file(content: FrameLayout, f: File, start: Int) {
        val v = VideoView(activity)
        video = v
        content.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val controls = MediaController(activity)
        controls.setAnchorView(v)
        v.setMediaController(controls)
        v.setOnPreparedListener {
            if (start > 0) v.seekTo(start * 1000)
            v.start()
        }
        v.setOnErrorListener { _, _, _ ->
            Toast.makeText(activity, "The phone cannot play this video format – it plays in the PC instead.", Toast.LENGTH_LONG).show()
            true
        }
        v.setVideoURI(Uri.fromFile(f))
    }

    // ---- downloads from web windows land in the PC's Downloads folder ----

    private fun download(url: String, ua: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(activity, "This download cannot be saved", Toast.LENGTH_SHORT).show()
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime).replace('/', '_')
        val cookie = CookieManager.getInstance().getCookie(url)
        Toast.makeText(activity, "Downloading $name to the PC (Downloads)…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                val dir = File(CloudPcEngine.rootfs(activity), "root/Downloads").apply { mkdirs() }
                var f = File(dir, name)
                var i = 1
                while (f.exists()) f = File(dir, name.substringBeforeLast('.', name) + " ($i)" +
                    (if (name.contains('.')) "." + name.substringAfterLast('.') else "")).also { i++ }
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                if (ua != null) conn.setRequestProperty("User-Agent", ua)
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                try {
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                } finally {
                    conn.disconnect()
                }
                true
            } catch (_: Exception) {
                false
            }
            activity.runOnUiThread {
                Toast.makeText(activity, if (ok) "Saved in the PC: Downloads/$name" else "The download failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ---- helpers ----

    private fun openYoutubeApp() {
        val url = youtubeUrl ?: return
        pause()
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        try {
            activity.startActivity(app)
        } catch (_: ActivityNotFoundException) {
            openExternal(url)
        }
    }

    private fun openExternal(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
        }
    }

    /** A video file inside the PC; never anything else of the app. */
    private fun guestFile(guestPath: String): File? {
        val root = CloudPcEngine.rootfs(activity).canonicalFile
        val f = File(root, Uri.decode(guestPath).removePrefix("/")).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    companion object {
        private val YT = listOf(
            Regex("""^https?://(?:www\.|m\.|music\.)?youtube\.com/watch\?(?:.*&)?v=([\w-]{11})"""),
            Regex("""^https?://(?:www\.|m\.)?youtube\.com/(?:shorts|live|embed)/([\w-]{11})"""),
            Regex("""^https?://youtu\.be/([\w-]{11})"""),
        )

        fun youtubeId(url: String): String? {
            for (r in YT) r.find(url.trim())?.let { return it.groupValues[1] }
            return null
        }
    }
}
