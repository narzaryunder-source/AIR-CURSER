package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PairingGateTest {
    @Test
    fun rightCodePassesAndResetsTheCount() {
        val g = PairingGate({ "123456" }, {}, wrongPauseMs = 0)
        assertFalse(g.check("000000"))
        assertEquals(1, g.wrongInARow)
        assertTrue(g.check("123456"))
        assertEquals(0, g.wrongInARow)
    }

    @Test
    fun fiveWrongCodesMakeANewCode() {
        var code = "111111"
        var renewed = 0
        val g = PairingGate({ code }, { renewed++; code = "222222" }, wrongPauseMs = 0)
        repeat(4) { assertFalse(g.check("999999")) }
        assertEquals(0, renewed)
        assertFalse(g.check("999999"))
        assertEquals(1, renewed)
        assertFalse("the old code no longer works", g.check("111111"))
        assertTrue(g.check("222222"))
    }

    @Test
    fun aWrongCodeCostsAPauseAndARightOneDoesNot() {
        val slept = ArrayList<Long>()
        val g = PairingGate({ "123456" }, {}, wrongPauseMs = 1000, sleep = { slept += it })
        g.check("123456")
        assertTrue(slept.isEmpty())
        g.check(null)   // not even a hello line
        g.check("654321")
        assertEquals(listOf(1000L, 1000L), slept)
    }

    @Test
    fun parallelGuessesAreCheckedOneAtATime() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        var renewed = 0
        val g = PairingGate({ "123456" }, { renewed++ }, wrongPauseMs = 5, sleep = {
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            Thread.sleep(it)
            inside.decrementAndGet()
        })
        val start = CountDownLatch(1)
        val threads = (1..20).map { i ->
            Thread {
                start.await()
                g.check("%06d".format(i))
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals("never two guesses at the same time", 1, most.get())
        assertEquals("20 wrong guesses = 4 new codes", 4, renewed)
    }
}
