package com.aircursor.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aircursor.core.EngineOutput
import com.aircursor.core.GestureEvent
import com.aircursor.data.AppSettings
import com.aircursor.data.Prefs
import com.aircursor.core.CursorMode
import com.aircursor.core.KeyMouse
import com.aircursor.pc.PcMode
import com.aircursor.pc.TaskbarOverlay

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
    private lateinit var scroller: ScrollController
    private lateinit var haptics: Haptics
    private var clickSound = false
    private var tone: ToneGenerator? = null
    private var showFps = false
    private lateinit var taskbar: TaskbarOverlay
    private val pcListener: () -> Unit = { updateTaskbar() }

    // Keyboard cursor position (also follows the hand cursor).
    private var cursorX = -1f
    private var cursorY = -1f
    private var metaAlone = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        drag = DragController(this, main)
        scroller = ScrollController(this, main) { screenW() to screenH() }
        haptics = Haptics(this)
        taskbar = TaskbarOverlay(this)
        PcMode.addListener(pcListener)
        windowManager = getSystemService(WindowManager::class.java)
        TrackingBus.update { it.copy(accessibilityConnected = true) }
        if (HandTrackingService.isRunning) onTrackingStarted()
        updateTaskbar()
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
        scroller.cancel()
        PcMode.removeListener(pcListener)
        if (::taskbar.isInitialized) taskbar.hide()
        removeOverlay()
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        tone?.release()
        tone = null
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
        cursorView?.prediction = prefs.get(AppSettings.PREDICTION)
        haptics.enabled = prefs.get(AppSettings.HAPTICS)
        clickSound = prefs.get(AppSettings.CLICK_SOUND)
        updateTaskbar()
        if (!showFps) cursorView?.debugText = null
    }

    fun onTrackingStarted() {
        addOverlay()
    }

    fun onTrackingStopped() {
        drag.cancel()
        scroller.cancel()
        // In PC mode the cursor stays for the keyboard; otherwise it goes away with the camera.
        if (!PcMode.isEnabled(this)) removeOverlay()
    }

    // ---- PC mode: taskbar over all apps ----

    private fun updateTaskbar() {
        if (!::taskbar.isInitialized) return
        val want = PcMode.isEnabled(this) && !PcMode.desktopVisible
        if (want && !taskbar.isShown) {
            taskbar.show()
            // Keep the cursor drawn above the taskbar.
            if (cursorView != null) {
                removeOverlay()
                addOverlay()
            }
        } else if (!want && taskbar.isShown) {
            taskbar.hide()
        }
    }

    // ---- PC mode: keyboard shortcuts and keyboard cursor ----

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::prefs.isInitialized || !PcMode.isEnabled(this)) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val isMeta = code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT

        // The PC desktop handles its own Win key / Alt+Tab (for its windows).
        if (!PcMode.desktopVisible) {
            if (isMeta) {
                if (down) {
                    metaAlone = true
                } else if (metaAlone) {
                    PcMode.openDesktop(this, startMenu = true)
                }
                return true
            }
            if (down && event.isMetaPressed) {
                metaAlone = false
                val handled = when (code) {
                    KeyEvent.KEYCODE_D -> { PcMode.openDesktop(this); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                        performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    KeyEvent.KEYCODE_N -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    KeyEvent.KEYCODE_L -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    else -> false
                }
                if (handled) return true
            }
            if (down && event.isAltPressed && code == KeyEvent.KEYCODE_TAB) {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return true
            }
        }

        // Keyboard cursor: Alt + arrows move, Alt + Enter clicks.
        if (PcMode.keyboardCursor(this) && event.isAltPressed) {
            val dx = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1f
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1f
                else -> 0f
            }
            val dy = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> -1f
                KeyEvent.KEYCODE_DPAD_DOWN -> 1f
                else -> 0f
            }
            if (dx != 0f || dy != 0f) {
                if (down) moveKeyboardCursor(dx, dy, event.repeatCount)
                return true
            }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (down && event.repeatCount == 0) keyboardClick()
                return true
            }
        }
        return false
    }

    private fun moveKeyboardCursor(dx: Float, dy: Float, repeat: Int) {
        if (cursorView == null) addOverlay()
        val view = cursorView ?: return
        if (cursorX < 0f) {
            cursorX = screenW() / 2f
            cursorY = screenH() / 2f
        }
        val step = KeyMouse.step(repeat, resources.displayMetrics.density)
        cursorX = (cursorX + dx * step).coerceIn(0f, screenW() - 1f)
        cursorY = (cursorY + dy * step).coerceIn(0f, screenH() - 1f)
        view.update(cursorX, cursorY, android.os.SystemClock.uptimeMillis(), CursorMode.NORMAL, true, null, 0f)
        HandTrackingService.warpCursor(cursorX, cursorY)
    }

    private fun keyboardClick() {
        if (cursorX < 0f) return
        cursorView?.ripple(cursorX, cursorY)
        haptics.click()
        tap(cursorX, cursorY)
    }


    fun showLabel(text: String) {
        cursorView?.showLabel(text)
    }

    // ---- Engine output ----

    fun onEngineOutput(out: EngineOutput, stats: TrackingStats) {
        val view = cursorView ?: return
        if (out.handPresent) {
            cursorX = out.x
            cursorY = out.y
        }
        view.update(out.x, out.y, out.timestampMs, out.mode, out.handPresent, out.holdKind, out.holdProgress)
        if (showFps) {
            view.debugText = ("Tracking: %.1f fps\nCamera: %.0f fps (%s)\nInference: %.0f ms (%s)\n" +
                "Hand: %s  Pose: %s\nPinch: %.2f").format(
                stats.trackingFps,
                stats.cameraFps,
                stats.resolution,
                stats.inferenceMs,
                stats.delegate,
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
                haptics.click()
                if (clickSound) clickTone()
                tap(e.x, e.y)
            }
            is GestureEvent.LongPress -> {
                view.ripple(e.x, e.y, 0xFFFFC107.toInt())
                view.showLabel("Long press")
                haptics.heavy()
                longPress(e.x, e.y)
            }
            is GestureEvent.DragStart -> {
                view.showLabel("Drag")
                haptics.tick()
                scroller.cancel()
                drag.start(clampX(e.x), clampY(e.y), e.holdFirst)
            }
            is GestureEvent.DragMove -> drag.moveTo(clampX(e.x), clampY(e.y))
            is GestureEvent.DragEnd -> drag.end(clampX(e.x), clampY(e.y))
            is GestureEvent.ScrollVelocity -> scroller.update(e.x, e.y, e.vx, e.vy)
            GestureEvent.ScrollEnd -> scroller.stop()
            GestureEvent.Back -> global(GLOBAL_ACTION_BACK, "Back", view)
            GestureEvent.Home -> global(GLOBAL_ACTION_HOME, "Home", view)
            GestureEvent.Notifications -> global(GLOBAL_ACTION_NOTIFICATIONS, "Notifications", view)
            GestureEvent.Recents -> global(GLOBAL_ACTION_RECENTS, "Recents", view)
            is GestureEvent.PauseChanged -> {
                haptics.heavy()
                scroller.cancel()
                view.showLabel(if (e.paused) "Paused" else "Resumed")
            }
            GestureEvent.Recalibrated -> view.showLabel("Recalibrated")
            is GestureEvent.AutoTuned -> {
                haptics.tick()
                view.showLabel("Auto-tuned: anti-shake %.0f px".format(e.deadZonePx))
            }
        }
    }

    private fun clickTone() {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 70).also { tone = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
        } catch (_: Exception) {
            tone = null
        }
    }

    private fun global(action: Int, label: String, view: CursorView) {
        drag.cancel()
        scroller.cancel()
        haptics.heavy()
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
        scroller.cancel()
        val p = Path().apply { moveTo(clampX(x), clampY(y)) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 60))
    }

    fun longPress(x: Float, y: Float) {
        drag.cancel()
        scroller.cancel()
        val p = Path().apply { moveTo(clampX(x), clampY(y)) }
        dispatch(GestureDescription.StrokeDescription(p, 0, ViewConfiguration.getLongPressTimeout() + 300L))
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
