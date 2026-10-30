package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchDetectorTest {

    @Test
    fun entersBelowEnterThreshold() {
        val p = PinchDetector(0.25f, 0.40f)
        assertFalse(p.update(0.5f))
        assertFalse(p.update(0.30f))
        assertTrue(p.update(0.20f))
    }

    @Test
    fun staysPinchedBetweenThresholds() {
        val p = PinchDetector(0.25f, 0.40f)
        p.update(0.1f)
        assertTrue(p.update(0.30f))
        assertTrue(p.update(0.39f))
        assertTrue(p.update(0.40f))
        assertFalse(p.update(0.41f))
    }

    @Test
    fun noFlickerWhenOscillatingInsideHysteresisBand() {
        val p = PinchDetector(0.25f, 0.40f)
        p.update(0.1f)
        var changes = 0
        var last = p.isPinched
        for (i in 0 until 50) {
            val now = p.update(if (i % 2 == 0) 0.26f else 0.38f)
            if (now != last) changes++
            last = now
        }
        assertEquals(0, changes)
        assertTrue(p.isPinched)
    }

    @Test
    fun unpinchedNoiseAroundEnterDoesNotFlicker() {
        val p = PinchDetector(0.25f, 0.40f)
        p.update(0.24f) // pinched once
        var changes = 0
        var last = p.isPinched
        for (i in 0 until 50) {
            val now = p.update(if (i % 2 == 0) 0.24f else 0.27f)
            if (now != last) changes++
            last = now
        }
        assertEquals(0, changes)
    }

    @Test
    fun nanKeepsState() {
        val p = PinchDetector()
        p.update(0.05f)
        assertTrue(p.update(Float.NaN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exitMustBeAboveEnter() {
        PinchDetector(0.4f, 0.3f)
    }

    @Test
    fun ratioIsIndependentOfHandSize() {
        val hand = FakeHand.build(FakeHand.Shape.POINT, 0.5f, 0.5f, 0.1f)
        val small = HandGeometry(hand).pinchRatio
        val big = HandGeometry(FakeHand.scaled(hand, 2f)).pinchRatio
        assertEquals(small, big, 1e-4f)
    }

    @Test
    fun syntheticPinchHandIsDetected() {
        val p = PinchDetector()
        assertFalse(p.update(HandGeometry(FakeHand.build(FakeHand.Shape.POINT))))
        assertTrue(p.update(HandGeometry(FakeHand.build(FakeHand.Shape.PINCH))))
    }
}
