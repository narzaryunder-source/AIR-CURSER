package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    @Test
    fun firstSamplePassesThrough() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.0)
        assertEquals(42.0, f.filter(42.0, 0), 1e-9)
    }

    @Test
    fun constantSignalStaysConstant() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.01)
        var out = 0.0
        for (i in 0 until 100) out = f.filter(100.0, i * 33L)
        assertEquals(100.0, out, 1e-6)
    }

    @Test
    fun jitterIsStronglyReduced() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.0)
        var maxDev = 0.0
        for (i in 0 until 300) {
            val noisy = 500.0 + if (i % 2 == 0) 10.0 else -10.0
            val out = f.filter(noisy, i * 33L)
            if (i > 30) maxDev = maxOf(maxDev, abs(out - 500.0))
        }
        assertTrue("jitter of +-10 should shrink a lot, was $maxDev", maxDev < 3.0)
    }

    @Test
    fun betaReducesLagOnFastMovement() {
        fun lagAfterRamp(beta: Double): Double {
            val f = OneEuroFilter(minCutoff = 1.0, beta = beta)
            var out = 0.0
            var value = 0.0
            for (i in 0 until 30) {
                value = i * 40.0 // 40 px per frame = ~1200 px/s
                out = f.filter(value, i * 33L)
            }
            return value - out
        }
        val slow = lagAfterRamp(0.0)
        val fast = lagAfterRamp(0.05)
        assertTrue("beta should reduce lag ($fast vs $slow)", fast < slow * 0.5)
    }

    @Test
    fun resetForgetsHistory() {
        val f = OneEuroFilter(minCutoff = 0.5, beta = 0.0)
        for (i in 0 until 10) f.filter(0.0, i * 33L)
        f.reset()
        assertEquals(900.0, f.filter(900.0, 400), 1e-9)
    }

    @Test
    fun alphaIsBetweenZeroAndOne() {
        val a = OneEuroFilter.alpha(0.033, 1.0)
        assertTrue(a > 0.0 && a < 1.0)
    }
}
