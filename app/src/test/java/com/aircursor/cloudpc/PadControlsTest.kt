package com.aircursor.cloudpc

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The other phone's laptop layout: touchpad gestures, and a picture of the real screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h370dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PadControlsTest {
    private val sent = ArrayList<Cmd>()
    private var movedX = 0f
    private var moves = 0

    private fun setUp(): Pair<Activity, View> {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = PadControls(a, { sent += it }, { dx, _ -> movedX += dx; moves++ }).build("CPH2665", "Disconnect", landscape = true) {}
        a.setContentView(v)
        shadowOf(Looper.getMainLooper()).idle()
        return a to v
    }

    private fun touchpad(v: View): View {
        if (v.javaClass.simpleName == "Touchpad") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) touchpad(v.getChildAt(i)).let { if (it.javaClass.simpleName == "Touchpad") return it }
        return v
    }

    private fun ev(t0: Long, dt: Long, action: Int, vararg pts: Pair<Float, Float>): MotionEvent {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(t0, t0 + dt, action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
    }

    @Test
    fun tapClicksAndAWobbleDoesNotMoveThePointer() {
        val (_, root) = setUp()
        val pad = touchpad(root)
        val t = SystemClock.uptimeMillis()
        pad.dispatchTouchEvent(ev(t, 0, MotionEvent.ACTION_DOWN, 100f to 100f))
        pad.dispatchTouchEvent(ev(t, 40, MotionEvent.ACTION_MOVE, 102f to 101f))   // ~1 dp of finger roll
        pad.dispatchTouchEvent(ev(t, 120, MotionEvent.ACTION_UP, 102f to 101f))
        assertEquals("no pointer move for a tap", 0, moves)
        assertTrue("tap = left click: $sent", sent.contains(Cmd.Click('l')))
    }

    @Test
    fun oneFingerMovesThePointer() {
        val (_, root) = setUp()
        val pad = touchpad(root)
        val t = SystemClock.uptimeMillis()
        pad.dispatchTouchEvent(ev(t, 0, MotionEvent.ACTION_DOWN, 100f to 100f))
        for (i in 1..10) pad.dispatchTouchEvent(ev(t, i * 16L, MotionEvent.ACTION_MOVE, (100f + i * 10f) to 100f))
        pad.dispatchTouchEvent(ev(t, 200, MotionEvent.ACTION_UP, 200f to 100f))
        assertTrue("pointer moved right ($movedX)", movedX > 30f)
        assertTrue("no click after a move: $sent", sent.none { it is Cmd.Click })
    }

    @Test
    fun twoFingersScroll() {
        val (_, root) = setUp()
        val pad = touchpad(root)
        val t = SystemClock.uptimeMillis()
        pad.dispatchTouchEvent(ev(t, 0, MotionEvent.ACTION_DOWN, 100f to 100f))
        pad.dispatchTouchEvent(ev(t, 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f to 100f, 160f to 100f))
        for (i in 1..12) pad.dispatchTouchEvent(ev(t, 10 + i * 16L, MotionEvent.ACTION_MOVE, 100f to (100f - i * 12f), 160f to (100f - i * 12f)))
        pad.dispatchTouchEvent(ev(t, 220, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f to -44f, 160f to -44f))
        pad.dispatchTouchEvent(ev(t, 230, MotionEvent.ACTION_UP, 100f to -44f))
        val wheels = sent.filterIsInstance<Cmd.Wheel>()
        assertTrue("two fingers up = wheel down steps: $sent", wheels.size >= 3 && wheels.all { it.dy > 0 })
        assertTrue("no right click after scrolling: $sent", sent.none { it is Cmd.Click })
    }

    @Test
    fun pictureOfTheScreen() {
        val (a, root) = setUp()
        val w = root.width
        val h = root.height
        assertTrue("laid out ${w}x$h", w > 0 && h > 0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        a.window.decorView.draw(Canvas(bmp))
        val out = File(System.getProperty("user.dir"), "build/pad-screen.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PICTURE ${out.absolutePath} ${w}x$h")
    }
}
