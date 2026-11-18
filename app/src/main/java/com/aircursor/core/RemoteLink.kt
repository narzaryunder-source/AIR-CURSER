package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The other phone's side of "phone as keyboard & touchpad": one TCP connection, one writer thread.
 *
 * Every touchpad move is sent at once (no fixed timer: a timer that does not match the touch rate
 * sends 1 move in one packet and 2 in the next, and the pointer stutters). Moves that pile up while
 * the writer is busy are merged; keys and clicks keep their exact order with the moves around them.
 * Pure Kotlin (unit-tested with a real socket).
 */
class RemoteLink(private val host: String, private val port: Int, private val code: String) {
    /** Pointer moves (or scroll movement) merged into one line until the writer takes them. */
    private class MoveBox(val scroll: Boolean, var dx: Float, var dy: Float)

    private val queue = LinkedBlockingQueue<Any>()
    private val lock = Object()
    private var openBox: MoveBox? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var closed = false
    /** The PC phone said BYE (another phone took over, or a new code): do not come back. */
    @Volatile private var kicked = false
    private val wake = Any()
    var onClosed: (() -> Unit)? = null

    /** The link dropped (Wi-Fi hiccup) and is being made again (true), or is back (false). */
    var onReconnecting: ((Boolean) -> Unit)? = null

    /** How long a dropped link keeps trying to come back before giving up. */
    var reconnectForMs = 60_000L

    private class Paired(val socket: Socket, val out: OutputStreamWriter, val name: String?)

    /** Connects and pairs; throws on network errors, returns null when the code was refused. */
    private fun pair(): Paired? {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            s.soTimeout = 6000
            val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            out.write(RemoteInput.hello(code) + "\n"); out.flush()
            val answer = input.readLine() ?: ""
            if (!answer.startsWith("OK")) {
                s.close()
                return null
            }
            return Paired(s, out, answer.removePrefix("OK").trim().ifEmpty { null })
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** Connects and pairs on a background thread; [done] gets (paired, pc name / "NO" / null). */
    fun open(done: (Boolean, String?) -> Unit) {
        Thread({
            try {
                val first = try {
                    pair()
                } catch (_: Exception) {
                    done(false, null)
                    return@Thread
                }
                if (first == null) {
                    done(false, "NO")
                    return@Thread
                }
                sock = first.socket
                done(true, first.name)
                var out = first.out
                while (!closed) {
                    try {
                        writeLoop(sock!!, out)
                    } catch (_: Exception) {
                    }
                    if (closed || kicked) break
                    // The link dropped: what was waiting is stale now. Make the link again.
                    onReconnecting?.invoke(true)
                    synchronized(lock) { queue.clear(); openBox = null }
                    val until = System.nanoTime() + reconnectForMs * 1_000_000
                    var again: Paired? = null
                    while (!closed && again == null && System.nanoTime() < until) {
                        again = try {
                            pair() ?: break   // the code was changed on the PC: stop
                        } catch (_: Exception) {
                            Thread.sleep(500)
                            null
                        }
                    }
                    if (again == null) break
                    sock = again.socket
                    out = again.out
                    synchronized(lock) { queue.clear(); openBox = null }
                    onReconnecting?.invoke(false)
                }
            } finally {
                val wasClosed = closed
                closed = true
                try { sock?.close() } catch (_: Exception) {}
                if (!wasClosed) onClosed?.invoke()
            }
        }, "remote-pad").start()
    }

    private fun writeLoop(s: Socket, out: OutputStreamWriter) {
        // The PC phone never writes after pairing: reading tells at once when it closes the link.
        s.soTimeout = 0
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val l = r.readLine() ?: break
                    if (l == "BYE") { kicked = true; break }
                }
            } catch (_: Exception) {
            }
            try { s.close() } catch (_: Exception) {}
            queue.offer(wake)   // the writer notices at once
        }, "remote-pad-watch").apply { isDaemon = true }.start()
        var lastWrite = System.nanoTime()
        while (!closed) {
            if (s.isClosed) throw java.io.IOException("link closed")
            var item: Any? = queue.poll(1, TimeUnit.SECONDS)
            if (item == null) {
                // Keep-alive while idle (the PC phone drops a silent link after 30 s).
                if (System.nanoTime() - lastWrite > 4_000_000_000L) {
                    out.write("P\n"); out.flush()
                    lastWrite = System.nanoTime()
                }
                continue
            }
            // Everything waiting goes out in one write, in order.
            while (item != null) {
                when (item) {
                    is MoveBox -> {
                        val (dx, dy) = synchronized(lock) {
                            if (openBox === item) openBox = null
                            item.dx to item.dy
                        }
                        if (dx != 0f || dy != 0f) {
                            val c = if (item.scroll) Cmd.Scroll(dx, dy) else Cmd.Move(dx, dy)
                            out.write(RemoteInput.encode(c) + "\n")
                        }
                    }
                    is String -> out.write(item + "\n")
                    else -> if (s.isClosed) throw java.io.IOException("link closed")
                }
                item = queue.poll()
            }
            out.flush()
            lastWrite = System.nanoTime()
        }
    }

    /** Pointer movement (touchpad points); sent at once, merged with moves not sent yet. */
    fun move(dx: Float, dy: Float) = add(false, dx, dy)

    private fun add(scroll: Boolean, dx: Float, dy: Float) {
        if (closed) return
        synchronized(lock) {
            val b = openBox
            if (b != null && b.scroll == scroll) {
                b.dx += dx
                b.dy += dy
            } else {
                val nb = MoveBox(scroll, dx, dy)
                openBox = nb
                queue.offer(nb)
            }
        }
    }

    /** Any other command (keys, clicks, scrolling): after the moves before it, before the ones after. */
    fun send(c: Cmd) {
        if (closed) return
        if (c is Cmd.Scroll) return add(true, c.dx, c.dy)
        if (c is Cmd.Move) return add(false, c.dx, c.dy)
        synchronized(lock) {
            openBox = null
            queue.offer(RemoteInput.encode(c))
        }
    }

    fun close() {
        closed = true
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }
}
