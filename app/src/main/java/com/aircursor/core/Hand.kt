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

enum class FingerState { EXTENDED, HALF, FOLDED }

/**
 * Distance helpers on a hand. [aspect] = imageWidth / imageHeight, so distances are
 * measured in "image height" units and are not skewed by a non-square frame.
 * MediaPipe's z uses about the same scale as x, so 3D distances are used: this keeps
 * finger detection right even when the hand is tilted towards the camera.
 */
class HandGeometry(val lm: List<Landmark>, val aspect: Float = 1f) {
    fun d(a: Int, b: Int): Float {
        val dx = (lm[a].x - lm[b].x) * aspect
        val dy = lm[a].y - lm[b].y
        val dz = (lm[a].z - lm[b].z) * aspect
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Wrist -> middle finger MCP: a stable measure of hand size, independent of finger pose. */
    val handSize: Float get() = max(d(HandIdx.WRIST, HandIdx.MIDDLE_MCP), 1e-4f)

    /**
     * How far the fingertip reaches compared to its knuckle (seen from the wrist).
     * Straight finger ~1.8, curled into the palm ~0.8.
     */
    fun finger(tip: Int, mcp: Int): FingerState {
        val e = d(HandIdx.WRIST, tip) / max(d(HandIdx.WRIST, mcp), 1e-4f)
        return when {
            e > EXTENDED_RATIO -> FingerState.EXTENDED
            e < FOLDED_RATIO -> FingerState.FOLDED
            else -> FingerState.HALF
        }
    }

    val index get() = finger(HandIdx.INDEX_TIP, HandIdx.INDEX_MCP)
    val middle get() = finger(HandIdx.MIDDLE_TIP, HandIdx.MIDDLE_MCP)
    val ring get() = finger(HandIdx.RING_TIP, HandIdx.RING_MCP)
    val pinky get() = finger(HandIdx.PINKY_TIP, HandIdx.PINKY_MCP)

    /** Index tip clearly away from the palm (true for a pinch, false for a fist). */
    val indexAwayFromPalm get() =
        d(HandIdx.WRIST, HandIdx.INDEX_TIP) > d(HandIdx.WRIST, HandIdx.INDEX_MCP) * 1.15f

    /** Thumb tip to index tip distance divided by hand size. */
    val pinchRatio: Float get() = d(HandIdx.THUMB_TIP, HandIdx.INDEX_TIP) / handSize

    companion object {
        const val EXTENDED_RATIO = 1.5f
        const val FOLDED_RATIO = 1.3f
    }
}

enum class HandPose { POINT, TWO_FINGERS, OPEN_PALM, FIST, OTHER }

/**
 * Strict on purpose: a finger only counts as "up" when it is clearly straight, so a relaxed
 * middle finger while pointing never turns into a scroll.
 */
object PoseClassifier {
    fun classify(g: HandGeometry): HandPose {
        val i = g.index
        val m = g.middle
        val r = g.ring
        val p = g.pinky
        val ext = FingerState.EXTENDED
        val fold = FingerState.FOLDED
        return when {
            i == ext && m == ext && r == ext && p == ext -> HandPose.OPEN_PALM
            i == fold && m == fold && r != ext && p != ext -> HandPose.FIST
            i == ext && m == ext && r != ext && p != ext -> HandPose.TWO_FINGERS
            i == ext && m != ext -> HandPose.POINT
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
