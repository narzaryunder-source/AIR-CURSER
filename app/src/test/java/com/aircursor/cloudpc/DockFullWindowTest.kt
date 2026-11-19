package com.aircursor.cloudpc

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A full-size Air window reaches the bottom of the screen: the dock steps aside (no strip of desktop
 * under the window) and the PC gets no bottom margin; the dock comes back when the window shrinks,
 * and from the bottom edge while it is full size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-land")
class DockFullWindowTest {
    private class Scene {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val commands = ArrayList<String>()
        var full = false
        val shell: AirShell
        val looper = shadowOf(Looper.getMainLooper())

        init {
            val rootfs = CloudPcEngine.rootfs(activity)
            File(rootfs, "root/.config/xfce4").mkdirs()
            File(rootfs, "root/.config/xfce4/aircursor-style").writeText("mac\n")
            File(rootfs, "root/.cache/cloudpc-shell").mkdirs()
            File(rootfs, "root/.cache/cloudpc-shell/apps.tsv").writeText(
                "cloudpc-youtube.desktop\tYouTube\t/usr/share/applications/cloudpc-youtube.desktop\t\t\tAudioVideo\t\n",
            )
            activity.setContentView(root)
            shell = AirShell(activity, root, object : AirShell.Actions {
                override fun runPc(cmd: String) { commands += cmd }
                override fun openAir(url: String?) = Unit
                override fun airWindows(): com.aircursor.air.AirWindows? = null
                override fun desktopSize() = 1280 to 640
                override fun showToolbar() = Unit
                override fun keyboard() = Unit
                override fun lock() = Unit
                override fun assistant() = Unit
                override fun boostMenu() = Unit
                override fun exit() = Unit
                override fun tour() = Unit
                override fun phoneKeyboard() = Unit
                override fun monitor() = Unit
                override fun changed() = Unit
                override fun fullWindow() = full
            })
            settle()
        }

        fun settle() {
            repeat(3) { looper.idleFor(Duration.ofMillis(1200)) }
            looper.idle()
        }

        /** The dock box: the parent of the horizontal scroll view that holds the app icons. */
        fun dock(): View {
            fun find(v: View): HorizontalScrollView? {
                if (v is HorizontalScrollView) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            return find(root)!!.parent as View
        }

        /** The last bottom margin the PC was told about. */
        fun bottomMargin(): Int? = commands.lastOrNull { it.contains("margin_bottom") }
            ?.substringAfter("margin_bottom --create -t int -s ")?.trim()?.toIntOrNull()
    }

    @Test
    fun dockStepsAsideForAFullWindowAndComesBack() {
        val s = Scene()
        assertEquals("dock shows on the empty desktop", View.VISIBLE, s.dock().visibility)
        val withDock = s.bottomMargin()
        assertTrue("the PC keeps the dock's space free: $withDock", (withDock ?: 0) > 0)

        s.full = true
        s.shell.windowsChanged()
        s.settle()
        assertEquals("dock is hidden under a full window", View.GONE, s.dock().visibility)
        assertEquals("nothing is kept free at the bottom any more", 0, s.bottomMargin())

        s.full = false
        s.shell.windowsChanged()
        s.settle()
        assertEquals("dock is back when the window is not full size", View.VISIBLE, s.dock().visibility)
        assertEquals("its space is kept free again", withDock, s.bottomMargin())
    }

    @Test
    fun theBottomEdgeBringsTheDockBackOverAFullWindow() {
        val s = Scene()
        s.full = true
        s.shell.windowsChanged()
        s.settle()
        assertFalse(s.dock().isShown)

        s.shell.pointerAt(s.root.height.toFloat())   // the other phone's pointer reaches the bottom edge
        s.looper.idleFor(Duration.ofMillis(400))
        assertTrue("the dock came up", s.dock().isShown)
        assertEquals("a full window still reaches the bottom", 0, s.bottomMargin())
    }

    @Test
    fun theUsersAutoHideChoiceIsNotChangedByFullWindows() {
        val s = Scene()
        val prefs = s.activity.getSharedPreferences("air_shell", android.content.Context.MODE_PRIVATE)
        s.full = true
        s.shell.windowsChanged()
        s.settle()
        s.full = false
        s.shell.windowsChanged()
        s.settle()
        assertFalse("still off: the dock was only hidden for the window", prefs.getBoolean("dock_autohide", false))
    }
}
