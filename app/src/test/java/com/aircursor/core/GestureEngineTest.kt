package com.aircursor.core

import com.aircursor.core.FakeHand.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private val baseConfig = GestureConfig(screenWidth = 1080, screenHeight = 2400, mirrorX = false)

    /** Feeds frames at ~30 fps and collects all events. */
    private class Driver(val engine: GestureEngine) {
        var t = 1000L
        val events = mutableListOf<GestureEvent>()
        lateinit var last: EngineOutput

        fun feed(shape: Shape?, cx: Float = 0.5f, cy: Float = 0.7f, frames: Int = 1): Driver {
            repeat(frames) {
                val lms = shape?.let { FakeHand.build(it, cx, cy) }
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
        // Pinch and jerk the hand a little in the same frame: cursor must stay put.
        d.feed(Shape.PINCH, cx = 0.52f)
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
        d.feed(null)
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
        d.feed(Shape.FIST, frames = 40)
        assertEquals(listOf(GestureEvent.PauseChanged(true)), d.events.filterIsInstance<GestureEvent.PauseChanged>())
        assertTrue(d.engine.paused)
        assertEquals(CursorMode.PAUSED, d.last.mode)

        // While paused, pinches do nothing.
        d.feed(Shape.POINT, frames = 5).feed(Shape.PINCH, frames = 3).feed(Shape.POINT, frames = 3)
        assertEquals(0, d.count<GestureEvent.Tap>())

        // Fist again resumes.
        d.feed(Shape.FIST, frames = 60)
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

    @Test
    fun twoFingersMovedDownScrollsWithSpeedProportionalToDistance() {
        fun scrollAmount(offset: Float): Float {
            val d = Driver(GestureEngine(baseConfig))
            d.feed(Shape.TWO_FINGERS, cy = 0.6f, frames = 5)
            d.feed(Shape.TWO_FINGERS, cy = 0.6f + offset, frames = 10)
            val scrolls = d.events.filterIsInstance<GestureEvent.Scroll>()
            assertTrue(scrolls.isNotEmpty())
            assertTrue(scrolls.all { it.dx == 0f })
            return scrolls.last().dy
        }
        val small = scrollAmount(0.06f)
        val big = scrollAmount(0.12f)
        // Hand down -> page scrolls down -> finger swipes up (negative dy).
        assertTrue(small < 0f)
        assertTrue(big < small * 2f)
    }

    @Test
    fun twoFingersMovedSidewaysIsHorizontalSwipe() {
        val d = Driver(GestureEngine(baseConfig))
        d.feed(Shape.TWO_FINGERS, cx = 0.5f, frames = 5)
        d.feed(Shape.TWO_FINGERS, cx = 0.6f, frames = 10)
        val scrolls = d.events.filterIsInstance<GestureEvent.Scroll>()
        assertTrue(scrolls.isNotEmpty())
        assertTrue(scrolls.all { it.dy == 0f && it.dx < 0f })
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
