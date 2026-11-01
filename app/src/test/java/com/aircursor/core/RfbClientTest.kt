package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class RfbClientTest {
    /** Everything a VNC server sends, written up front (the client never has to wait for it). */
    private fun server(w: Int, h: Int, minor: Int = 8, security: List<Int> = listOf(1), body: DataOutputStream.() -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write("RFB 003.00$minor\n".toByteArray())
            if (minor >= 7) {
                writeByte(security.size)
                security.forEach { writeByte(it) }
                if (minor >= 8) writeInt(0)
            } else {
                writeInt(1)
            }
            writeShort(w); writeShort(h)
            write(ByteArray(16))
            writeInt(4); write("test".toByteArray())
            body()
        }
        return bo.toByteArray()
    }

    private fun DataOutputStream.updateHeader(rects: Int) {
        writeByte(0); writeByte(0); writeShort(rects)
    }

    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, enc: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(enc)
    }

    /** One pixel in the format the client asked for: B G R X. */
    private fun DataOutputStream.px(rgb: Int) {
        writeByte(rgb and 0xFF); writeByte((rgb shr 8) and 0xFF); writeByte((rgb shr 16) and 0xFF); writeByte(0)
    }

    @Test
    fun handshakeAndClientSetup() {
        val out = ByteArrayOutputStream()
        val c = RfbClient(ByteArrayInputStream(server(4, 2) {}), out)
        c.handshake()
        assertEquals(4, c.width)
        assertEquals(2, c.height)
        assertEquals("test", c.name)
        val d = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val ver = ByteArray(12)
        d.readFully(ver)
        assertEquals("RFB 003.008\n", String(ver))
        assertEquals(1, d.readUnsignedByte()) // security None
        assertEquals(1, d.readUnsignedByte()) // shared
        assertEquals(0, d.readUnsignedByte()) // SetPixelFormat
        d.skipBytes(3)
        assertEquals(32, d.readUnsignedByte())
        d.skipBytes(15)
        assertEquals(2, d.readUnsignedByte()) // SetEncodings
        d.skipBytes(1)
        val n = d.readUnsignedShort()
        val enc = (0 until n).map { d.readInt() }
        assertTrue(RfbClient.ENC_RAW in enc && RfbClient.ENC_COPYRECT in enc)
        assertEquals(3, d.readUnsignedByte()) // first full update request
        assertEquals(0, d.readUnsignedByte())
    }

    @Test
    fun rawCopyRectAndResize() {
        val data = server(4, 2) {
            updateHeader(1)
            rect(1, 0, 2, 1, RfbClient.ENC_RAW)
            px(0xFF0000); px(0x00FF00)
            updateHeader(1)
            rect(0, 1, 2, 1, RfbClient.ENC_COPYRECT)
            writeShort(1); writeShort(0)
            writeByte(2) // bell: ignored
            updateHeader(1)
            rect(0, 0, 8, 3, RfbClient.ENC_DESKTOP_SIZE)
        }
        val out = ByteArrayOutputStream()
        val c = RfbClient(ByteArrayInputStream(data), out)
        c.handshake()
        val d1 = c.readMessage()!!
        assertEquals(RfbDirty(1, 0, 2, 1, false), d1)
        assertEquals(0xFFFF0000.toInt(), c.pixels[1])
        assertEquals(0xFF00FF00.toInt(), c.pixels[2])
        val d2 = c.readMessage()!!
        assertEquals(RfbDirty(0, 1, 2, 1, false), d2)
        assertEquals(0xFFFF0000.toInt(), c.pixels[4]) // copied from (1,0)
        assertEquals(0xFF00FF00.toInt(), c.pixels[5])
        assertEquals(null, c.readMessage()) // bell
        val d3 = c.readMessage()!!
        assertTrue(d3.resized)
        assertEquals(8, c.width)
        assertEquals(24, c.pixels.size)
    }

    @Test
    fun pointerAndKeysAreEncoded() {
        val out = ByteArrayOutputStream()
        val c = RfbClient(ByteArrayInputStream(server(100, 50) {}), out)
        c.handshake()
        val before = out.size()
        c.pointer(10, 999, RfbClient.BUTTON_LEFT)
        c.key(Keysyms.forChar('a'.code), true)
        val d = DataInputStream(ByteArrayInputStream(out.toByteArray(), before, out.size() - before))
        assertEquals(5, d.readUnsignedByte())
        assertEquals(1, d.readUnsignedByte())
        assertEquals(10, d.readUnsignedShort())
        assertEquals(49, d.readUnsignedShort()) // clamped to the screen
        assertEquals(4, d.readUnsignedByte())
        assertEquals(1, d.readUnsignedByte())
        d.skipBytes(2)
        assertEquals('a'.code, d.readInt())
    }

    @Test
    fun oldServersAndKeysyms() {
        val c = RfbClient(ByteArrayInputStream(server(2, 2, minor = 3) {}), ByteArrayOutputStream())
        c.handshake()
        assertEquals(2, c.width)
        assertEquals(Keysyms.RETURN, Keysyms.forChar('\n'.code))
        assertEquals(0x010020AC, Keysyms.forChar(0x20AC)) // €
        assertEquals(0xE9, Keysyms.forChar(0xE9)) // é
    }

    @Test
    fun clipboardBothWays() {
        val data = server(2, 2) {
            writeByte(3); write(ByteArray(3)); writeInt(5); write("hello".toByteArray())
        }
        val out = ByteArrayOutputStream()
        val c = RfbClient(ByteArrayInputStream(data), out)
        var got: String? = null
        c.onClipboard = { got = it }
        c.handshake()
        assertEquals(null, c.readMessage())
        assertEquals("hello", got)
        val before = out.size()
        c.clipboard("phone")
        val d = DataInputStream(ByteArrayInputStream(out.toByteArray(), before, out.size() - before))
        assertEquals(6, d.readUnsignedByte())
        d.skipBytes(3)
        assertEquals(5, d.readInt())
        val b = ByteArray(5)
        d.readFully(b)
        assertEquals("phone", String(b))
    }

    @Test(expected = IOException::class)
    fun passwordOnlyServerIsReported() {
        RfbClient(ByteArrayInputStream(server(2, 2, security = listOf(2)) {}), ByteArrayOutputStream()).handshake()
    }
}
