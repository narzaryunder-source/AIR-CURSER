package com.aircursor.core

import java.util.Base64

/**
 * "Phone as keyboard and touchpad": a second phone (running AirCursor) sends mouse and keyboard
 * input to AirCursor PC on the first phone over the local Wi-Fi / hotspot.
 *
 * One TCP connection, one command per text line. The first line must be the pairing code that the
 * PC phone shows on its screen; anything else closes the connection. Pure Kotlin (unit-tested).
 *
 *   HELLO 1 <code>       pairing (client → host); host answers "OK <name>" or "NO"
 *   M <dx> <dy>          move the pointer (touchpad points, already accelerated)
 *   B <l|m|r> <d|u>      mouse button down / up (drag)
 *   C <l|m|r>            click
 *   W <dy> <dx>          scroll wheel steps (dy > 0 = down)
 *   K <keysym>           key press (down + up), e.g. Enter, arrows
 *   KD <keysym> / KU <keysym>   key held / released (Ctrl, Alt, Shift, Win)
 *   T <base64 utf-8>     type text
 *   P                    keep-alive
 */
object RemoteInput {
    const val SERVICE_TYPE = "_aircursorpc._tcp."
    const val PROTOCOL = 1
    const val CODE_LENGTH = 6
    private const val MAX_MOVE = 4000f
    private const val MAX_TEXT = 4000

    sealed class Cmd {
        data class Move(val dx: Float, val dy: Float) : Cmd()
        data class Button(val button: Char, val down: Boolean) : Cmd()
        data class Click(val button: Char) : Cmd()
        data class Wheel(val dy: Int, val dx: Int) : Cmd()
        data class Key(val keysym: Int) : Cmd()
        data class KeyState(val keysym: Int, val down: Boolean) : Cmd()
        data class Text(val text: String) : Cmd()
        data object Ping : Cmd()
    }

    fun hello(code: String) = "HELLO $PROTOCOL $code"

    /** The code from a HELLO line, or null when the line is not a valid HELLO for this protocol. */
    fun parseHello(line: String): String? {
        val p = line.trim().split(' ')
        if (p.size != 3 || p[0] != "HELLO" || p[1] != PROTOCOL.toString()) return null
        return p[2].takeIf { it.length == CODE_LENGTH && it.all(Char::isDigit) }
    }

    fun encode(c: Cmd): String = when (c) {
        is Cmd.Move -> "M ${fmt(c.dx)} ${fmt(c.dy)}"
        is Cmd.Button -> "B ${c.button} ${if (c.down) 'd' else 'u'}"
        is Cmd.Click -> "C ${c.button}"
        is Cmd.Wheel -> "W ${c.dy} ${c.dx}"
        is Cmd.Key -> "K ${c.keysym}"
        is Cmd.KeyState -> "${if (c.down) "KD" else "KU"} ${c.keysym}"
        is Cmd.Text -> "T " + Base64.getEncoder().encodeToString(c.text.toByteArray(Charsets.UTF_8))
        Cmd.Ping -> "P"
    }

    private fun fmt(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)

    private fun button(s: String): Char? = s.singleOrNull()?.takeIf { it == 'l' || it == 'm' || it == 'r' }

    private fun keysym(s: String): Int? = s.toIntOrNull()?.takeIf { it in 0x20..0x0110FFFF }

    /** One line from the other phone → a command, or null for anything malformed or out of range. */
    fun parse(line: String): Cmd? {
        if (line.length > MAX_TEXT * 2) return null
        val p = line.trim().split(' ')
        return try {
            when (p[0]) {
                "M" -> if (p.size != 3) null else {
                    val dx = p[1].toFloat()
                    val dy = p[2].toFloat()
                    if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > MAX_MOVE || kotlin.math.abs(dy) > MAX_MOVE) null
                    else Cmd.Move(dx, dy)
                }
                "B" -> if (p.size != 3) null else {
                    val b = button(p[1])
                    when {
                        b == null -> null
                        p[2] == "d" -> Cmd.Button(b, true)
                        p[2] == "u" -> Cmd.Button(b, false)
                        else -> null
                    }
                }
                "C" -> if (p.size != 2) null else button(p[1])?.let { Cmd.Click(it) }
                "W" -> if (p.size != 3) null else {
                    val dy = p[1].toInt()
                    val dx = p[2].toInt()
                    if (kotlin.math.abs(dy) > 50 || kotlin.math.abs(dx) > 50) null else Cmd.Wheel(dy, dx)
                }
                "K" -> if (p.size != 2) null else keysym(p[1])?.let { Cmd.Key(it) }
                "KD", "KU" -> if (p.size != 2) null else keysym(p[1])?.let { Cmd.KeyState(it, p[0] == "KD") }
                "T" -> if (p.size != 2) null else {
                    val t = String(Base64.getDecoder().decode(p[1]), Charsets.UTF_8)
                    if (t.isEmpty() || t.length > MAX_TEXT) null else Cmd.Text(t)
                }
                "P" -> if (p.size == 1) Cmd.Ping else null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Touchpad acceleration: slow finger = precise, fast finger = across the screen. */
    fun accelerate(d: Float, speedPointsPerSec: Float): Float {
        val gain = when {
            speedPointsPerSec < 150f -> 1.0f
            speedPointsPerSec > 1500f -> 2.6f
            else -> 1.0f + (speedPointsPerSec - 150f) / (1500f - 150f) * 1.6f
        }
        return d * gain
    }

    /** A fresh pairing code (6 digits). */
    fun newCode(random: java.util.Random = java.security.SecureRandom()): String =
        (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
}
