package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcHelpersTest {
    @Test
    fun calculatorBasics() {
        assertEquals("7", Calc.evaluate("3+4"))
        assertEquals("14", Calc.evaluate("2+3×4"))
        assertEquals("20", Calc.evaluate("(2+3)×4"))
        assertEquals("2.5", Calc.evaluate("5÷2"))
        assertEquals("-3", Calc.evaluate("-5+2"))
        assertEquals("0.5", Calc.evaluate("50%"))
        assertEquals("0.3", Calc.evaluate("0.1+0.2"))
    }

    @Test
    fun calculatorRejectsBadInput() {
        assertNull(Calc.evaluate(""))
        assertNull(Calc.evaluate("3+"))
        assertNull(Calc.evaluate("5÷0"))
        assertNull(Calc.evaluate("(2+3"))
        assertNull(Calc.evaluate("1..2"))
        assertNull(Calc.evaluate("abc"))
    }

    @Test
    fun uniqueNamesNeverOverwrite() {
        assertEquals("a.txt", SafeNames.unique("a.txt", listOf("b.txt")))
        assertEquals("a (2).txt", SafeNames.unique("a.txt", listOf("a.txt")))
        assertEquals("a (3).txt", SafeNames.unique("a.txt", listOf("A.TXT", "a (2).txt")))
        assertEquals("folder (2)", SafeNames.unique("folder", listOf("folder")))
    }

    @Test
    fun cleanNames() {
        assertEquals("a_b", SafeNames.clean("a/b"))
        assertEquals("Untitled", SafeNames.clean("   "))
    }

    @Test
    fun keyMouseAccelerates() {
        assertTrue(KeyMouse.step(10, 1f) > KeyMouse.step(0, 1f))
        assertEquals(40f, KeyMouse.step(1000, 1f), 0f)
    }
}
