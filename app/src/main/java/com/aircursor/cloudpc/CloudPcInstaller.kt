package com.aircursor.cloudpc

import android.content.Context
import com.aircursor.core.SafeFiles
import com.aircursor.core.TarExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Installs the built-in Ubuntu step by step:
 * download (resumable) -> SHA-256 check -> unpack -> basic setup -> engine test -> first boot (apt).
 * Each step is skipped when it was already done, so "Retry" continues where it stopped.
 */
class CloudPcInstaller(private val c: Context) {
    class Cancelled : Exception("Cancelled")

    @Volatile
    var cancelled = false

    @Volatile
    var process: Process? = null

    /** The base system is the first part of the full install: its 0..1 is shown as 0..[scale]. */
    var scale = 1f

    private fun step(title: String, progress: Float) {
        CloudPcBus.update { it.copy(title = title, progress = progress * scale) }
        CloudPcBus.line("==> $title", force = true)
    }

    private fun checkCancel() {
        if (cancelled) throw Cancelled()
    }

    fun install() {
        if (!CloudPcEngine.isSupported()) throw IOException("This phone is not 64-bit (arm64), so AirCursor PC cannot run on it.")
        val rootfs = CloudPcEngine.rootfs(c)
        if (!CloudPcEngine.isExtracted(c)) {
            val need = 3_000_000_000L
            if (CloudPcEngine.freeBytes(c) < need) {
                throw IOException("Not enough free space. AirCursor PC needs at least 3 GB free.")
            }
            val dl = CloudPcEngine.downloads(c).apply { mkdirs() }
            val tar = File(dl, "ubuntu-base.tar.gz")
            step("Downloading Ubuntu…", 0f)
            download(CloudPcEngine.ROOTFS_URL, tar, CloudPcEngine.ROOTFS_SIZE) { f ->
                CloudPcBus.update { it.copy(progress = f * 0.40f * scale) }
            }
            step("Checking the download…", 0.40f)
            if (sha256(tar) != CloudPcEngine.ROOTFS_SHA256) {
                tar.delete()
                throw IOException("The download was damaged. Please check the internet connection and try again.")
            }
            checkCancel()
            step("Unpacking Ubuntu…", 0.45f)
            val partial = File(CloudPcEngine.base(c), "rootfs.partial")
            SafeFiles.deleteTree(partial.toPath(), CloudPcEngine.base(c).toPath())
            val x = TarExtractor(partial.toPath())
            val total = tar.length().toFloat()
            CountingStream(tar.inputStream().buffered(1 shl 16)) { done ->
                CloudPcBus.update { it.copy(progress = (0.45f + 0.20f * (done / total).coerceIn(0f, 1f)) * scale) }
                if (cancelled) throw Cancelled()
            }.use { counted -> GZIPInputStream(counted, 1 shl 16).use { x.extract(it) } }
            CloudPcBus.line("${x.files} files ready")
            SafeFiles.deleteTree(rootfs.toPath(), CloudPcEngine.base(c).toPath())
            if (!partial.renameTo(rootfs)) throw IOException("Could not save the files (storage full?)")
            configure(rootfs.toPath())
            File(rootfs, CloudPcEngine.MARK_EXTRACTED).writeText("1\n")
            tar.delete()
        }
        checkCancel()

        step("Starting the engine…", 0.66f)
        if (!engineWorks()) {
            CloudPcBus.line("Trying compatibility mode…")
            CloudPcEngine.setNoSeccomp(c, true)
            if (!engineWorks()) throw IOException("The engine could not start on this phone. Please send a screenshot of the log.")
        }
        checkCancel()

        step("Setting up the system…", 0.70f)
        val script = """
            set -e
            echo "==> apt-get update"
            apt-get update
            echo "==> basic tools"
            apt-get install -y --no-install-recommends ca-certificates curl nano procps less
            echo CLOUDPC_FIRSTBOOT_OK
        """.trimIndent()
        var ok = false
        val code = CloudPcEngine.run(c, script, onProcess = { process = it }) { line ->
            if (line.contains("CLOUDPC_FIRSTBOOT_OK")) ok = true
            if (line.startsWith("==> basic tools")) CloudPcBus.update { it.copy(progress = 0.85f * scale) }
            CloudPcBus.line(line)
        }
        process = null
        checkCancel()
        if (code != 0 || !ok) throw IOException("Setup stopped (code $code). Check the internet connection and tap Continue.")
        File(rootfs, CloudPcEngine.MARK_READY).writeText("1\n")
        step("Base system ready", 1f)
    }

    private fun engineWorks(): Boolean {
        var seen = false
        val code = try {
            CloudPcEngine.run(c, "echo CLOUDPC_OK; uname -m; cat /etc/os-release | head -n 2", onProcess = { process = it }) { line ->
                if (line.trim() == "CLOUDPC_OK") seen = true
                CloudPcBus.line(line)
            }
        } catch (e: Exception) {
            CloudPcBus.line("Engine error: ${e.message}")
            -1
        }
        process = null
        return code == 0 && seen
    }

    /** Basic Ubuntu settings for running under proot on Android. */
    private fun configure(root: Path) {
        CloudPcEngine.writeGuestFile(root, "etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        CloudPcEngine.writeGuestFile(root, "etc/hosts", "127.0.0.1 localhost cloudpc\n::1 localhost ip6-localhost ip6-loopback\n")
        CloudPcEngine.writeGuestFile(root, "etc/hostname", "cloudpc\n")
        // apt cannot switch to its "_apt" user inside proot; retry flaky mobile downloads.
        CloudPcEngine.writeGuestFile(root, "etc/apt/apt.conf.d/01-cloudpc", "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
        // Phones: skip slow fsync calls while installing packages.
        CloudPcEngine.writeGuestFile(root, "etc/dpkg/dpkg.cfg.d/01-cloudpc", "force-unsafe-io\n")
        // Android network groups, so "id" and apt do not complain about unknown groups.
        val group = root.resolve("etc/group")
        if (Files.isRegularFile(group, LinkOption.NOFOLLOW_LINKS)) {
            val text = String(Files.readAllBytes(group))
            if (!text.contains("aid_inet")) {
                CloudPcEngine.writeGuestFile(root, "etc/group", text + "aid_inet:x:3003:root\naid_net_raw:x:3004:root\naid_everybody:x:9997:root\n")
            }
        }
    }

    private fun download(url: String, dest: File, expected: Long, onProgress: (Float) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, dest, expected, onProgress)
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                attempt++
                CloudPcBus.line("Download interrupted (${e.message}). Retry $attempt…")
                if (attempt >= 5) throw IOException("Download failed. Check Wi-Fi or mobile data and try again.")
                Thread.sleep(2000L * attempt)
                checkCancel()
            }
        }
    }

    private fun downloadOnce(url: String, dest: File, expected: Long, onProgress: (Float) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        if (expected > 0 && have == expected) return
        if (expected > 0 && have > expected) {
            dest.delete()
            have = 0
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            if (!append) have = 0
            val total = if (expected > 0) expected else have + conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    var lastUi = 0L
                    while (true) {
                        checkCancel()
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 200 && total > 0) {
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            lastUi = now
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (expected > 0 && dest.length() != expected) throw IOException("incomplete ${dest.length()}/$expected")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reports how many compressed bytes were read (for the unpack progress bar). */
    private class CountingStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) add(1)
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) add(r.toLong())
            return r
        }

        private fun add(n: Long) {
            count += n
            if (count - lastReport > 256 * 1024) {
                lastReport = count
                onCount(count)
            }
        }
    }
}
