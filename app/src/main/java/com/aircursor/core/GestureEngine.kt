package com.aircursor.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** All tunables for cursor + gesture logic. Pure data, so it can be unit tested. */
data class GestureConfig(
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    // Cursor mapping and smoothing
    val margin: Float = 0.15f,
    val sensitivity: Float = 1f,
    val mirrorX: Boolean = true,
    val minCutoff: Double = 1.0,
    val beta: Double = 0.01,
    val deadZonePx: Float = 4f,
    // Pinch
    val pinchEnter: Float = 0.28f,
    val pinchExitFactor: Float = 1.5f,
    val pinchFreezeMs: Long = 120,
    val pinchLookbackMs: Long = 60,
    val longPressMs: Long = 500,
    /** Movement (fraction of the shorter screen side) needed to turn a pinch into a drag. */
    val dragStartFraction: Float = 0.035f,
    val tapCooldownMs: Long = 150,
    // Scroll (two fingers, works like PC middle-click auto scroll)
    val scrollDeadZone: Float = 0.035f,
    val scrollIntervalMs: Long = 260,
    val scrollSpeed: Float = 1.2f,
    val reverseScroll: Boolean = false,
    // Open palm
    val palmSwipeDistance: Float = 0.16f,
    val palmSwipeWindowMs: Long = 350,
    val palmHoldMs: Long = 1000,
    val palmStillTolerance: Float = 0.04f,
    // Fist
    val fistHoldMs: Long = 1500,
    // General
    val gestureCooldownMs: Long = 800,
    val poseDebounceMs: Long = 90,
    // Enable / disable each gesture
    val enableTap: Boolean = true,
    val enableLongPress: Boolean = true,
    val enableDrag: Boolean = true,
    val enableScroll: Boolean = true,
    val enableBack: Boolean = true,
    val enableHome: Boolean = true,
    val enableNotifications: Boolean = true,
    val enableRecents: Boolean = true,
    val enableFistPause: Boolean = true,
)

sealed class GestureEvent {
    data class Tap(val x: Float, val y: Float) : GestureEvent()
    data class LongPress(val x: Float, val y: Float) : GestureEvent()
    /** [holdFirst] = a long press already happened, so the drag should start with a hold (icon moving). */
    data class DragStart(val x: Float, val y: Float, val holdFirst: Boolean) : GestureEvent()
    data class DragMove(val x: Float, val y: Float) : GestureEvent()
    data class DragEnd(val x: Float, val y: Float) : GestureEvent()
    /** A swipe starting near (x, y) with vector (dx, dy) in pixels. */
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : GestureEvent()
    data object Back : GestureEvent()
    data object Home : GestureEvent()
    data object Notifications : GestureEvent()
    data object Recents : GestureEvent()
    data class PauseChanged(val paused: Boolean) : GestureEvent()
    data object Recalibrated : GestureEvent()
}

enum class CursorMode { NORMAL, PRESSING, DRAGGING, SCROLLING, PALM, PAUSED }

enum class HoldKind { FIST, PALM }

class HandFrame(
    val timestampMs: Long,
    val landmarks: List<Landmark>?,
    /** imageWidth / imageHeight of the (upright) frame. */
    val aspect: Float = 1f,
)

data class EngineOutput(
    val timestampMs: Long,
    val handPresent: Boolean,
    val x: Float,
    val y: Float,
    val mode: CursorMode,
    val holdKind: HoldKind?,
    val holdProgress: Float,
    val pose: HandPose?,
    val pinchRatio: Float,
    val paused: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    val events: List<GestureEvent>,
)

/**
 * Turns a stream of hand landmarks into a smoothed cursor position and discrete gestures.
 * Not thread safe: call from one thread (or synchronize externally).
 */
class GestureEngine(config: GestureConfig = GestureConfig()) {

    var config: GestureConfig = config
        set(value) {
            field = value
            applyConfig()
        }

    var paused = false
        private set

    private var mapper = buildMapper()
    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()
    private val pinch = PinchDetector()
    private val poses = PoseDebouncer()

    private var offsetX = 0f
    private var offsetY = 0f
    private var recalibrateRequested = false
    private val pendingEvents = mutableListOf<GestureEvent>()

    // Cursor
    private var cursorX = config.screenWidth / 2f
    private var cursorY = config.screenHeight / 2f
    private var filteredX = cursorX
    private var filteredY = cursorY
    private val history = ArrayDeque<TimedPoint>()

    // Pinch
    private enum class PinchPhase { IDLE, PRESSED, LONG_PRESSED, DRAGGING, IGNORED }
    private var phase = PinchPhase.IDLE
    private var pinchStartMs = 0L
    private var pinchX = 0f
    private var pinchY = 0f
    private var freezeUntilMs = Long.MIN_VALUE
    private var pinchBlockedUntilMs = Long.MIN_VALUE

    // Scroll
    private var scrollActive = false
    private var scrollAnchor = Point2(0f, 0f)
    private var lastScrollMs = 0L

    // Palm
    private val palmHistory = ArrayDeque<TimedPoint>()
    private var palmHoldAnchor: Point2? = null
    private var palmHoldStartMs = 0L
    private var palmConsumed = false

    // Fist
    private var fistStartMs = -1L
    private var fistConsumed = false

    private var cooldownUntilMs = Long.MIN_VALUE
    private var lastPose: HandPose? = null

    // Per-frame outputs
    private var holdKind: HoldKind? = null
    private var holdProgress = 0f

    private data class TimedPoint(val t: Long, val x: Float, val y: Float)

    init {
        applyConfig()
    }

    private fun buildMapper() = CoordinateMapper(
        screenWidth = config.screenWidth,
        screenHeight = config.screenHeight,
        margin = config.margin,
        sensitivity = config.sensitivity,
        mirrorX = config.mirrorX,
        offsetX = offsetX,
        offsetY = offsetY,
    )

    private fun applyConfig() {
        offsetX = CoordinateMapper.clampOffset(offsetX, config.margin)
        offsetY = CoordinateMapper.clampOffset(offsetY, config.margin)
        mapper = buildMapper()
        filterX.minCutoff = config.minCutoff
        filterX.beta = config.beta
        filterY.minCutoff = config.minCutoff
        filterY.beta = config.beta
        pinch.setThresholds(config.pinchEnter, config.pinchEnter * max(config.pinchExitFactor, 1.05f))
        poses.holdMs = config.poseDebounceMs
        cursorX = cursorX.coerceIn(0f, config.screenWidth - 1f)
        cursorY = cursorY.coerceIn(0f, config.screenHeight - 1f)
    }

    /** Pause/resume from outside (notification, app). Ends any running drag. */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        if (phase == PinchPhase.DRAGGING) pendingEvents += GestureEvent.DragEnd(cursorX, cursorY)
        phase = PinchPhase.IDLE
        pinch.reset()
        paused = value
    }

    /** The next frame with a hand re-centres the active box on the index fingertip. */
    fun requestRecalibrate() {
        recalibrateRequested = true
    }

    fun resetCalibration() {
        offsetX = 0f
        offsetY = 0f
        mapper = buildMapper()
    }

    fun process(frame: HandFrame): EngineOutput {
        val t = frame.timestampMs
        val events = ArrayList<GestureEvent>(pendingEvents)
        pendingEvents.clear()
        holdKind = null
        holdProgress = 0f

        val lm = frame.landmarks
        if (lm == null || lm.size < HandIdx.COUNT) {
            onHandLost(events)
            return output(t, false, null, Float.NaN, events)
        }
        val g = HandGeometry(lm, frame.aspect)
        val tip = lm[HandIdx.INDEX_TIP]

        if (recalibrateRequested) {
            recalibrateRequested = false
            offsetX = CoordinateMapper.clampOffset(mapper.userX(tip.x) - 0.5f, config.margin)
            offsetY = CoordinateMapper.clampOffset(tip.y - 0.5f, config.margin)
            mapper = buildMapper()
            filterX.reset()
            filterY.reset()
            events += GestureEvent.Recalibrated
        }

        // Smoothed target position (always updated, even while the output is frozen).
        val target = mapper.map(tip.x, tip.y)
        filteredX = filterX.filter(target.x.toDouble(), t).toFloat()
        filteredY = filterY.filter(target.y.toDouble(), t).toFloat()

        val pose = poses.update(PoseClassifier.classify(g), t)
        if (pose != lastPose) onPoseChanged()
        lastPose = pose

        val ratio = g.pinchRatio
        if (paused) {
            if (pose == HandPose.FIST) handleFist(t, events)
            return output(t, true, pose, ratio, events)
        }

        val wasPinched = pinch.isPinched
        // A fist also brings thumb and index together: only allow a new pinch with the index out.
        val pinched = pinch.update(if (wasPinched || g.indexAwayFromPalm) ratio else Float.POSITIVE_INFINITY)
        when {
            pinched && !wasPinched -> onPinchStart(t)
            !pinched && wasPinched -> onPinchEnd(t, events)
            pinched -> onPinchHold(t, events)
        }

        if (phase == PinchPhase.IDLE) {
            when (pose) {
                HandPose.TWO_FINGERS ->
                    if (config.enableScroll) handleScroll(lm, t, events) else follow(t)
                HandPose.OPEN_PALM -> handlePalm(lm, t, events)
                HandPose.FIST -> handleFist(t, events)
                else -> follow(t)
            }
        }

        recordHistory(t)
        return output(t, true, pose, ratio, events)
    }

    // ---- Cursor ----

    private fun follow(t: Long) {
        if (t < freezeUntilMs) return
        if (hypot(filteredX - cursorX, filteredY - cursorY) >= config.deadZonePx) {
            cursorX = filteredX
            cursorY = filteredY
        }
    }

    private fun recordHistory(t: Long) {
        history.addLast(TimedPoint(t, cursorX, cursorY))
        while (history.size > 1 && t - history.first().t > 400) history.removeFirst()
    }

    private fun positionAt(time: Long): Point2 {
        for (i in history.indices.reversed()) {
            val p = history[i]
            if (p.t <= time) return Point2(p.x, p.y)
        }
        return history.firstOrNull()?.let { Point2(it.x, it.y) } ?: Point2(cursorX, cursorY)
    }

    // ---- Pinch: tap / long press / drag ----

    private fun onPinchStart(t: Long) {
        if (t < pinchBlockedUntilMs || !(config.enableTap || config.enableLongPress || config.enableDrag)) {
            phase = PinchPhase.IGNORED
            return
        }
        // Use where the cursor was just before the fingers closed: closing the pinch moves the tip.
        val p = positionAt(t - config.pinchLookbackMs)
        pinchX = p.x
        pinchY = p.y
        cursorX = pinchX
        cursorY = pinchY
        pinchStartMs = t
        freezeUntilMs = t + config.pinchFreezeMs
        phase = PinchPhase.PRESSED
    }

    private fun onPinchHold(t: Long, events: MutableList<GestureEvent>) {
        val moved = hypot(filteredX - pinchX, filteredY - pinchY)
        val dragThreshold = config.dragStartFraction * min(config.screenWidth, config.screenHeight)
        when (phase) {
            PinchPhase.PRESSED -> when {
                config.enableDrag && t >= freezeUntilMs && moved > dragThreshold -> startDrag(false, events)
                config.enableLongPress && t - pinchStartMs >= config.longPressMs -> {
                    events += GestureEvent.LongPress(pinchX, pinchY)
                    phase = PinchPhase.LONG_PRESSED
                }
            }
            PinchPhase.LONG_PRESSED ->
                if (config.enableDrag && moved > dragThreshold) startDrag(true, events)
            PinchPhase.DRAGGING -> {
                cursorX = filteredX
                cursorY = filteredY
                events += GestureEvent.DragMove(cursorX, cursorY)
            }
            else -> Unit
        }
    }

    private fun startDrag(holdFirst: Boolean, events: MutableList<GestureEvent>) {
        events += GestureEvent.DragStart(pinchX, pinchY, holdFirst)
        cursorX = filteredX
        cursorY = filteredY
        events += GestureEvent.DragMove(cursorX, cursorY)
        phase = PinchPhase.DRAGGING
    }

    private fun onPinchEnd(t: Long, events: MutableList<GestureEvent>) {
        when (phase) {
            PinchPhase.PRESSED -> if (config.enableTap) events += GestureEvent.Tap(pinchX, pinchY)
            PinchPhase.DRAGGING -> events += GestureEvent.DragEnd(cursorX, cursorY)
            else -> Unit
        }
        if (phase != PinchPhase.IGNORED) {
            pinchBlockedUntilMs = t + config.tapCooldownMs
            freezeUntilMs = t + config.pinchFreezeMs
        }
        phase = PinchPhase.IDLE
    }

    // ---- Two fingers: scroll ----

    private fun handleScroll(lm: List<Landmark>, t: Long, events: MutableList<GestureEvent>) {
        val a = lm[HandIdx.INDEX_TIP]
        val b = lm[HandIdx.MIDDLE_TIP]
        val p = Point2(mapper.userX((a.x + b.x) / 2f), (a.y + b.y) / 2f)
        if (!scrollActive) {
            scrollActive = true
            scrollAnchor = p
            lastScrollMs = t - config.scrollIntervalMs
            return
        }
        if (t - lastScrollMs < config.scrollIntervalMs) return
        val dx = p.x - scrollAnchor.x
        val dy = p.y - scrollAnchor.y
        val vertical = abs(dy) >= abs(dx)
        val amount = (if (vertical) abs(dy) else abs(dx)) - config.scrollDeadZone
        if (amount <= 0f) return

        // Hand down = scroll down = finger swipes up (like PC auto scroll). Reverse flips it.
        val sign = if (config.reverseScroll) 1f else -1f
        val w = config.screenWidth.toFloat()
        val h = config.screenHeight.toFloat()
        val swipe = if (vertical) {
            val len = min(amount * mapper.pixelsPerUnitY() * config.scrollSpeed, h * 0.6f)
            Point2(0f, sign * len * (if (dy > 0) 1f else -1f))
        } else {
            val len = min(amount * mapper.pixelsPerUnitX() * config.scrollSpeed, w * 0.6f)
            Point2(sign * len * (if (dx > 0) 1f else -1f), 0f)
        }
        events += GestureEvent.Scroll(cursorX, cursorY, swipe.x, swipe.y)
        lastScrollMs = t
    }

    // ---- Open palm: back / notifications / recents / home ----

    private fun handlePalm(lm: List<Landmark>, t: Long, events: MutableList<GestureEvent>) {
        val c = lm[HandIdx.MIDDLE_MCP]
        val p = Point2(mapper.userX(c.x), c.y)
        palmHistory.addLast(TimedPoint(t, p.x, p.y))
        while (palmHistory.size > 1 && t - palmHistory.first().t > config.palmSwipeWindowMs) {
            palmHistory.removeFirst()
        }
        if (t < cooldownUntilMs) {
            palmHoldAnchor = null
            return
        }

        val oldest = palmHistory.first()
        val dx = p.x - oldest.x
        val dy = p.y - oldest.y
        val dist = config.palmSwipeDistance
        val swipe: GestureEvent? = when {
            config.enableBack && dx <= -dist && abs(dy) < abs(dx) * 0.7f -> GestureEvent.Back
            config.enableNotifications && dy >= dist && abs(dx) < abs(dy) * 0.7f -> GestureEvent.Notifications
            config.enableRecents && dy <= -dist && abs(dx) < abs(dy) * 0.7f -> GestureEvent.Recents
            else -> null
        }
        if (swipe != null) {
            events += swipe
            cooldownUntilMs = t + config.gestureCooldownMs
            palmHistory.clear()
            palmHoldAnchor = null
            palmConsumed = true
            return
        }

        if (!config.enableHome || palmConsumed) return
        val anchor = palmHoldAnchor
        if (anchor == null || hypot(p.x - anchor.x, p.y - anchor.y) > config.palmStillTolerance) {
            palmHoldAnchor = p
            palmHoldStartMs = t
            return
        }
        val progress = (t - palmHoldStartMs).toFloat() / config.palmHoldMs
        if (progress >= 1f) {
            events += GestureEvent.Home
            cooldownUntilMs = t + config.gestureCooldownMs
            palmConsumed = true
            palmHoldAnchor = null
        } else {
            holdKind = HoldKind.PALM
            holdProgress = progress
        }
    }

    // ---- Fist: pause / resume ----

    private fun handleFist(t: Long, events: MutableList<GestureEvent>) {
        if (!config.enableFistPause || fistConsumed) return
        if (fistStartMs < 0) fistStartMs = t
        val progress = (t - fistStartMs).toFloat() / config.fistHoldMs
        if (progress >= 1f) {
            paused = !paused
            events += GestureEvent.PauseChanged(paused)
            fistConsumed = true
            cooldownUntilMs = t + config.gestureCooldownMs
        } else {
            holdKind = HoldKind.FIST
            holdProgress = progress
        }
    }

    // ---- State resets ----

    private fun onPoseChanged() {
        scrollActive = false
        palmHistory.clear()
        palmHoldAnchor = null
        palmConsumed = false
        fistStartMs = -1L
        fistConsumed = false
    }

    private fun onHandLost(events: MutableList<GestureEvent>) {
        if (phase == PinchPhase.DRAGGING) events += GestureEvent.DragEnd(cursorX, cursorY)
        phase = PinchPhase.IDLE
        pinch.reset()
        poses.reset()
        filterX.reset()
        filterY.reset()
        history.clear()
        onPoseChanged()
        lastPose = null
    }

    private fun output(t: Long, present: Boolean, pose: HandPose?, ratio: Float, events: List<GestureEvent>): EngineOutput {
        val mode = when {
            paused -> CursorMode.PAUSED
            phase == PinchPhase.PRESSED || phase == PinchPhase.LONG_PRESSED -> CursorMode.PRESSING
            phase == PinchPhase.DRAGGING -> CursorMode.DRAGGING
            scrollActive -> CursorMode.SCROLLING
            pose == HandPose.OPEN_PALM -> CursorMode.PALM
            else -> CursorMode.NORMAL
        }
        return EngineOutput(
            timestampMs = t,
            handPresent = present,
            x = cursorX,
            y = cursorY,
            mode = mode,
            holdKind = holdKind,
            holdProgress = holdProgress,
            pose = pose,
            pinchRatio = ratio,
            paused = paused,
            offsetX = offsetX,
            offsetY = offsetY,
            events = events,
        )
    }
}
