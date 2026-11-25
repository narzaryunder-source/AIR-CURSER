package com.aircursor.core

import com.aircursor.core.RemoteInput.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone app's real touchpad link ([RemoteLink]) driving AirCursor for Windows (desktop/,
 * Python): pairing, pointer, clicks, keys and typed text arrive as the computer expects.
 */
class WindowsHostInteropTest {
    private fun project(): File {
        var d = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    @Test
    fun thePhonePadDrivesTheWindowsVersion() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("python3 is needed for this test", python != null)
        val p = ProcessBuilder(python!!, "-I", File(project(), "desktop/tests/harness_server.py").path)
            .redirectErrorStream(true).start()
        val lines = LinkedBlockingQueue<String>()
        Thread { p.inputStream.bufferedReader().forEachLine { lines.put(it) } }.apply { isDaemon = true }.start()
        try {
            val ready = lines.poll(15, TimeUnit.SECONDS) ?: error("the Windows host did not start")
            val (_, port, code) = ready.split(' ')
            assertTrue(ready, ready.startsWith("READY"))

            // A wrong code is refused.
            val wrong = CountDownLatch(1)
            var refused: String? = null
            RemoteLink("127.0.0.1", port.toInt(), if (code == "000000") "111111" else "000000").open { ok, name ->
                if (!ok) refused = name
                wrong.countDown()
            }
            assertTrue(wrong.await(10, TimeUnit.SECONDS))
            assertEquals("NO", refused)

            val paired = CountDownLatch(1)
            var pcName: String? = null
            val link = RemoteLink("127.0.0.1", port.toInt(), code)
            link.open { ok, name ->
                if (ok) pcName = name
                paired.countDown()
            }
            assertTrue(paired.await(10, TimeUnit.SECONDS))
            assertEquals("Windows Test|w1", pcName)
            link.send(Cmd.Move(12f, -4f))
            link.send(Cmd.Click('l'))
            link.send(Cmd.KeyState(0xffe3, true))
            link.send(Cmd.Key('c'.code))
            link.send(Cmd.KeyState(0xffe3, false))
            link.send(Cmd.Text("नमस्ते 👋"))
            link.send(Cmd.Scroll(0f, -30f))
            link.send(Cmd.ScrollEnd)
            val want = listOf(
                "CMD ('move', 12.0, -4.0)", "CMD ('click', 'l')", "CMD ('key_state', 65507, True)",
                "CMD ('key', 99)", "CMD ('key_state', 65507, False)", "CMD ('text', 'नमस्ते 👋')",
                "CMD ('scroll', 0.0, -30.0)", "CMD ('scroll_end',)",
            )
            val got = ArrayList<String>()
            while (got.size < want.size) {
                val l = lines.poll(10, TimeUnit.SECONDS) ?: break
                if (l.startsWith("CMD")) got += l
            }
            assertEquals(want, got)
            link.close()
        } finally {
            p.outputStream.close()
            p.destroy()
            p.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
