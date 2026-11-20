package com.aircursor.cloudpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the OpenCode and Antigravity launchers (as the PC does) with stand-in programs. */
class AgentLaunchersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun script(field: String): String {
        val f = CloudPcTools::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(CloudPcTools) as String
    }

    /** Runs [field]'s script with /root moved into the test folder and [programs] on the PATH. */
    private fun run(field: String, vararg programs: String): Pair<String, File> {
        val root = tmp.newFolder()
        val bin = tmp.newFolder()
        val log = File(root, "ran.txt")
        for (p in programs) {
            File(bin, p).apply {
                writeText("#!/bin/bash\necho \"\$PWD|\$*|\$BROWSER|\${PATH%%:*}\" > '${log.path}'\n")
                setExecutable(true)
            }
        }
        val file = File(root, "launcher").apply {
            writeText(script(field).replace("/root", root.path))
            setExecutable(true)
        }
        val syntax = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        assertEquals(syntax.inputStream.bufferedReader().readText(), 0, syntax.waitFor())
        val pb = ProcessBuilder("bash", file.path, "hello").redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
        pb.environment()["TERM"] = "dumb"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out to log
    }

    @Test
    fun openCodeStartsInProjectsWithItsServerTiedToTheWindow() {
        val (out, log) = run("OPENCODE", "opencode")
        assertTrue(out, out.contains("Free AI models work without an account"))
        val (pwd, args, browser, firstPath) = log.readText().trim().split("|")
        assertTrue(pwd, pwd.endsWith("/Projects"))
        assertEquals("--standalone hello", args)
        assertEquals("cloudpc-open-on-phone", browser)
        assertEquals("/usr/local/lib/cloudpc/phone-browser", firstPath)
    }

    @Test
    fun antigravityStartsAgyWithSignInOnThePhone() {
        val (out, log) = run("ANTIGRAVITY", "agy")
        assertTrue(out, out.contains("sign in with your Google account"))
        val (pwd, args, browser, firstPath) = log.readText().trim().split("|")
        assertTrue(pwd, pwd.endsWith("/Projects"))
        assertEquals("hello", args)
        assertEquals("cloudpc-open-on-phone", browser)
        assertEquals("/usr/local/lib/cloudpc/phone-browser", firstPath)
    }

    @Test
    fun notInstalledPointsToTheAirStore() {
        val (out, log) = run("OPENCODE")
        assertTrue(out, out.contains("OpenCode is not installed yet: Air Store → AI & Coding → OpenCode → Install."))
        assertTrue(!log.exists())
        val (out2, _) = run("ANTIGRAVITY")
        assertTrue(out2, out2.contains("Antigravity CLI is not installed yet: Air Store → AI & Coding → Antigravity CLI → Install."))
    }
}
