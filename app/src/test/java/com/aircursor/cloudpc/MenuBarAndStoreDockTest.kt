package com.aircursor.cloudpc

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The real phone-drawn menu bar and dock (AirShell): window buttons for the window in front, the
 * Air Store from the dock's "+", and the Air Store's "Add to dock".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-land")
class MenuBarAndStoreDockTest {
    private lateinit var activity: Activity
    private lateinit var rootfs: File
    private lateinit var root: FrameLayout
    private val commands = ArrayList<String>()
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun start(apps: String) {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        rootfs = CloudPcEngine.rootfs(activity)
        File(rootfs, "root/.config/xfce4").mkdirs()
        File(rootfs, "root/.config/xfce4/aircursor-style").writeText("mac\n")
        File(rootfs, "root/.cache/cloudpc-shell").mkdirs()
        File(rootfs, "root/.cache/cloudpc-shell/apps.tsv").writeText(apps)
        File(rootfs, "tmp").mkdirs()
        root = FrameLayout(activity)
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
        settle()
    }

    private fun settle(rounds: Int = 4) {
        repeat(rounds) { looper.idleFor(Duration.ofMillis(1100)) }
        looper.idle()
    }

    private fun views(v: View = root, out: MutableList<View> = ArrayList()): MutableList<View> {
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
        return out
    }

    private fun byLabel(label: String) = views().firstOrNull { it.contentDescription == label }

    private fun dockNames() = views().filter { it.parent?.parent is android.widget.HorizontalScrollView }
        .mapNotNull { it.contentDescription?.toString() }

    /** Writes a PC file with a newer time than before (the shell looks at file times). */
    private fun write(rel: String, text: String) {
        val f = File(rootfs, rel)
        val before = f.lastModified()
        f.writeText(text)
        f.setLastModified(maxOf(System.currentTimeMillis(), before + 2000))
    }

    private val opencode = "cloudpc-ai-opencode.desktop\tOpenCode\t/usr/share/applications/cloudpc-ai-opencode.desktop\t\topencode\tDevelopment\t\n"
    private val youtube = "cloudpc-youtube.desktop\tYouTube\t/usr/share/applications/cloudpc-youtube.desktop\t\t\tAudioVideo\t\n"

    @Test
    fun windowButtonsAppearForTheWindowInFrontAndActOnIt() {
        start(opencode)
        assertFalse("no window: no buttons", byLabel("Full size on / off")?.isShown ?: false)

        // OpenCode's window is in front (the PC's window list).
        write("tmp/cloudpc-windows", "active\t0x1e00003\n0x1e00003\topencode\tOpenCode\tOpenCode\n")
        settle(2)
        for (label in listOf("Close the window", "Minimise the window", "Full size on / off")) {
            assertTrue("$label is shown", byLabel(label)?.isShown == true)
        }
        commands.clear()
        byLabel("Full size on / off")!!.performClick()
        byLabel("Minimise the window")!!.performClick()
        byLabel("Close the window")!!.performClick()
        assertEquals(
            listOf(
                "python3 -I /usr/local/lib/cloudpc/win-place.py --toggle-max 0x1e00003",
                "xdotool windowminimize 0x1e00003",
                "python3 -I /usr/local/lib/cloudpc/win-place.py --close 0x1e00003",
            ),
            commands,
        )

        // Back on the empty desktop: the buttons go away.
        write("tmp/cloudpc-windows", "active\t0x1200004\n")
        settle(2)
        assertFalse("no window: no buttons", byLabel("Full size on / off")?.isShown ?: false)
    }

    @Test
    fun thePcLearnsTheMenuBarHeightForNewWindows() {
        start(opencode)
        val cmd = commands.last { it.contains("margin_top") }
        val top = cmd.substringAfter("margin_top --create -t int -s ").substringBefore(";").trim().toInt()
        val bottom = cmd.substringAfter("margin_bottom --create -t int -s ").substringBefore(";").trim().toInt()
        assertTrue("the menu bar takes space: $cmd", top > 0)
        assertTrue("win-place.py reads the same numbers: $cmd", cmd.endsWith("printf '%s %s\\n' $top $bottom > /tmp/cloudpc-margins"))
    }

    @Test
    fun aWindowIdThatIsNotAnIdGivesNoCommand() {
        assertNull(PcWindowCommands.close("0x1; rm -rf /"))
        assertNull(PcWindowCommands.toggleFullSize("\$(reboot)"))
        assertNull(PcWindowCommands.minimize(""))
        assertNull(PcWindowCommands.closeAll(listOf("x", "0x1 0x2")))
        assertEquals("python3 -I /usr/local/lib/cloudpc/win-place.py --close 0x1 0xabc",
            PcWindowCommands.closeAll(listOf("0x1", "bad", "0xabc")))
    }

    @Test
    fun thePlusOffersTheAirStore() {
        start(opencode)
        byLabel("Add an app to the dock")!!.performClick()
        looper.idle()
        val store = byLabel("Get more apps from the Air Store")
        assertTrue("the add list has an Air Store button", store?.isShown == true)
        commands.clear()
        store!!.performClick()
        looper.idle()
        assertEquals(listOf("airstore"), commands)
        assertTrue("the list closed", views().none { it is android.widget.GridView })
    }

    @Test
    fun theAirStoresAddToDockPutsTheAppInTheDock() {
        start(youtube + opencode)
        assertTrue("OpenCode is not in the dock yet: ${dockNames()}", "OpenCode" !in dockNames())
        val request = File(rootfs, "root/.cache/cloudpc-shell/dock-add")
        request.writeText("cloudpc-ai-opencode.desktop\n../../etc/passwd\n")
        settle(2)
        assertTrue("OpenCode is in the dock now: ${dockNames()}", "OpenCode" in dockNames())
        assertFalse("the request was taken", request.exists())
        assertTrue("adding opened nothing: $commands", commands.none { it.contains("opencode") })

        // A just-installed app that is not listed yet: added once the apps list has it.
        commands.clear()
        request.writeText("gimp.desktop\n")
        settle(2)
        assertTrue("not listed yet, so not in the dock: ${dockNames()}", "GIMP" !in dockNames())
        assertTrue("the PC was asked for a new apps list: $commands", commands.any { it.contains("shell-index.py") })
        write("root/.cache/cloudpc-shell/apps.tsv",
            youtube + opencode + "gimp.desktop\tGIMP\t/usr/share/applications/gimp.desktop\t\tgimp\tGraphics\t\n",
        )
        settle(2)
        assertTrue("GIMP is in the dock now: ${dockNames()}", "GIMP" in dockNames())
    }
}
