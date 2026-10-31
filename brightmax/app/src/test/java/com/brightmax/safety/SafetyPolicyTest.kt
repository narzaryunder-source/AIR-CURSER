package com.brightmax.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    @Test fun normalConditionsAllowFullBoost() {
        val v = SafetyPolicy.evaluate(tempC = 32f, batteryPct = 80, currentlyReduced = false)
        assertNull(v.stop)
        assertFalse(v.reduced)
    }

    @Test fun at40CBoostIsReduced() {
        val v = SafetyPolicy.evaluate(tempC = 40f, batteryPct = 80, currentlyReduced = false)
        assertNull(v.stop)
        assertTrue(v.reduced)
    }

    @Test fun reducedStaysReducedUntilCooledBelow38() {
        assertTrue(SafetyPolicy.evaluate(39f, 80, currentlyReduced = true).reduced)
        assertTrue(SafetyPolicy.evaluate(38.5f, 80, currentlyReduced = true).reduced)
        assertFalse(SafetyPolicy.evaluate(38f, 80, currentlyReduced = true).reduced)
        // Not reduced yet at 39 °C when coming from cool.
        assertFalse(SafetyPolicy.evaluate(39f, 80, currentlyReduced = false).reduced)
    }

    @Test fun at43CBoostTurnsOff() {
        assertEquals(StopReason.OVERHEAT, SafetyPolicy.evaluate(43f, 80, false).stop)
        assertEquals(StopReason.OVERHEAT, SafetyPolicy.evaluate(45.2f, 80, true).stop)
        assertNull(SafetyPolicy.evaluate(42.9f, 80, true).stop)
    }

    @Test fun under15PercentBatteryTurnsOff() {
        assertEquals(StopReason.LOW_BATTERY, SafetyPolicy.evaluate(30f, 14, false).stop)
        assertNull(SafetyPolicy.evaluate(30f, 15, false).stop)
    }

    @Test fun androidThermalStatus() {
        // MODERATE (2) reduces, SEVERE (3) stops.
        assertTrue(SafetyPolicy.evaluate(30f, 80, false, thermalStatus = 2).reduced)
        assertEquals(StopReason.THERMAL_SEVERE, SafetyPolicy.evaluate(30f, 80, false, thermalStatus = 3).stop)
        assertFalse(SafetyPolicy.evaluate(30f, 80, false, thermalStatus = 1).reduced)
    }

    @Test fun unknownReadingsDoNotBlock() {
        assertNull(SafetyPolicy.startBlocker(null, null, null))
        val v = SafetyPolicy.evaluate(null, null, currentlyReduced = true)
        assertNull(v.stop)
        assertFalse(v.reduced)
    }

    @Test fun startIsBlockedWhenHotOrLow() {
        assertEquals(StopReason.LOW_BATTERY, SafetyPolicy.startBlocker(30f, 10))
        assertEquals(StopReason.OVERHEAT, SafetyPolicy.startBlocker(43.5f, 90))
        assertNull(SafetyPolicy.startBlocker(41f, 90))
    }

    @Test fun effectiveLevelAppliesReduction() {
        assertEquals(1f, SafetyPolicy.effectiveLevel(1f, reduced = false), 1e-6f)
        assertEquals(0.8f, SafetyPolicy.effectiveLevel(1f, reduced = true), 1e-6f)
        assertEquals(0.4f, SafetyPolicy.effectiveLevel(0.5f, reduced = true), 1e-6f)
        // Slider range is clamped to 50..100 %.
        assertEquals(0.5f, SafetyPolicy.effectiveLevel(0.1f, reduced = false), 1e-6f)
        assertEquals(1f, SafetyPolicy.effectiveLevel(3f, reduced = false), 1e-6f)
    }

    @Test fun systemBrightnessMapping() {
        assertEquals(255, SafetyPolicy.toSystemBrightness(1f))
        assertEquals(128, SafetyPolicy.toSystemBrightness(0.5f))
        assertEquals(204, SafetyPolicy.toSystemBrightness(0.8f))
        assertEquals(1, SafetyPolicy.toSystemBrightness(0f))
    }

    @Test fun timerAndWarning() {
        assertEquals(600_000L, SafetyPolicy.timerMs(SafetyPolicy.DEFAULT_TIMER_MIN))
        assertFalse(SafetyPolicy.shouldWarn(61_000L, alreadyWarned = false))
        assertTrue(SafetyPolicy.shouldWarn(60_000L, alreadyWarned = false))
        assertFalse(SafetyPolicy.shouldWarn(30_000L, alreadyWarned = true))
        assertFalse(SafetyPolicy.shouldWarn(0L, alreadyWarned = false))
    }
}
