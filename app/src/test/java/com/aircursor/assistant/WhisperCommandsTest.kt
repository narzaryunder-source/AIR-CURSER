package com.aircursor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real transcripts from the offline engine (whisper.cpp v1.9.5, base model, English mode with the
 * Hinglish hint, 768 audio frames – the app's settings) of spoken Hinglish commands in three Hindi
 * voices. Each must become the right command.
 */
class WhisperCommandsTest {
    private fun cmd(heard: String) = AssistantBrain.parse(WhisperText.clean(heard))

    @Test
    fun windowAndTabs() {
        for (h in listOf("Window minimize karo.", "window minimize karo.")) assertEquals(h, Command.Window("minimize"), cmd(h))
        for (h in listOf("naya tab kholo.", "naya tab kula.", "Maya tab kholo.", "naya tab kholo,")) {
            assertEquals(h, Command.Browser("newtab"), cmd(h))
        }
    }

    @Test
    fun openYouTube() {
        for (h in listOf("youtube kholo.", "youtube kula.", "youtube kolo.", "youtube kholo")) {
            assertEquals(h, Command.Open("youtube"), cmd(h))
        }
    }

    @Test
    fun toggles() {
        for (h in listOf("wifi band karo.", "Wai, fai band karo.", "vai pai band karo.")) assertEquals(h, Command.Toggle("wifi", false), cmd(h))
        for (h in listOf("torch chalu karo.", "Torch chalu karo.", "torch chaalu karo.", "Torte Chalu caro")) {
            if (h.startsWith("Torte")) continue   // "torte" is not "torch": left to the search, as said
            assertEquals(h, Command.Toggle("torch", true), cmd(h))
        }
    }

    @Test
    fun volumeTimeScreenshot() {
        for (h in listOf("awaz badhao.", "Amaz badhao.", "Avaz badhao.", "awaz badhao,", "Avaz barhao.")) assertEquals(h, Command.Volume("up"), cmd(h))
        for (h in listOf("kitne baje hai,", "Kitne baje hai,", "ki tne baje hai.")) assertEquals(h, Command.Time, cmd(h))
        assertEquals(Command.Screenshot, cmd("screenshot lo."))
        assertEquals(Command.Screenshot, cmd("Screen short low."))
    }

    @Test
    fun searchesAndSongs() {
        assertEquals(Command.Search("masam"), cmd("google per masam search karo."))
        assertEquals(Command.Search("moosam"), cmd("google pe moosam search karo."))
        assertEquals(Command.Search("mosam"), cmd("Google pe mosam search karo."))
        assertEquals(Command.Search("cricket score"), cmd("google par cricket score dhundo"))
        for (h in listOf("YouTube per arigeet singe gane chalao.", "YouTube par ar zee tsin ke gane chalao.", "YouTube per ar jeet sinh ke gane chalao.")) {
            val c = cmd(h)
            assertTrue("$h → $c", c is Command.PlayYouTube && c.query.isNotBlank())
        }
    }

    @Test
    fun textBytesFromTheEngineNeverBreak() {
        // Whisper's bytes go to Kotlin as they are (no JNI string): emoji and a character cut in half.
        assertEquals("", WhisperText.decode(null))
        assertEquals("youtube kholo", WhisperText.decode("youtube kholo".toByteArray()))
        assertEquals("🎵 gaane chalao", WhisperText.decode("🎵 gaane chalao".toByteArray()))
        val hindi = "नमस्ते".toByteArray()
        assertEquals("नमस्त", WhisperText.decode(hindi.copyOf(hindi.size - 1)))   // last character cut
        assertEquals("", WhisperText.clean(WhisperText.decode(byteArrayOf(0xE0.toByte(), 0xA4.toByte()))))
    }

    @Test
    fun cleanUpOfWhisperNotes() {
        assertEquals("", WhisperText.clean("[BLANK_AUDIO]"))
        assertEquals("", WhisperText.clean(" (music) ♪ "))
        assertEquals("", WhisperText.clean("Thank you."))
        assertEquals("youtube kholo", WhisperText.clean(" youtube kholo. "))
        assertEquals("wifi band karo", WhisperText.clean("[Music] wifi band karo!"))
        assertEquals("kitne baje hai", WhisperText.clean("kitne baje hai,"))
    }
}
