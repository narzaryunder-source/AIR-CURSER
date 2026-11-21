package com.aircursor.cloudpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SidePinsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pin(n: Int) = SidePins.Pin("/usr/share/applications/app$n.desktop", "App $n", "/root/.cache/cloudpc-shell/icons/app$n.png")

    @Test
    fun pinsAreSavedAndReadBack() {
        val list = listOf(pin(1), SidePins.Pin("/usr/local/share/applications/cloudpc-ai-opencode.desktop", "OpenCode", ""))
        assertEquals(list, SidePins.decode(SidePins.encode(list)))
        assertEquals(emptyList<SidePins.Pin>(), SidePins.decode(null))
        assertEquals(emptyList<SidePins.Pin>(), SidePins.decode("not json"))
    }

    @Test
    fun addingMovesAnAppToTheEndAndKeepsAtMostEight() {
        var list = emptyList<SidePins.Pin>()
        for (n in 1..3) list = SidePins.add(list, pin(n))
        list = SidePins.add(list, pin(1))
        assertEquals(listOf("App 2", "App 3", "App 1"), list.map { it.name })
        for (n in 4..12) list = SidePins.add(list, pin(n))
        assertEquals(SidePins.MAX, list.size)
        assertEquals("the newest stays", "App 12", list.last().name)
        assertEquals(listOf("App 12"), SidePins.remove(list, pin(11).path).takeLast(1).map { it.name })
        assertTrue(SidePins.remove(list, pin(12).path).none { it.name == "App 12" })
    }

    @Test
    fun theAppListComesFromThePcsIndex() {
        val root = tmp.newFolder()
        File(root, SidePins.INDEX).apply {
            parentFile!!.mkdirs()
            writeText(
                "youtube\tYouTube\t/usr/share/applications/cloudpc-youtube.desktop\t/root/.cache/cloudpc-shell/icons/yt.png\tAir\tNetwork\t\n" +
                    "broken line\n" +
                    "airstore\tAir Store\t/usr/share/applications/airstore.desktop\t\tAirstore\tSystem\t\n",
            )
        }
        val apps = SidePins.readIndex(root)
        assertEquals(listOf("Air Store", "YouTube"), apps.map { it.name })
        assertEquals("/usr/share/applications/cloudpc-youtube.desktop", apps[1].path)
        assertEquals(emptyList<SidePins.Pin>(), SidePins.readIndex(File(root, "no-pc")))
    }

    @Test
    fun openingQuotesThePathSafely() {
        val p = SidePins.Pin("/root/Desktop/It's mine.desktop", "Mine", "")
        assertEquals("setsid gio launch '/root/Desktop/It'\\''s mine.desktop' >/dev/null 2>&1 &", SidePins.launchCommand(p))
    }
}
