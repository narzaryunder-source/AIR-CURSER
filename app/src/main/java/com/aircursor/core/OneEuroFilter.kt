package com.aircursor.core

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): an adaptive low-pass filter.
 * Slow movement -> heavy smoothing (kills jitter); fast movement -> light smoothing (low lag).
 *
 * @param minCutoff minimum cutoff frequency in Hz. Lower = steadier but laggier.
 * @param beta speed coefficient. Higher = less lag on fast moves.
 * @param dCutoff cutoff used to smooth the derivative.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.0,
    var dCutoff: Double = 1.0,
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrevMs = 0L

    fun reset() {
        initialized = false
        dxPrev = 0.0
    }

    fun filter(value: Double, timestampMs: Long): Double {
        if (!initialized) {
            initialized = true
            xPrev = value
            dxPrev = 0.0
            tPrevMs = timestampMs
            return value
        }
        val dt = (timestampMs - tPrevMs).coerceAtLeast(1L) / 1000.0
        tPrevMs = timestampMs

        val dx = (value - xPrev) / dt
        val edx = lowPass(dx, dxPrev, alpha(dt, dCutoff))
        dxPrev = edx

        val cutoff = minCutoff + beta * abs(edx)
        val result = lowPass(value, xPrev, alpha(dt, cutoff))
        xPrev = result
        return result
    }

    private fun lowPass(x: Double, prev: Double, a: Double) = a * x + (1.0 - a) * prev

    companion object {
        fun alpha(dtSeconds: Double, cutoffHz: Double): Double {
            val tau = 1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(1e-6))
            return 1.0 / (1.0 + tau / dtSeconds)
        }
    }
}
