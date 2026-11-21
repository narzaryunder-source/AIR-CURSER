package com.aircursor.cloudpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * html-serve.py: a web page you made (an HTML game) opens in Air, served on this phone only under a
 * secret path – nothing else of the PC can be read through it.
 */
class HtmlServeTest {
    private fun project(): File {
        var d = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    private fun status(url: String): Pair<Int, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.responseCode to c.getHeaderField("Content-Type")
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun servesOnlyTheGamesFolderUnderASecretPath() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("python3 is needed for this test", python != null)
        val tmp = Files.createTempDirectory("html-serve").toFile()
        val game = File(tmp, "My Game").apply { mkdirs() }
        File(game, "index.html").writeText("<!doctype html><script type=module src=game.js></script>")
        File(game, "game.js").writeText("console.log(1)")
        File(tmp, "secret.txt").writeText("not for the page")
        val pb = ProcessBuilder(python!!, "-I", File(project(), "ubuntu/html-serve.py").path, File(game, "index.html").path)
        pb.environment().putAll(mapOf("AIRCURSOR_PRINT_ONLY" to "1", "AIRCURSOR_HTML_STATE" to File(tmp, "state").path,
            "AIRCURSOR_HTML_IDLE" to "3"))
        val p = pb.redirectErrorStream(true).start()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        val url = p.inputStream.bufferedReader().readText().trim()
        assertTrue("an address on this phone only: $url", Regex("""http://127\.0\.0\.1:\d+/[\w-]{20,}/index\.html""").matches(url))
        val base = url.removeSuffix("/index.html")
        val root = base.substringBeforeLast('/')
        assertEquals(200, status(url).first)
        assertEquals(200 to "text/javascript", status("$base/game.js"))
        assertEquals("no secret path", 404, status("$root/index.html").first)
        assertEquals("wrong secret path", 404, status("$root/wrong/index.html").first)
        assertEquals("nothing outside the game's folder", 404, status("$base/%2e%2e/secret.txt").first)
        tmp.deleteRecursively()
    }
}
