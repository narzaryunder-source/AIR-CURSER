package com.aircursor.cloudpc

import android.app.AlertDialog
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
import com.aircursor.service.HandTrackingService
import com.aircursor.service.Permissions
import com.aircursor.service.ScreenUtils
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen AirCursor PC desktop. The hand cursor works here as a real mouse. */
class CloudPcDesktopActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "cloudpc_engine"
        private const val KEY_FPS = "show_fps"
        private const val ON = 0xEE4F46E5.toInt()

        /** The PC asks to open a web link in the phone's own browser (logins: fast, already signed in). */
        private const val OPEN_ON_PHONE = "aircursor-open:"
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
    private lateinit var tools: LinearLayout
    private val stickyButtons = HashMap<Int, View>()
    private var handButton: View? = null
    private var fpsButton: View? = null
    private var pendingExport: File? = null

    // Start screen: the latest step from the log, and one automatic restart if the desktop hangs.
    private var baseStatus: String? = null
    private var connectingSince = 0L
    private var restarted = false
    private val statusTick = object : Runnable {
        override fun run() {
            val base = baseStatus
            if (base != null && status.visibility == View.VISIBLE) {
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

        val v = RfbView(this, CloudPcDesktop.socketPath(this))
        v.sharpness = AirBoost.current(this).sharpen
        v.fpsLimit = AirBoost.fpsLimit(this)
        v.showFps = prefs.getBoolean(KEY_FPS, false)
        view = v
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = round(0xE6131A2E.toInt(), 18)
            text = "Starting AirCursor PC…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

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
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(handle)
            addView(toolScroll)
        }
        root.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, dp(6), 0)
        })

        tools.addView(iconButton(R.drawable.ic_ui_keyboard, "Keyboard") { toggleKeyboard() })
        handButton = iconButton(R.drawable.ic_ui_hand, "Hand control") { toggleHand() }.also { tools.addView(it) }
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

        v.onStatus = { msg ->
            if (msg != null && baseStatus == null) connectingSince = SystemClock.uptimeMillis()
            baseStatus = msg
            status.text = msg ?: ""
            status.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
        connectingSince = SystemClock.uptimeMillis()
        status.post(statusTick)
        v.onStickyChanged = { updateButtons() }
        v.onDesktopClipboard = { text ->
            if (text.startsWith(OPEN_ON_PHONE)) {
                openOnPhone(text.removePrefix(OPEN_ON_PHONE).trim())
            } else if (text.startsWith(PhoneApps.MARKER)) {
                if (!PhoneApps.open(this, text.removePrefix(PhoneApps.MARKER).trim())) {
                    Toast.makeText(this, "This phone app could not be opened", Toast.LENGTH_SHORT).show()
                }
            } else {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AirCursor PC", text))
                Toast.makeText(this, "Copied to phone", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(root)
        updateButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askExit()
        })
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
        setOn(handButton, HandTrackingService.isRunning)
        setOn(fpsButton, v.showFps)
    }

    // ---- Toolbar actions ----

    /** Hand cursor on/off right from the desktop (the camera service may only start while we are visible). */
    private fun toggleHand() {
        if (HandTrackingService.isRunning) {
            HandTrackingService.stop(this)
            Toast.makeText(this, "Hand control off", Toast.LENGTH_SHORT).show()
        } else if (!Permissions.camera(this) || !Permissions.accessibility(this)) {
            Toast.makeText(this, "Open AirCursor and finish the setup first", Toast.LENGTH_LONG).show()
        } else {
            HandTrackingService.start(this)
            Toast.makeText(this, "Hand control on – show your hand to the front camera", Toast.LENGTH_SHORT).show()
        }
        tools.postDelayed({ updateButtons() }, 800)
    }

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
        val labels = profiles.map { "${it.label} – ${it.about}" }.toTypedArray() +
            "Steady FPS: ${AirBoost.fpsLabel(fps)} – tap to change" +
            "Boost now – stop background jobs, free memory"
        AlertDialog.Builder(this)
            .setTitle("AirBoost (now: ${now.label})")
            .setItems(labels) { _, i ->
                if (i == profiles.size) {
                    fpsMenu()
                    return@setItems
                }
                if (i > profiles.size) {
                    boostNow()
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
            .setItems(arrayOf("Keep running in the background", "Stop the desktop", "Cancel")) { _, which ->
                when (which) {
                    0 -> finish()
                    1 -> {
                        CloudPcService.send(this, CloudPcService.ACTION_DESKTOP_STOP)
                        finish()
                    }
                }
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        view?.connect()
    }

    override fun onResume() {
        super.onResume()
        DesktopInput.target = view
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
        view?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        if (::status.isInitialized) status.removeCallbacks(statusTick)
        view?.release()
        view = null
        super.onDestroy()
    }
}
