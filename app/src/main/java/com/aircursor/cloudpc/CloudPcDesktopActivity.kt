package com.aircursor.cloudpc

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aircursor.MainActivity
import com.aircursor.R
import com.aircursor.core.Keysyms
import com.aircursor.service.ScreenUtils
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen AirCursor PC desktop. */
class CloudPcDesktopActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "cloudpc_engine"
        private const val KEY_FPS = "show_fps"
        private const val KEY_PHONE_TOUCH = "phone_touch2"
        private const val KEY_STYLE = "pc_style"
        private const val ON = 0xEE4F46E5.toInt()

        /** The PC asks to open a web link in the phone's own browser (logins: fast, already signed in). */
        private const val OPEN_ON_PHONE = "aircursor-open:"

        /** Files → right-click → Send to phone: the PC names a file, the phone asks where to save it. */
        private const val SAVE_ON_PHONE = "aircursor-save:"

        /** Files → right-click → Edit on the phone: a video or photo opens in CapCut (or another editor). */
        private const val EDIT_ON_PHONE = "aircursor-edit:"
        private const val CAPCUT = "com.lemon.lvoverseas"

        /** TS Station opens one of the phone's own games: "aircursor-launch:<package>". */
        private const val LAUNCH_ON_PHONE = "aircursor-launch:"

        /** The PC hands a video to the phone's own player (smooth, hardware decoding). */
        private const val PLAY_ON_PHONE = "aircursor-video:"

        /** The PC opens a website in the fast window (the phone's own web engine). */
        private const val WEB_ON_PHONE = "aircursor-web:"

        /** The PC opens an app window in Air: "key|max-or-win|Title|https://…" (Air Store, TS Station, YouTube…). */
        private const val APP_ON_PHONE = "aircursor-app:"

        /** In the PC: web apps open in the PC's own browser instead of the fast window. */
        private const val WEB_IN_PC_FILE = "root/.config/cloudpc/web-in-pc-browser"
        private const val OFF = 0xA0101828.toInt()

        /** Desktop size with the phone's landscape shape (even numbers for the VNC server). */
        fun desktopSize(c: Context): Pair<Int, Int> {
            val s = ScreenUtils.realSize(c)
            val long = max(s.x, s.y).toFloat()
            val short = min(s.x, s.y).toFloat()
            val w = AirBoost.current(c).width
            val h = ((w * short / long) / 2f).roundToInt() * 2
            return w to h.coerceAtLeast(480)
        }
    }

    private var view: RfbView? = null
    private lateinit var status: TextView
    /** The start screen (logo + progress) until the desktop's first picture; later a small card. */
    private lateinit var boot: LinearLayout
    private var bootDone = false
    private lateinit var tools: LinearLayout
    private val stickyButtons = HashMap<Int, View>()
    private var fpsButton: View? = null
    private var pendingExport: File? = null
    private lateinit var rootLayout: FrameLayout
    private lateinit var side: LinearLayout
    private var lockView: PcLockScreen? = null
    private lateinit var videoPlayer: PhoneVideoPlayer
    private var air: com.aircursor.air.AirWindows? = null
    private var assistant: com.aircursor.assistant.AirAssistant? = null
    private var videoChip: View? = null
    private var shell: AirShell? = null
    private var tour: PcTour? = null

    // ---- Another phone as keyboard and touchpad ----
    private var remote: RemoteInputHost? = null
    private var remoteState = RemoteInputHost.State(false, "")
    private var remoteDialog: AlertDialog? = null

    private fun applyRemote(cmd: com.aircursor.core.RemoteInput.Cmd) {
        val v = view ?: return
        if (lockView != null) return
        when (cmd) {
            is com.aircursor.core.RemoteInput.Cmd.Move -> v.remoteMove(cmd.dx, cmd.dy)
            is com.aircursor.core.RemoteInput.Cmd.Button -> v.remoteButton(cmd.button, cmd.down)
            is com.aircursor.core.RemoteInput.Cmd.Click -> v.remoteClick(cmd.button)
            is com.aircursor.core.RemoteInput.Cmd.Wheel -> v.remoteWheel(cmd.dy, cmd.dx)
            is com.aircursor.core.RemoteInput.Cmd.Key -> v.sendKey(cmd.keysym)
            is com.aircursor.core.RemoteInput.Cmd.KeyState -> v.remoteKeyState(cmd.keysym, cmd.down)
            is com.aircursor.core.RemoteInput.Cmd.Text -> v.typeText(cmd.text)
            com.aircursor.core.RemoteInput.Cmd.Ping -> Unit
        }
    }

    private fun remoteHost(): RemoteInputHost = remote ?: RemoteInputHost(this, { applyRemote(it) }) { st ->
        val was = remoteState.connected
        remoteState = st
        if (st.connected != null && was == null) Toast.makeText(this, "Phone keyboard connected", Toast.LENGTH_SHORT).show()
        if (st.connected == null && was != null) Toast.makeText(this, "Phone keyboard disconnected", Toast.LENGTH_SHORT).show()
        remoteDialog?.setMessage(remoteText())
    }.also { remote = it }

    private fun remoteText(): String {
        val st = remoteState
        return if (!st.running) "Off. Turn it on to use another phone as keyboard and touchpad."
        else "Code:  ${st.code.chunked(3).joinToString(" ")}\n\n" +
            (if (st.connected != null) "✅ Connected (${st.connected})\n\n" else "Waiting for the other phone…\n\n") +
            "On the other phone: open AirCursor → \"Use as keyboard & touchpad\" → tap this PC → type the code.\n" +
            "Both phones on the same Wi-Fi, or the other phone on this phone's hotspot." +
            (remote?.address()?.let { "\n\nNot found? Connect by address: $it" } ?: "")
    }

    private fun phoneKeyboardMenu() {
        val host = remoteHost()
        if (!host.running) {
            host.start()
            prefs.edit().putBoolean("remote_on", true).apply()
        }
        remoteDialog = AlertDialog.Builder(this)
            .setTitle("Phone as keyboard & touchpad")
            .setMessage(remoteText())
            .setPositiveButton("OK", null)
            .setNeutralButton("New code") { _, _ -> host.renewCode(); phoneKeyboardMenu() }
            .setNegativeButton("Turn off") { _, _ ->
                host.stop()
                prefs.edit().putBoolean("remote_on", false).apply()
                Toast.makeText(this, "Phone keyboard off", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { remoteDialog = null }
            .show()
    }

    /** The five-card tour: once, after the first picture, never over the lock screen. */
    private fun showTour(force: Boolean = false) {
        if (tour != null || lockView != null || !bootDone) return
        if (!force && PcTour.done(this)) return
        val t = PcTour(this) { tour = null }
        tour = t
        rootLayout.addView(t, FrameLayout.LayoutParams(-1, -1))
    }
    private var hiddenAt = 0L

    // Start screen: the latest step from the log, and one automatic restart if the desktop hangs.
    private var baseStatus: String? = null
    private var connectingSince = 0L
    private var restarted = false
    private val statusTick = object : Runnable {
        override fun run() {
            val base = baseStatus
            if (base != null && boot.visibility == View.VISIBLE) {
                val last = CloudPcBus.log.value.lastOrNull { it.isNotBlank() }?.trim()?.take(90)
                status.text = if (last != null) "$base\n\n$last" else base
                val st = CloudPcBus.state.value
                val waited = SystemClock.uptimeMillis() - connectingSince
                // Ready but no picture, or the desktop ended on its own: start it once more.
                if (!restarted && waited > 45_000 && !st.busy && (st.desktopReady || !st.desktopRunning)) {
                    restarted = true
                    restartDesktop()
                }
            }
            status.postDelayed(this, 1000)
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CloudPcEngine.isReady(this) || !CloudPcDesktop.isInstalled(this)) {
            Toast.makeText(this, "Install AirCursor PC first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "cloudpc"))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val (w, h) = desktopSize(this)
        CloudPcService.startDesktop(this, w, h)
        // Air Drive was part of the old Pro plan (dropped): remove its key and address if a PC still has them.
        // TS Station: the phone's own games (list + icons) for its library.
        Thread {
            PhoneGames.export(applicationContext)
            for (f in listOf("pro-license", "airdrive-url")) File(CloudPcEngine.rootfs(this), "root/.config/cloudpc/$f").delete()
        }.start()

        val v = RfbView(this, CloudPcDesktop.socketPath(this))
        v.sharpness = AirBoost.current(this).sharpen
        v.fpsLimit = AirBoost.fpsLimit(this)
        v.showFps = prefs.getBoolean(KEY_FPS, false)
        v.phoneTouch = prefs.getBoolean(KEY_PHONE_TOUCH, false)
        // Hand cursor over Air windows, the toolbar, the assistant or a dialog: tap the phone UI there.
        v.systemArea = { x, y -> overPhoneUi(x, y) }
        view = v
        val root = FrameLayout(this)
        rootLayout = root
        videoPlayer = PhoneVideoPlayer(
            this, root,
            pickFiles = { cb ->
                pendingUpload?.onReceiveValue(null)
                pendingUpload = cb
                try {
                    uploadFiles.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    pendingUpload = null
                    cb.onReceiveValue(null)
                }
            },
            openInPc = { url ->
                val q = "'" + url.replace("'", "'\\''") + "'"
                CloudPcService.send(this, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession("CLOUDPC_PC_BROWSER=1 cloudpc-web $q"))
                Toast.makeText(this, "Opening in the PC browser…", Toast.LENGTH_SHORT).show()
            },
        )
        root.setBackgroundColor(Color.BLACK)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            maxLines = 4
            text = "Starting AirCursor PC…"
        }
        // Start screen like a computer starting: our logo, a thin progress bar, the step below.
        boot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(android.widget.ImageView(this@CloudPcDesktopActivity).apply {
                setImageResource(R.drawable.logo_pc)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(92), dp(92)))
            addView(android.widget.ProgressBar(this@CloudPcDesktopActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            }, LinearLayout.LayoutParams(dp(180), dp(6)).apply { topMargin = dp(34) })
            addView(status, LinearLayout.LayoutParams(dp(420), -2).apply { topMargin = dp(18) })
        }
        root.addView(boot, FrameLayout.LayoutParams(-1, -1))

        tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val toolScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(tools)
        }
        // Small, see-through handle on the edge: it opens the toolbar and stays out of the way.
        val handle = iconButton(R.drawable.ic_ui_menu, "Toolbar") {
            tools.visibility = if (tools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            it.alpha = if (tools.visibility == View.VISIBLE) 1f else 0.55f
        }.apply {
            alpha = 0.55f
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(handle)
            addView(toolScroll)
        }
        root.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, dp(6), 0)
        })

        tools.addView(iconButton(R.drawable.ic_ui_keyboard, "Keyboard") { toggleKeyboard() })
        tools.addView(iconButton(R.drawable.ic_ui_lock, "Lock and password") { lockMenu() })
        tools.addView(iconButton(R.drawable.ic_ui_globe, "Air browser and windows") { b ->
            val w = air
            if (w != null && w.windows.isNotEmpty()) w.switcher(b) else openAir(null)
        })
        tools.addView(iconButton(R.drawable.ic_ui_mic, "Voice assistant (Win+A)") { startAssistant() })
        tools.addView(iconButton(R.drawable.ic_ui_play, "Smooth video on the phone (Win+Y)") { smoothVideo() })
        tools.addView(iconButton(R.drawable.ic_ui_phone, "Another phone as keyboard & touchpad") { phoneKeyboardMenu() })
        tools.addView(iconButton(R.drawable.ic_ui_paste, "Paste from phone") { pasteFromPhone() })
        tools.addView(iconButton(R.drawable.ic_ui_folder, "Files") { filesMenu() })
        tools.addView(iconButton(R.drawable.ic_ui_image, "Wallpaper") {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        tools.addView(iconButton(R.drawable.ic_ui_bolt, "AirBoost") { boostMenu() })
        fpsButton = iconButton(R.drawable.ic_ui_speed, "FPS meter") {
            v.showFps = !v.showFps
            prefs.edit().putBoolean(KEY_FPS, v.showFps).apply()
            updateButtons()
        }.also { tools.addView(it) }
        tools.addView(keyButton("Esc") { v.sendKey(Keysyms.ESCAPE) })
        tools.addView(keyButton("Tab") { v.sendKey(Keysyms.TAB) })
        for ((label, sym) in listOf("Ctrl" to Keysyms.CONTROL_L, "Alt" to Keysyms.ALT_L, "Win" to Keysyms.SUPER_L)) {
            val b = keyButton(label) { v.toggleSticky(sym) }
            stickyButtons[sym] = b
            tools.addView(b)
        }
        tools.addView(iconButton(R.drawable.ic_ui_up, "Up") { v.sendKey(Keysyms.UP) })
        tools.addView(iconButton(R.drawable.ic_ui_down, "Down") { v.sendKey(Keysyms.DOWN) })
        tools.addView(iconButton(R.drawable.ic_ui_left, "Left") { v.sendKey(Keysyms.LEFT) })
        tools.addView(iconButton(R.drawable.ic_ui_right, "Right") { v.sendKey(Keysyms.RIGHT) })
        tools.addView(iconButton(R.drawable.ic_ui_close, "Close") { askExit() })

        // Mac style: menu bar, dock and apps grid drawn here by the phone (shows when the PC uses it).
        shell = AirShell(this, root, object : AirShell.Actions {
            override fun runPc(cmd: String) = this@CloudPcDesktopActivity.runPc(cmd)
            override fun openAir(url: String?) = this@CloudPcDesktopActivity.openAir(url)
            override fun airWindows() = air
            override fun desktopSize() = desktopSize(this@CloudPcDesktopActivity)
            override fun showToolbar() {
                tools.visibility = View.VISIBLE
                side.bringToFront()
            }
            override fun keyboard() = toggleKeyboard()
            override fun lock() {
                if (PcLock.hasPassword(this@CloudPcDesktopActivity)) showLock(PcLockScreen.Mode.SIGN_IN) else lockMenu()
            }
            override fun assistant() = startAssistant()
            override fun boostMenu() = this@CloudPcDesktopActivity.boostMenu()
            override fun exit() = askExit()
            override fun tour() = showTour(force = true)
            override fun phoneKeyboard() = phoneKeyboardMenu()
            override fun changed() = keepControlsOnTop()
        })
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) shell?.applyInsets()
        }

        v.onStatus = { msg ->
            if (msg != null && baseStatus == null) connectingSince = SystemClock.uptimeMillis()
            baseStatus = msg
            status.text = msg ?: ""
            if (msg == null && !bootDone) {
                // First picture: the start screen fades away; later messages use a small card.
                bootDone = true
                boot.animate().alpha(0f).setDuration(350).withEndAction {
                    boot.visibility = View.GONE
                    boot.alpha = 1f
                    boot.setBackgroundColor(Color.TRANSPARENT)
                    boot.isClickable = false
                    for (i in 0 until boot.childCount) if (boot.getChildAt(i) !== status) boot.getChildAt(i).visibility = View.GONE
                    status.setTextColor(Color.WHITE)
                    status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    status.setPadding(dp(28), dp(18), dp(28), dp(18))
                    status.background = round(0xE6131A2E.toInt(), 18)
                    status.layoutParams = LinearLayout.LayoutParams(-2, -2)
                    showTour()
                }.start()
            } else if (bootDone) {
                boot.visibility = if (msg == null) View.GONE else View.VISIBLE
            }
        }
        connectingSince = SystemClock.uptimeMillis()
        status.post(statusTick)
        v.onStickyChanged = { updateButtons() }
        v.onDesktopClipboard = { text ->
            if (text.startsWith(OPEN_ON_PHONE)) {
                openOnPhone(text.removePrefix(OPEN_ON_PHONE).trim())
            } else if (text.startsWith(LAUNCH_ON_PHONE)) {
                if (lockView == null && !PhoneGames.launch(this, text.removePrefix(LAUNCH_ON_PHONE).trim())) {
                    Toast.makeText(this, "That game is not on this phone any more", Toast.LENGTH_SHORT).show()
                }
            } else if (text.startsWith(EDIT_ON_PHONE)) {
                if (lockView == null) editOnPhone(text.removePrefix(EDIT_ON_PHONE).trim())
            } else if (text.startsWith(SAVE_ON_PHONE)) {
                sendToPhone(text.removePrefix(SAVE_ON_PHONE).trim())
            } else if (text.startsWith(WEB_ON_PHONE)) {
                val url = text.removePrefix(WEB_ON_PHONE).trim()
                if (lockView == null) {
                    when {
                        url == "air:home" -> openAir(null)
                        // A game started from TS Station (shown in Air): Air steps aside, then comes back.
                        url == "air:minimize" -> air?.minimizeAll()
                        url == "air:show" -> air?.showLast()
                        url.startsWith("https://") || url.startsWith("http://") -> openAir(url)
                    }
                }
            } else if (text.startsWith(APP_ON_PHONE)) {
                if (lockView == null) openApp(text.removePrefix(APP_ON_PHONE).trim())
            } else if (text.startsWith(PLAY_ON_PHONE)) {
                hideVideoChip()
                if (lockView == null) videoPlayer.play(text.removePrefix(PLAY_ON_PHONE))
            } else {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AirCursor PC", text))
                if (PhoneVideoPlayer.youtubeId(text) != null && lockView == null) {
                    offerVideo(text.trim())
                } else {
                    Toast.makeText(this, "Copied to phone", Toast.LENGTH_SHORT).show()
                }
            }
        }
        setContentView(root)
        updateButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    lockView != null -> finish()
                    tour != null -> tour?.finish()
                    assistant?.isShowing == true -> assistant?.dismiss()
                    shell?.closeGrid() == true -> Unit
                    air?.back() == true -> Unit
                    videoPlayer.isOpen -> if (!videoPlayer.back()) videoPlayer.close()
                    else -> askExit()
                }
            }
        })

        if (prefs.getBoolean("remote_on", false)) remoteHost().start()
        boot.bringToFront()
        // Windows-style: password screen (or the first-start question) in front, desktop starting behind it.
        when {
            PcLock.hasPassword(this) -> showLock(PcLockScreen.Mode.SIGN_IN)
            PcLock.shouldOfferSetup(this) -> showLock(PcLockScreen.Mode.SETUP)
        }
    }

    // ---- Smooth video on the phone's own video chip ----

    /** Toolbar button / Win+Y in the PC: the video in the browser pauses there and plays here. */
    private fun smoothVideo() {
        if (videoPlayer.isOpen) {
            videoPlayer.close()
            return
        }
        CloudPcService.send(this, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession("cloudpc-phone-video"))
        Toast.makeText(this, "Moving the video to the phone player…", Toast.LENGTH_SHORT).show()
    }

    /** Files for a web page in the fast window (from the phone's own storage). */
    private var pendingUpload: android.webkit.ValueCallback<Array<Uri>>? = null
    private val uploadFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingUpload?.onReceiveValue(uris.toTypedArray())
        pendingUpload = null
    }

    private fun webInPc() = File(CloudPcEngine.rootfs(this), WEB_IN_PC_FILE).exists()

    private fun setWebInPc(on: Boolean) {
        val f = File(CloudPcEngine.rootfs(this), WEB_IN_PC_FILE)
        if (on) {
            f.parentFile?.mkdirs()
            f.writeText("1\n")
        } else {
            f.delete()
        }
    }

    // ---- Air, AirCursor's browser (phone engine) ----

    private fun openAir(url: String?) {
        if (!com.aircursor.air.AirEngine.supported()) {
            // 32-bit phones: the simple fast window (Android's web view) instead.
            videoPlayer.browse(url ?: "https://www.google.com")
            return
        }
        airWindows().openBrowser(url)
        tools.visibility = View.GONE
    }

    /** "key|max|Title|url" from the PC: an app window in Air (no tabs or address bar). */
    private fun openApp(spec: String) {
        val parts = spec.split('|', limit = 4)
        if (parts.size < 4) return
        val (key, size, title, url) = parts
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (!com.aircursor.air.AirEngine.supported()) {
            videoPlayer.browse(url)
            return
        }
        airWindows().openApp(key.ifBlank { "app" }.take(40), title.ifBlank { "App" }.take(60), url, size)
        tools.visibility = View.GONE
    }

    private fun pcQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun airWindows(): com.aircursor.air.AirWindows = air ?: com.aircursor.air.AirWindows(
        this, rootLayout,
        object : com.aircursor.air.AirWindows.Callbacks {
            override fun pickPhoneFiles(multiple: Boolean, done: (List<Uri>) -> Unit) {
                pendingAirUpload = done
                try {
                    airUpload.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    pendingAirUpload = null
                    done(emptyList())
                }
            }

            override fun requestPermissions(perms: Array<String>, done: (Boolean) -> Unit) = askPermissions(perms, done)

            override fun openInPcBrowser(url: String) {
                CloudPcService.send(this@CloudPcDesktopActivity, CloudPcService.ACTION_RUN,
                    CloudPcDesktop.inSession("CLOUDPC_PC_BROWSER=1 cloudpc-web ${pcQuote(url)}"))
                Toast.makeText(this@CloudPcDesktopActivity, "Opening in the PC browser…", Toast.LENGTH_SHORT).show()
            }

            override fun openFileInPc(path: String) {
                CloudPcService.send(this@CloudPcDesktopActivity, CloudPcService.ACTION_RUN,
                    CloudPcDesktop.inSession("xdg-open ${pcQuote(path)}"))
                Toast.makeText(this@CloudPcDesktopActivity, "Opening in the PC…", Toast.LENGTH_SHORT).show()
            }

            override fun assistant() = startAssistant()

            override fun windowsChanged() {
                keepControlsOnTop()
                shell?.rebuildDock()
            }
        },
    ).also {
        air = it
        shell?.applyInsets()
    }

    /** True when phone UI (not the PC picture) is under this screen point. */
    private fun overPhoneUi(sx: Float, sy: Float): Boolean {
        if (!hasWindowFocus() || lockView != null) return true
        if (!::rootLayout.isInitialized) return false
        val loc = IntArray(2)
        rootLayout.getLocationOnScreen(loc)
        val x = sx - loc[0]
        val y = sy - loc[1]
        for (i in rootLayout.childCount - 1 downTo 0) {
            val c = rootLayout.getChildAt(i)
            if (c === view || c === boot || c.visibility != View.VISIBLE) continue
            if (x >= c.x && x < c.x + c.width && y >= c.y && y < c.y + c.height) return true
        }
        return false
    }

    /** The PC toolbar, the assistant and the lock screen stay above Air's windows. */
    private fun keepControlsOnTop() {
        shell?.bringToFront()
        if (::side.isInitialized) side.bringToFront()
        assistant?.bringToFront()
        if (::boot.isInitialized && !bootDone) boot.bringToFront()
        lockView?.bringToFront()
    }

    // ---- Android permissions for Air (camera / microphone) and the assistant ----

    private var pendingPermissions: ((Boolean) -> Unit)? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        pendingPermissions?.invoke(res.isNotEmpty() && res.values.all { it })
        pendingPermissions = null
    }

    private fun askPermissions(perms: Array<String>, done: (Boolean) -> Unit) {
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            done(true)
            return
        }
        pendingPermissions?.invoke(false)
        pendingPermissions = done
        try {
            permissionRequest.launch(missing.toTypedArray())
        } catch (_: Exception) {
            pendingPermissions = null
            done(false)
        }
    }

    // ---- Voice assistant ----

    /** A command in the desktop session: through the session helper (apps stay open), else the old way. */
    private fun runPc(cmd: String) {
        if (!CloudPcDesktop.sendToSession(this, cmd)) {
            CloudPcService.send(this, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession(cmd))
        }
    }

    /** The Air window in front (if any): voice commands for pages go there. */
    private fun frontBrowser(): com.aircursor.air.AirBrowser? = air?.top()?.browser

    /** Web apps the assistant opens by name: key, title, address, own window (else an Air tab). */
    private val webApps = mapOf(
        "youtube" to listOf("youtube", "YouTube", "https://www.youtube.com/", "app"),
        "you tube" to listOf("youtube", "YouTube", "https://www.youtube.com/", "app"),
        "youtube music" to listOf("ytmusic", "YouTube Music", "https://music.youtube.com/", "app"),
        "yt music" to listOf("ytmusic", "YouTube Music", "https://music.youtube.com/", "app"),
        "spotify" to listOf("spotify", "Spotify", "https://open.spotify.com/", "app"),
        "jiosaavn" to listOf("jiosaavn", "JioSaavn", "https://www.jiosaavn.com/", "app"),
        "saavn" to listOf("jiosaavn", "JioSaavn", "https://www.jiosaavn.com/", "app"),
        "gaana" to listOf("gaana", "Gaana", "https://gaana.com/", "app"),
        "netflix" to listOf("netflix", "Netflix", "https://www.netflix.com/", "app"),
        "prime video" to listOf("primevideo", "Prime Video", "https://www.primevideo.com/", "app"),
        "amazon prime" to listOf("primevideo", "Prime Video", "https://www.primevideo.com/", "app"),
        "hotstar" to listOf("hotstar", "JioHotstar", "https://www.hotstar.com/", "app"),
        "jiohotstar" to listOf("hotstar", "JioHotstar", "https://www.hotstar.com/", "app"),
        "twitch" to listOf("twitch", "Twitch", "https://www.twitch.tv/", "app"),
        "whatsapp" to listOf("whatsapp", "WhatsApp", "https://web.whatsapp.com/", "app"),
        "gmail" to listOf("", "Gmail", "https://mail.google.com/", "tab"),
        "mail" to listOf("", "Gmail", "https://mail.google.com/", "tab"),
        "google" to listOf("", "Google", "https://www.google.com/", "tab"),
        "chatgpt" to listOf("", "ChatGPT", "https://chatgpt.com/", "tab"),
        "chat gpt" to listOf("", "ChatGPT", "https://chatgpt.com/", "tab"),
        "claude" to listOf("", "Claude", "https://claude.ai/", "tab"),
        "gemini" to listOf("", "Gemini", "https://gemini.google.com/", "tab"),
        "deepseek" to listOf("", "DeepSeek", "https://chat.deepseek.com/", "tab"),
        "perplexity" to listOf("", "Perplexity", "https://www.perplexity.ai/", "tab"),
        "github" to listOf("", "GitHub", "https://github.com/", "tab"),
        "instagram" to listOf("", "Instagram", "https://www.instagram.com/", "tab"),
        "insta" to listOf("", "Instagram", "https://www.instagram.com/", "tab"),
        "facebook" to listOf("", "Facebook", "https://www.facebook.com/", "tab"),
        "twitter" to listOf("", "X", "https://x.com/", "tab"),
        "x" to listOf("", "X", "https://x.com/", "tab"),
        "linkedin" to listOf("", "LinkedIn", "https://www.linkedin.com/", "tab"),
        "telegram" to listOf("", "Telegram", "https://web.telegram.org/", "tab"),
        "discord" to listOf("", "Discord", "https://discord.com/app", "tab"),
        "reddit" to listOf("", "Reddit", "https://www.reddit.com/", "tab"),
        "wikipedia" to listOf("", "Wikipedia", "https://www.wikipedia.org/", "tab"),
        "maps" to listOf("", "Google Maps", "https://www.google.com/maps", "tab"),
        "google maps" to listOf("", "Google Maps", "https://www.google.com/maps", "tab"),
        "drive" to listOf("", "Google Drive", "https://drive.google.com/", "tab"),
        "google drive" to listOf("", "Google Drive", "https://drive.google.com/", "tab"),
        "docs" to listOf("", "Google Docs", "https://docs.google.com/document/", "tab"),
        "sheets" to listOf("", "Google Sheets", "https://docs.google.com/spreadsheets/", "tab"),
        "slides" to listOf("", "Google Slides", "https://docs.google.com/presentation/", "tab"),
        "translate" to listOf("", "Google Translate", "https://translate.google.com/", "tab"),
        "google translate" to listOf("", "Google Translate", "https://translate.google.com/", "tab"),
        "amazon" to listOf("", "Amazon", "https://www.amazon.in/", "tab"),
        "flipkart" to listOf("", "Flipkart", "https://www.flipkart.com/", "tab"),
        "canva" to listOf("", "Canva", "https://www.canva.com/", "tab"),
        "figma" to listOf("", "Figma", "https://www.figma.com/", "tab"),
        "notion" to listOf("", "Notion", "https://www.notion.so/", "tab"),
        "photopea" to listOf("", "Photopea", "https://www.photopea.com/", "tab"),
        "leetcode" to listOf("", "LeetCode", "https://leetcode.com/", "tab"),
        "duolingo" to listOf("", "Duolingo", "https://www.duolingo.com/", "tab"),
    )

    private inner class AssistantBridge : com.aircursor.assistant.AirAssistant.Actions {
        override fun openTarget(target: String): String {
            val t = target.lowercase(java.util.Locale.ROOT).trim().removePrefix("the ").trim()
            when (t) {
                "air", "air browser", "browser", "chrome", "google chrome", "internet", "web browser", "brave browser" -> {
                    openAir(null)
                    return "Opening Air."
                }
                "air store", "airstore", "app store", "store", "play store", "apps store" -> {
                    runPc("airstore")
                    return "Opening Air Store."
                }
                "ts station", "t s station", "tsstation", "game station", "games", "gaming" -> {
                    runPc("ts-station || pc-open 'TS Station'")
                    return "Opening TS Station."
                }
                "files", "file", "file manager", "my files", "folders", "files app" -> {
                    runPc("thunar")
                    return "Opening Files."
                }
                "downloads", "download folder", "downloads folder" -> {
                    runPc("thunar /root/Downloads")
                    return "Opening Downloads."
                }
                "documents", "documents folder" -> {
                    runPc("thunar /root/Documents")
                    return "Opening Documents."
                }
                "terminal", "command line", "console" -> {
                    runPc("xfce4-terminal")
                    return "Opening the terminal."
                }
                "settings", "pc settings", "setting", "control panel" -> {
                    runPc("xfce4-settings-manager")
                    return "Opening settings."
                }
                "new tab" -> return browser("newtab")
                "notepad", "text editor", "notes", "editor" -> {
                    runPc("mousepad || pc-open 'Text Editor'")
                    return "Opening the text editor."
                }
                "calculator", "calc" -> {
                    runPc("gnome-calculator || galculator || pc-open calculator")
                    return "Opening the calculator."
                }
                "vs code", "vscode", "visual studio code", "code", "code editor" -> {
                    runPc("cloudpc-vscode || pc-open code")
                    return "Opening VS Code."
                }
                "task manager", "system monitor" -> {
                    runPc("xfce4-taskmanager || pc-open 'Task Manager'")
                    return "Opening the task manager."
                }
                "claude code" -> {
                    runPc("xfce4-terminal --title='Claude Code' -x cloudpc-claude")
                    return "Opening Claude Code."
                }
            }
            webApps[t]?.let { (key, title, url, kind) ->
                if (kind == "app" && com.aircursor.air.AirEngine.supported()) {
                    airWindows().openApp(key, title, url, "max")
                    tools.visibility = View.GONE
                } else {
                    openAir(url)
                }
                return "Opening $title."
            }
            val host = t.replace(" dot ", ".").replace(" ", "")
            if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$").matches(host)) {
                openAir("https://$host")
                return "Opening $host."
            }
            // An app in the PC by its name – or a web search when there is none.
            val q = pcQuote(target)
            val web = pcQuote("https://www.google.com/search?q=" + Uri.encode(target))
            runPc("pc-open $q >/dev/null 2>&1 || cloudpc-air $web")
            return "Opening $target."
        }

        override fun closeApp(target: String): String {
            val t = target.lowercase(java.util.Locale.ROOT).trim()
            val key = webApps[t]?.get(0)?.ifEmpty { null } ?: t.replace(" ", "")
            val w = air?.windows?.firstOrNull { it.key == key || it.title.lowercase(java.util.Locale.ROOT) == t }
            if (w != null) {
                air?.close(w)
                return "Closed ${w.title}."
            }
            return window("close")
        }

        override fun search(query: String): String {
            val b = frontBrowser()
            if (b != null && b.app == null) b.search(query) else openAir(com.aircursor.air.AirData.searchUrl(this@CloudPcDesktopActivity, query))
            return "Searching for $query."
        }

        override fun youtube(query: String): String {
            val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
            if (com.aircursor.air.AirEngine.supported()) {
                val w = airWindows()
                val yt = w.windows.firstOrNull { it.key == "youtube" }
                if (yt != null) {
                    w.show(yt)
                    yt.browser.load(url)
                } else {
                    w.openApp("youtube", "YouTube", url, "max")
                }
                tools.visibility = View.GONE
            } else {
                openAir(url)
            }
            return "Playing $query on YouTube."
        }

        override fun typeText(text: String): String {
            val b = frontBrowser()
            if (b != null) b.typeText(text) else view?.pasteFromPhone(text)
            return "Typed."
        }

        override fun toggle(feature: String, on: Boolean?): String = when (feature) {
            "fps" -> {
                val v = view
                val want = on ?: !(v?.showFps ?: false)
                v?.showFps = want
                prefs.edit().putBoolean(KEY_FPS, want).apply()
                updateButtons()
                if (want) "FPS meter on." else "FPS meter off."
            }
            "keyboard" -> {
                toggleKeyboard()
                "Keyboard."
            }
            "game" -> {
                val want = on ?: (AirBoost.current(this@CloudPcDesktopActivity) != AirBoost.GAME)
                val b = if (want) AirBoost.GAME else AirBoost.BALANCED
                AirBoost.applyNow(this@CloudPcDesktopActivity, b)
                view?.sharpness = b.sharpen
                if (want) "Game mode on." else "Back to balanced mode."
            }
            "shields" -> {
                val p = com.aircursor.air.AirData.prefs(this@CloudPcDesktopActivity)
                val want = on ?: !p.getBoolean(com.aircursor.air.AirData.KEY_ADS, true)
                com.aircursor.air.AirEngine.setAdsBlocked(this@CloudPcDesktopActivity, want)
                if (want) "Ad blocking on." else "Ad blocking off."
            }
            "dark" -> {
                val want = on ?: true
                com.aircursor.air.AirEngine.setColorScheme(
                    this@CloudPcDesktopActivity,
                    if (want) org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_DARK else org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_LIGHT,
                )
                if (want) "Dark pages on." else "Light pages on."
            }
            "torch" -> torch(on)
            "gpu" -> {
                val want = on ?: !AirBoost.phoneGpu(this@CloudPcDesktopActivity)
                AirBoost.setPhoneGpu(this@CloudPcDesktopActivity, want)
                if (want) "Phone GPU on for games." else "Phone GPU off."
            }
            "wifi" -> {
                startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
                "Here are the Wi-Fi settings."
            }
            "bluetooth" -> {
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                "Here are the Bluetooth settings."
            }
            "desktop" -> {
                val p = com.aircursor.air.AirData.prefs(this@CloudPcDesktopActivity)
                val want = on ?: !p.getBoolean(com.aircursor.air.AirData.KEY_DESKTOP, true)
                p.edit().putBoolean(com.aircursor.air.AirData.KEY_DESKTOP, want).apply()
                if (want) "Desktop sites on (new tabs)." else "Mobile sites on (new tabs)."
            }
            else -> "I can't switch that yet."
        }

        private fun torch(on: Boolean?): String {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return "No flashlight here."
            return try {
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "No flashlight here."
                val want = on ?: !torchOn
                cm.setTorchMode(id, want)
                torchOn = want
                if (want) "Flashlight on." else "Flashlight off."
            } catch (_: Exception) {
                "The flashlight is busy."
            }
        }

        override fun scroll(down: Boolean): String {
            val b = frontBrowser()
            if (b != null) b.scrollPage(down) else view?.sendKey(if (down) com.aircursor.core.Keysyms.PAGE_DOWN else com.aircursor.core.Keysyms.PAGE_UP)
            return if (down) "Scrolling down." else "Scrolling up."
        }

        override fun browser(action: String): String {
            val b = frontBrowser()
            if (b == null && action == "newtab") {
                openAir(null)
                air?.windows?.firstOrNull { it.key == com.aircursor.air.AirWindows.BROWSER }?.browser?.openNewTab()
                return "New tab."
            }
            if (b == null) return "Open Air first."
            when (action) {
                "back" -> b.goBack()
                "forward" -> b.goForward()
                "reload" -> b.reloadOrStop()
                "newtab" -> if (b.app == null) b.openNewTab() else openAir(null)
                "closetab" -> b.closeCurrentTab()
                "nexttab" -> b.nextTab(1)
                "prevtab" -> b.nextTab(-1)
            }
            return when (action) {
                "back" -> "Going back."
                "reload" -> "Reloading."
                "newtab" -> "New tab."
                "closetab" -> "Tab closed."
                else -> "Done."
            }
        }

        override fun window(action: String): String {
            val w = air?.top()
            return when (action) {
                "desktop" -> {
                    air?.minimizeAll()
                    "Here's the desktop."
                }
                "minimize" -> if (w != null) { air?.minimize(w); "Minimized." } else "Nothing to minimize."
                "maximize" -> if (w != null) { if (!w.maximized) air?.toggleSize(w); "Full screen." } else "Open a window first."
                "close" -> if (w != null) { air?.close(w); "Closed ${w.title}." } else "Nothing to close."
                else -> "Done."
            }
        }

        override fun screenshot(): String {
            val name = "Screenshot-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".png"
            runPc("mkdir -p /root/Pictures && pc-screenshot /root/Pictures/$name")
            return "Screenshot saved in the PC's Pictures."
        }

        override fun lock(): String {
            if (!PcLock.hasPassword(this@CloudPcDesktopActivity)) return "Set a password first (toolbar → lock)."
            showLock(PcLockScreen.Mode.SIGN_IN)
            return "Locked."
        }
    }

    private var torchOn = false

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && lockView == null) {
            val k = event.keyCode
            if (k == android.view.KeyEvent.KEYCODE_ASSIST || k == android.view.KeyEvent.KEYCODE_VOICE_ASSIST ||
                (event.isMetaPressed && k == android.view.KeyEvent.KEYCODE_A)
            ) {
                startAssistant()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startAssistant() {
        if (lockView != null) return
        val a = assistant ?: com.aircursor.assistant.AirAssistant(this, rootLayout, AssistantBridge()).also { assistant = it }
        askPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { ok ->
            if (ok) a.listen() else Toast.makeText(this, "The assistant needs the microphone", Toast.LENGTH_LONG).show()
        }
        keepControlsOnTop()
    }

    private var pendingAirUpload: ((List<Uri>) -> Unit)? = null
    private val airUpload = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingAirUpload?.invoke(uris)
        pendingAirUpload = null
    }

    /** A YouTube link was copied in the PC: offer to play it smoothly here. */
    private fun offerVideo(url: String) {
        hideVideoChip()
        val chip = TextView(this).apply {
            text = "▶  Play smoothly on the phone"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = round(ON, 22)
            elevation = dp(8).toFloat()
            setOnClickListener {
                hideVideoChip()
                videoPlayer.play("0|$url")
            }
        }
        videoChip = chip
        rootLayout.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        chip.postDelayed({ if (videoChip === chip) hideVideoChip() }, 8000)
    }

    private fun hideVideoChip() {
        videoChip?.let { rootLayout.removeView(it) }
        videoChip = null
    }

    // ---- Password lock ----

    private fun showLock(mode: PcLockScreen.Mode) {
        lockView?.let { rootLayout.removeView(it) }
        videoPlayer.close()
        air?.minimizeAll()
        assistant?.dismiss()
        hideVideoChip()
        view?.locked = true
        if (DesktopInput.target === view) DesktopInput.target = null
        side.visibility = View.GONE
        val l = PcLockScreen(this, mode, object : PcLockScreen.Listener {
            override fun onUnlocked() = hideLock()
            override fun onForgot() = forgotPassword()
        })
        lockView = l
        rootLayout.addView(l, FrameLayout.LayoutParams(-1, -1))
        // With a hardware keyboard, typing goes straight into the password box.
        if (resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS) l.focusPassword()
    }

    private fun hideLock() {
        lockView?.let { rootLayout.removeView(it) }
        lockView = null
        view?.locked = false
        side.visibility = View.VISIBLE
        DesktopInput.target = view
        view?.requestFocus()
        showTour()
    }

    private fun lockMenu() {
        val has = PcLock.hasPassword(this)
        val items = if (has) arrayOf("Lock now", "Change password", "Remove password", "Change name (${PcLock.name(this)})")
        else arrayOf("Set a password", "Change name (${PcLock.name(this)})")
        AlertDialog.Builder(this)
            .setTitle(if (has) "Lock and password" else "Password: not set")
            .setItems(items) { _, i ->
                when (if (has) i else i + 10) {
                    0 -> showLock(PcLockScreen.Mode.SIGN_IN)
                    1 -> confirmCurrent { showLock(PcLockScreen.Mode.SET_PASSWORD) }
                    2 -> confirmCurrent {
                        PcLock.removePassword(this)
                        Toast.makeText(this, "Password removed – AirCursor PC opens without it", Toast.LENGTH_LONG).show()
                    }
                    10 -> showLock(PcLockScreen.Mode.SET_PASSWORD)
                    else -> changeName()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun passwordInput(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine()
    }

    private fun padded(v: View) = FrameLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(v)
    }

    /** Changing or removing the password needs the current one. */
    private fun confirmCurrent(then: () -> Unit) {
        val f = passwordInput("Current password")
        AlertDialog.Builder(this)
            .setTitle("Enter your current password")
            .setView(padded(f))
            .setPositiveButton("OK") { _, _ ->
                val pw = f.text.toString()
                Thread {
                    val ok = PcLock.check(this, pw)
                    runOnUiThread {
                        if (ok) then() else Toast.makeText(this, "The password is incorrect", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changeName() {
        val f = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(PcLock.name(this@CloudPcDesktopActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Your name on the sign-in screen")
            .setView(padded(f))
            .setPositiveButton("Save") { _, _ -> if (f.text.isNotBlank()) PcLock.setName(this, f.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Forgot the password: prove it is the phone's owner with the phone's own screen lock, then set a new one. */
    private val confirmPhoneLock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) resetPassword()
    }

    @Suppress("DEPRECATION")
    private fun forgotPassword() {
        val km = getSystemService(KeyguardManager::class.java)
        val intent = if (km?.isDeviceSecure == true) {
            km.createConfirmDeviceCredentialIntent("Reset the AirCursor PC password", "Confirm with your phone's screen lock")
        } else {
            null
        }
        if (intent != null) {
            confirmPhoneLock.launch(intent)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reset the password?")
            .setMessage(
                "This phone has no screen lock, so the PC password can be reset here. " +
                    "Your files in the PC stay as they are. Tip: set a phone screen lock so nobody else can do this.",
            )
            .setPositiveButton("Reset") { _, _ -> resetPassword() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetPassword() {
        PcLock.removePassword(this)
        showLock(PcLockScreen.Mode.SET_PASSWORD)
        Toast.makeText(this, "Password reset – set a new one or tap Cancel", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconButton(@DrawableRes icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        background = round(OFF, 12)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnClickListener { onClick(it) }
        setOnLongClickListener {
            Toast.makeText(context, description, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun keyButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = round(OFF, 12)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(40)).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnClickListener { onClick() }
    }

    private fun setOn(b: View?, on: Boolean) {
        (b?.background as? GradientDrawable)?.setColor(if (on) ON else OFF)
    }

    private fun updateButtons() {
        val v = view ?: return
        for ((sym, b) in stickyButtons) setOn(b, sym in v.sticky)
        setOn(fpsButton, v.showFps)
    }

    // ---- PC style: Mac-like (phone dock), dock inside the PC, Windows taskbar ----

    private fun pcStyle(): String = try {
        File(CloudPcEngine.rootfs(this), "root/.config/xfce4/aircursor-style").readText().trim()
    } catch (_: Exception) {
        prefs.getString(KEY_STYLE, "mac") ?: "mac"
    }

    private fun styleLabel(s: String) = when (s) {
        "windows" -> "Windows taskbar"
        "dock" -> "dock inside the PC"
        else -> "Mac-like (menu bar and dock)"
    }

    private fun styleMenu() {
        val keys = listOf("mac", "dock", "windows")
        val now = pcStyle()
        AlertDialog.Builder(this)
            .setTitle("PC style")
            .setSingleChoiceItems(keys.map { styleLabel(it).replaceFirstChar { c -> c.uppercase() } }.toTypedArray(),
                keys.indexOf(now).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                val next = keys[i]
                if (next == now) return@setSingleChoiceItems
                prefs.edit().putString(KEY_STYLE, next).apply()
                runPc("aircursor-style $next")
                Toast.makeText(this, "PC style: ${styleLabel(next)} (a few seconds)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Toolbar actions ----

    private fun pasteFromPhone() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "The phone clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        view?.pasteFromPhone(text)
    }

    /** Phone photo -> desktop wallpaper (the photo is copied; the phone's file is never changed). */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setWallpaper(uri)
    }

    private fun setWallpaper(uri: Uri) {
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val ext = when (contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val dir = File(CloudPcEngine.rootfs(this), "root/Pictures/Wallpapers").apply { mkdirs() }
                val name = "phone-${System.currentTimeMillis()}.$ext"
                contentResolver.openInputStream(uri)?.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                CloudPcService.send(
                    this, CloudPcService.ACTION_RUN,
                    CloudPcDesktop.inSession("aircursor-set-wallpaper /root/Pictures/Wallpapers/$name"),
                )
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not copy the photo: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** AirBoost profiles, applied live (desktop size + sharpening + light mode), plus "Boost now". */
    private fun boostMenu() {
        val profiles = AirBoost.entries
        val now = AirBoost.current(this)
        val fps = AirBoost.fpsLimit(this)
        val gpu = AirBoost.phoneGpu(this)
        val labels = profiles.map { "${it.label} – ${it.about}" }.toTypedArray() +
            "Steady FPS: ${AirBoost.fpsLabel(fps)} – tap to change" +
            "Boost now – stop background jobs, free memory" +
            (if (gpu) "Graphics: Phone GPU (VirGL) – tap for CPU" else "Graphics: CPU – tap for Phone GPU (VirGL)") +
            (if (view?.phoneTouch != false) "Touch: phone style (swipe scrolls) – tap for mouse style"
            else "Touch: mouse style (drag selects) – tap for phone style") +
            (if (webInPc()) "Web links: Brave in the PC (slow) – tap for Air"
            else "Web links: Air, phone engine (fast) – tap for Brave in the PC") +
            "PC style: ${styleLabel(pcStyle())} – tap to change"
        AlertDialog.Builder(this)
            .setTitle("AirBoost (now: ${now.label})")
            .setItems(labels) { _, i ->
                if (i == profiles.size) {
                    fpsMenu()
                    return@setItems
                }
                if (i == profiles.size + 1) {
                    boostNow()
                    return@setItems
                }
                if (i == profiles.size + 5) {
                    styleMenu()
                    return@setItems
                }
                if (i == profiles.size + 4) {
                    val inPc = !webInPc()
                    setWebInPc(inPc)
                    Toast.makeText(
                        this,
                        if (inPc) "Web apps open in Brave inside the PC (Air stays in the toolbar)"
                        else "Web apps and links open in Air (phone engine)",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setItems
                }
                if (i == profiles.size + 3) {
                    val phone = view?.phoneTouch == false
                    view?.phoneTouch = phone
                    prefs.edit().putBoolean(KEY_PHONE_TOUCH, phone).apply()
                    Toast.makeText(
                        this,
                        if (phone) "Phone style: swipe scrolls, tap clicks, hold = right click, hold + move = select / drag"
                        else "Mouse style: drag selects, hold = right click, two fingers scroll",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setItems
                }
                if (i == profiles.size + 2) {
                    AirBoost.setPhoneGpu(this, !gpu)
                    Toast.makeText(
                        this,
                        if (!gpu) "Phone GPU on – games use it from their next start. Benchmark compares both."
                        else "Graphics: CPU – games use it from their next start.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setItems
                }
                val b = profiles[i]
                AirBoost.applyNow(this, b)
                view?.sharpness = b.sharpen
                Toast.makeText(this, "AirBoost: ${b.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openOnPhone(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return   // web links only, nothing else
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            Toast.makeText(this, "Opened on your phone. Sign in, then come back to AirCursor PC.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", url))
            Toast.makeText(this, "Link copied – paste it in your phone's browser", Toast.LENGTH_LONG).show()
        }
    }

    /** Stops a hung desktop and starts it again (the viewer keeps trying to connect meanwhile). */
    private fun restartDesktop() {
        baseStatus = "Restarting AirCursor PC…"
        CloudPcService.send(this, CloudPcService.ACTION_DESKTOP_STOP)
        Thread {
            val t0 = SystemClock.uptimeMillis()
            while (CloudPcBus.state.value.desktopRunning && SystemClock.uptimeMillis() - t0 < 15_000) {
                Thread.sleep(300)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (w, h) = desktopSize(this)
                CloudPcService.startDesktop(this, w, h)
                connectingSince = SystemClock.uptimeMillis()
            }
        }.start()
    }

    /** Steady FPS: games and the desktop picture are held at this rate (smoother than ups and downs). */
    private fun fpsMenu() {
        val choices = AirBoost.FPS_CHOICES
        val cur = choices.indexOf(AirBoost.fpsLimit(this)).coerceAtLeast(0)
        val labels = choices.map {
            when (it) {
                30 -> "30 FPS – steadiest, coolest phone (3D games)"
                45 -> "45 FPS – steady and smooth"
                60 -> "60 FPS – smoothest when the phone can keep up"
                else -> "No limit – highest numbers, more ups and downs"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Steady FPS")
            .setSingleChoiceItems(labels, cur) { d, i ->
                val n = choices[i]
                AirBoost.setFpsLimit(this, n)
                view?.fpsLimit = n
                Toast.makeText(this, "Steady FPS: ${AirBoost.fpsLabel(n)} (games use it from their next start)", Toast.LENGTH_LONG).show()
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** AirBoost now: stop background jobs inside the desktop and free memory. */
    private fun boostNow() {
        CloudPcService.send(this, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession("cloudpc-light 0; free -h | head -n 2"))
        Toast.makeText(this, "Boost: background jobs stopped", Toast.LENGTH_SHORT).show()
    }

    // ---- Files between the phone and the PC ----

    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFromPhone(uris)
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = pendingExport
        pendingExport = null
        if (uri != null && src != null) saveToPhone(src, uri)
    }

    private fun filesMenu() {
        AlertDialog.Builder(this)
            .setTitle("Files")
            .setItems(arrayOf("Import from phone", "Save to phone")) { _, which ->
                if (which == 0) importFiles.launch(arrayOf("*/*")) else chooseExport()
            }
            .show()
    }

    private fun importFromPhone(uris: List<Uri>) {
        val dir = File(CloudPcEngine.rootfs(this), "root/Downloads/${CloudPcTools.FROM_PHONE}").apply { mkdirs() }
        Thread {
            var ok = 0
            for (uri in uris) {
                try {
                    val target = uniqueFile(dir, displayName(uri) ?: "file-${System.currentTimeMillis()}")
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ok++
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                Toast.makeText(this, "$ok file(s) copied to Downloads/${CloudPcTools.FROM_PHONE}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun chooseExport() {
        val dir = File(CloudPcEngine.rootfs(this), "root/Desktop/${CloudPcTools.TO_PHONE}").apply { mkdirs() }
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "Put files in the \"${CloudPcTools.TO_PHONE}\" folder on the desktop first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save to phone")
            .setItems(files.map { it.name }.toTypedArray()) { _, which ->
                pendingExport = files[which]
                exportFile.launch(files[which].name)
            }
            .show()
    }

    /** A file from the PC's home folder in the phone's editor: CapCut when it is installed, else the share sheet. */
    private fun editOnPhone(guestPath: String) {
        val home = File(CloudPcEngine.rootfs(this), "root").canonicalFile
        val file = File(CloudPcEngine.rootfs(this), guestPath.removePrefix("/")).canonicalFile
        if (!file.path.startsWith(home.path + File.separator) || !file.isFile) {
            Toast.makeText(this, "That file could not be found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.pcfiles", file)
        } catch (_: Exception) {
            Toast.makeText(this, "That file cannot be shared", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            setDataAndType(uri, type)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent(send).setPackage(CAPCUT))
            return
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent.createChooser(send, "Edit ${file.name} with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "No app on the phone can open it", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendToPhone(guestPath: String) {
        val root = CloudPcEngine.rootfs(this).canonicalFile
        val file = File(root, guestPath.removePrefix("/")).canonicalFile
        // Only files inside the PC, never anything else of the app.
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            Toast.makeText(this, "That file could not be found", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = file
        exportFile.launch(file.name)
    }

    private fun saveToPhone(src: File, uri: Uri) {
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Saved to phone" else "Could not save the file", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return name?.replace('/', '_')
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    @Suppress("DEPRECATION")
    private fun toggleKeyboard() {
        val v = view ?: return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        v.requestFocus()
        if (!imm.showSoftInput(v, InputMethodManager.SHOW_FORCED)) imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun askExit() {
        AlertDialog.Builder(this)
            .setTitle("AirCursor PC")
            .setItems(arrayOf("Keep running in the background", "Stop the desktop", "Send a problem report", "Cancel")) { _, which ->
                when (which) {
                    0 -> finish()
                    1 -> {
                        CloudPcService.send(this, CloudPcService.ACTION_DESKTOP_STOP)
                        finish()
                    }
                    2 -> ProblemReport.share(this)
                }
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        view?.connect()
        // Away for a while (screen off, other apps): ask for the password again, like Windows.
        if (lockView == null && hiddenAt > 0 && PcLock.hasPassword(this) &&
            SystemClock.uptimeMillis() - hiddenAt > PcLock.RELOCK_AFTER_MS
        ) {
            showLock(PcLockScreen.Mode.SIGN_IN)
        }
        hiddenAt = 0L
    }

    override fun onResume() {
        super.onResume()
        if (lockView == null) DesktopInput.target = view
        if (::videoPlayer.isInitialized) videoPlayer.resume()
        air?.resume()
        view?.sharpness = AirBoost.current(this).sharpen
        view?.fpsLimit = AirBoost.fpsLimit(this)
        // Steady speed instead of short bursts followed by heat slow-downs (phones that support it).
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                window.setSustainedPerformanceMode(true)
            } catch (_: Exception) {
            }
        }
        updateButtons()
    }

    override fun onPause() {
        if (::videoPlayer.isInitialized) videoPlayer.pause()
        air?.pause()
        if (DesktopInput.target === view) DesktopInput.target = null
        view?.releaseAll()
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                window.setSustainedPerformanceMode(false)
            } catch (_: Exception) {
            }
        }
        super.onPause()
    }

    override fun onStop() {
        hiddenAt = SystemClock.uptimeMillis()
        view?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        remote?.stop()
        shell?.destroy()
        if (::status.isInitialized) status.removeCallbacks(statusTick)
        if (::videoPlayer.isInitialized) videoPlayer.close()
        air?.closeAll()
        assistant?.release()
        view?.release()
        view = null
        super.onDestroy()
    }
}
