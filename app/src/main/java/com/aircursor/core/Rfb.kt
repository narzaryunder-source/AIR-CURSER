package com.aircursor.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Changed part of the screen after one server message. */
data class RfbDirty(val x: Int, val y: Int, val w: Int, val h: Int, val resized: Boolean)

/**
 * Small VNC (RFB 3.3/3.7/3.8) client: "None" security, 32-bit true colour, Raw + CopyRect
 * + DesktopSize. Enough for a desktop on the same phone (unix socket), so no compression needed.
 * One thread calls [readMessage] in a loop; input events may be sent from any thread.
 */
class RfbClient(input: InputStream, output: OutputStream) {
    private val din = DataInputStream(BufferedInputStream(input, 1 shl 16))
    private val dout = DataOutputStream(BufferedOutputStream(output, 4096))

    var width = 0
        private set
    var height = 0
        private set
    var name = ""
        private set

    /** ARGB pixels, row by row. Lock on [lock] while reading or writing. */
    var pixels = IntArray(0)
        private set
    val lock = Any()

    private var row = ByteArray(0)

    fun handshake() {
        val ver = ByteArray(12)
        din.readFully(ver)
        val v = String(ver, Charsets.US_ASCII)
        if (!v.startsWith("RFB 003.")) throw IOException("Not a VNC server: ${v.trim()}")
        val minor = v.substring(8, 11).toIntOrNull() ?: 3
        val useMinor = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        send { write("RFB 003.00$useMinor\n".toByteArray(Charsets.US_ASCII)) }
        if (useMinor >= 7) {
            val n = din.readUnsignedByte()
            if (n == 0) throw IOException("VNC refused: ${readReason()}")
            val types = ByteArray(n)
            din.readFully(types)
            if (types.none { it.toInt() == SEC_NONE }) throw IOException("VNC needs a password (unsupported)")
            send { writeByte(SEC_NONE) }
            if (useMinor >= 8) {
                val result = din.readInt()
                if (result != 0) throw IOException("VNC refused: ${readReason()}")
            }
        } else {
            val type = din.readInt()
            if (type == 0) throw IOException("VNC refused: ${readReason()}")
            if (type != SEC_NONE) throw IOException("VNC needs a password (unsupported)")
        }
        send { writeByte(1) } // ClientInit: shared
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        din.readFully(ByteArray(16)) // server pixel format (we set our own)
        val nameLen = din.readInt()
        if (nameLen < 0 || nameLen > 4096) throw IOException("Bad server name")
        val nb = ByteArray(nameLen)
        din.readFully(nb)
        name = String(nb, Charsets.UTF_8)
        resize(w, h)
        // 32 bpp, depth 24, little endian, true colour: bytes B G R X.
        send {
            writeByte(0)
            write(ByteArray(3))
            writeByte(32); writeByte(24); writeByte(0); writeByte(1)
            writeShort(255); writeShort(255); writeShort(255)
            writeByte(16); writeByte(8); writeByte(0)
            write(ByteArray(3))
        }
        val encodings = intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_CURSOR)
        send {
            writeByte(2)
            writeByte(0)
            writeShort(encodings.size)
            for (e in encodings) writeInt(e)
        }
        requestUpdate(incremental = false)
    }

    private fun readReason(): String {
        val len = din.readInt()
        if (len <= 0 || len > 4096) return "unknown"
        val b = ByteArray(len)
        din.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w
            height = h
            pixels = IntArray(w * h)
        }
        if (row.size < w * 4) row = ByteArray(w * 4)
    }

    fun requestUpdate(incremental: Boolean) = send {
        writeByte(3)
        writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
    }

    /** Buttons: 1 left, 2 middle, 4 right, 8 wheel up, 16 wheel down. */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5)
        writeByte(buttons and 0xFF)
        writeShort(x.coerceIn(0, maxOf(0, width - 1)))
        writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        writeByte(4)
        writeByte(if (down) 1 else 0)
        writeShort(0)
        writeInt(keysym)
    }

    /**
     * Reads one message from the server. Returns the changed area for a screen update
     * (and asks for the next one), or null for other messages.
     */
    fun readMessage(): RfbDirty? {
        when (val type = din.readUnsignedByte()) {
            0 -> return readUpdate()
            1 -> { // colour map: not used in true colour
                din.readUnsignedByte()
                din.readUnsignedShort()
                val n = din.readUnsignedShort()
                din.skipBytes(n * 6)
            }
            2 -> Unit // bell
            3 -> {
                din.readFully(ByteArray(3))
                val len = din.readInt()
                if (len < 0) throw IOException("Bad clipboard size")
                skipFully(len.toLong())
            }
            else -> throw IOException("Unknown VNC message $type")
        }
        return null
    }

    private fun readUpdate(): RfbDirty {
        din.readUnsignedByte()
        val n = din.readUnsignedShort()
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = 0
        var y1 = 0
        var resized = false
        for (i in 0 until n) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            when (val enc = din.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPYRECT -> {
                    val sx = din.readUnsignedShort()
                    val sy = din.readUnsignedShort()
                    copyRect(sx, sy, x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> {
                    resize(w, h)
                    resized = true
                    continue
                }
                ENC_CURSOR -> { // the hand cursor / phone draws its own pointer: skip the shape
                    skipFully(w.toLong() * h * 4 + ((w + 7) / 8).toLong() * h)
                    continue
                }
                else -> throw IOException("Unsupported VNC encoding $enc")
            }
            if (w == 0 || h == 0) continue
            x0 = minOf(x0, x)
            y0 = minOf(y0, y)
            x1 = maxOf(x1, x + w)
            y1 = maxOf(y1, y + h)
        }
        requestUpdate(incremental = true)
        return if (resized) {
            RfbDirty(0, 0, width, height, true)
        } else if (x0 == Int.MAX_VALUE) {
            RfbDirty(0, 0, 0, 0, false) // only a cursor shape: nothing to redraw
        } else {
            RfbDirty(x0, y0, x1 - x0, y1 - y0, false)
        }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val bytes = w * 4
        if (row.size < bytes) row = ByteArray(bytes)
        for (j in 0 until h) {
            din.readFully(row, 0, bytes)
            val yy = y + j
            if (yy >= height) continue
            synchronized(lock) {
                val base = yy * width
                var o = 0
                for (i in 0 until w) {
                    val xx = x + i
                    if (xx < width) {
                        pixels[base + xx] = (0xFF shl 24) or
                            ((row[o + 2].toInt() and 0xFF) shl 16) or
                            ((row[o + 1].toInt() and 0xFF) shl 8) or
                            (row[o].toInt() and 0xFF)
                    }
                    o += 4
                }
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            if (sx + w > width || sy + h > height || x + w > width || y + h > height) return
            val tmp = IntArray(w * h)
            for (j in 0 until h) System.arraycopy(pixels, (sy + j) * width + sx, tmp, j * w, w)
            for (j in 0 until h) System.arraycopy(tmp, j * w, pixels, (y + j) * width + x, w)
        }
    }

    private fun skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = din.skip(left)
            if (s <= 0) {
                din.readByte()
                left--
            } else {
                left -= s
            }
        }
    }

    private inline fun send(block: DataOutputStream.() -> Unit) {
        synchronized(dout) {
            dout.block()
            dout.flush()
        }
    }

    companion object {
        const val SEC_NONE = 1
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_CURSOR = -239

        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
    }
}

/** X11 keysyms for typing into the desktop. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9
    const val SUPER_L = 0xffeb

    /** Keysym for a typed character (Latin-1 directly, everything else as Unicode keysym). */
    fun forChar(codePoint: Int): Int = when {
        codePoint == '\n'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        else -> 0x01000000 or codePoint
    }
}
