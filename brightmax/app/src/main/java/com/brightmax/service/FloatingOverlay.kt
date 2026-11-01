package com.brightmax.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.brightmax.R
import com.brightmax.hdr.HdrController
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draggable floating ON/OFF button shown over other apps (TYPE_APPLICATION_OVERLAY).
 * Tap = toggle boost, long-press = open the app. While the boost is on, the overlay window also
 * carries the brightness override, HDR color mode and a 1-pixel HDR image (the "invisible HDR surface").
 */
class FloatingOverlay(
    context: Context,
    private val onToggle: () -> Unit,
    private val onOpenApp: () -> Unit,
) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).roundToInt()

    private val circle = TextView(ctx).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        elevation = dp(6f).toFloat()
    }

    private val caption = TextView(ctx).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    /** Tiny HDR image: keeps real HDR content on screen so the display keeps its HDR headroom. */
    private val hdrPixel = ImageView(ctx).apply {
        HdrController.hdrBitmap()?.let(::setImageBitmap)
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(ctx).apply {
            addView(circle, FrameLayout.LayoutParams(dp(60f), dp(60f)))
            addView(hdrPixel, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        })
        addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12f)
        y = dp(180f)
        title = "BrightMax"
    }

    var isShown = false
        private set

    private var appliedHdr = false

    init {
        setupTouch()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPressRunnable = Runnable {
            longPressed = true
            circle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onOpenApp()
        }
        circle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPressRunnable, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && isShown) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longPressed) {
                        v.performClick()
                        onToggle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
            }
            true
        }
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(): Boolean {
        if (isShown) return true
        if (!canShow()) return false
        return runCatching { wm.addView(root, lp) }.onSuccess { isShown = true }.isSuccess
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!isShown) return
        runCatching { wm.removeViewImmediate(root) }
        isShown = false
    }

    fun render(state: BoostState) {
        val on = state.active
        circle.text = if (on) {
            ctx.getString(R.string.overlay_on, (state.effectiveLevel * 100).roundToInt())
        } else {
            ctx.getString(R.string.overlay_off)
        }
        circle.contentDescription = ctx.getString(if (on) R.string.overlay_cd_on else R.string.overlay_cd_off)
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) {
                colors = if (state.thermalReduced) intArrayOf(0xFFFF7043.toInt(), 0xFFE64A19.toInt())
                else intArrayOf(0xFFFFD54F.toInt(), 0xFFFF8F00.toInt())
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(40f).toFloat()
            } else {
                setColor(0xCC263238.toInt())
            }
            setStroke(dp(2f), if (state.warned) Color.RED else 0x66FFFFFF)
        }
        caption.text = if (on) formatRemaining(state.remainingMs) else ""
        caption.visibility = if (on) View.VISIBLE else View.GONE
        caption.setTextColor(if (state.warned) 0xFFFF8A80.toInt() else Color.WHITE)

        hdrPixel.visibility = if (state.hdrActive) View.VISIBLE else View.GONE

        // Window attributes only change when the level / HDR state changes (not on every countdown tick).
        // In Sunlight mode the window must not override brightness, or auto-brightness (and the phone's
        // outdoor boost) would be switched off while this window is on screen.
        val brightness = if (on && !state.sunlightActive) {
            state.effectiveLevel
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (brightness != lp.screenBrightness || state.hdrActive != appliedHdr) {
            lp.screenBrightness = brightness
            HdrController.applyToParams(lp, state.hdrActive)
            appliedHdr = state.hdrActive
            if (isShown) runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    companion object {
        fun formatRemaining(ms: Long): String {
            val total = (ms.coerceAtLeast(0) + 999) / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}
