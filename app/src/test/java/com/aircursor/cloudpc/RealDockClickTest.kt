package com.aircursor.cloudpc

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The real phone-drawn dock (AirShell) and the other phone's pointer: a click opens the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-land")
class RealDockClickTest {
    @Test
    fun aClickJustAboveTheIconOpensItToo() = pointerClickOnDockIconLaunchesTheApp(aboveDock = true)

    @Test
    fun pointerClickOnDockIconLaunchesTheApp() = pointerClickOnDockIconLaunchesTheApp(aboveDock = false)

    private fun pointerClickOnDockIconLaunchesTheApp(aboveDock: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val rootfs = CloudPcEngine.rootfs(activity)
        File(rootfs, "root/.config/xfce4").mkdirs()
        File(rootfs, "root/.config/xfce4/aircursor-style").writeText("mac\n")
        File(rootfs, "root/.cache/cloudpc-shell").mkdirs()
        File(rootfs, "root/.cache/cloudpc-shell/apps.tsv").writeText(
            "cloudpc-youtube.desktop\tYouTube\t/usr/share/applications/cloudpc-youtube.desktop\t\t\tAudioVideo\t\n",
        )
        val root = FrameLayout(activity)
        val commands = ArrayList<String>()
        activity.setContentView(root)
        AirShell(activity, root, object : AirShell.Actions {
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
        })
        val looper = shadowOf(Looper.getMainLooper())
        repeat(5) { looper.idleFor(java.time.Duration.ofMillis(1200)) }
        looper.idle()

        // The dock icon: a clickable view inside the dock that is not the dock itself.
        fun find(v: View, out: MutableList<View>) {
            if (v.hasOnClickListeners() && v !== root) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), out)
        }
        val clickable = ArrayList<View>().also { find(root, it) }
        // The dock's icons sit in a horizontal scroll view.
        val icon = clickable.lastOrNull {
            it.isShown && it.parent?.parent is android.widget.HorizontalScrollView && it.contentDescription == "YouTube"
        }
        assertTrue("the dock has an app icon (clickables: ${clickable.size})", icon != null)
        // Same hit test as the desktop screen: something drawn there that is not the PC picture.
        val cursor = RemoteCursor(activity, root, { null }, { x, y ->
            var hit = false
            for (i in root.childCount - 1 downTo 0) {
                val c = root.getChildAt(i)
                if (c is RemoteCursor || c.visibility != View.VISIBLE) continue
                if (x >= c.x && x < c.x + c.width && y >= c.y && y < c.y + c.height && (c.background != null || c.isClickable)) hit = true
            }
            hit
        })
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        looper.idle()
        // Position in the root: add up the offsets from the icon to the root.
        var ox = 0f
        var oy = 0f
        var pv: View = icon!!
        while (pv !== root) {
            ox += pv.left + pv.translationX - (pv.parent as View).scrollX
            oy += pv.top + pv.translationY - (pv.parent as View).scrollY
            pv = pv.parent as View
        }
        val cx = ox + icon.width / 2f
        // Above the dock: 4 px over its top edge, on the PC picture (any part of the arrow on the icon).
        val dockTop = oy - icon.top - (icon.parent as View).top - ((icon.parent as View).parent as View).top
        val cy = if (aboveDock) dockTop - 4f else oy + icon.height / 2f

        cursor.apply(Cmd.Move(0f, 0f))
        looper.idle()
        val k = root.width / 470f
        cursor.apply(Cmd.Move((cx - root.width / 2f) / k, (cy - root.height / 2f) / k))
        looper.idle()
        commands.clear()
        cursor.apply(Cmd.Click('l'))
        repeat(3) { looper.idleFor(java.time.Duration.ofMillis(300)) }
        assertTrue("the app was launched: $commands", commands.any { it.contains("cloudpc-youtube.desktop") })

        // The Left button (down, up) on the icon opens it too.
        commands.clear()
        cursor.apply(Cmd.Button('l', true))
        cursor.apply(Cmd.Button('l', false))
        repeat(3) { looper.idleFor(java.time.Duration.ofMillis(300)) }
        assertTrue("Left button opened the app: $commands", commands.any { it.contains("cloudpc-youtube.desktop") })
    }

    @Test
    fun thePlusInTheDockAddsAnyAppToTheDock() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val rootfs = CloudPcEngine.rootfs(activity)
        File(rootfs, "root/.config/xfce4").mkdirs()
        File(rootfs, "root/.config/xfce4/aircursor-style").writeText("mac\n")
        File(rootfs, "root/.cache/cloudpc-shell").mkdirs()
        File(rootfs, "root/.cache/cloudpc-shell/apps.tsv").writeText(
            "cloudpc-youtube.desktop\tYouTube\t/usr/share/applications/cloudpc-youtube.desktop\t\t\tAudioVideo\t\n" +
                "cloudpc-ai-opencode.desktop\tOpenCode\t/usr/share/applications/cloudpc-ai-opencode.desktop\t\t\tDevelopment\t\n",
        )
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val commands = ArrayList<String>()
        AirShell(activity, root, object : AirShell.Actions {
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
        })
        val looper = shadowOf(Looper.getMainLooper())
        repeat(5) { looper.idleFor(java.time.Duration.ofMillis(1200)) }
        looper.idle()

        fun views(v: View, out: MutableList<View> = ArrayList()): MutableList<View> {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
            return out
        }
        fun dockNames() = views(root).filter { it.parent?.parent is android.widget.HorizontalScrollView }
            .mapNotNull { it.contentDescription?.toString() }
        assertTrue("OpenCode is not in the dock yet: ${dockNames()}", "OpenCode" !in dockNames())
        val plus = views(root).firstOrNull { it.contentDescription == "Add an app to the dock" }
        assertTrue("the dock ends with +: ${dockNames()}", plus != null && dockNames().last() == "Add an app to the dock")
        plus!!.performClick()
        looper.idle()
        val grid = views(root).filterIsInstance<android.widget.GridView>().firstOrNull()
        assertTrue("the app list opened", grid != null)
        val texts = views(root).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue("it says what a tap does: $texts", "Tap an app to add it to the dock" in texts)
        val pos = (0 until grid!!.adapter.count).first { (grid.adapter.getItem(it) as? AirShell.App)?.name == "OpenCode" }
        grid.performItemClick(grid.adapter.getView(pos, null, grid), pos, pos.toLong())
        repeat(3) { looper.idleFor(java.time.Duration.ofMillis(800)) }
        assertTrue("OpenCode is in the dock now: ${dockNames()}", "OpenCode" in dockNames())
        assertTrue("adding opened nothing: $commands", commands.none { it.contains("opencode") })
    }
}
