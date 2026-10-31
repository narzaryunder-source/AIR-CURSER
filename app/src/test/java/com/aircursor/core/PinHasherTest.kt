package com.aircursor.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun rightPinVerifies() {
        val enc = PinHasher.encode("2468")
        assertTrue(PinHasher.verify("2468", enc))
        assertFalse(PinHasher.verify("2469", enc))
        assertFalse(PinHasher.verify("", enc))
    }

    @Test
    fun pinIsNotStoredInPlainTextAndIsSalted() {
        val a = PinHasher.encode("1234")
        val b = PinHasher.encode("1234")
        assertFalse(a.contains("1234"))
        assertNotEquals(a, b)
    }

    @Test
    fun brokenStoredValueNeverVerifies() {
        assertFalse(PinHasher.verify("1234", null))
        assertFalse(PinHasher.verify("1234", "v1:x:y:z"))
        assertFalse(PinHasher.verify("1234", "nonsense"))
    }

    @Test
    fun pinRules() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("12345678"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("123456789"))
        assertFalse(PinHasher.isValidPin("12a4"))
    }
}
