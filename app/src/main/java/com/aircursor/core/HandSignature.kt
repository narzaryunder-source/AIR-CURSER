package com.aircursor.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A rough "who's hand is this" check from bone proportions (palm shape, finger bone lengths,
 * size). Bones do not change length when fingers bend, so this works in any pose.
 *
 * Not a biometric like a fingerprint: a hand with very similar proportions can pass.
 * It stops other people casually controlling the phone.
 */
object HandSignature {
    /** Pairs of landmarks whose distance is measured. Palm + first finger bones + thumb. */
    private val BONES = listOf(
        0 to 1, 0 to 5, 0 to 13, 0 to 17,
        5 to 9, 9 to 13, 13 to 17, 5 to 17, 1 to 5,
        5 to 6, 9 to 10, 13 to 14, 17 to 18,
        1 to 2, 2 to 3,
    )

    /** Number of values in a feature vector: the ratios plus the absolute hand size. */
    val SIZE = BONES.size + 1

    /**
     * Features from MediaPipe *world* landmarks (metres, 3D). Ratios are divided by the
     * wrist -> middle knuckle length; the last value is that length itself.
     */
    fun features(world: List<Landmark>): FloatArray? {
        if (world.size < HandIdx.COUNT) return null
        fun d(a: Int, b: Int): Float {
            val dx = world[a].x - world[b].x
            val dy = world[a].y - world[b].y
            val dz = world[a].z - world[b].z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val ref = d(HandIdx.WRIST, HandIdx.MIDDLE_MCP)
        if (ref < 1e-4f) return null
        val f = FloatArray(SIZE)
        BONES.forEachIndexed { i, (a, b) -> f[i] = d(a, b) / ref }
        f[SIZE - 1] = ref
        return f
    }
}

/** The registered owner hand: average and spread of each feature, plus left/right. */
class HandProfile(
    val handedness: String,
    val mean: FloatArray,
    val std: FloatArray,
) {
    /**
     * How different [f] is from this hand: root-mean-square of per-feature z-scores, ignoring
     * the 2 worst features (one badly estimated joint must not fail the owner). ~1 = same hand.
     * RMS (not a plain average) so a few clearly different bones count strongly.
     */
    fun distance(f: FloatArray): Float {
        val z = FloatArray(mean.size) { i ->
            val spread = max(std[i], mean[i] * if (i == mean.size - 1) 0.06f else 0.035f)
            kotlin.math.abs(f[i] - mean[i]) / spread
        }
        z.sort()
        val keep = (z.size - 2).coerceAtLeast(1)
        var sum = 0f
        for (i in 0 until keep) sum += z[i] * z[i]
        return sqrt(sum / keep)
    }

    fun serialize(): String =
        handedness + ";" + mean.joinToString(",") + ";" + std.joinToString(",")

    companion object {
        fun parse(s: String?): HandProfile? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(';')
            if (parts.size != 3) return null
            val mean = parts[1].split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
            val std = parts[2].split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (mean.size != HandSignature.SIZE || std.size != HandSignature.SIZE) return null
            return HandProfile(parts[0], mean, std)
        }
    }
}

/** Collects feature vectors while the user shows their hand, then builds a [HandProfile]. */
class HandEnroller(private val needed: Int = 45) {
    private val samples = ArrayList<FloatArray>()
    private val hands = HashMap<String, Int>()

    val progress: Float get() = samples.size.toFloat() / needed
    val done: Boolean get() = samples.size >= needed

    fun add(features: FloatArray, handedness: String) {
        if (done) return
        samples += features
        hands[handedness] = (hands[handedness] ?: 0) + 1
    }

    fun reset() {
        samples.clear()
        hands.clear()
    }

    fun build(): HandProfile? {
        if (!done) return null
        val n = samples.size
        val size = HandSignature.SIZE
        // Median per feature is robust against a few bad frames.
        val mean = FloatArray(size) { i -> samples.map { it[i] }.sorted()[n / 2] }
        val std = FloatArray(size) { i ->
            val m = mean[i]
            var acc = 0f
            for (s in samples) acc += (s[i] - m) * (s[i] - m)
            sqrt(acc / n)
        }
        val hand = hands.maxByOrNull { it.value }?.key ?: "?"
        return HandProfile(hand, mean, std)
    }
}

/**
 * Decides whether the hand in view may control the cursor. A new hand is checked over a few
 * frames first; a hand that is accepted is watched all the time, so swapping hands is caught.
 */
class OwnerGate(
    var profile: HandProfile,
    var threshold: Float = 2.5f,
    private val checkFrames: Int = 6,
    private val lostResetMs: Long = 500,
) {
    enum class State { NO_HAND, CHECKING, OWNER, STRANGER }

    var state = State.NO_HAND
        private set
    var score = 0f
        private set

    private val pending = ArrayList<Float>()
    private var lastSeenMs = Long.MIN_VALUE

    /** Returns true when this frame's hand may be used. [features] null = no hand in view. */
    fun update(features: FloatArray?, handedness: String?, tMs: Long): Boolean {
        if (features == null) {
            if (state != State.NO_HAND && tMs - lastSeenMs > lostResetMs) {
                state = State.NO_HAND
                pending.clear()
            }
            return state == State.OWNER
        }
        lastSeenMs = tMs
        val sameSide = handedness == null || profile.handedness == "?" || handedness == profile.handedness
        val d = if (sameSide) profile.distance(features) else 99f

        when (state) {
            State.NO_HAND, State.CHECKING -> {
                state = State.CHECKING
                pending += d
                score = pending.average().toFloat()
                if (pending.size >= checkFrames) {
                    state = if (score <= threshold) State.OWNER else State.STRANGER
                    pending.clear()
                }
            }
            State.OWNER, State.STRANGER -> {
                score = score * 0.85f + d * 0.15f
                // Hysteresis so the state does not flip on single noisy frames.
                if (state == State.OWNER && score > threshold * 1.3f) state = State.STRANGER
                if (state == State.STRANGER && score < threshold * 0.8f) state = State.OWNER
            }
        }
        return state == State.OWNER
    }

    fun reset() {
        state = State.NO_HAND
        pending.clear()
        score = 0f
    }
}
