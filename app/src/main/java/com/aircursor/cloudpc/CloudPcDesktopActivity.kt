package com.aircursor.cloudpc

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aircursor.MainActivity
import com.aircursor.core.Keysyms
import com.aircursor.service.ScreenUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen Cloud PC desktop. The hand cursor works here as a real mouse. */
class CloudPcDesktopActivity : ComponentActivity() {
    companion object {
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
    private val stickyButtons = HashMap<Int, Button>()
    private var handButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CloudPcEngine.isReady(this) || !CloudPcDesktop.isInstalled(this)) {
            Toast.makeText(this, "Pehle Cloud PC aur Desktop install karo", Toast.LENGTH_LONG).show()
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
        view = v
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(0xCC1E2A3A.toInt())
                cornerRadius = dp(16).toFloat()
            }
            text = "Desktop start ho raha hai…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toolScroll = ScrollView(this).apply { addView(tools) }
        val handle = toolButton("☰") {
            tools.visibility = if (tools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(handle)
            addView(toolScroll)
        }
        root.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))

        tools.addView(toolButton("⌨") { toggleKeyboard() })
        handButton = toolButton("✋") { toggleHand() }
        tools.addView(handButton)
        tools.addView(toolButton("📋") { pasteFromPhone() })
        tools.addView(toolButton("🖼") {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        tools.addView(toolButton("⚡") { boostNow() })
        tools.addView(toolButton("Esc") { v.sendKey(Keysyms.ESCAPE) })
        tools.addView(toolButton("Tab") { v.sendKey(Keysyms.TAB) })
        for ((label, sym) in listOf("Ctrl" to Keysyms.CONTROL_L, "Alt" to Keysyms.ALT_L, "Win" to Keysyms.SUPER_L)) {
            val b = toolButton(label) { v.toggleSticky(sym) }
            stickyButtons[sym] = b
            tools.addView(b)
        }
        tools.addView(toolButton("↑") { v.sendKey(Keysyms.UP) })
        tools.addView(toolButton("↓") { v.sendKey(Keysyms.DOWN) })
        tools.addView(toolButton("←") { v.sendKey(Keysyms.LEFT) })
        tools.addView(toolButton("→") { v.sendKey(Keysyms.RIGHT) })
        tools.addView(toolButton("✕") { askExit() })

        v.onStatus = { msg ->
            status.text = msg ?: ""
            status.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
        v.onDesktopClipboard = { text ->
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Cloud PC", text))
            Toast.makeText(this, "📋 Desktop se copy → phone clipboard", Toast.LENGTH_SHORT).show()
        }
        v.onStickyChanged = {
            for ((sym, b) in stickyButtons) {
                (b.background as? GradientDrawable)?.setColor(if (sym in v.sticky) 0xEE1E88E5.toInt() else 0x99000000.toInt())
            }
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askExit()
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toolButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = 0
        minimumWidth = dp(48)
        minHeight = 0
        minimumHeight = dp(36)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply {
            setColor(0x99000000.toInt())
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(38)).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
        setOnClickListener { onClick() }
    }

    /** Phone photo -> Cloud PC wallpaper (the photo is copied; the phone's file is never changed). */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setWallpaper(uri)
    }

    private fun setWallpaper(uri: android.net.Uri) {
        Toast.makeText(this, "🖼 Wallpaper lag raha hai…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val ext = when (contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val dir = java.io.File(CloudPcEngine.rootfs(this), "root/Pictures/Wallpapers").apply { mkdirs() }
                val name = "phone-${System.currentTimeMillis()}.$ext"
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(dir, name).outputStream().use { input.copyTo(it) }
                }
                CloudPcService.send(
                    this, CloudPcService.ACTION_RUN,
                    CloudPcDesktop.inSession("aircursor-set-wallpaper /root/Pictures/Wallpapers/$name"),
                )
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Photo copy nahi hui: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** AirBoost now: stop background jobs inside the desktop and free RAM. */
    private fun boostNow() {
        CloudPcService.send(this, CloudPcService.ACTION_RUN, CloudPcDesktop.inSession("cloudpc-light 0; free -h | head -n 2"))
        Toast.makeText(this, "⚡ Boost: background kaam band, RAM free", Toast.LENGTH_SHORT).show()
    }

    private fun updateHandButton() {
        val on = com.aircursor.service.HandTrackingService.isRunning
        (handButton?.background as? GradientDrawable)?.setColor(if (on) 0xEE2E7D32.toInt() else 0x99000000.toInt())
    }

    /** Hand cursor on/off right from the desktop (camera service may only start while we are visible). */
    private fun toggleHand() {
        val svc = com.aircursor.service.HandTrackingService
        if (svc.isRunning) {
            svc.stop(this)
            Toast.makeText(this, "Haath ka cursor OFF", Toast.LENGTH_SHORT).show()
        } else if (!com.aircursor.service.Permissions.camera(this) || !com.aircursor.service.Permissions.accessibility(this)) {
            Toast.makeText(this, "AirCursor app me pehle setup poora karo (camera + accessibility)", Toast.LENGTH_LONG).show()
        } else {
            svc.start(this)
            Toast.makeText(this, "Haath ka cursor ON – ungli camera ke saamne", Toast.LENGTH_SHORT).show()
        }
        tools.postDelayed({ updateHandButton() }, 800)
    }

    private fun pasteFromPhone() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Phone clipboard khali hai", Toast.LENGTH_SHORT).show()
            return
        }
        view?.pasteFromPhone(text)
        Toast.makeText(this, "📋 Phone ka text desktop me paste", Toast.LENGTH_SHORT).show()
    }

    private fun toggleKeyboard() {
        val v = view ?: return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        v.requestFocus()
        if (!imm.showSoftInput(v, InputMethodManager.SHOW_FORCED)) imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun askExit() {
        AlertDialog.Builder(this)
            .setTitle("Cloud PC desktop")
            .setItems(arrayOf("Background me chalne do (wapas aa sakte ho)", "Desktop band karo", "Cancel")) { _, which ->
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
        updateHandButton()
    }

    override fun onPause() {
        if (DesktopInput.target === view) DesktopInput.target = null
        super.onPause()
    }

    override fun onStop() {
        view?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        view?.release()
        view = null
        super.onDestroy()
    }
}
