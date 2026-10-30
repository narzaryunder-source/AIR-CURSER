package com.aircursor.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aircursor.core.EngineOutput
import com.aircursor.core.GestureEvent
import com.aircursor.data.AppSettings
import com.aircursor.data.Prefs

/**
 * Draws the cursor overlay and performs taps / swipes / global actions.
 * The hand tracking service pushes [EngineOutput]s here on the main thread.
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AirCursorA11y"

        @Volatile
        var instance: CursorAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private lateinit var prefs: Prefs
    private lateinit var drag: DragController
    private var showFps = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        drag = DragController(this, main)
        windowManager = getSystemService(WindowManager::class.java)
        TrackingBus.update { it.copy(accessibilityConnected = true) }
        if (HandTrackingService.isRunning) onTrackingStarted()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance !== this) return
        instance = null
        drag.cancel()
        removeOverlay()
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        TrackingBus.update { it.copy(accessibilityConnected = false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changed: resize the overlay to the new screen size.
        cursorView?.let { v -> windowManager?.updateViewLayout(v, overlayParams()) }
    }

    // ---- Overlay ----

    private fun overlayParams(): WindowManager.LayoutParams {
        val size = ScreenUtils.realSize(this)
        return WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            title = "AirCursor"
        }
    }

    private fun addOverlay() {
        if (cursorView != null) return
        val view = CursorView(this)
        try {
            windowManager?.addView(view, overlayParams())
            cursorView = view
            applyPrefs()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot add overlay", e)
        }
    }

    private fun removeOverlay() {
        cursorView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        cursorView = null
    }

    private fun applyPrefs() {
        if (!::prefs.isInitialized) return
        showFps = prefs.get(AppSettings.SHOW_FPS)
        cursorView?.cursorScale = prefs.get(AppSettings.CURSOR_SIZE)
        if (!showFps) cursorView?.debugText = null
    }

    fun onTrackingStarted() {
        addOverlay()
    }

    fun onTrackingStopped() {
        drag.cancel()
        removeOverlay()
    }

    fun showLabel(text: String) {
        cursorView?.showLabel(text)
    }

    // ---- Engine output ----

    fun onEngineOutput(out: EngineOutput, fps: Float) {
        val view = cursorView ?: return
        view.update(out.x, out.y, out.mode, out.handPresent, out.holdKind, out.holdProgress)
        if (showFps) {
            view.debugText = "FPS %.1f\nHand: %s\nPose: %s\nPinch: %.2f".format(
                fps,
                if (out.handPresent) "yes" else "no",
                out.pose?.name ?: "-",
                out.pinchRatio,
            )
        }
        for (e in out.events) handleEvent(e, view)
    }

    private fun handleEvent(e: GestureEvent, view: CursorView) {
        when (e) {
            is GestureEvent.Tap -> {
                view.ripple(e.x, e.y)
                tap(e.x, e.y)
            }
            is GestureEvent.LongPress -> {
                view.ripple(e.x, e.y, 0xFFFFC107.toInt())
                view.showLabel("Long press")
                longPress(e.x, e.y)
            }
            is GestureEvent.DragStart -> {
                view.showLabel("Drag")
                drag.start(clampX(e.x), clampY(e.y), e.holdFirst)
            }
            is GestureEvent.DragMove -> drag.moveTo(clampX(e.x), clampY(e.y))
            is GestureEvent.DragEnd -> drag.end(clampX(e.x), clampY(e.y))
            is GestureEvent.Scroll -> scroll(e)
            GestureEvent.Back -> global(GLOBAL_ACTION_BACK, "Back", view)
            GestureEvent.Home -> global(GLOBAL_ACTION_HOME, "Home", view)
            GestureEvent.Notifications -> global(GLOBAL_ACTION_NOTIFICATIONS, "Notifications", view)
            GestureEvent.Recents -> global(GLOBAL_ACTION_RECENTS, "Recents", view)
            is GestureEvent.PauseChanged -> view.showLabel(if (e.paused) "Paused" else "Resumed")
            GestureEvent.Recalibrated -> view.showLabel("Recalibrated")
        }
    }

    private fun global(action: Int, label: String, view: CursorView) {
        drag.cancel()
        performGlobalAction(action)
        view.showLabel(label)
    }

    // ---- Gestures ----

    private fun screenW() = ScreenUtils.realSize(this).x.toFloat()
    private fun screenH() = ScreenUtils.realSize(this).y.toFloat()
    private fun clampX(x: Float) = x.coerceIn(0f, screenW() - 1f)
    private fun clampY(y: Float) = y.coerceIn(0f, screenH() - 1f)

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed", e)
        }
    }

    fun tap(x: Float, y: Float) {
        drag.cancel()
        val p = Path().apply { moveTo(clampX(x), clampY(y)) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 60))
    }

    fun longPress(x: Float, y: Float) {
        drag.cancel()
        val p = Path().apply { moveTo(clampX(x), clampY(y)) }
        dispatch(GestureDescription.StrokeDescription(p, 0, ViewConfiguration.getLongPressTimeout() + 300L))
    }

    private fun scroll(e: GestureEvent.Scroll) {
        drag.cancel()
        val w = screenW()
        val h = screenH()
        // Keep the swipe away from system gesture edges (back, home, notification shade).
        val padX = w * 0.08f
        val padY = h * 0.1f
        val dx = e.dx.coerceIn(-(w - 2 * padX), w - 2 * padX)
        val dy = e.dy.coerceIn(-(h - 2 * padY), h - 2 * padY)
        val sx = fitStart(e.x, dx, padX, w - padX)
        val sy = fitStart(e.y, dy, padY, h - padY)
        val p = Path().apply {
            moveTo(sx, sy)
            lineTo(sx + dx, sy + dy)
        }
        dispatch(GestureDescription.StrokeDescription(p, 0, 250))
    }

    /** Start position so that start and start+delta both stay within [lo, hi]. */
    private fun fitStart(pos: Float, delta: Float, lo: Float, hi: Float): Float {
        val min = lo - minOf(0f, delta)
        val max = hi - maxOf(0f, delta)
        return if (min <= max) pos.coerceIn(min, max) else (lo + hi - delta) / 2f
    }
}

/**
 * Performs a drag as a chain of continued strokes, so the "finger" stays down while the
 * hand keeps pinching and follows the cursor.
 */
class DragController(
    private val service: AccessibilityService,
    private val main: Handler,
) {
    private var active = false
    private var ending = false
    private var inFlight = false
    private var current: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var generation = 0

    fun start(x: Float, y: Float, holdFirst: Boolean) {
        cancel()
        active = true
        ending = false
        lastX = x
        lastY = y
        targetX = x
        targetY = y
        val path = Path().apply { moveTo(x, y) }
        // After a long press already happened, hold again so launchers start an icon drag.
        val duration = if (holdFirst) ViewConfiguration.getLongPressTimeout() + 150L else 40L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration, true)
        send(stroke)
    }

    fun moveTo(x: Float, y: Float) {
        if (!active) return
        targetX = x
        targetY = y
    }

    fun end(x: Float, y: Float) {
        if (!active) return
        targetX = x
        targetY = y
        ending = true
        if (!inFlight) next()
    }

    fun cancel() {
        generation++
        active = false
        ending = false
        inFlight = false
        current = null
    }

    private fun next() {
        val prev = current ?: return
        if (!active) return
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(targetX, targetY)
        }
        val stroke = try {
            prev.continueStroke(path, 0, 40L, !ending)
        } catch (e: Exception) {
            Log.w("AirCursorDrag", "continueStroke failed", e)
            cancel()
            return
        }
        lastX = targetX
        lastY = targetY
        send(stroke)
    }

    private fun send(stroke: GestureDescription.StrokeDescription) {
        val gen = generation
        current = stroke
        inFlight = true
        val finalStroke = ending
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    inFlight = false
                    if (finalStroke) {
                        active = false
                        current = null
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
