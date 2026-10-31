package com.aircursor.pc

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * A Windows-style taskbar shown above every app while PC mode is on (drawn by the
 * accessibility service). It can be collapsed to a small tab so it never hides app buttons.
 */
class TaskbarOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var collapsed = false
    private var clock: TextView? = null
    private val density = service.resources.displayMetrics.density

    private val tick = object : Runnable {
        override fun run() {
            clock?.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            main.postDelayed(this, 20_000)
        }
    }

    val isShown get() = view != null

    fun show() {
        if (view != null) return
        val v = if (collapsed) buildCollapsed() else buildBar()
        try {
            wm.addView(v, params())
            view = v
            main.removeCallbacks(tick)
            if (!collapsed) main.post(tick)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        main.removeCallbacks(tick)
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
        clock = null
    }

    private fun rebuild() {
        hide()
        show()
    }

    private fun params(): WindowManager.LayoutParams {
        val h = (44 * density).toInt()
        return WindowManager.LayoutParams(
            if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "AirCursor taskbar"
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(service).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val p = (10 * density).toInt()
        setPadding(p, 0, p, 0)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(0x22FFFFFF)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            val m = (3 * density).toInt()
            setMargins(m, m, m, m)
        }
    }

    private fun buildBar(): View = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xE6101418.toInt())
        gravity = Gravity.CENTER_VERTICAL
        addView(button("▲ Start") { PcMode.openDesktop(service, startMenu = true) })
        addView(button("🖥 Desktop") { PcMode.openDesktop(service) })
        addView(button("◀ Back") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) })
        addView(button("⧉ Apps") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) })
        addView(button("⊟ Split") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) })
        addView(button("🔔") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) })
        addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
        val c = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        clock = c
        addView(c)
        addView(button("▾") {
            collapsed = true
            rebuild()
        })
    }

    private fun buildCollapsed(): View = LinearLayout(service).apply {
        setBackgroundColor(0xCC101418.toInt())
        addView(button("▴ PC") {
            collapsed = false
            rebuild()
        })
    }
}
