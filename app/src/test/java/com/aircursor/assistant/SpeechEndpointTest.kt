package com.aircursor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** When a spoken command starts and ends, from 20 ms microphone frames. */
class SpeechEndpointTest {
    private val rnd = Random(7)

    /** 20 ms of room noise (about -55 dB) or of a voice (about -18 dB). */
    private fun frame(voice: Boolean, noise: Int = 60): ShortArray = ShortArray(320) { i ->
        val n = rnd.nextInt(-noise, noise + 1)
        val v = if (voice) (4000 * sin(2 * PI * 220 * i / 16000.0)).toInt() else 0
        (n + v).toShort()
    }

    private fun run(e: SpeechEndpoint, frames: List<Boolean>, noise: Int = 60): List<SpeechEndpoint.State> =
        frames.map { e.feed(frame(it, noise)) }

    private fun ms(n: Int, voice: Boolean) = List(n / 20) { voice }

    @Test
    fun commandAfterAPauseEndsAfterTheQuiet() {
        val e = SpeechEndpoint()
        val states = run(e, ms(600, false) + ms(1200, true) + ms(1000, false))
        assertEquals(SpeechEndpoint.State.WAITING, states[25])                 // room only
        assertEquals(SpeechEndpoint.State.SPEAKING, states[30 + 5])            // talking
        val end = states.indexOf(SpeechEndpoint.State.DONE)
        // Done about 800 ms after the last word (1.8 s in), not before.
        assertTrue("ended at frame $end", end in (1800 + 760) / 20..(1800 + 900) / 20)
    }

    @Test
    fun aShortBreathBetweenWordsDoesNotEndIt() {
        val e = SpeechEndpoint()
        val states = run(e, ms(400, false) + ms(500, true) + ms(400, false) + ms(500, true) + ms(200, false))
        assertTrue(SpeechEndpoint.State.DONE !in states)
        assertEquals(SpeechEndpoint.State.SPEAKING, states.last())
    }

    @Test
    fun nobodySpeaks() {
        val e = SpeechEndpoint()
        val states = run(e, ms(6200, false))
        assertEquals(SpeechEndpoint.State.NO_SPEECH, states.last())
    }

    @Test
    fun aClickIsNotSpeech() {
        val e = SpeechEndpoint()
        val states = run(e, ms(400, false) + ms(20, true) + ms(1000, false))
        assertTrue(SpeechEndpoint.State.SPEAKING !in states)
    }

    @Test
    fun aNoisyRoomStillWorks() {
        // Fan or traffic noise much louder than a quiet room; the voice is clearly above it.
        val e = SpeechEndpoint()
        val states = run(e, ms(600, false) + ms(1000, true) + ms(1200, false), noise = 900)
        assertTrue(SpeechEndpoint.State.SPEAKING in states)
        assertEquals(SpeechEndpoint.State.DONE, states.last())
    }

    @Test
    fun aVeryLongCommandIsCut() {
        val e = SpeechEndpoint(maxMs = 3000)
        val states = run(e, ms(200, false) + ms(4000, true))
        assertEquals(SpeechEndpoint.State.DONE, states.last())
    }
}
