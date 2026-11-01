package com.aircursor.core

import com.aircursor.core.FakeHand.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private val baseConfig = GestureConfig(
        screenWidth = 1080,
        screenHeight = 2400,
        mirrorX = false,
        pointerMode = PointerMode.ABSOLUTE,
    )
    private val mouseConfig = baseConfig.copy(pointerMode = PointerMode.RELATIVE)

    /** Feeds frames at ~30 fps and collects all events. */
    private class Driver(val engine: GestureEngine) {
        var t = 1000L
        val events = mutableListOf<GestureEvent>()
        lateinit var last: EngineOutput

        fun feed(
            shape: Shape?,
            cx: Float = 0.5f,
            cy: Float = 0.7f,
            frames: Int = 1,
            size: Float = 0.15f,
            noise: (() -> Float)? = null,
        ): Driver {
            repeat(frames) {
                var lms = shape?.let { FakeHand.build(it, cx, cy, size) }
                if (noise != null) lms = lms?.map { Landmark(it.x + noise(), it.y + noise(), it.z) }
                last = engine.process(HandFrame(t, lms))
                events += last.events
                t += 33
            }
            return this
        }

        fun feedLandmarks(lms: List<Landmark>?, frames: Int = 1): Driver {
            repeat(frames) {
                last = engine.process(HandFrame(t, lms))
                events += last.events
                t += 33
            }
            return this
        }

        inline fun <reified T : GestureEvent> count() = events.count { it is T }
    }

    @Test
    fun quickPinchIsTapAtPrePinchPosition() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10)
        val before = d.last
        d.feed(Shape.PINCH, frames = 4).feed(Shape.POINT, frames = 3)
        val taps = d.events.filterIsInstance<GestureEvent.Tap>()
        assertEquals(1, taps.size)
        assertEquals(before.x, taps[0].x, 1f)
        assertEquals(before.y, taps[0].y, 1f)
        assertEquals(0, d.count<GestureEvent.LongPress>())
        assertEquals(0, d.count<GestureEvent.DragStart>())
    }

    @Test
    fun cursorIsFrozenRightAfterPinch() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10)
        val before = d.last
        // Pinch (confirmed on the 2nd frame) and jerk the hand a little: cursor must stay put.
        d.feed(Shape.PINCH).feed(Shape.PINCH, cx = 0.52f)
        assertEquals(before.x, d.last.x, 0.5f)
        assertEquals(before.y, d.last.y, 0.5f)
        assertEquals(CursorMode.PRESSING, d.last.mode)
    }

    @Test
    fun pinchHeldStillIsLongPress() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 20).feed(Shape.POINT, frames = 3)
        assertEquals(1, d.count<GestureEvent.LongPress>())
        assertEquals(0, d.count<GestureEvent.Tap>())
    }

    @Test
    fun pinchAndMoveIsDrag() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(10) {
            x += 0.02f
            d.feed(Shape.PINCH, cx = x)
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
        d.feed(Shape.POINT, cx = x, frames = 2)
        assertEquals(1, d.count<GestureEvent.DragStart>())
        assertTrue(d.count<GestureEvent.DragMove>() > 3)
        assertEquals(1, d.count<GestureEvent.DragEnd>())
        assertEquals(0, d.count<GestureEvent.Tap>())
        val start = d.events.filterIsInstance<GestureEvent.DragStart>().first()
        val end = d.events.filterIsInstance<GestureEvent.DragEnd>().first()
        assertTrue(end.x > start.x + 100)
    }

    @Test
    fun losingHandEndsDrag() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(6) {
            x += 0.03f
            d.feed(Shape.PINCH, cx = x)
        }
        d.feed(null, frames = 8)
        assertEquals(1, d.count<GestureEvent.DragEnd>())
        assertFalse(d.last.handPresent)
    }

    @Test
    fun fistDoesNotCountAsPinch() {
        val d = Driver(GestureEngine(baseConfig.copy(enableFistPause = false)))
        d.feed(Shape.POINT, frames = 5).feed(Shape.FIST, frames = 10).feed(Shape.POINT, frames = 5)
        assertEquals(0, d.count<GestureEvent.Tap>())
        assertEquals(0, d.count<GestureEvent.LongPress>())
    }

    @Test
    fun fistHeldTogglesPauseWithProgress() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 5).feed(Shape.FIST, frames = 20)
        assertEquals(HoldKind.FIST, d.last.holdKind)
        assertTrue(d.last.holdProgress > 0.2f)
        d.feed(Shape.FIST, frames = 50)
        assertEquals(listOf(GestureEvent.PauseChanged(true)), d.events.filterIsInstance<GestureEvent.PauseChanged>())
        assertTrue(d.engine.paused)
        assertEquals(CursorMode.PAUSED, d.last.mode)

        // While paused, pinches do nothing.
        d.feed(Shape.POINT, frames = 5).feed(Shape.PINCH, frames = 3).feed(Shape.POINT, frames = 3)
        assertEquals(0, d.count<GestureEvent.Tap>())

        // Fist again resumes.
        d.feed(Shape.FIST, frames = 80)
        assertFalse(d.engine.paused)
        assertEquals(2, d.count<GestureEvent.PauseChanged>())
    }

    @Test
    fun palmSwipeLeftIsBack() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.OPEN_PALM, cx = 0.7f, frames = 5)
        var x = 0.7f
        repeat(6) {
            x -= 0.05f
            d.feed(Shape.OPEN_PALM, cx = x)
        }
        assertEquals(1, d.count<GestureEvent.Back>())
        assertEquals(0, d.count<GestureEvent.Home>())
    }

    @Test
    fun palmSwipeLeftWithMirroredCamera() {
        // Front camera: the user moving left makes the hand move right in the raw image.
        val d = Driver(GestureEngine(baseConfig.copy(mirrorX = true)))
        d.feed(Shape.OPEN_PALM, cx = 0.3f, frames = 5)
        var x = 0.3f
        repeat(6) {
            x += 0.05f
            d.feed(Shape.OPEN_PALM, cx = x)
        }
        assertEquals(1, d.count<GestureEvent.Back>())
    }

    @Test
    fun palmSwipeDownIsNotifications() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.OPEN_PALM, cy = 0.5f, frames = 5)
        var y = 0.5f
        repeat(6) {
            y += 0.05f
            d.feed(Shape.OPEN_PALM, cy = y)
        }
        assertEquals(1, d.count<GestureEvent.Notifications>())
    }

    @Test
    fun palmHeldStillIsHomeOnce() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.OPEN_PALM, frames = 20)
        assertEquals(HoldKind.PALM, d.last.holdKind)
        d.feed(Shape.OPEN_PALM, frames = 60)
        assertEquals(1, d.count<GestureEvent.Home>())
    }

    @Test
    fun disabledGesturesDoNotFire() {
        val cfg = baseConfig.copy(enableHome = false, enableBack = false, enableTap = false)
        val d = Driver(GestureEngine(cfg))
        d.feed(Shape.OPEN_PALM, frames = 60)
        d.feed(Shape.OPEN_PALM, cx = 0.7f, frames = 5)
        var x = 0.7f
        repeat(6) {
            x -= 0.05f
            d.feed(Shape.OPEN_PALM, cx = x)
        }
        d.feed(Shape.POINT, frames = 5).feed(Shape.PINCH, frames = 3).feed(Shape.POINT, frames = 3)
        assertEquals(0, d.count<GestureEvent.Home>())
        assertEquals(0, d.count<GestureEvent.Back>())
        assertEquals(0, d.count<GestureEvent.Tap>())
    }

    private fun Driver.lastScroll() = events.filterIsInstance<GestureEvent.ScrollVelocity>().last()

    @Test
    fun twoFingersMovedDownScrollsWithSpeedProportionalToDistance() {
        fun scrollSpeed(offset: Float): Float {
            val d = Driver(GestureEngine(baseConfig))
            d.feed(Shape.TWO_FINGERS, cy = 0.6f, frames = 5)
            d.feed(Shape.TWO_FINGERS, cy = 0.6f + offset, frames = 5)
            val s = d.lastScroll()
            assertEquals(0f, s.vx, 0f)
            return s.vy
        }
        val small = scrollSpeed(0.06f)
        val big = scrollSpeed(0.12f)
        // Hand down -> page scrolls down -> virtual finger moves up (negative vy).
        assertTrue(small < 0f)
        assertTrue(big < small * 2f)
    }

    @Test
    fun handInsideScrollDeadZoneHoldsStill() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.TWO_FINGERS, cy = 0.6f, frames = 5).feed(Shape.TWO_FINGERS, cy = 0.61f, frames = 5)
        assertEquals(0f, d.lastScroll().vy, 0f)
        assertEquals(CursorMode.SCROLLING, d.last.mode)
    }

    @Test
    fun twoFingersMovedSidewaysIsHorizontalScroll() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.TWO_FINGERS, cx = 0.5f, frames = 5)
        d.feed(Shape.TWO_FINGERS, cx = 0.6f, frames = 5)
        val s = d.lastScroll()
        assertEquals(0f, s.vy, 0f)
        assertTrue(s.vx < 0f)
    }

    @Test
    fun cursorDoesNotMoveWhileScrolling() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, cy = 0.6f, frames = 10)
        val before = d.last
        var y = 0.6f
        repeat(10) {
            y += 0.015f
            d.feed(Shape.TWO_FINGERS, cy = y)
            assertEquals(before.x, d.last.x, 0.5f)
            assertEquals(before.y, d.last.y, 0.5f)
        }
        assertTrue(d.count<GestureEvent.ScrollVelocity>() > 0)
    }

    @Test
    fun shortPoseFlickerDoesNotEndScroll() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.TWO_FINGERS, cy = 0.6f, frames = 5).feed(Shape.TWO_FINGERS, cy = 0.7f, frames = 5)
        val frozenAt = d.last
        d.feed(Shape.POINT, cy = 0.7f, frames = 4)
        d.feed(Shape.TWO_FINGERS, cy = 0.7f, frames = 5)
        assertEquals(0, d.count<GestureEvent.ScrollEnd>())
        assertEquals(frozenAt.y, d.last.y, 0.5f)
        d.feed(Shape.POINT, cy = 0.7f, frames = 15)
        assertEquals(1, d.count<GestureEvent.ScrollEnd>())
    }

    @Test
    fun cursorGlidesBackAfterScrollInsteadOfJumping() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, cy = 0.6f, frames = 10)
        d.feed(Shape.TWO_FINGERS, cy = 0.6f, frames = 3).feed(Shape.TWO_FINGERS, cy = 0.7f, frames = 10)
        var prevY = d.last.y
        repeat(40) {
            d.feed(Shape.POINT, cy = 0.7f)
            assertTrue("cursor jumped ${d.last.y - prevY}px", kotlin.math.abs(d.last.y - prevY) < 150f)
            prevY = d.last.y
        }
        // Finally the cursor sits on the finger again (0.1 hand move = ~343 px lower).
        val target = GestureEngine(baseConfig).let { e ->
            var o = e.process(HandFrame(0, FakeHand.build(Shape.POINT, cy = 0.7f)))
            o = e.process(HandFrame(33, FakeHand.build(Shape.POINT, cy = 0.7f)))
            o.y
        }
        assertEquals(target, d.last.y, 8f)
    }

    @Test
    fun handLostEndsScroll() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.TWO_FINGERS, frames = 8).feed(null, frames = 8)
        assertEquals(1, d.count<GestureEvent.ScrollEnd>())
    }

    @Test
    fun shortTrackingDropOutIsBridged() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 5).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(6) {
            x += 0.03f
            d.feed(Shape.PINCH, cx = x)
        }
        d.feed(null, frames = 3)
        assertTrue(d.last.handPresent)
        assertEquals(0, d.count<GestureEvent.DragEnd>())
        d.feed(Shape.PINCH, cx = x, frames = 2)
        assertEquals(CursorMode.DRAGGING, d.last.mode)
    }

    @Test
    fun relaxedMiddleFingerWhilePointingIsNotScroll() {
        // Middle finger half bent (tip a bit above its knuckle) must still be "pointing".
        val hand = FakeHand.build(Shape.POINT).toMutableList()
        val mcp = hand[HandIdx.MIDDLE_MCP]
        val size = 0.15f
        hand[HandIdx.MIDDLE_PIP] = Landmark(mcp.x, mcp.y - 0.3f * size)
        hand[HandIdx.MIDDLE_DIP] = Landmark(mcp.x, mcp.y - 0.4f * size)
        hand[HandIdx.MIDDLE_TIP] = Landmark(mcp.x, mcp.y - 0.4f * size)
        assertEquals(HandPose.POINT, PoseClassifier.classify(HandGeometry(hand)))
    }

    // ---- Cursor v5: debounce, sticky drag, gesture guard, dwell, auto-tune ----

    /** A pinch whose thumb-index gap is [gap] hand sizes (0.07 = closed, 1.2 = open). */
    private fun pinchWithGap(gap: Float, cx: Float = 0.5f, cy: Float = 0.7f, size: Float = 0.15f): List<Landmark> {
        val h = FakeHand.build(Shape.PINCH, cx, cy, size).toMutableList()
        val tip = h[HandIdx.INDEX_TIP]
        val o = gap * size / kotlin.math.sqrt(2f)
        h[HandIdx.THUMB_TIP] = Landmark(tip.x + o, tip.y + o)
        return h
    }

    @Test
    fun singleNoisyPinchFrameDoesNotClick() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 1).feed(Shape.POINT, frames = 6)
        assertEquals(0, d.count<GestureEvent.Tap>())
    }

    @Test
    fun dragSurvivesFingersOpeningABit() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(6) {
            x += 0.02f
            d.feed(Shape.PINCH, cx = x)
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
        // Fingers open to 0.48 hand sizes (more than a normal release) while still moving.
        repeat(4) {
            x += 0.01f
            d.feedLandmarks(pinchWithGap(0.48f, cx = x))
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
        assertEquals(0, d.count<GestureEvent.DragEnd>())
        d.feed(Shape.POINT, cx = x, frames = 2)
        assertEquals(1, d.count<GestureEvent.DragEnd>())
    }

    @Test
    fun noPauseProgressRightAfterDrag() {
        val d = Driver(GestureEngine(baseConfig.copy(enableFistPause = true)))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(6) {
            x += 0.02f
            d.feed(Shape.PINCH, cx = x)
        }
        d.feed(Shape.POINT, cx = x, frames = 2)
        assertEquals(1, d.count<GestureEvent.DragEnd>())
        // Closing the hand right after the drag must not start the pause ring.
        repeat(18) {
            d.feed(Shape.FIST, cx = x)
            assertTrue(d.last.holdKind != HoldKind.FIST)
        }
        assertEquals(0, d.count<GestureEvent.PauseChanged>())
    }

    @Test
    fun movingFistNeverPauses() {
        val d = Driver(GestureEngine(baseConfig.copy(enableFistPause = true)))
        d.feed(Shape.POINT, frames = 5)
        var x = 0.3f
        repeat(120) {
            x += 0.004f
            d.feed(Shape.FIST, cx = x)
        }
        assertEquals(0, d.count<GestureEvent.PauseChanged>())
    }

    @Test
    fun dwellClickFiresOnceThenNeedsAMove() {
        val d = Driver(GestureEngine(baseConfig.copy(enableDwell = true)))
        d.feed(Shape.POINT, frames = 45)
        assertEquals(1, d.count<GestureEvent.Tap>())
        d.feed(Shape.POINT, frames = 45)
        assertEquals(1, d.count<GestureEvent.Tap>())
        d.feed(Shape.POINT, cx = 0.56f, frames = 3).feed(Shape.POINT, cx = 0.56f, frames = 45)
        assertEquals(2, d.count<GestureEvent.Tap>())
    }

    @Test
    fun dwellIsOffByDefault() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.POINT, frames = 90)
        assertEquals(0, d.count<GestureEvent.Tap>())
    }

    @Test
    fun autoTuneReportsAnAntiShakeRadius() {
        val rnd = java.util.Random(11)
        val engine = GestureEngine(mouseConfig)
        val d = Driver(engine)
        d.feed(Shape.POINT, frames = 5)
        engine.startAutoTune(40)
        d.feed(Shape.POINT, frames = 60, noise = { (rnd.nextFloat() - 0.5f) * 0.002f })
        val tuned = d.events.filterIsInstance<GestureEvent.AutoTuned>()
        assertEquals(1, tuned.size)
        assertTrue(tuned[0].deadZonePx in 3f..20f)
    }

    // ---- Simple (click-only) mode ----

    @Test
    fun simpleModeCursorFollowsEveryPoseAndOnlyClicks() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true)))
        d.feed(Shape.POINT, frames = 10)
        val start = d.last.x
        var x = 0.5f
        // Open palm / two fingers / fist must not stop the cursor or fire anything.
        for (shape in listOf(Shape.OPEN_PALM, Shape.TWO_FINGERS, Shape.FIST)) {
            repeat(15) {
                x += 0.004f
                d.feed(shape, cx = x)
            }
        }
        assertTrue("cursor should keep moving", d.last.x > start + 50f)
        d.feed(Shape.OPEN_PALM, cx = x, frames = 60)
        // Quick pinch = click; pinch held still = long press (right click on the PC).
        d.feed(Shape.POINT, cx = x, frames = 5).feed(Shape.PINCH, cx = x, frames = 4).feed(Shape.POINT, cx = x, frames = 8)
        assertEquals(1, d.count<GestureEvent.Tap>())
        d.feed(Shape.PINCH, cx = x, frames = 25).feed(Shape.POINT, cx = x, frames = 3)
        assertEquals(1, d.count<GestureEvent.Tap>())
        assertEquals(1, d.count<GestureEvent.LongPress>())
        assertEquals(0, d.count<GestureEvent.Home>())
        assertEquals(0, d.count<GestureEvent.PauseChanged>())
        assertEquals(0, d.count<GestureEvent.ScrollVelocity>())
    }

    @Test
    fun simpleModeCanDragButNeverPauses() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true, enableFistPause = true)))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 6)
        var x = 0.5f
        repeat(10) {
            x += 0.01f
            d.feed(Shape.PINCH, cx = x)
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
        d.feed(Shape.POINT, cx = x, frames = 3).feed(Shape.FIST, cx = x, frames = 120)
        assertEquals(1, d.count<GestureEvent.DragStart>())
        assertEquals(1, d.count<GestureEvent.DragEnd>())
        assertEquals(0, d.count<GestureEvent.Tap>())
        assertEquals(0, d.count<GestureEvent.PauseChanged>())
    }

    // ---- Click vs drag (no accidental drags / selection boxes) ----

    @Test
    fun handDriftWhilePinchingStaysAClick() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true)))
        d.feed(Shape.POINT, frames = 10)
        var x = 0.5f
        // The hand drifts a little (~5% of the hand size) while the fingers are pinched (short pinch).
        repeat(6) {
            x += 0.0013f
            d.feed(Shape.PINCH, cx = x)
        }
        d.feed(Shape.POINT, cx = x, frames = 3)
        assertEquals(1, d.count<GestureEvent.Tap>())
        assertEquals(0, d.count<GestureEvent.DragStart>())
    }

    @Test
    fun holdThenMoveStartsADrag() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true)))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 9) // ~300 ms still
        var x = 0.5f
        repeat(8) {
            x += 0.01f
            d.feed(Shape.PINCH, cx = x)
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
        d.feed(Shape.POINT, cx = x, frames = 3)
        assertEquals(1, d.count<GestureEvent.DragStart>())
        assertEquals(1, d.count<GestureEvent.DragEnd>())
        assertEquals(0, d.count<GestureEvent.Tap>())
        assertEquals(0, d.count<GestureEvent.LongPress>())
    }

    @Test
    fun bigFastMoveDragsWithoutWaiting() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true)))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 5)
        var x = 0.5f
        repeat(3) {
            x += 0.05f
            d.feed(Shape.PINCH, cx = x)
        }
        assertEquals(CursorMode.DRAGGING, d.last.mode)
    }

    @Test
    fun holdingStillShowsARingThenLongPress() {
        val d = Driver(GestureEngine(mouseConfig.copy(clickOnly = true)))
        d.feed(Shape.POINT, frames = 10).feed(Shape.PINCH, frames = 2)
        var sawRing = false
        repeat(16) {
            d.feed(Shape.PINCH)
            if (d.last.holdKind == HoldKind.PRESS) sawRing = true
        }
        assertTrue(sawRing)
        assertEquals(1, d.count<GestureEvent.LongPress>())
        d.feed(Shape.POINT, frames = 3)
        assertEquals(0, d.count<GestureEvent.Tap>())
    }

    // ---- Mouse (relative) mode ----

    private fun mouseMove(size: Float, handSizes: Float, steps: Int): Float {
        val d = Driver(GestureEngine(mouseConfig))
        d.feed(Shape.POINT, cx = 0.3f, size = size, frames = 10)
        val start = d.last.x
        var x = 0.3f
        repeat(steps) {
            x += handSizes * size / steps
            d.feed(Shape.POINT, cx = x, size = size)
        }
        d.feed(Shape.POINT, cx = x, size = size, frames = 20)
        return d.last.x - start
    }

    @Test
    fun mouseModeFeelsTheSameNearAndFar() {
        val near = mouseMove(size = 0.25f, handSizes = 0.1f, steps = 8)
        val far = mouseMove(size = 0.08f, handSizes = 0.1f, steps = 8)
        assertTrue(near > 100f)
        assertEquals(near, far, near * 0.15f)
    }

    @Test
    fun mouseModeAcceleratesFastMoves() {
        val slow = mouseMove(size = 0.15f, handSizes = 0.1f, steps = 60)
        val fast = mouseMove(size = 0.15f, handSizes = 0.1f, steps = 3)
        assertTrue("fast $fast vs slow $slow", fast > slow * 1.3f)
    }

    @Test
    fun mouseModeIgnoresTremor() {
        val rnd = java.util.Random(7)
        val d = Driver(GestureEngine(mouseConfig))
        val noise = { (rnd.nextFloat() - 0.5f) * 0.0016f }
        d.feed(Shape.POINT, frames = 20, noise = noise)
        val start = d.last
        var maxDrift = 0f
        repeat(60) {
            d.feed(Shape.POINT, noise = noise)
            maxDrift = maxOf(maxDrift, kotlin.math.hypot(d.last.x - start.x, d.last.y - start.y))
        }
        assertTrue("cursor shook by $maxDrift px", maxDrift < 20f)
    }

    @Test
    fun mouseModeDoesNotJumpWhenHandComesBack() {
        val d = Driver(GestureEngine(mouseConfig))
        d.feed(Shape.POINT, cx = 0.5f, frames = 10)
        val before = d.last
        d.feed(null, frames = 20).feed(Shape.POINT, cx = 0.2f, cy = 0.5f, frames = 5)
        assertEquals(before.x, d.last.x, 1f)
        assertEquals(before.y, d.last.y, 1f)
    }

    @Test
    fun mouseModeClickLandsWhereCursorWas() {
        val d = Driver(GestureEngine(mouseConfig))
        d.feed(Shape.POINT, frames = 10)
        var x = 0.5f
        repeat(5) {
            x += 0.02f
            d.feed(Shape.POINT, cx = x)
        }
        d.feed(Shape.POINT, cx = x, frames = 10)
        val before = d.last
        d.feed(Shape.PINCH, cx = x, frames = 3).feed(Shape.POINT, cx = x, frames = 3)
        val tap = d.events.filterIsInstance<GestureEvent.Tap>().single()
        assertEquals(before.x, tap.x, 1f)
        assertEquals(before.y, tap.y, 1f)
    }

    @Test
    fun mouseModeCursorStaysStillWhileScrolling() {
        val d = Driver(GestureEngine(mouseConfig))
        d.feed(Shape.POINT, frames = 10)
        val before = d.last
        var y = 0.7f
        repeat(15) {
            y += 0.01f
            d.feed(Shape.TWO_FINGERS, cy = y)
        }
        assertEquals(before.x, d.last.x, 0.5f)
        assertEquals(before.y, d.last.y, 0.5f)
        assertTrue(d.events.filterIsInstance<GestureEvent.ScrollVelocity>().any { it.vy != 0f })
    }

    @Test
    fun recalibrateCentersBoxOnFinger() {
        val engine = GestureEngine(baseConfig)
        val d = Driver(engine)
        d.feed(Shape.POINT, cx = 0.6f, cy = 0.7f, frames = 3)
        engine.requestRecalibrate()
        d.feed(Shape.POINT, cx = 0.6f, cy = 0.7f, frames = 3)
        assertEquals(1, d.count<GestureEvent.Recalibrated>())
        assertEquals(540f, d.last.x, 2f)
    }
}
