package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The other phone's link over a real local socket: moves go out at once, order is kept. */
class RemoteLinkTest {
    private class FakePc {
        val server = ServerSocket(0)
        val lines = LinkedBlockingQueue<Pair<Long, Cmd>>()
        init {
            Thread {
                val s = server.accept()
                s.tcpNoDelay = true
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                r.readLine()   // HELLO
                s.getOutputStream().write("OK Test|v1\n".toByteArray()); s.getOutputStream().flush()
                while (true) {
                    val l = r.readLine() ?: break
                    RemoteInput.parse(l)?.let { lines.put(System.nanoTime() to it) }
                }
            }.start()
        }
    }

    private fun connect(pc: FakePc): RemoteLink {
        val link = RemoteLink("127.0.0.1", pc.server.localPort, "123456")
        val ok = CountDownLatch(1)
        link.open { paired, _ -> if (paired) ok.countDown() }
        assertTrue("paired", ok.await(5, TimeUnit.SECONDS))
        return link
    }

    @Test
    fun aMoveArrivesAtOnce() {
        val pc = FakePc()
        val link = connect(pc)
        // Several single moves, spaced like touch events: each must arrive within a few ms.
        val delays = ArrayList<Long>()
        repeat(20) {
            val t = System.nanoTime()
            link.move(3f, 0f)
            val (at, cmd) = pc.lines.poll(1, TimeUnit.SECONDS)!!
            assertTrue(cmd is Cmd.Move)
            delays += (at - t) / 1_000_000
            Thread.sleep(16)
        }
        link.close()
        val worst = delays.max()
        assertTrue("every move within 5 ms (worst $worst ms, all $delays)", worst <= 5)
    }

    @Test
    fun movesAndClicksKeepTheirOrder() {
        val pc = FakePc()
        val link = connect(pc)
        link.move(1f, 0f)
        link.move(1f, 0f)
        link.send(Cmd.Click('l'))
        link.move(5f, 0f)
        link.send(Cmd.Key(Keysyms.RETURN))
        val got = ArrayList<Cmd>()
        while (got.size < 4) got += pc.lines.poll(2, TimeUnit.SECONDS)!!.second
        link.close()
        // The two first moves may arrive merged or apart; what matters: total before the click.
        val beforeClick = got.takeWhile { it !is Cmd.Click }.filterIsInstance<Cmd.Move>().sumOf { it.dx.toDouble() }
        assertEquals(2.0, beforeClick, 0.001)
        val afterClick = got.dropWhile { it !is Cmd.Click }
        assertEquals(Cmd.Click('l'), afterClick[0])
        assertEquals(Cmd.Move(5f, 0f), afterClick[1])
        assertEquals(Cmd.Key(Keysyms.RETURN), afterClick[2])
    }

    @Test
    fun aFastFlickIsNotLost() {
        val pc = FakePc()
        val link = connect(pc)
        repeat(500) { link.move(1f, 2f) }
        link.send(Cmd.Click('l'))
        var dx = 0.0
        var dy = 0.0
        while (true) {
            val c = pc.lines.poll(2, TimeUnit.SECONDS)!!.second
            if (c is Cmd.Click) break
            c as Cmd.Move
            dx += c.dx; dy += c.dy
        }
        link.close()
        assertEquals(500.0, dx, 0.01)
        assertEquals(1000.0, dy, 0.01)
    }
}
