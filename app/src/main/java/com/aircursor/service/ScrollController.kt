package com.aircursor.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.util.Log
import kotlin.math.abs

/**
 * Smooth, continuous scrolling: keeps one virtual finger down and moves it with the requested
 * velocity using chained continued strokes. When the finger reaches the edge of the safe area it
 * is lifted and placed back, so scrolling can go on forever while the cursor itself stays still.
 */
class ScrollController(
    private val service: AccessibilityService,
    private val main: Handler,
    private val screenSize: () -> Pair<Float, Float>,
) {
    private companion object {
        const val SEGMENT_MS = 24L
        const val STOP_HOLD_MS = 70L
        /** Lift the finger if it would stay still this long (avoids an accidental long press). */
        const val IDLE_LIFT_MS = 120L
        /** Slower moves would never leave the touch slop and could turn into a long press. */
        const val MIN_SPEED = 300f
    }

    private var vx = 0f
    private var vy = 0f
    private var anchorX = 0f
    private var anchorY = 0f

    private var stroking = false
    private var inFlight = false
    private var stopping = false
    private var current: GestureDescription.StrokeDescription? = null
    private var x = 0f
    private var y = 0f
    private var generation = 0
    private var idleSinceMs = -1L

    val isActive get() = stroking || inFlight

    /** Called every tracking frame while in scroll mode. */
    fun update(cursorX: Float, cursorY: Float, vx: Float, vy: Float) {
        this.vx = boost(vx)
        this.vy = boost(vy)
        anchorX = cursorX
        anchorY = cursorY
        if (vx != 0f || vy != 0f) {
            stopping = false
            if (!stroking && !inFlight) begin()
        }
    }

    private fun boost(v: Float) = when {
        v == 0f -> 0f
        abs(v) < MIN_SPEED -> if (v > 0) MIN_SPEED else -MIN_SPEED
        else -> v
    }

    /** Scroll mode ended: hold still for a moment (no fling) and lift the finger. */
    fun stop() {
        vx = 0f
        vy = 0f
        if (stroking) stopping = true
    }

    fun cancel() {
        generation++
        stroking = false
        inFlight = false
        stopping = false
        current = null
        vx = 0f
        vy = 0f
    }

    private fun safeArea(): FloatArray {
        val (w, h) = screenSize()
        // Stay away from system gesture edges (back, home, notification shade).
        return floatArrayOf(w * 0.1f, h * 0.15f, w * 0.9f, h * 0.85f)
    }

    private fun begin() {
        val area = safeArea()
        val l = area[0]
        val t = area[1]
        val r = area[2]
        val b = area[3]
        var sx = anchorX.coerceIn(l, r)
        var sy = anchorY.coerceIn(t, b)
        // Not enough room in the moving direction? Start from the opposite side.
        val minRoomY = (b - t) * 0.4f
        val minRoomX = (r - l) * 0.4f
        if (vy < 0 && sy - t < minRoomY) sy = b
        if (vy > 0 && b - sy < minRoomY) sy = t
        if (vx < 0 && sx - l < minRoomX) sx = r
        if (vx > 0 && r - sx < minRoomX) sx = l
        x = sx
        y = sy
        stroking = true
        stopping = false
        idleSinceMs = -1L
        val path = Path().apply { moveTo(x, y) }
        send(GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true), last = false)
    }

    private fun next() {
        val prev = current ?: return
        if (!stroking) return
        val area = safeArea()
        val path = Path().apply { moveTo(x, y) }
        val stroke: GestureDescription.StrokeDescription
        var last = false
        if (vx == 0f && vy == 0f) {
            val now = android.os.SystemClock.uptimeMillis()
            if (idleSinceMs < 0) idleSinceMs = now
            if (now - idleSinceMs >= IDLE_LIFT_MS) stopping = true
        } else {
            idleSinceMs = -1L
        }
        try {
            if (stopping) {
                // Finger stays still before lifting, so the list stops exactly here.
                path.lineTo(x, y)
                stroke = prev.continueStroke(path, 0, STOP_HOLD_MS, false)
                last = true
            } else {
                val dt = SEGMENT_MS / 1000f
                var nx = x + vx * dt
                var ny = y + vy * dt
                val hitEdge = nx < area[0] || nx > area[2] || ny < area[1] || ny > area[3]
                nx = nx.coerceIn(area[0], area[2])
                ny = ny.coerceIn(area[1], area[3])
                path.lineTo(nx, ny)
                x = nx
                y = ny
                // At the edge: lift (the list keeps its momentum) and start again next round.
                last = hitEdge
                stroke = prev.continueStroke(path, 0, SEGMENT_MS, !last)
            }
        } catch (e: Exception) {
            Log.w("AirCursorScroll", "continueStroke failed", e)
            cancel()
            return
        }
        send(stroke, last)
    }

    private fun send(stroke: GestureDescription.StrokeDescription, last: Boolean) {
        val gen = generation
        current = stroke
        inFlight = true
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    inFlight = false
                    if (last) {
                        stroking = false
                        current = null
                        stopping = false
                        // Still scrolling? Put the finger down again.
                        if (vx != 0f || vy != 0f) begin()
                    } else {
                        next()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    cancel()
                }
            },
            main,
        )
        if (!ok) cancel()
    }
}
