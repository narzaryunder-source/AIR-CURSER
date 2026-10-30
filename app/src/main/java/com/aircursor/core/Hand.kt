package com.aircursor.core

import kotlin.math.max
import kotlin.math.sqrt

/** One hand landmark in normalized image coordinates (0..1), as returned by MediaPipe. */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

data class Point2(val x: Float, val y: Float)

/** MediaPipe hand landmark indices. */
object HandIdx {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20
    const val COUNT = 21

    /** Bone connections, used to draw the skeleton in the preview. */
    val CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17,
    )
}

/**
 * Distance helpers on a hand. [aspect] = imageWidth / imageHeight, so distances are
 * measured in "image height" units and are not skewed by a non-square frame.
 */
class HandGeometry(val lm: List<Landmark>, private val aspect: Float = 1f) {
    fun d(a: Int, b: Int): Float {
        val dx = (lm[a].x - lm[b].x) * aspect
        val dy = lm[a].y - lm[b].y
        return sqrt(dx * dx + dy * dy)
    }

    /** Wrist -> middle finger MCP: a stable measure of hand size, independent of finger pose. */
    val handSize: Float get() = max(d(HandIdx.WRIST, HandIdx.MIDDLE_MCP), 1e-4f)

    fun fingerExtended(tip: Int, pip: Int): Boolean =
        d(HandIdx.WRIST, tip) > d(HandIdx.WRIST, pip) * EXTENDED_RATIO

    val indexExtended get() = fingerExtended(HandIdx.INDEX_TIP, HandIdx.INDEX_PIP)
    val middleExtended get() = fingerExtended(HandIdx.MIDDLE_TIP, HandIdx.MIDDLE_PIP)
    val ringExtended get() = fingerExtended(HandIdx.RING_TIP, HandIdx.RING_PIP)
    val pinkyExtended get() = fingerExtended(HandIdx.PINKY_TIP, HandIdx.PINKY_PIP)

    /** Index tip clearly away from the palm (true for a pinch, false for a fist). */
    val indexAwayFromPalm get() =
        d(HandIdx.WRIST, HandIdx.INDEX_TIP) > d(HandIdx.WRIST, HandIdx.INDEX_MCP) * 1.15f

    /** Thumb tip to index tip distance divided by hand size. */
    val pinchRatio: Float get() = d(HandIdx.THUMB_TIP, HandIdx.INDEX_TIP) / handSize

    companion object {
        const val EXTENDED_RATIO = 1.1f
    }
}

enum class HandPose { POINT, TWO_FINGERS, OPEN_PALM, FIST, OTHER }

object PoseClassifier {
    fun classify(g: HandGeometry): HandPose {
        val i = g.indexExtended
        val m = g.middleExtended
        val r = g.ringExtended
        val p = g.pinkyExtended
        return when {
            i && m && r && p -> HandPose.OPEN_PALM
            !i && !m && !r && !p -> HandPose.FIST
            i && m && !r && !p -> HandPose.TWO_FINGERS
            i && !m && !r && !p -> HandPose.POINT
            else -> HandPose.OTHER
        }
    }
}

/** A pose only becomes "stable" after it was seen continuously for [holdMs]. */
class PoseDebouncer(var holdMs: Long = 90) {
    var stable: HandPose? = null
        private set
    private var candidate: HandPose? = null
    private var candidateSince = 0L

    fun update(raw: HandPose, tMs: Long): HandPose? {
        if (raw != candidate) {
            candidate = raw
            candidateSince = tMs
        }
        if (stable == null || tMs - candidateSince >= holdMs) stable = candidate
        return stable
    }

    fun reset() {
        stable = null
        candidate = null
    }
}
