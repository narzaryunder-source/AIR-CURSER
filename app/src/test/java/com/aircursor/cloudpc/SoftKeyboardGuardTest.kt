package com.aircursor.cloudpc

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** While another phone is the keyboard, this phone's own keyboard is kept away (except for the password). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoftKeyboardGuardTest {
    private val flag = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

    private fun Activity.keyboardKeptAway() = (window.attributes.flags and flag) != 0

    @Test
    fun keyboardIsKeptAwayOnlyWhileAnotherPhoneIsConnected() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val g = SoftKeyboardGuard(a.window)
        assertFalse("nothing connected: the phone's keyboard works", a.keyboardKeptAway())
        assertFalse(g.blocking)

        g.otherPhoneIsKeyboard(true)
        assertTrue("connected: the phone's keyboard may not open", a.keyboardKeptAway())
        assertTrue(g.blocking)

        g.otherPhoneIsKeyboard(false)
        assertFalse("disconnected: the phone's keyboard works again", a.keyboardKeptAway())
    }

    @Test
    fun thePasswordScreenStillGetsThePhonesKeyboard() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val g = SoftKeyboardGuard(a.window)
        g.otherPhoneIsKeyboard(true)

        g.passwordScreen(true)
        assertFalse("the other phone does not type the password", a.keyboardKeptAway())
        assertFalse(g.blocking)

        g.passwordScreen(false)
        assertTrue("password screen closed: kept away again", a.keyboardKeptAway())
    }

    @Test
    fun otherWindowFlagsAreLeftAlone() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val g = SoftKeyboardGuard(a.window)
        g.otherPhoneIsKeyboard(true)
        g.otherPhoneIsKeyboard(false)
        assertTrue((a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
    }
}
