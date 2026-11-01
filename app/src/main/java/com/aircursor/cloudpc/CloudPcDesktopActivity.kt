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
        private const val PREFS = "cloudpc_engine"
        private const val KEY_WIDTH = "desktop_width"
        val WIDTHS = listOf(1024, 1280, 1600, 1920)

        fun desktopWidth(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WIDTH, 1280)
        fun setDesktopWidth(c: Context, w: Int) =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_WIDTH, w).apply()

        /** Desktop size with the phone's landscape shape (even numbers for the VNC server). */
        fun desktopSize(c: Context): Pair<Int, Int> {
            val s = ScreenUtils.realSize(c)
            val long = max(s.x, s.y).toFloat()
            val short = min(s.x, s.y).toFloat()
            val w = desktopWidth(c)
            val h = ((w * short / long) / 2f).roundToInt() * 2
            return w to h.coerceAtLeast(480)
        }
    }

    private var view: RfbView? = null
    private lateinit var status: TextView
    private lateinit var tools: LinearLayout
    private val stickyButtons = HashMap<Int, Button>()

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
