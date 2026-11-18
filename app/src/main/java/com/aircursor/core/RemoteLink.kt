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
    /** Moves merged into one line until the writer takes them. */
    private class MoveBox(var dx: Float, var dy: Float)

    private val queue = LinkedBlockingQueue<Any>()
    private val lock = Object()
    private var openBox: MoveBox? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var closed = false
    var onClosed: (() -> Unit)? = null

    /** Connects and pairs on a background thread; [done] gets (paired, pc name / "NO" / null). */
    fun open(done: (Boolean, String?) -> Unit) {
        Thread({
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.soTimeout = 6000
                val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                out.write(RemoteInput.hello(code) + "\n"); out.flush()
                val answer = input.readLine() ?: ""
                if (!answer.startsWith("OK")) {
                    s.close()
                    done(false, if (answer == "NO") "NO" else null)
                    return@Thread
                }
                sock = s
                done(true, answer.removePrefix("OK").trim().ifEmpty { null })
                writeLoop(s, out)
            } catch (_: Exception) {
                if (sock == null) done(false, null)
            } finally {
                if (!closed) {
                    closed = true
                    onClosed?.invoke()
                }
            }
        }, "remote-pad").start()
    }

    private fun writeLoop(s: Socket, out: OutputStreamWriter) {
        var lastWrite = System.nanoTime()
        while (!closed) {
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
                        if (dx != 0f || dy != 0f) out.write(RemoteInput.encode(Cmd.Move(dx, dy)) + "\n")
                    }
                    is String -> out.write(item + "\n")
                }
                item = queue.poll()
            }
            out.flush()
            lastWrite = System.nanoTime()
        }
        s.close()
    }

    /** Pointer movement (touchpad points); sent at once, merged with moves not sent yet. */
    fun move(dx: Float, dy: Float) {
        if (closed) return
        synchronized(lock) {
            val b = openBox
            if (b != null) {
                b.dx += dx
                b.dy += dy
            } else {
                val nb = MoveBox(dx, dy)
                openBox = nb
                queue.offer(nb)
            }
        }
    }

    /** Any other command (keys, clicks, scrolling): after the moves before it, before the ones after. */
    fun send(c: Cmd) {
        if (closed) return
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
