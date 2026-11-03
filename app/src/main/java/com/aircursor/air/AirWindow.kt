package com.aircursor.air

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Air in a window over AirCursor PC: whole screen by default, or a big movable window
 * (drag it by "Air" in the tab strip). Minimize keeps the tabs; Close ends them.
 */
@SuppressLint("ClickableViewAccessibility")
class AirWindow(
    private val activity: Activity,
    private val host: FrameLayout,
    private val pickPhoneFiles: (multiple: Boolean, done: (List<Uri>) -> Unit) -> Unit,
    private val openInPc: (String) -> Unit,
) {
    private val dp = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).roundToInt()
    private var frame: FrameLayout? = null
    private var browser: AirBrowser? = null
    private var big = true          // whole screen, else a movable window
    private var videoFull = false

    val isShown get() = frame?.visibility == View.VISIBLE

    /** Shows Air with [url] (null: just show it, with the start page if there are no tabs). */
    fun open(url: String?) {
        val b = browser ?: create()
        frame?.visibility = View.VISIBLE
        frame?.bringToFront()
        b.setActive(true)
        when {
            url != null -> b.open(url)
            !b.hasTabs -> b.open(AirEngine.START)
        }
    }

    fun minimize() {
        browser?.setActive(false)
        frame?.visibility = View.GONE
    }

    fun close() {
        browser?.closeAll()
        frame?.let { host.removeView(it) }
        frame = null
        browser = null
    }

    /** Back key: inside Air first; when there is nothing to go back to, Air is minimized. */
    fun back(): Boolean {
        if (!isShown) return false
        if (browser?.back() != true) minimize()
        return true
    }

    fun pause() = browser?.setActive(false)

    fun resume() {
        if (isShown) browser?.setActive(true)
    }

    private fun create(): AirBrowser {
        val f = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0B0D12.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(14).toFloat()
            isClickable = true   // nothing reaches the PC desktop behind
        }
        val b = AirBrowser(activity, pickPhoneFiles, object : AirBrowser.Window {
            override fun toggleSize() {
                big = !big
                layout()
            }

            override fun minimize() = this@AirWindow.minimize()

            override fun closeAll() = close()

            override fun fullScreen(on: Boolean) {
                videoFull = on
                layout()
            }

            override fun openInPcBrowser(url: String) {
                minimize()
                openInPc(url)
            }
        })
        f.addView(b, FrameLayout.LayoutParams(-1, -1))
        host.addView(f, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        frame = f
        browser = b
        // Drag the window by "Air" (only when it is not whole screen).
        var dx = 0f
        var dy = 0f
        b.dragHandle.setOnTouchListener { _, e ->
            if (big || videoFull) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = f.x - e.rawX; dy = f.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    f.x = (e.rawX + dx).coerceIn(0f, (host.width - f.width).toFloat().coerceAtLeast(0f))
                    f.y = (e.rawY + dy).coerceIn(0f, (host.height - f.height).toFloat().coerceAtLeast(0f))
                }
            }
            true
        }
        layout()
        return b
    }

    private fun layout() {
        val f = frame ?: return
        val hw = host.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val hh = host.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val whole = big || videoFull
        val w = if (whole) hw else (hw * 0.82f).roundToInt()
        val h = if (whole) hh else (hh * 0.86f).roundToInt()
        f.layoutParams = (f.layoutParams as FrameLayout.LayoutParams).apply {
            width = w
            height = h
        }
        if (whole) {
            f.x = 0f
            f.y = 0f
        } else {
            f.x = ((hw - w) / 2f)
            f.y = ((hh - h) / 2f)
        }
        f.requestLayout()
    }
}
