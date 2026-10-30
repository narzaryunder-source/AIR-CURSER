package com.aircursor.core

/**
 * Maps a normalized camera point to screen pixels.
 *
 * Only an inner "active box" of the camera frame (with [margin] removed on each side) is used,
 * and it is stretched to the full screen, so the user never has to reach the frame edges.
 * [mirrorX] flips X for the front camera so moving the hand right moves the cursor right.
 * [sensitivity] scales movement around the box centre (>1 = less hand movement needed).
 * [offsetX]/[offsetY] shift the box centre (set by "Recalibrate").
 */
class CoordinateMapper(
    val screenWidth: Int,
    val screenHeight: Int,
    val margin: Float = 0.15f,
    val sensitivity: Float = 1f,
    val mirrorX: Boolean = true,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    private val boxSize = (1f - 2f * margin.coerceIn(0f, 0.45f)).coerceAtLeast(0.1f)

    /** Raw camera x -> user-space x (what the user sees in a mirror). */
    fun userX(rawX: Float) = if (mirrorX) 1f - rawX else rawX

    /** Normalized (0..1) position on screen, before scaling to pixels. */
    fun toUnit(rawX: Float, rawY: Float): Point2 {
        val ux = userX(rawX)
        var fx = (ux - offsetX - (0.5f - boxSize / 2f)) / boxSize
        var fy = (rawY - offsetY - (0.5f - boxSize / 2f)) / boxSize
        fx = 0.5f + (fx - 0.5f) * sensitivity
        fy = 0.5f + (fy - 0.5f) * sensitivity
        return Point2(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }

    fun map(rawX: Float, rawY: Float): Point2 {
        val u = toUnit(rawX, rawY)
        return Point2(u.x * (screenWidth - 1), u.y * (screenHeight - 1))
    }

    /** How many screen pixels one unit of normalized hand movement is worth. */
    fun pixelsPerUnitX() = (screenWidth - 1) * sensitivity / boxSize
    fun pixelsPerUnitY() = (screenHeight - 1) * sensitivity / boxSize

    companion object {
        /** Clamp a recalibration offset so the box never leaves the camera frame. */
        fun clampOffset(value: Float, margin: Float) = value.coerceIn(-margin, margin)
    }
}
