package com.aircursor.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class PointerMode {
    /** Like a PC mouse: hand movement moves the cursor, scaled by hand size (works at any distance). */
    RELATIVE,

    /** The camera "active box" is mapped 1:1 onto the screen. */
    ABSOLUTE,
}

/** All tunables for cursor + gesture logic. Pure data, so it can be unit tested. */
data class GestureConfig(
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val pointerMode: PointerMode = PointerMode.RELATIVE,
    /** Simple mode: the cursor always follows the hand; pinch = click, pinch + move = drag. No other gestures. */
    val clickOnly: Boolean = false,
    // Cursor mapping and smoothing
    val margin: Float = 0.15f,
    val sensitivity: Float = 1f,
    val mirrorX: Boolean = true,
    val minCutoff: Double = 0.8,
    val beta: Double = 0.01,
    /** Sticky dead zone: the cursor ignores tremor smaller than this (pixels). */
    val deadZonePx: Float = 6f,
    /** Relative mode: cursor pixels per one hand-size of movement, as a multiple of the shorter screen side. */
    val relativeScale: Float = 1.8f,
    /** Relative mode pointer acceleration: gain at slow and at fast hand speed (like a PC mouse). */
    val accelMin: Float = 0.65f,
    val accelMax: Float = 1.9f,
    val accelSlowPxPerSec: Float = 150f,
    val accelFastPxPerSec: Float = 1600f,
    /** Short tracking drop-outs (motion blur) are bridged instead of resetting everything. */
    val handLostGraceMs: Long = 160,
    // Pinch
    val pinchEnter: Float = 0.28f,
    val pinchExitFactor: Float = 1.5f,
    val pinchFreezeMs: Long = 120,
    val pinchLookbackMs: Long = 100,
    val longPressMs: Long = 500,
    /** Movement (fraction of the shorter screen side) needed to turn a pinch into a drag. */
    val dragStartFraction: Float = 0.035f,
    val tapCooldownMs: Long = 150,
    /** Absolute mode: after a frozen phase the cursor glides back to the finger instead of jumping. */
    val clutchTauMs: Float = 120f,
    // Scroll (two fingers, like PC middle-click auto scroll). Distances are in hand sizes.
    val scrollEnterMs: Long = 150,
    val scrollExitMs: Long = 220,
    val scrollDeadZone: Float = 0.25f,
    val scrollSpeed: Float = 1.2f,
    /** Scroll px/s per hand-size of offset, as a multiple of the shorter screen side. */
    val scrollGain: Float = 2.2f,
    val scrollMaxSpeed: Float = 4500f,
    val reverseScroll: Boolean = false,
    // Open palm (distances in hand sizes)
    val palmSwipeDistance: Float = 1.0f,
    val palmSwipeWindowMs: Long = 350,
    val palmHoldMs: Long = 1000,
    val palmStillTolerance: Float = 0.25f,
    // Fist
    val fistHoldMs: Long = 2000,
    /** The fist must stay this still (hand sizes) while held, so a moving hand never pauses. */
    val fistStillTolerance: Float = 0.3f,
    /** A pinch (and its release) must be seen this many frames in a row – no accidental clicks. */
    val pinchConfirmFrames: Int = 2,
    /** While dragging, the fingers may open this much wider before the drag ends (sticky drag). */
    val dragExitFactor: Float = 1.9f,
    /** No palm / fist gestures this long after a click, drag or scroll (stops "drag -> pause"). */
    val gestureGuardMs: Long = 800,
    /** Dwell click: keep the cursor still to click, no pinch needed (accessibility). */
    val enableDwell: Boolean = false,
    val dwellMs: Long = 1200,
    val dwellRadiusPx: Float = 18f,
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
    /**
     * Continuous scroll: a virtual finger placed near (x, y) should move with velocity
     * (vx, vy) in px/s. Sent every frame while scrolling (0, 0 = hold still).
     */
    data class ScrollVelocity(val x: Float, val y: Float, val vx: Float, val vy: Float) : GestureEvent()
    data object ScrollEnd : GestureEvent()
    data object Back : GestureEvent()
    data object Home : GestureEvent()
    data object Notifications : GestureEvent()
    data object Recents : GestureEvent()
    data class PauseChanged(val paused: Boolean) : GestureEvent()
    data object Recalibrated : GestureEvent()
    /** Auto-tune finished: the measured hand tremor gives this anti-shake radius. */
    data class AutoTuned(val deadZonePx: Float) : GestureEvent()
}

enum class CursorMode { NORMAL, PRESSING, DRAGGING, SCROLLING, PALM, PAUSED }

enum class HoldKind { FIST, PALM, DWELL }

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
            val modeChanged = value.pointerMode != field.pointerMode
            field = value
            applyConfig()
            if (modeChanged) resetPointer()
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

    // Cursor (what is shown) and pointer (where the hand points right now; differs while frozen)
    private var cursorX = config.screenWidth / 2f
    private var cursorY = config.screenHeight / 2f
    private var pointerX = cursorX
    private var pointerY = cursorY
    private val history = ArrayDeque<TimedPoint>()

    // Smoothed pointer source. Absolute: screen px. Relative: "virtual px" integrated from hand-size
    // normalized movement, so distance to the camera does not matter.
    private var smoothX = 0f
    private var smoothY = 0f
    private var stickyX = Float.NaN
    private var stickyY = Float.NaN
    private var stickyDx = 0f
    private var stickyDy = 0f
    private var virtX = 0f
    private var virtY = 0f
    private var prevSrc: Point2? = null
    private var prevFrameMs = 0L
    private var handSize = 0f
    private var speedEma = 0f
    private var lastSeenMs = Long.MIN_VALUE
    private var handPresent = false

    // Pinch
    private enum class PinchPhase { IDLE, PRESSED, LONG_PRESSED, DRAGGING, IGNORED }
    private var phase = PinchPhase.IDLE
    private var pinchStartMs = 0L
    private var pinchX = 0f
    private var pinchY = 0f
    private var freezeUntilMs = Long.MIN_VALUE
    private var pinchBlockedUntilMs = Long.MIN_VALUE

    // Clutch (absolute mode): offset between cursor and finger that decays after a frozen phase
    private var frozen = false
    private var clutchX = 0f
    private var clutchY = 0f
    private var clutchStartMs = 0L

    // Scroll
    private var scrollActive = false
    private var twoFingersSinceMs = -1L
    private var scrollAnchor = Point2(0f, 0f)
    private var scrollLeftAtMs = -1L

    // Palm
    private val palmHistory = ArrayDeque<TimedPoint>()
    private var palmHoldAnchor: Point2? = null
    private var palmHoldStartMs = 0L
    private var palmConsumed = false

    // Fist
    private var fistStartMs = -1L
    private var fistConsumed = false

    private var cooldownUntilMs = Long.MIN_VALUE
    private var nowMs = 0L

    // Debounced pinch + gesture guard
    private var stablePinched = false
    private var pinchFrames = 0
    private var lastInteractionEndMs = -100_000L
    private var fistAnchor: Point2? = null

    // Dwell click
    private var dwellAnchorX = 0f
    private var dwellAnchorY = 0f
    private var dwellStartMs = -1L
    private var dwellArmed = true

    // Auto-tune (measures hand tremor)
    private var tuneTarget = 0
    private val tuneSamples = ArrayList<Point2>()
    private var lastPose: HandPose? = null
    private var lastRatio = Float.NaN

    // Per-frame outputs
    private var holdKind: HoldKind? = null
    private var holdProgress = 0f

    private data class TimedPoint(val t: Long, val x: Float, val y: Float)

    private val relative get() = config.pointerMode == PointerMode.RELATIVE

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
        endScroll(pendingEvents)
        phase = PinchPhase.IDLE
        pinch.reset()
        stablePinched = false
        pinchFrames = 0
        paused = value
    }

    /** Measures the tremor of a still hand over [frames] frames and reports [GestureEvent.AutoTuned]. */
    fun startAutoTune(frames: Int = 60) {
        tuneTarget = frames
        tuneSamples.clear()
    }

    /**
     * Absolute mode: the next frame with a hand re-centres the active box on the finger.
     * Relative mode: the cursor jumps to the centre of the screen.
     */
    fun requestRecalibrate() {
        recalibrateRequested = true
    }

    /** Moves the cursor somewhere else (keyboard cursor); the hand continues from there. */
    fun warpCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, config.screenWidth - 1f)
        cursorY = y.coerceIn(0f, config.screenHeight - 1f)
        pointerX = cursorX
        pointerY = cursorY
        frozen = true
    }

    fun resetCalibration() {
        offsetX = 0f
        offsetY = 0f
        mapper = buildMapper()
    }

    fun process(frame: HandFrame): EngineOutput {
        val t = frame.timestampMs
        nowMs = t
        val events = ArrayList<GestureEvent>(pendingEvents)
        pendingEvents.clear()
        holdKind = null
        holdProgress = 0f

        val lm = frame.landmarks
        if (lm == null || lm.size < HandIdx.COUNT) {
            // Bridge short drop-outs (fast moves blur the image): keep every state as it is.
            if (handPresent && t - lastSeenMs <= config.handLostGraceMs) {
                return output(t, true, lastPose, lastRatio, events)
            }
            onHandLost(events)
            return output(t, false, null, Float.NaN, events)
        }
        val g = HandGeometry(lm, frame.aspect)
        val newHand = !handPresent
        handPresent = true
        lastSeenMs = t

        if (recalibrateRequested) {
            recalibrateRequested = false
            if (relative) {
                cursorX = (config.screenWidth - 1) / 2f
                cursorY = (config.screenHeight - 1) / 2f
                pointerX = cursorX
                pointerY = cursorY
            } else {
                val src = absoluteSource(lm)
                offsetX = CoordinateMapper.clampOffset(mapper.userX(src.x) - 0.5f, config.margin)
                offsetY = CoordinateMapper.clampOffset(src.y - 0.5f, config.margin)
                mapper = buildMapper()
            }
            resetPointer()
            events += GestureEvent.Recalibrated
        }

        updatePointerSource(lm, g, frame.aspect, t, newHand)

        val rawPose = PoseClassifier.classify(g)
        val pose = poses.update(rawPose, t)
        if (pose != lastPose) onPoseChanged(pose, lm, g, t)
        lastPose = pose

        val ratio = g.pinchRatio
        lastRatio = ratio
        if (tuneTarget > 0 && phase == PinchPhase.IDLE && isPointing(pose)) {
            tuneSamples += Point2(smoothX, smoothY)
            if (tuneSamples.size >= tuneTarget) {
                val cx = tuneSamples.map { it.x }.average().toFloat()
                val cy = tuneSamples.map { it.y }.average().toFloat()
                val rms = sqrt(tuneSamples.map { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.average()).toFloat()
                events += GestureEvent.AutoTuned((rms * 2.5f).coerceIn(3f, 20f))
                tuneTarget = 0
                tuneSamples.clear()
            }
        }
        if (paused) {
            frozen = true
            syncPointer()
            if (pose == HandPose.FIST && !config.clickOnly) handleFist(lm, g, t, events)
            return output(t, true, pose, ratio, events)
        }

        // Sticky drag: while dragging, the fingers may open wider before the drag ends.
        val exitFactor = if (phase == PinchPhase.DRAGGING) config.dragExitFactor else config.pinchExitFactor
        pinch.setThresholds(config.pinchEnter, config.pinchEnter * max(exitFactor, 1.05f))
        // A fist also brings thumb and index together: only allow a new pinch with the index out.
        val raw = pinch.update(if (pinch.isPinched || g.indexAwayFromPalm) ratio else Float.POSITIVE_INFINITY)
        // Debounce: a change must last a few frames (no clicks from one noisy frame).
        val wasPinched = stablePinched
        if (raw != stablePinched) {
            if (++pinchFrames >= max(1, config.pinchConfirmFrames)) {
                stablePinched = raw
                pinchFrames = 0
            }
        } else {
            pinchFrames = 0
        }
        val pinched = stablePinched
        when {
            pinched && !wasPinched -> onPinchStart(t, events)
            !pinched && wasPinched -> onPinchEnd(t, events)
            pinched -> onPinchHold(t, events)
        }

        if (phase == PinchPhase.IDLE && config.clickOnly) {
            follow(t)
            handleDwell(t, events)
        } else if (phase == PinchPhase.IDLE) {
            val twoFingers = config.enableScroll && pose == HandPose.TWO_FINGERS
            if (twoFingers && (scrollActive || t - twoFingersSinceMs >= config.scrollEnterMs)) {
                scrollLeftAtMs = -1L
                handleScroll(lm, g, t, events)
            } else if (scrollActive) {
                // Two-finger pose lost: keep scroll mode (holding still) for a moment so a
                // flickering middle finger does not end the scroll or move the cursor.
                if (scrollLeftAtMs < 0) scrollLeftAtMs = t
                if (t - scrollLeftAtMs >= config.scrollExitMs) {
                    endScroll(events)
                } else {
                    events += GestureEvent.ScrollVelocity(cursorX, cursorY, 0f, 0f)
                }
            }
            if (scrollActive || twoFingers) {
                frozen = true
                syncPointer()
            } else {
                when (pose) {
                    HandPose.OPEN_PALM -> handlePalm(lm, g, t, events)
                    HandPose.FIST -> handleFist(lm, g, t, events)
                    else -> Unit
                }
                // The raw pose freezes the cursor at once, before the debounced pose switches,
                // so starting a scroll/palm/fist never drags the cursor along.
                if (isPointing(rawPose) && isPointing(pose)) {
                    follow(t)
                    handleDwell(t, events)
                } else {
                    frozen = true
                    syncPointer()
                    dwellStartMs = -1L
                }
            }
        } else if (phase == PinchPhase.IGNORED) {
            frozen = true
            syncPointer()
        }

        recordHistory(t)
        return output(t, true, pose, ratio, events)
    }

    // ---- Pointer source ----

    /** Absolute mode points with the fingertip (blended with the DIP joint to reduce jitter). */
    private fun absoluteSource(lm: List<Landmark>): Point2 {
        val tip = lm[HandIdx.INDEX_TIP]
        val dip = lm[HandIdx.INDEX_DIP]
        return Point2(tip.x * 0.6f + dip.x * 0.4f, tip.y * 0.6f + dip.y * 0.4f)
    }

    /**
     * Relative mode points with the index/middle knuckles: they barely move when you pinch,
     * fold fingers or tremble, so clicks land exactly where the cursor is.
     */
    private fun relativeSource(lm: List<Landmark>, aspect: Float): Point2 {
        val a = lm[HandIdx.INDEX_MCP]
        val b = lm[HandIdx.MIDDLE_MCP]
        val c = lm[HandIdx.INDEX_PIP]
        val x = mapper.userX(a.x * 0.45f + b.x * 0.35f + c.x * 0.2f)
        val y = a.y * 0.45f + b.y * 0.35f + c.y * 0.2f
        return Point2(x * aspect, y)
    }

    private fun updatePointerSource(lm: List<Landmark>, g: HandGeometry, aspect: Float, t: Long, newHand: Boolean) {
        val dtMs = if (newHand) 33L else (t - prevFrameMs).coerceIn(1L, 300L)
        prevFrameMs = t
        val rawX: Float
        val rawY: Float
        if (relative) {
            val size = g.handSize
            handSize = if (newHand || handSize <= 0f) size else handSize * 0.9f + size * 0.1f
            val src = relativeSource(lm, aspect)
            val prev = prevSrc
            if (prev != null && !newHand) {
                val scale = config.relativeScale * min(config.screenWidth, config.screenHeight)
                // Movement measured in hand sizes: same feel near or far from the camera.
                var dx = (src.x - prev.x) / handSize
                var dy = (src.y - prev.y) / handSize
                val len = hypot(dx, dy)
                if (len > 0.6f) { // detection glitch: ignore impossible jumps
                    dx = 0f
                    dy = 0f
                }
                virtX += dx * scale
                virtY += dy * scale
            }
            prevSrc = src
            rawX = virtX
            rawY = virtY
        } else {
            val src = absoluteSource(lm)
            val target = mapper.map(src.x, src.y)
            rawX = target.x
            rawY = target.y
        }
        if (newHand) {
            filterX.reset()
            filterY.reset()
            stickyX = Float.NaN
        }
        smoothX = filterX.filter(rawX.toDouble(), t).toFloat()
        smoothY = filterY.filter(rawY.toDouble(), t).toFloat()

        // Sticky dead zone: the anchor only moves when the point leaves a small circle around it,
        // so tremor is ignored completely but real movement passes through 1:1.
        if (stickyX.isNaN()) {
            stickyX = smoothX
            stickyY = smoothY
            stickyDx = 0f
            stickyDy = 0f
        } else {
            val ox = smoothX - stickyX
            val oy = smoothY - stickyY
            val d = sqrt(ox * ox + oy * oy)
            val r = config.deadZonePx
            if (d > r) {
                val k = (d - r) / d
                stickyDx = ox * k
                stickyDy = oy * k
                stickyX += stickyDx
                stickyY += stickyDy
            } else {
                stickyDx = 0f
                stickyDy = 0f
            }
        }

        if (relative) {
            // Mouse-like acceleration: slow = precise, fast = covers the screen quickly.
            val instSpeed = hypot(stickyDx, stickyDy) / (dtMs / 1000f)
            // Smoothed speed: the gain must not jump from frame to frame (that looks like shaking).
            speedEma = if (newHand) instSpeed else speedEma * 0.6f + instSpeed * 0.4f
            val speed = speedEma
            val s = ((speed - config.accelSlowPxPerSec) /
                (config.accelFastPxPerSec - config.accelSlowPxPerSec)).coerceIn(0f, 1f)
            val smooth = s * s * (3f - 2f * s)
            val gain = (config.accelMin + (config.accelMax - config.accelMin) * smooth) * config.sensitivity
            if (phase != PinchPhase.IGNORED) {
                pointerX = (pointerX + stickyDx * gain).coerceIn(0f, config.screenWidth - 1f)
                pointerY = (pointerY + stickyDy * gain).coerceIn(0f, config.screenHeight - 1f)
            }
        } else {
            pointerX = stickyX
            pointerY = stickyY
        }
    }

    /** Relative mode: while the cursor is frozen the pointer must not wander off. */
    private fun syncPointer() {
        if (relative && phase == PinchPhase.IDLE || relative && phase == PinchPhase.IGNORED) {
            pointerX = cursorX
            pointerY = cursorY
        }
    }

    private fun resetPointer() {
        filterX.reset()
        filterY.reset()
        stickyX = Float.NaN
        prevSrc = null
        virtX = 0f
        virtY = 0f
        pointerX = cursorX
        pointerY = cursorY
        resetClutch()
    }

    // ---- Cursor ----

    private fun isPointing(p: HandPose?) =
        p == null || p == HandPose.POINT || p == HandPose.OTHER ||
            (p == HandPose.TWO_FINGERS && !config.enableScroll)

    private fun follow(t: Long) {
        if (t < freezeUntilMs) {
            frozen = true
            syncPointer()
            return
        }
        if (relative) {
            frozen = false
            cursorX = pointerX
            cursorY = pointerY
            return
        }
        if (frozen) {
            frozen = false
            clutchX = cursorX - pointerX
            clutchY = cursorY - pointerY
            clutchStartMs = t
        }
        var tx = pointerX
        var ty = pointerY
        if (clutchX != 0f || clutchY != 0f) {
            val k = exp(-(t - clutchStartMs) / config.clutchTauMs.coerceAtLeast(1f))
            if (k < 0.01f) {
                resetClutch()
            } else {
                tx += clutchX * k
                ty += clutchY * k
            }
        }
        cursorX = tx
        cursorY = ty
    }

    private fun resetClutch() {
        frozen = false
        clutchX = 0f
        clutchY = 0f
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

    private fun onPinchStart(t: Long, events: MutableList<GestureEvent>) {
        endScroll(events)
        val anyPinchGesture = config.clickOnly || config.enableTap || config.enableLongPress || config.enableDrag
        if (t < pinchBlockedUntilMs || !anyPinchGesture) {
            phase = PinchPhase.IGNORED
            return
        }
        // Use where the cursor was just before the fingers closed: closing the pinch moves the hand.
        val p = positionAt(t - config.pinchLookbackMs)
        pinchX = p.x
        pinchY = p.y
        cursorX = pinchX
        cursorY = pinchY
        pointerX = pinchX
        pointerY = pinchY
        pinchStartMs = t
        freezeUntilMs = t + config.pinchFreezeMs
        phase = PinchPhase.PRESSED
    }

    private fun onPinchHold(t: Long, events: MutableList<GestureEvent>) {
        val moved = hypot(pointerX - pinchX, pointerY - pinchY)
        val dragThreshold = config.dragStartFraction * min(config.screenWidth, config.screenHeight)
        when (phase) {
            PinchPhase.PRESSED -> when {
                config.enableDrag && t >= freezeUntilMs && moved > dragThreshold -> startDrag(false, events)
                config.clickOnly -> Unit
                config.enableLongPress && t - pinchStartMs >= config.longPressMs -> {
                    events += GestureEvent.LongPress(pinchX, pinchY)
                    phase = PinchPhase.LONG_PRESSED
                }
            }
            PinchPhase.LONG_PRESSED ->
                if (config.enableDrag && moved > dragThreshold) startDrag(true, events)
            PinchPhase.DRAGGING -> {
                cursorX = pointerX
                cursorY = pointerY
                events += GestureEvent.DragMove(cursorX, cursorY)
            }
            else -> Unit
        }
        // The press freezes the drawn cursor; the pointer keeps moving underneath.
        if (relative && t < freezeUntilMs && phase == PinchPhase.PRESSED) {
            pointerX = pinchX
            pointerY = pinchY
        }
    }

    private fun startDrag(holdFirst: Boolean, events: MutableList<GestureEvent>) {
        events += GestureEvent.DragStart(pinchX, pinchY, holdFirst)
        cursorX = pointerX
        cursorY = pointerY
        events += GestureEvent.DragMove(cursorX, cursorY)
        phase = PinchPhase.DRAGGING
    }

    private fun onPinchEnd(t: Long, events: MutableList<GestureEvent>) {
        when (phase) {
            PinchPhase.PRESSED -> if (config.enableTap || config.clickOnly) events += GestureEvent.Tap(pinchX, pinchY)
            PinchPhase.DRAGGING -> events += GestureEvent.DragEnd(cursorX, cursorY)
            else -> Unit
        }
        if (phase != PinchPhase.IGNORED) {
            pinchBlockedUntilMs = t + config.tapCooldownMs
            freezeUntilMs = t + config.pinchFreezeMs
        }
        lastInteractionEndMs = t
        dwellArmed = false
        phase = PinchPhase.IDLE
        syncPointer()
    }

    // ---- Two fingers: scroll ----

    private fun scrollPoint(lm: List<Landmark>, aspect: Float): Point2 {
        val a = lm[HandIdx.INDEX_MCP]
        val b = lm[HandIdx.MIDDLE_MCP]
        return Point2(mapper.userX((a.x + b.x) / 2f) * aspect, (a.y + b.y) / 2f)
    }

    private fun handleScroll(lm: List<Landmark>, g: HandGeometry, t: Long, events: MutableList<GestureEvent>) {
        if (!scrollActive) {
            scrollActive = true
            events += GestureEvent.ScrollVelocity(cursorX, cursorY, 0f, 0f)
            return
        }
        val p = scrollPoint(lm, g.aspect)
        val hs = g.handSize
        val dx = (p.x - scrollAnchor.x) / hs
        val dy = (p.y - scrollAnchor.y) / hs
        val vertical = abs(dy) >= abs(dx)
        val amount = (if (vertical) abs(dy) else abs(dx)) - config.scrollDeadZone
        var vx = 0f
        var vy = 0f
        if (amount > 0f) {
            // Hand down = scroll down = finger moves up (like PC auto scroll). Reverse flips it.
            val sign = if (config.reverseScroll) 1f else -1f
            val unit = min(config.screenWidth, config.screenHeight).toFloat()
            val speed = min(amount * unit * config.scrollGain * config.scrollSpeed, config.scrollMaxSpeed)
            if (vertical) vy = sign * speed * (if (dy > 0) 1f else -1f)
            else vx = sign * speed * (if (dx > 0) 1f else -1f)
        }
        events += GestureEvent.ScrollVelocity(cursorX, cursorY, vx, vy)
    }

    private fun endScroll(events: MutableList<GestureEvent>) {
        if (!scrollActive) return
        scrollActive = false
        scrollLeftAtMs = -1L
        lastInteractionEndMs = nowMs
        events += GestureEvent.ScrollEnd
    }

    // ---- Open palm: back / notifications / recents / home ----

    private fun handlePalm(lm: List<Landmark>, g: HandGeometry, t: Long, events: MutableList<GestureEvent>) {
        val c = lm[HandIdx.MIDDLE_MCP]
        val hs = g.handSize
        // Palm position in hand sizes, so swipes need the same effort at any distance.
        val p = Point2(mapper.userX(c.x) * g.aspect / hs, c.y / hs)
        palmHistory.addLast(TimedPoint(t, p.x, p.y))
        while (palmHistory.size > 1 && t - palmHistory.first().t > config.palmSwipeWindowMs) {
            palmHistory.removeFirst()
        }
        if (t < cooldownUntilMs || t - lastInteractionEndMs < config.gestureGuardMs) {
            palmHoldAnchor = null
            if (t - lastInteractionEndMs < config.gestureGuardMs) palmHistory.clear()
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

    // ---- Dwell click: hold the cursor still to click ----

    private fun handleDwell(t: Long, events: MutableList<GestureEvent>) {
        if (!config.enableDwell) return
        val moved = hypot(cursorX - dwellAnchorX, cursorY - dwellAnchorY)
        if (dwellStartMs < 0 || moved > config.dwellRadiusPx) {
            if (dwellStartMs >= 0 && moved > config.dwellRadiusPx) dwellArmed = true // moved away: ready again
            dwellAnchorX = cursorX
            dwellAnchorY = cursorY
            dwellStartMs = t
            return
        }
        if (!dwellArmed) return
        val progress = (t - dwellStartMs).toFloat() / config.dwellMs
        if (progress >= 1f) {
            events += GestureEvent.Tap(cursorX, cursorY)
            dwellArmed = false
            lastInteractionEndMs = t
        } else if (progress > 0.15f) {
            holdKind = HoldKind.DWELL
            holdProgress = progress
        }
    }

    // ---- Fist: pause / resume ----

    private fun handleFist(lm: List<Landmark>, g: HandGeometry, t: Long, events: MutableList<GestureEvent>) {
        if (!config.enableFistPause || fistConsumed) return
        if (t - lastInteractionEndMs < config.gestureGuardMs) {
            fistStartMs = -1L
            return
        }
        // The fist has to be held still: a hand that moves (e.g. while dragging) never pauses.
        val c = lm[HandIdx.MIDDLE_MCP]
        val p = Point2(mapper.userX(c.x) * g.aspect / g.handSize, c.y / g.handSize)
        val anchor = fistAnchor
        if (fistStartMs < 0 || anchor == null || hypot(p.x - anchor.x, p.y - anchor.y) > config.fistStillTolerance) {
            fistStartMs = t
            fistAnchor = p
        }
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

    private fun onPoseChanged(pose: HandPose?, lm: List<Landmark>, g: HandGeometry, t: Long) {
        palmHistory.clear()
        palmHoldAnchor = null
        palmConsumed = false
        fistStartMs = -1L
        fistConsumed = false
        fistAnchor = null
        if (pose == HandPose.TWO_FINGERS && !scrollActive) {
            // Scroll speed is measured from where the two fingers first appeared.
            twoFingersSinceMs = t
            scrollAnchor = scrollPoint(lm, g.aspect)
        }
    }

    private fun onHandLost(events: MutableList<GestureEvent>) {
        if (phase == PinchPhase.DRAGGING) events += GestureEvent.DragEnd(cursorX, cursorY)
        endScroll(events)
        phase = PinchPhase.IDLE
        handPresent = false
        pinch.reset()
        stablePinched = false
        pinchFrames = 0
        dwellStartMs = -1L
        dwellArmed = true
        poses.reset()
        history.clear()
        palmHistory.clear()
        palmHoldAnchor = null
        palmConsumed = false
        fistStartMs = -1L
        fistConsumed = false
        lastPose = null
        resetPointer()
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
