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
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

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
        // Fast way: the ready-made PC (desktop, browser and tools already inside).
        if (!CloudPcEngine.isReady(c) && installImage()) return
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

    // ---- The ready-made PC ----

    /** The ready-made PC that GitHub offers (built by .github/workflows/pc-image.yml). */
    data class Image(val version: Int, val file: String, val url: String, val size: Long, val sha256: String, val unpacked: Long)

    private fun fetchImage(): Image? = try {
        val conn = URL(IMAGE_MANIFEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode != 200) null else {
                val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                Image(
                    j.getInt("version"), j.getString("file"), j.getString("url"),
                    j.getLong("size"), j.getString("sha256"), j.getLong("unpacked"),
                ).takeIf { it.file.matches(Regex("""aircursor-pc-[0-9]+-arm64\.tar\.xz""")) && it.url.startsWith("https://github.com/") }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        CloudPcBus.line("Ready-made PC not available (${e.message}) – installing step by step.")
        null
    }

    /**
     * Installs the ready-made PC: one download (resumable), SHA-256 check, unpack. Replaces a base
     * system whose desktop never finished; personal files found in it are kept in "Old files".
     * Returns false (nothing changed) when there is no image or not enough space – then the
     * step-by-step install runs as before.
     */
    fun installImage(): Boolean {
        val img = fetchImage() ?: return false
        val need = img.size + img.unpacked + 400_000_000L
        if (CloudPcEngine.freeBytes(c) < need) {
            CloudPcBus.line("The ready-made PC needs ${need / 1_000_000_000.0} GB free – installing step by step instead.", force = true)
            return false
        }
        val oldScale = scale
        scale = 1f
        try {
            val dl = CloudPcEngine.downloads(c).apply { mkdirs() }
            dl.listFiles()?.filter { it.name.startsWith("aircursor-pc-") && it.name != img.file }?.forEach { it.delete() }
            val file = File(dl, img.file)
            step("Downloading AirCursor PC (${img.size / 1_000_000} MB)…", 0f)
            download(img.url, file, img.size) { f -> CloudPcBus.update { it.copy(progress = f * 0.55f) } }
            step("Checking the download…", 0.55f)
            if (sha256(file) != img.sha256) {
                file.delete()
                throw IOException("The download was damaged. Please check the internet connection and tap Continue.")
            }
            checkCancel()
            step("Unpacking AirCursor PC…", 0.6f)
            val base = CloudPcEngine.base(c)
            val partial = File(base, "rootfs.partial")
            SafeFiles.deleteTree(partial.toPath(), base.toPath())
            val x = TarExtractor(partial.toPath())
            val total = file.length().toFloat()
            CountingStream(file.inputStream().buffered(1 shl 16)) { done ->
                CloudPcBus.update { it.copy(progress = 0.6f + 0.35f * (done / total).coerceIn(0f, 1f)) }
                if (cancelled) throw Cancelled()
            }.use { counted -> XZInputStream(counted).use { x.extract(it) } }
            CloudPcBus.line("${x.files} files ready")
            val rootfs = CloudPcEngine.rootfs(c)
            if (rootfs.exists()) {
                keepPersonalFiles(rootfs, partial)
                SafeFiles.deleteTree(rootfs.toPath(), base.toPath())
            }
            if (!partial.renameTo(rootfs)) throw IOException("Could not save the files (storage full?)")
            configure(rootfs.toPath())
            File(rootfs, CloudPcEngine.MARK_EXTRACTED).writeText("1\n")
            checkCancel()
            step("Starting the engine…", 0.96f)
            if (!engineWorks()) {
                CloudPcBus.line("Trying compatibility mode…")
                CloudPcEngine.setNoSeccomp(c, true)
                if (!engineWorks()) throw IOException("The engine could not start on this phone. Please send a screenshot of the log.")
            }
            File(rootfs, CloudPcEngine.MARK_READY).writeText("1\n")
            File(rootfs, CloudPcDesktop.MARK_DESKTOP).writeText("1\n")
            File(base, "image-version").writeText("${img.version}\n")
            file.delete()
            step("AirCursor PC ready", 1f)
            return true
        } finally {
            scale = oldScale
        }
    }

    /** Files the person made in an older system (Desktop, Documents…) move to "Old files" in the new one. */
    private fun keepPersonalFiles(oldRoot: File, newRoot: File) {
        val home = File(oldRoot, "root")
        val mine = listOf("Desktop", "Documents", "Downloads", "Projects", "Pictures", "Music", "Videos", "Google Drive")
        val hasFiles = mine.any { d ->
            try {
                Files.walk(File(home, d).toPath(), 4).use { s ->
                    s.anyMatch { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().endsWith(".desktop") }
                }
            } catch (_: Exception) {
                false
            }
        }
        if (!hasFiles) return
        val dest = File(newRoot, "root/Old files")
        dest.parentFile?.mkdirs()
        if (home.renameTo(dest)) CloudPcBus.line("Your earlier files were kept in the folder \"Old files\".", force = true)
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

    /** GitHub sends downloads on to a short-lived address: follow it by hand so resuming (Range) works. */
    private fun finalUrl(url: String): String {
        var u = url
        repeat(5) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = "HEAD"
            try {
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && loc != null) u = URL(URL(u), loc).toString() else return u
            } finally {
                conn.disconnect()
            }
        }
        return u
    }

    private fun downloadOnce(url0: String, dest: File, expected: Long, onProgress: (Float) -> Unit) {
        val url = if (url0.startsWith("https://github.com/")) finalUrl(url0) else url0
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

    companion object {
        const val IMAGE_MANIFEST = "https://github.com/narzaryunder-source/AIR-CURSER/releases/download/pc-image/pc-image.json"
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
