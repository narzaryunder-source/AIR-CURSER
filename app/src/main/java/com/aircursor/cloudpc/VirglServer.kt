package com.aircursor.cloudpc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * "Phone GPU" for AirCursor PC: the VirGL server (Termux build of virglrenderer-android) runs as a
 * normal Android program and draws with the phone's own OpenGL ES driver. Games inside the PC talk
 * to it through a socket in the PC's /tmp (Mesa's "virpipe" driver). Switched on in AirBoost.
 */
object VirglServer {
    private const val TAG = "VirglServer"
    const val GUEST_SOCKET = "/tmp/.virgl_test"
    @Volatile private var process: Process? = null

    fun available(c: Context) = File(c.applicationInfo.nativeLibraryDir, "libvirgl_server.so").exists()

    fun running() = process?.isAlive == true

    private fun socket(c: Context) = File(CloudPcEngine.rootfs(c), GUEST_SOCKET.removePrefix("/"))

    /** Starts the server (once). Returns false when this phone cannot run it. */
    @Synchronized
    fun start(c: Context): Boolean {
        if (running()) return true
        if (!available(c)) return false
        return try {
            val lib = c.applicationInfo.nativeLibraryDir
            val sock = socket(c)
            sock.parentFile?.mkdirs()
            sock.delete()
            val log = File(CloudPcEngine.rootfs(c).parentFile, "virgl.log")
            val pb = ProcessBuilder("$lib/libvirgl_server.so", "--socket-path", sock.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(log)
            pb.environment()["LD_LIBRARY_PATH"] = lib
            pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
            process = pb.start()
            // Ready when the socket exists (normally well under a second).
            for (i in 0 until 40) {
                if (sock.exists() || process?.isAlive != true) break
                Thread.sleep(100)
            }
            val ok = process?.isAlive == true && sock.exists()
            if (!ok) {
                Log.w(TAG, "VirGL server did not start: ${log.takeIf { it.exists() }?.readText()?.take(400)}")
                stop(c)
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            false
        }
    }

    @Synchronized
    fun stop(c: Context) {
        process?.let {
            it.destroy()
            try {
                if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        process = null
        socket(c).delete()
    }

    /** Tells the PC which graphics to use for games ("virgl" or "cpu"). */
    fun writeMode(c: Context, on: Boolean) {
        try {
            CloudPcEngine.writeGuestFile(
                CloudPcEngine.rootfs(c).toPath(), "etc/cloudpc/gpu-mode", if (on && running()) "virgl\n" else "cpu\n",
            )
        } catch (_: Exception) {
        }
    }
}
