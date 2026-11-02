package com.aircursor.core

/**
 * Game controller (Bluetooth / USB, connected to the phone) → keys in AirCursor PC.
 * The PC cannot see the phone's controller directly, so every button becomes a key that is held
 * as long as the button is held – this works in every game and in TS Station.
 *
 *  D-pad / left stick → arrow keys      A → Enter        B → Esc
 *  X → Space                            Y → F            L1 / R1 → Q / E
 *  L2 / R2 → Z / X                      Start → Menu     Select → F1
 *
 * Key codes are Android's KeyEvent constants (copied here so the mapping stays testable).
 */
object GamepadKeys {
    const val KEY_DPAD_UP = 19
    const val KEY_DPAD_DOWN = 20
    const val KEY_DPAD_LEFT = 21
    const val KEY_DPAD_RIGHT = 22
    const val KEY_DPAD_CENTER = 23
    const val KEY_BUTTON_A = 96
    const val KEY_BUTTON_B = 97
    const val KEY_BUTTON_X = 99
    const val KEY_BUTTON_Y = 100
    const val KEY_BUTTON_L1 = 102
    const val KEY_BUTTON_R1 = 103
    const val KEY_BUTTON_L2 = 104
    const val KEY_BUTTON_R2 = 105
    const val KEY_BUTTON_START = 108
    const val KEY_BUTTON_SELECT = 109

    const val MENU = 0xff67

    /** Keysym for a controller button, or null for buttons left to Android (Home, stick clicks). */
    fun forButton(keyCode: Int): Int? = when (keyCode) {
        KEY_DPAD_UP -> Keysyms.UP
        KEY_DPAD_DOWN -> Keysyms.DOWN
        KEY_DPAD_LEFT -> Keysyms.LEFT
        KEY_DPAD_RIGHT -> Keysyms.RIGHT
        KEY_DPAD_CENTER, KEY_BUTTON_A -> Keysyms.RETURN
        KEY_BUTTON_B -> Keysyms.ESCAPE
        KEY_BUTTON_X -> ' '.code
        KEY_BUTTON_Y -> 'f'.code
        KEY_BUTTON_L1 -> 'q'.code
        KEY_BUTTON_R1 -> 'e'.code
        KEY_BUTTON_L2 -> 'z'.code
        KEY_BUTTON_R2 -> 'x'.code
        KEY_BUTTON_START -> MENU
        KEY_BUTTON_SELECT -> Keysyms.F1
        else -> null
    }

    /**
     * Arrow keys that should be held for a stick / hat position (x, y in -1..1, y down = +1).
     * A small dead zone keeps a resting stick from drifting.
     */
    fun forStick(x: Float, y: Float, dead: Float = 0.5f): Set<Int> {
        val keys = HashSet<Int>(2)
        if (x <= -dead) keys += Keysyms.LEFT
        if (x >= dead) keys += Keysyms.RIGHT
        if (y <= -dead) keys += Keysyms.UP
        if (y >= dead) keys += Keysyms.DOWN
        return keys
    }

    /** Analog triggers (pressed past half way) → the same keys as the L2 / R2 buttons. */
    fun forTriggers(left: Float, right: Float): Set<Int> {
        val keys = HashSet<Int>(2)
        if (left >= 0.5f) keys += 'z'.code
        if (right >= 0.5f) keys += 'x'.code
        return keys
    }
}
