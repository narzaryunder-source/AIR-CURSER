package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadKeysTest {
    @Test
    fun buttonsBecomeKeys() {
        assertEquals(Keysyms.RETURN, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_A))
        assertEquals(Keysyms.ESCAPE, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_B))
        assertEquals(' '.code, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_X))
        assertEquals('f'.code, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_Y))
        assertEquals(Keysyms.LEFT, GamepadKeys.forButton(GamepadKeys.KEY_DPAD_LEFT))
        assertEquals(GamepadKeys.MENU, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_START))
        assertEquals(Keysyms.F1, GamepadKeys.forButton(GamepadKeys.KEY_BUTTON_SELECT))
        assertNull(GamepadKeys.forButton(110)) // BUTTON_MODE (Home) stays with Android
    }

    @Test
    fun stickHasDeadZoneAndDiagonals() {
        assertTrue(GamepadKeys.forStick(0.2f, -0.3f).isEmpty())
        assertEquals(setOf(Keysyms.RIGHT), GamepadKeys.forStick(0.9f, 0.1f))
        assertEquals(setOf(Keysyms.LEFT, Keysyms.UP), GamepadKeys.forStick(-0.8f, -0.7f))
        assertEquals(setOf(Keysyms.DOWN), GamepadKeys.forStick(0f, 1f))
    }

    @Test
    fun triggersPastHalfWay() {
        assertTrue(GamepadKeys.forTriggers(0.3f, 0.4f).isEmpty())
        assertEquals(setOf('z'.code, 'x'.code), GamepadKeys.forTriggers(0.6f, 1f))
    }
}
