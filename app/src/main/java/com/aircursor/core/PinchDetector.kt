package com.aircursor.core

/**
 * Pinch detection with hysteresis: the pinch starts when the normalized thumb-index distance
 * drops below [enterRatio] and only ends when it rises above [exitRatio] (> enterRatio).
 * Values in between keep the previous state, so the pinch does not flicker.
 */
class PinchDetector(
    enterRatio: Float = 0.28f,
    exitRatio: Float = 0.42f,
) {
    var enterRatio: Float = enterRatio
        private set
    var exitRatio: Float = exitRatio
        private set

    init {
        setThresholds(enterRatio, exitRatio)
    }

    var isPinched = false
        private set

    fun setThresholds(enter: Float, exit: Float) {
        require(exit > enter) { "exit threshold must be larger than enter threshold" }
        enterRatio = enter
        exitRatio = exit
    }

    /** Feed a normalized distance (thumb-index distance / hand size). Returns the pinch state. */
    fun update(ratio: Float): Boolean {
        if (ratio.isNaN()) return isPinched
        isPinched = if (isPinched) ratio <= exitRatio else ratio < enterRatio
        return isPinched
    }

    fun update(geometry: HandGeometry): Boolean = update(geometry.pinchRatio)

    fun reset() {
        isPinched = false
    }
}
