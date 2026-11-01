package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class TarExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Files.readString/writeString are not in Android's java.nio (unit tests compile against it).
    private fun read(p: Path) = String(Files.readAllBytes(p))
    private fun write(p: Path, text: String) {
        Files.write(p, text.toByteArray())
    }

    /** Minimal tar writer for the tests (ustar headers, GNU long names, pax). */
    private class TarBuilder {
        val out = ByteArrayOutputStream()

        fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "", mode: Int = 420): TarBuilder {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            fun octal(off: Int, len: Int, v: Long) = put(off, v.toString(8).padStart(len - 1, '0'))
            put(0, name.take(100))
            octal(100, 8, mode.toLong())
            octal(108, 8, 0)
            octal(116, 8, 0)
            octal(124, 12, data.size.toLong())
            octal(136, 12, 0)
            h[156] = type.code.toByte()
            put(157, link.take(100))
            put(257, "ustar")
            put(263, "00")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, sum.toString(8).padStart(6, '0'))
            h[154] = 0
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
            return this
        }

        fun file(name: String, text: String, mode: Int = 420) = entry(name, '0', text.toByteArray(), mode = mode)
        fun dir(name: String, mode: Int = 493) = entry(name, '5', mode = mode)
        fun symlink(name: String, target: String) = entry(name, '2', link = target)
        fun hardlink(name: String, target: String) = entry(name, '1', link = target)
        fun longName(name: String) = entry("././@LongLink", 'L', (name + "\u0000").toByteArray())
        fun pax(vararg kv: Pair<String, String>): TarBuilder {
            val body = StringBuilder()
            for ((k, v) in kv) {
                val rec = " $k=$v\n"
                var len = rec.length + 1
                while ((len.toString() + rec).length != len) len = (len.toString() + rec).length
                body.append(len).append(rec)
            }
            return entry("PaxHeaders/x", 'x', body.toString().toByteArray())
        }

        fun bytes(): ByteArray {
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }

    private fun extract(root: Path, tar: ByteArray): TarExtractor =
        TarExtractor(root).also { it.extract(ByteArrayInputStream(tar)) }

    @Test
    fun unpacksFilesFoldersAndLinks() {
        val root = tmp.newFolder("rootfs").toPath()
        val longPath = "usr/share/" + "a".repeat(120) + "/file.txt"
        val tar = TarBuilder()
            .dir("./")
            .dir("./usr/")
            .dir("./usr/bin/")
            .file("./usr/bin/hello", "#!/bin/sh\necho hi\n", mode = 493)
            .symlink("./bin", "usr/bin")
            .symlink("./etc/resolv.conf", "../run/systemd/resolve/stub-resolv.conf")
            .hardlink("./usr/bin/hello2", "./usr/bin/hello")
            .longName(longPath).file("ignored-short-name", "long")
            .pax("path" to "usr/share/pax-name.txt").file("short", "pax")
            .entry("./dev/null", '3')
            .bytes()
        val x = extract(root, tar)

        assertEquals("#!/bin/sh\necho hi\n", read(root.resolve("usr/bin/hello")))
        assertTrue(Files.isExecutable(root.resolve("usr/bin/hello")))
        assertTrue(Files.isSymbolicLink(root.resolve("bin")))
        assertEquals("usr/bin", Files.readSymbolicLink(root.resolve("bin")).toString())
        assertTrue(Files.isSymbolicLink(root.resolve("etc/resolv.conf")))
        assertEquals("#!/bin/sh\necho hi\n", read(root.resolve("usr/bin/hello2")))
        assertEquals("long", read(root.resolve(longPath)))
        assertEquals("pax", read(root.resolve("usr/share/pax-name.txt")))
        assertFalse(Files.exists(root.resolve("dev/null"), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, x.skipped)
    }

    @Test
    fun neverWritesOutsideTheFolder() {
        val base = tmp.newFolder("base").toPath()
        val root = base.resolve("rootfs")
        val outside = tmp.newFolder("phone").toPath()
        write(outside.resolve("photo.jpg"), "precious")
        val tar = TarBuilder()
            .file("../evil.txt", "x")
            .file("/etc/../../evil2.txt", "x")
            .symlink("escape", outside.toString())
            .file("escape/photo.jpg", "overwritten!")
            .file("escape/new.txt", "x")
            .bytes()
        extract(root, tar)
        assertEquals("precious", read(outside.resolve("photo.jpg")))
        assertFalse(Files.exists(outside.resolve("new.txt")))
        assertFalse(Files.exists(base.resolve("evil.txt")))
        assertFalse(Files.exists(tmp.root.toPath().resolve("evil2.txt")))
    }

    @Test
    fun readOnlyFoldersStillGetTheirFiles() {
        val root = tmp.newFolder("ro").toPath()
        val tar = TarBuilder()
            .dir("locked/", mode = 365) // r-xr-xr-x
            .file("locked/inside.txt", "ok")
            .bytes()
        extract(root, tar)
        assertEquals("ok", read(root.resolve("locked/inside.txt")))
    }

    @Test
    fun damagedArchiveIsReported() {
        val root = tmp.newFolder("bad").toPath()
        val tar = TarBuilder().file("a.txt", "hello").bytes()
        tar[10] = 'Z'.code.toByte() // breaks the header checksum
        try {
            extract(root, tar)
            throw AssertionError("expected an error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Broken"))
        }
    }

    @Test
    fun safeDeleteRemovesLinksButNotWhatTheyPointTo() {
        val base = tmp.newFolder("cloudpc").toPath()
        val outside = tmp.newFolder("phone2").toPath()
        write(outside.resolve("keep.txt"), "keep")
        val root = base.resolve("rootfs")
        Files.createDirectories(root.resolve("home"))
        Files.createSymbolicLink(root.resolve("home/phone"), outside)
        Files.createSymbolicLink(root.resolve("photo-link"), outside.resolve("keep.txt"))
        write(root.resolve("home/a.txt"), "a")
        SafeFiles.deleteTree(root, base)
        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", read(outside.resolve("keep.txt")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun safeDeleteRefusesFoldersOutsideCloudPc() {
        val base = tmp.newFolder("cloudpc2").toPath()
        val other = tmp.newFolder("elsewhere").toPath()
        SafeFiles.deleteTree(other, base)
    }

    @Test
    fun prootCommandRunsInsideUbuntuWithCleanEnvironment() {
        val p = ProotPaths("/apk/lib", "/data/cpc/rootfs", "/data/cpc/tmp", "/data/cpc/lib", "/data/cpc/proc")
        val a = ProotCommand.args(p, "apt-get update", listOf("stat"))
        assertEquals("/apk/lib/libproot.so", a.first())
        assertTrue("--rootfs=/data/cpc/rootfs" in a)
        assertTrue("--bind=/data/cpc/proc/stat:/proc/stat" in a)
        assertEquals(listOf("/bin/bash", "-lc", "apt-get update"), a.takeLast(3))
        assertTrue(a.indexOf("-i") == a.indexOf("/usr/bin/env") + 1)
        val env = ProotCommand.hostEnv(p, noSeccomp = true)
        assertEquals("/apk/lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertTrue(env["LD_LIBRARY_PATH"]!!.startsWith("/data/cpc/lib"))
    }
}
