package com.aircursor.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import com.aircursor.core.CursorMode
import com.aircursor.core.HoldKind

/**
 * Full-screen, non-touchable overlay that draws the PC-style arrow cursor,
 * click ripples, the hold progress ring, a short gesture label and the FPS debug text.
 *
 * The cursor is drawn on every display frame (60/90/120 Hz), not only when a camera frame
 * arrives: between tracking samples the position is extrapolated from the hand velocity
 * (which also hides the camera + inference latency) and eased, so motion looks fluid.
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

    // Latest tracking sample (screen px, uptime ms) and the hand velocity (px per ms).
    private var tgtX = -1f
    private var tgtY = -1f
    private var tgtT = 0L
    private var velX = 0f
    private var velY = 0f

    /** 0 = no prediction, 1 = extrapolate over the full tracking latency. */
    var prediction = 0.6f

    private val choreographer = Choreographer.getInstance()
    private var loopRunning = false
    private var lastFrameMs = 0L
    private var renderFps = 0f
    private val frameCallback = Choreographer.FrameCallback { onFrame() }

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

    /** New tracking sample. [sampleMs] is the camera frame time (SystemClock.uptimeMillis base). */
    fun update(
        x: Float,
        y: Float,
        sampleMs: Long,
        mode: CursorMode,
        handPresent: Boolean,
        holdKind: HoldKind?,
        holdProgress: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        if (this.handPresent && !handPresent) handLostSince = now
        val moving = handPresent && this.handPresent &&
            (mode == CursorMode.NORMAL || mode == CursorMode.DRAGGING)
        val dt = (sampleMs - tgtT).toFloat()
        if (moving && tgtX >= 0f && dt in 1f..250f) {
            val ivx = ((x - tgtX) / dt).coerceIn(-8f, 8f)
            val ivy = ((y - tgtY) / dt).coerceIn(-8f, 8f)
            velX = velX * 0.4f + ivx * 0.6f
            velY = velY * 0.4f + ivy * 0.6f
        } else {
            velX = 0f
            velY = 0f
        }
        // A click must be drawn exactly where it lands.
        if (mode == CursorMode.PRESSING && this.mode != CursorMode.PRESSING || cx < 0f) {
            cx = x
            cy = y
        }
        tgtX = x
        tgtY = y
        tgtT = sampleMs
        this.mode = mode
        this.handPresent = handPresent
        this.holdKind = holdKind
        this.holdProgress = holdProgress
        startLoop()
    }

    private fun startLoop() {
        if (loopRunning || !isAttachedToWindow) return
        loopRunning = true
        lastFrameMs = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback)
        loopRunning = false
        super.onDetachedFromWindow()
    }

    private fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16f else (now - lastFrameMs).coerceIn(1L, 100L).toFloat()
        lastFrameMs = now
        renderFps = if (renderFps == 0f) 1000f / dt else renderFps * 0.9f + (1000f / dt) * 0.1f

        if (tgtX >= 0f) {
            val age = (now - tgtT).coerceIn(0L, 200L).toFloat()
            // Stop extrapolating when samples get old (hand stopped being tracked).
            val fresh = if (age <= 90f) 1f else (1f - (age - 90f) / 60f).coerceAtLeast(0f)
            val lead = min(age * prediction, 80f) * fresh
            val maxX = (width + screenOffset[0] - 1).toFloat().coerceAtLeast(0f)
            val maxY = (height + screenOffset[1] - 1).toFloat().coerceAtLeast(0f)
            val px = (tgtX + velX * lead).coerceIn(0f, maxX)
            val py = (tgtY + velY * lead).coerceIn(0f, maxY)
            // Ease towards the predicted point; more easing when prediction is low.
            val tau = 8f + (1f - prediction) * 22f
            val a = 1f - exp(-dt / tau)
            cx += (px - cx) * a
            cy += (py - cy) * a
        }
        invalidate()

        val settled = abs(cx - tgtX) < 0.3f && abs(cy - tgtY) < 0.3f
        val keepGoing = handPresent || !settled || now - handLostSince < 2600 ||
            ripples.isNotEmpty() || label != null
        if (keepGoing && isAttachedToWindow) {
            choreographer.postFrameCallback(frameCallback)
        } else {
            loopRunning = false
        }
    }

    fun ripple(x: Float, y: Float, color: Int = 0xFF2196F3.toInt()) {
        ripples += Ripple(x, y, SystemClock.uptimeMillis(), color)
        startLoop()
    }

    fun showLabel(text: String) {
        label = text
        labelUntil = SystemClock.uptimeMillis() + 1000
        startLoop()
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
        if (abs(alphaNow - targetAlpha) > 0.01f) {
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
            val lines = dbg.split('\n') + "Render: %.0f fps".format(renderFps)
            val lh = debugPaint.textSize * 1.25f
            val w = lines.maxOf { l -> debugPaint.measureText(l) } + 16f * density
            val top = 40f * density
            canvas.drawRect(0f, top, w, top + lh * lines.size + 8f * density, debugBgPaint)
            lines.forEachIndexed { i, l -> canvas.drawText(l, 8f * density, top + lh * (i + 1), debugPaint) }
        }

        if (animating) startLoop()
    }
}
