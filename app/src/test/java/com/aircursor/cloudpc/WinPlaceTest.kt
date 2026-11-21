package com.aircursor.cloudpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * win-place.py decides what happens to a new PC window under the phone's menu bar (Mac style).
 * The OpenCode Desktop case from a real screen: 1287 px wide on a 1604 px screen, title bar at
 * the very top (under the menu bar) – it must open full size.
 */
class WinPlaceTest {
    private fun project(): File {
        var d = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    /** Runs plan() for each case (a Python dict literal) and returns its answers, one per line. */
    private fun plans(vararg cases: String): List<String> {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("python3 is needed for this test", python != null)
        val script = File(project(), "ubuntu/win-place.py")
        val code = """
            import importlib.util, sys
            spec = importlib.util.spec_from_file_location("wp", sys.argv[1])
            wp = importlib.util.module_from_spec(spec); spec.loader.exec_module(wp)
            base = dict(x=2, y=30, w=1287, h=700, frame=(2, 2, 30, 2), kind="normal", transient=False, fixed=False, states=set())
            for case in sys.argv[2:]:
                win = dict(base); win.update(eval(case))
                print(wp.plan(win, (1604, 720), 30, 80))
        """.trimIndent()
        val p = ProcessBuilder(listOf(python!!, "-I", "-c", code, script.path) + cases).redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText().trim().lines()
        assertEquals(out.joinToString("\n"), 0, p.exitValue())
        return out
    }

    @Test
    fun bigAppWindowUnderTheMenuBarOpensFullSize() {
        assertEquals(listOf("('max',)"), plans("{}"))
    }

    @Test
    fun smallWindowUnderTheMenuBarMovesBelowIt() {
        assertEquals(listOf("('move', 100, 30)", "('move', 0, 30)"), plans(
            "dict(x=102, y=-10, w=600, h=400)",
            "dict(x=-200, y=0, w=600, h=400)",
        ))
    }

    @Test
    fun windowsThatCannotOrShouldNotGrowOnlyMove() {
        assertEquals(listOf("('move', 0, 30)", "('move', 0, 30)", "('move', 0, 30)"), plans(
            "dict(fixed=True)",
            "dict(kind='dialog', w=900, h=500, y=0)",
            "dict(transient=True, w=900, h=500, y=0)",
        ))
    }

    /** OpenCode's file chooser was 822 px tall on a 720 px screen: its Open / Cancel buttons were off it. */
    @Test
    fun aDialogTallerThanTheScreenIsMadeToFit() {
        // Free height 720 - 30 (menu bar) - 80 (dock) = 610; minus its frame (30 + 2) = 578.
        assertEquals(listOf("('fit', 235, 30, 1130, 578)", "('fit', 156, 30, 1287, 578)"), plans(
            "dict(kind='dialog', x=200, y=29, w=1130, h=822)",
            "dict(transient=True)",
        ))
    }

    @Test
    fun windowsThatAreFineAreLeftAlone() {
        assertEquals(List(6) { "None" }, plans(
            "dict(y=260, w=600, h=300)",                                   // below the menu bar, fits
            "dict(states={'MAXIMIZED_VERT', 'MAXIMIZED_HORZ'})",            // already full size
            "dict(states={'FULLSCREEN'}, y=0)",                            // a full-screen video or game
            "dict(states={'HIDDEN'})",                                     // minimised
            "dict(kind='other', y=-20)",                                   // splash, menu, panel
            "dict(y=60, w=1000, h=500)",                                   // title bar right below the menu bar
        ))
    }
}
