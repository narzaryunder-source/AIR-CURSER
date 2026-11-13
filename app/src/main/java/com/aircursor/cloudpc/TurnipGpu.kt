package com.aircursor.cloudpc

import android.content.Context
import com.aircursor.core.SafeFiles
import com.aircursor.core.TarExtractor
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "Snapdragon GPU" for AirCursor PC: Mesa's Turnip (Vulkan driver for Adreno GPUs) and Zink
 * (OpenGL on top of Vulkan), built by our CI (gpu/build-turnip.sh). Games in the PC then draw on
 * the phone's Adreno GPU directly through /dev/kgsl-3d0 – usually much faster than the CPU or VirGL.
 *
 * Only phones with a Snapdragon (Adreno) GPU have /dev/kgsl-3d0; Mali / PowerVR / Xclipse phones
 * (Dimensity, Exynos, Tensor, Helio) cannot use it and never see the option.
 */
object TurnipGpu {
    private const val PREFS = "cloudpc_engine"
    private const val KEY = "turnip_gpu"
    private const val BASE = "https://github.com/narzaryunder-source/AIR-CURSER/releases/download/turnip/"
    private const val FILE = "aircursor-turnip-arm64.tar.xz"
    private const val MAX_SIZE = 200L * 1024 * 1024
    const val KGSL = "/dev/kgsl-3d0"

    /** An Adreno GPU that apps may use (no root needed on Snapdragon phones). */
    fun supported(): Boolean = File(KGSL).let { it.exists() && it.canRead() && it.canWrite() }

    fun installed(c: Context) = File(CloudPcEngine.rootfs(c), "opt/aircursor-turnip/gpu.env").isFile

    fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Games use Turnip: switched on, downloaded and the phone has the GPU. */
    fun active(c: Context) = enabled(c) && installed(c) && supported()

    /** [send] = false when another setting follows that tells the running PC anyway. */
    fun setEnabled(c: Context, on: Boolean, send: Boolean = true) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (send && CloudPcBus.state.value.desktopRunning) CloudPcService.send(c, CloudPcService.ACTION_GPU)
    }

    /** Graphics for games: 0 = CPU, 1 = phone GPU through VirGL, 2 = Snapdragon GPU (Turnip). */
    fun graphics(c: Context): Int = when {
        enabled(c) && supported() -> 2
        AirBoost.phoneGpu(c) -> 1
        else -> 0
    }

    /** Sets both switches, then tells the running PC once. Turnip must be [installed] for 2. */
    fun setGraphics(c: Context, mode: Int) {
        setEnabled(c, mode == 2, send = false)
        AirBoost.setPhoneGpu(c, mode == 1)
    }

    /** Tells the PC's game launcher to use Turnip ("turnip" in /etc/cloudpc/gpu-mode). */
    fun writeMode(c: Context) {
        try {
            CloudPcEngine.writeGuestFile(CloudPcEngine.rootfs(c).toPath(), "etc/cloudpc/gpu-mode", "turnip\n")
        } catch (_: Exception) {
        }
    }

    /**
     * Downloads (about 6 MB) and unpacks the driver into the PC's /opt/aircursor-turnip, checked
     * against the published SHA-256. Runs on a background thread; [progress] gets 0..1.
     */
    fun install(c: Context, progress: (Float) -> Unit) {
        val root = CloudPcEngine.rootfs(c)
        val tmp = File(root.parentFile, "turnip-download.tar.xz")
        try {
            val want = text(BASE + "aircursor-turnip-arm64.sha256").trim().lowercase()
            if (!want.matches(Regex("[0-9a-f]{64}"))) throw IOException("No checksum for the Snapdragon GPU driver yet")
            download(BASE + FILE, tmp, progress)
            if (sha256(tmp) != want) throw IOException("The download was damaged – try again")
            val dir = File(root, "opt/aircursor-turnip")
            if (dir.exists()) SafeFiles.deleteTree(dir.toPath(), root.toPath())
            val x = TarExtractor(root.toPath())
            tmp.inputStream().buffered(1 shl 16).use { XZInputStream(it).use { s -> x.extract(s) } }
            if (!installed(c)) throw IOException("The driver package is incomplete")
        } finally {
            tmp.delete()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IOException(if (code == 404) "The Snapdragon GPU driver is not published yet" else "HTTP $code")
        }
        return conn
    }

    private fun text(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }.take(4096)
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, progress: (Float) -> Unit) {
        val conn = open(url)
        try {
            val total = conn.contentLengthLong
            if (total > MAX_SIZE) throw IOException("Unexpected size")
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done > MAX_SIZE) throw IOException("Unexpected size")
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
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
}
