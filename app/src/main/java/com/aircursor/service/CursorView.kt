package com.aircursor.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.aircursor.core.CursorMode
import com.aircursor.core.HoldKind

/**
 * Full-screen, non-touchable overlay that draws the PC-style arrow cursor,
 * click ripples, the hold progress ring, a short gesture label and the FPS debug text.
 */
@SuppressLint("ViewConstructor")
class CursorView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    // State, updated from the main thread.
    private var cx = -1f
    private var cy = -1f
    private var mode = CursorMode.NORMAL
    private var handPresent = false
    private var handLostSince = 0L
    private var holdKind: HoldKind? = null
    private var holdProgress = 0f
    var cursorScale = 1f
        set(value) {
            field = value
            rebuildArrow()
            invalidate()
        }
    var debugText: String? = null

    private var alphaNow = 1f

    private data class Ripple(val x: Float, val y: Float, val start: Long, val color: Int)
    private val ripples = ArrayList<Ripple>()

    private var label: String? = null
    private var labelUntil = 0L

    private val screenOffset = IntArray(2)

    private val arrow = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.6f * density
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        color = 0x66000000
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD202124.toInt() }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
    }
    private val debugBgPaint = Paint().apply { color = 0x99000000.toInt() }
    private val ringRect = RectF()
    private val tmpRect = RectF()

    init {
        rebuildArrow()
    }

    /** Classic mouse pointer, tip at (0, 0). */
    private fun rebuildArrow() {
        val u = density * cursorScale * 1.25f
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 17f * u)
        arrow.lineTo(4f * u, 13f * u)
        arrow.lineTo(7f * u, 19.5f * u)
        arrow.lineTo(9.6f * u, 18.4f * u)
        arrow.lineTo(6.7f * u, 12f * u)
        arrow.lineTo(12f * u, 12f * u)
        arrow.close()
    }

    fun update(
        x: Float,
        y: Float,
        mode: CursorMode,
        handPresent: Boolean,
        holdKind: HoldKind?,
        holdProgress: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        if (this.handPresent && !handPresent) handLostSince = now
        cx = x
        cy = y
        this.mode = mode
        this.handPresent = handPresent
        this.holdKind = holdKind
        this.holdProgress = holdProgress
        invalidate()
    }

    fun ripple(x: Float, y: Float, color: Int = 0xFF2196F3.toInt()) {
        ripples += Ripple(x, y, SystemClock.uptimeMillis(), color)
        invalidate()
    }

    fun showLabel(text: String) {
        label = text
        labelUntil = SystemClock.uptimeMillis() + 1000
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(screenOffset)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false
        // Cursor coordinates are screen coordinates; shift them in case the window is offset.
        canvas.save()
        canvas.translate(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())

        // Opacity: fade to 50% when the hand has been lost for 2 s or tracking is paused.
        val targetAlpha = when {
            mode == CursorMode.PAUSED -> 0.5f
            !handPresent && now - handLostSince > 2000 -> 0.5f
            else -> 1f
        }
        if (kotlin.math.abs(alphaNow - targetAlpha) > 0.01f) {
            alphaNow += (targetAlpha - alphaNow) * 0.15f
            animating = true
        } else {
            alphaNow = targetAlpha
        }
        if (!handPresent && now - handLostSince <= 2000) animating = true

        // Ripples
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val p = (now - r.start) / 400f
            if (p >= 1f) {
                it.remove()
                continue
            }
            ripplePaint.color = r.color
            ripplePaint.alpha = ((1f - p) * 255).toInt()
            canvas.drawCircle(r.x, r.y, (8f + 32f * p) * density, ripplePaint)
            animating = true
        }

        if (cx >= 0f) {
            // Hold progress ring (fist = pause, palm = home)
            val kind = holdKind
            if (kind != null && holdProgress > 0.12f) {
                val radius = 26f * density * cursorScale
                ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                ringPaint.color = if (kind == HoldKind.FIST) 0xFFFF5252.toInt() else 0xFF4CAF50.toInt()
                canvas.drawCircle(cx, cy, radius, ringBgPaint)
                canvas.drawArc(ringRect, -90f, 360f * holdProgress.coerceIn(0f, 1f), false, ringPaint)
            }

            fillPaint.color = when (mode) {
                CursorMode.DRAGGING -> 0xFFFF9800.toInt()
                CursorMode.SCROLLING -> 0xFF00BCD4.toInt()
                CursorMode.PRESSING -> 0xFFFFEB3B.toInt()
                CursorMode.PALM -> 0xFFC8E6C9.toInt()
                CursorMode.PAUSED -> 0xFFBDBDBD.toInt()
                CursorMode.NORMAL -> Color.WHITE
            }
            val a = (alphaNow * 255).toInt()
            fillPaint.alpha = a
            outlinePaint.alpha = a
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawPath(arrow, fillPaint)
            canvas.drawPath(arrow, outlinePaint)
            canvas.restore()

            if (mode == CursorMode.PAUSED) {
                // Small "pause" bars next to the arrow
                val s = density * cursorScale
                fillPaint.color = Color.BLACK
                fillPaint.alpha = a
                canvas.drawRect(cx + 14 * s, cy + 2 * s, cx + 17 * s, cy + 11 * s, fillPaint)
                canvas.drawRect(cx + 20 * s, cy + 2 * s, cx + 23 * s, cy + 11 * s, fillPaint)
            }
        }

        // Gesture label just below the cursor (kept on screen)
        val text = label
        if (text != null && now < labelUntil) {
            val pad = 8f * density
            val tw = labelTextPaint.measureText(text)
            val th = labelTextPaint.textSize
            val sw = (width + screenOffset[0]).toFloat()
            val sh = (height + screenOffset[1]).toFloat()
            var lx = (if (cx >= 0) cx else sw / 2f) - tw / 2f
            var ly = (if (cy >= 0) cy else sh / 2f) + 34f * density * cursorScale
            lx = lx.coerceIn(pad, (sw - tw - pad).coerceAtLeast(pad))
            if (ly + th + pad > sh) ly = cy - 40f * density
            tmpRect.set(lx - pad, ly - pad, lx + tw + pad, ly + th + pad * 0.6f)
            canvas.drawRoundRect(tmpRect, 12f * density, 12f * density, labelBgPaint)
            canvas.drawText(text, lx, ly + th * 0.8f, labelTextPaint)
            animating = true
        } else {
            label = null
        }
        canvas.restore()

        // Debug overlay (fixed in the top-left corner of the view)
        debugText?.let { dbg ->
            val lines = dbg.split('\n')
            val lh = debugPaint.textSize * 1.25f
            val w = lines.maxOf { l -> debugPaint.measureText(l) } + 16f * density
            val top = 40f * density
            canvas.drawRect(0f, top, w, top + lh * lines.size + 8f * density, debugBgPaint)
            lines.forEachIndexed { i, l -> canvas.drawText(l, 8f * density, top + lh * (i + 1), debugPaint) }
        }

        if (animating) postInvalidateOnAnimation()
    }
}
