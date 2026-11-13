package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputTest {
    private fun roundTrip(c: Cmd) = assertEquals(c, RemoteInput.parse(RemoteInput.encode(c)))

    @Test
    fun commandsRoundTrip() {
        roundTrip(Cmd.Move(12f, -3.5f))
        roundTrip(Cmd.Button('l', true))
        roundTrip(Cmd.Button('r', false))
        roundTrip(Cmd.Click('m'))
        roundTrip(Cmd.Wheel(3, -1))
        roundTrip(Cmd.Key(Keysyms.RETURN))
        roundTrip(Cmd.KeyState(Keysyms.CONTROL_L, true))
        roundTrip(Cmd.KeyState(Keysyms.CONTROL_L, false))
        roundTrip(Cmd.Text("नमस्ते hello 👋\nline"))
        roundTrip(Cmd.Ping)
    }

    @Test
    fun hello() {
        assertEquals("123456", RemoteInput.parseHello(RemoteInput.hello("123456")))
        assertNull(RemoteInput.parseHello("HELLO 2 123456"))
        assertNull(RemoteInput.parseHello("HELLO 1 12345"))
        assertNull(RemoteInput.parseHello("HELLO 1 12a456"))
        assertNull(RemoteInput.parseHello("GET / HTTP/1.1"))
    }

    @Test
    fun rejectsBadInput() {
        for (bad in listOf("", "X", "M 1", "M NaN 1", "M 99999 0", "B x d", "B l z", "C", "W 500 0",
            "K 5", "K -1", "KD abc", "T", "T !!!notbase64", "P extra")) {
            assertNull(bad, RemoteInput.parse(bad))
        }
    }

    @Test
    fun accelerationGrowsWithSpeed() {
        assertEquals(10f, RemoteInput.accelerate(10f, 50f), 0.001f)
        assertTrue(RemoteInput.accelerate(10f, 800f) > 10f)
        assertEquals(26f, RemoteInput.accelerate(10f, 5000f), 0.001f)
    }

    @Test
    fun codeIsSixDigits() {
        val c = RemoteInput.newCode()
        assertEquals(6, c.length)
        assertTrue(c.all(Char::isDigit))
    }
}
