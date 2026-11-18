package com.aircursor.cloudpc

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The other phone's pointer over a dock-like strip (raised, inside a horizontal scroll view, like
 * the real dock): its clicks, buttons and wheel must reach the phone's views under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteCursorTest {
    private class Scene {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        var clicks = 0
        var desktopTouches = 0
        var scrolls = 0
        val icon = View(activity).apply {
            setOnClickListener { clicks++ }
            setOnGenericMotionListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_SCROLL) scrolls++; true }
        }
        val desktop = View(activity).apply {
            setOnTouchListener { _, _ -> desktopTouches++; true }
        }
        val dock = FrameLayout(activity).apply {
            background = ColorDrawable(0x99202020.toInt())
            elevation = 8f
            val row = LinearLayout(activity).apply { addView(icon, LinearLayout.LayoutParams(100, 100)) }
            addView(HorizontalScrollView(activity).apply { addView(row) }, FrameLayout.LayoutParams(-2, -1))
        }
        val cursor: RemoteCursor

        init {
            root.addView(desktop, FrameLayout.LayoutParams(-1, -1))
            root.addView(dock, FrameLayout.LayoutParams(400, 100).apply { leftMargin = 300; topMargin = 800 })
            // Full-screen see-through layer, like the one that holds Air windows.
            root.addView(FrameLayout(activity), FrameLayout.LayoutParams(-1, -1))
            cursor = RemoteCursor(activity, root, { null }, { x, y ->
                x >= dock.left && x < dock.right && y >= dock.top && y < dock.bottom
            })
            root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 1000)
        }

        /** Pointer to (x, y): starts in the middle, moves are scaled by width / 420. */
        fun pointTo(x: Float, y: Float) {
            cursor.apply(Cmd.Move(0f, 0f))
            val k = 1000 / 470f
            cursor.apply(Cmd.Move((x - 500f) / k, (y - 500f) / k))
            idle()
        }

        fun idle() = shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun clickReachesDockIcon() {
        val s = Scene()
        s.pointTo(350f, 850f)
        s.cursor.apply(Cmd.Click('l'))
        s.idle()
        assertEquals("click on the icon", 1, s.clicks)
        assertEquals("nothing for the PC picture", 0, s.desktopTouches)
    }

    @Test
    fun leftButtonDownUpReachesDockIcon() {
        val s = Scene()
        s.pointTo(350f, 850f)
        s.cursor.apply(Cmd.Button('l', true))
        s.cursor.apply(Cmd.Button('l', false))
        s.idle()
        assertEquals(1, s.clicks)
    }

    @Test
    fun wheelReachesViewUnderPointer() {
        val s = Scene()
        s.pointTo(350f, 850f)
        s.cursor.apply(Cmd.Wheel(1, 0))
        s.idle()
        assertTrue("scroll event on the icon", s.scrolls > 0)
    }

    @Test
    fun aClickInAPageNextToAButtonStaysInThePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        var buttonClicks = 0
        var pageTouches = 0
        // A page (one leaf view, like the browser's picture) with a toolbar button right above it.
        val window = FrameLayout(activity).apply { background = ColorDrawable(0xFF101010.toInt()) }
        val button = View(activity).apply { setOnClickListener { buttonClicks++ } }
        val page = View(activity).apply { setOnTouchListener { _, _ -> pageTouches++; true } }
        window.addView(button, FrameLayout.LayoutParams(100, 40).apply { leftMargin = 300; topMargin = 0 })
        window.addView(page, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 40 })
        root.addView(window, FrameLayout.LayoutParams(800, 600).apply { leftMargin = 100; topMargin = 100 })
        val cursor = RemoteCursor(activity, root, { null }, { x, y -> x >= 100 && x < 900 && y >= 100 && y < 700 })
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 1000)
        // Tip on the page, 3 px under the button.
        cursor.apply(Cmd.Move(0f, 0f))
        val k = 1000 / 470f
        cursor.apply(Cmd.Move((450f - 500f) / k, (143f - 500f) / k))
        cursor.apply(Cmd.Click('l'))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the button next to the tip is not clicked", 0, buttonClicks)
        assertTrue("the page got the tap", pageTouches > 0)
    }
}
