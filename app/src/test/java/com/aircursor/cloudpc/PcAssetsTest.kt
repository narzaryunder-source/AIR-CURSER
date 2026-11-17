package com.aircursor.cloudpc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every PC file the app reads from its assets (asset(c, "name")) must be in the build's copy list:
 * a missing one stops the desktop from starting ("Desktop error: cloudpc/<name>").
 */
class PcAssetsTest {
    private fun project(): File {
        var d = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    @Test
    fun everyAssetTheAppReadsIsPackaged() {
        val root = project()
        val tools = File(root, "app/src/main/java/com/aircursor/cloudpc/CloudPcTools.kt").readText()
        val build = File(root, "app/build.gradle.kts").readText()
        val used = Regex("""asset\(c, "([^"$]+)"\)""").findAll(tools).map { it.groupValues[1] }.toSet()
        assertTrue("found the assets the app reads", used.isNotEmpty())
        val ubuntuList = Regex("""from\(rootProject\.file\("ubuntu"\)\) \{\s*include\(([^)]*)\)""").find(build)!!.groupValues[1]
        val packaged = Regex(""""([^"]+)"""").findAll(ubuntuList).map { it.groupValues[1] }.toSet()
        for (name in used) {
            if (name.startsWith("tsstation/")) continue   // TS Station has its own copy rule
            assertTrue("$name is read by CloudPcTools but not in the build's copy list", name in packaged)
            assertTrue("ubuntu/$name exists", File(root, "ubuntu/$name").exists())
        }
    }
}
