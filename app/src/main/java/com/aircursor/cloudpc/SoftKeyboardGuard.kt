package com.aircursor.cloudpc

import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * While another phone is the keyboard, this phone's own on-screen keyboard must never pop up
 * (tapping a text box in Air or the PC used to open it over the work).
 *
 * The window is marked "does not use the input method" (FLAG_ALT_FOCUSABLE_IM): Android then does
 * not show the keyboard for it, whoever asks (our text boxes, the web page, the system). The other
 * phone's keys do not need it: they reach the views as key events.
 *
 * The one exception is the password screen: the other phone does not type there, so the phone's
 * own keyboard works for it.
 */
class SoftKeyboardGuard(private val window: Window) {
    private var otherPhone = false
    private var passwordScreen = false

    /** The phone's own keyboard is kept away right now. */
    val blocking: Boolean get() = otherPhone && !passwordScreen

    fun otherPhoneIsKeyboard(on: Boolean) {
        otherPhone = on
        apply()
    }

    fun passwordScreen(on: Boolean) {
        passwordScreen = on
        apply()
    }

    private fun apply() {
        if (blocking) {
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            // A keyboard that is already up goes away now.
            try {
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            } catch (_: Exception) {
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }
}
