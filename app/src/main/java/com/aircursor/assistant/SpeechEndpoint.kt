package com.aircursor.assistant

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides, from short frames of 16 kHz microphone audio, when a spoken command starts and when it is
 * over (like a phone's voice typing does), so the offline speech engine gets only the words.
 *
 * The room's own noise is measured first; speech is anything clearly louder than it.
 */
class SpeechEndpoint(
    private val sampleRate: Int = 16_000,
    /** Quiet after the words that ends the command. */
    private val endSilenceMs: Int = 800,
    /** Nobody spoke for this long: give up. */
    private val noSpeechMs: Int = 6_000,
    /** A command longer than this is cut here. */
    private val maxMs: Int = 10_000,
    /** Speech must be this much louder than the room (dB). */
    private val marginDb: Float = 12f,
) {
    enum class State { WAITING, SPEAKING, DONE, NO_SPEECH }

    var state = State.WAITING
        private set

    /** Loudness of the last frame, 0..1 (for the listening animation). */
    var level = 0f
        private set

    private var floorDb = Float.NaN
    private var elapsedMs = 0
    private var quietMs = 0
    private var speechMs = 0

    /** Feeds one frame (any length); returns the state after it. */
    fun feed(samples: ShortArray, count: Int = samples.size): State {
        if (state == State.DONE || state == State.NO_SPEECH || count <= 0) return state
        val db = dbfs(samples, count)
        val ms = count * 1000 / sampleRate
        elapsedMs += ms
        level = ((db + 60f) / 50f).coerceIn(0f, 1f)
        // The room's noise: the quietest recent level (follows slowly upwards, at once downwards).
        floorDb = when {
            floorDb.isNaN() -> db
            db < floorDb -> db
            state == State.WAITING -> floorDb + (db - floorDb) * 0.02f
            else -> floorDb
        }
        val loud = db > max(floorDb + marginDb, MIN_SPEECH_DB)
        when (state) {
            State.WAITING -> {
                speechMs = if (loud) speechMs + ms else 0
                if (speechMs >= START_MS) {
                    state = State.SPEAKING
                    quietMs = 0
                } else if (elapsedMs >= noSpeechMs) {
                    state = State.NO_SPEECH
                }
            }
            State.SPEAKING -> {
                quietMs = if (loud) 0 else quietMs + ms
                if (quietMs >= endSilenceMs || elapsedMs >= maxMs) state = State.DONE
            }
            else -> Unit
        }
        return state
    }

    companion object {
        /** Speech needs this much sound to start (a few frames, not one click). */
        private const val START_MS = 60

        /** Never call anything quieter than this speech, even in a silent room. */
        private const val MIN_SPEECH_DB = -50f

        fun dbfs(samples: ShortArray, count: Int): Float {
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i] / 32768.0
                sum += s * s
            }
            val rms = sqrt(sum / count)
            return if (rms <= 1e-6) -120f else (20 * log10(rms)).toFloat()
        }
    }
}
