package com.aircursor.cloudpc

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.aircursor.core.ProotCommand
import com.aircursor.core.ProotPaths
import com.aircursor.core.SafeFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * The built-in Cloud PC: Ubuntu files in the app's private storage, started with proot
 * (bundled in the APK). No Termux needed. Every phone has its own copy – nothing is shared.
 */
object CloudPcEngine {
    private const val TAG = "CloudPcEngine"
    const val UBUNTU_LABEL = "Ubuntu 24.04 LTS"
    const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val ROOTFS_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    const val ROOTFS_SIZE = 29_936_675L

    /** Inside the rootfs: written when the files are unpacked and set up / when first boot worked. */
    const val MARK_EXTRACTED = ".cloudpc-extracted"
    const val MARK_READY = ".cloudpc-ready"

    private const val PREFS = "cloudpc_engine"
    private const val KEY_NO_SECCOMP = "no_seccomp"

    fun base(c: Context) = File(c.filesDir, "cloudpc")
    fun rootfs(c: Context) = File(base(c), "rootfs")
    fun downloads(c: Context) = File(base(c), "dl")

    fun isSupported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
    fun isExtracted(c: Context) = File(rootfs(c), MARK_EXTRACTED).exists()
    fun isReady(c: Context) = File(rootfs(c), MARK_READY).exists()

    fun freeBytes(c: Context): Long = try {
        StatFs(c.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        0L
    }

    fun totalRam(c: Context): Long {
        val am = c.getSystemService(ActivityManager::class.java) ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }

    /** Space the Cloud PC uses now (its own folder only). */
    fun usedBytes(c: Context): Long {
        var sum = 0L
        try {
            Files.walk(base(c).toPath()).use { s ->
                s.forEach { p ->
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) sum += Files.size(p)
                }
            }
        } catch (e: Exception) {
        }
        return sum
    }

    fun noSeccomp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NO_SECCOMP, false)
    fun setNoSeccomp(c: Context, v: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NO_SECCOMP, v).apply()

    /** Creates the helper folders/links proot needs. Run before every start (the APK path changes on updates). */
    fun prepare(c: Context): Pair<ProotPaths, List<String>> {
        val b = base(c)
        val tmp = File(b, "tmp").apply { mkdirs() }
        val lib = File(b, "lib").apply { mkdirs() }
        val proc = File(b, "proc").apply { mkdirs() }
        val native = c.applicationInfo.nativeLibraryDir
        // proot asks for "libtalloc.so.2"; Android only ships files named lib*.so, so link it.
        val link = Paths.get(lib.absolutePath, "libtalloc.so.2")
        val target = Paths.get(native, "libtalloc.so")
        try {
            val ok = Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target
            if (!ok) {
                Files.deleteIfExists(link)
                Files.createSymbolicLink(link, target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "talloc link failed", e)
        }
        // Fake /proc files for the entries Android hides from apps.
        val fake = ArrayList<String>()
        for (name in ProotCommand.FAKE_PROC) {
            if (canRead(File("/proc/$name"))) continue
            val f = File(proc, name)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText(ProotCommand.fakeProcContent(name))
            fake += name
        }
        val paths = ProotPaths(
            nativeLibDir = native,
            rootfs = rootfs(c).absolutePath,
            tmpDir = tmp.absolutePath,
            libLinkDir = lib.absolutePath,
            fakeProcDir = proc.absolutePath,
        )
        return paths to fake
    }

    private fun canRead(f: File): Boolean = try {
        f.inputStream().use { it.read() }
        true
    } catch (e: Exception) {
        false
    }

    /** Starts [command] inside Ubuntu (bash -lc). Output (stdout + stderr) comes from the returned process. */
    fun start(c: Context, command: String): Process {
        val (paths, fake) = prepare(c)
        val args = ProotCommand.args(paths, command, fake)
        val pb = ProcessBuilder(args).redirectErrorStream(true).directory(base(c))
        val env = pb.environment()
        env.putAll(ProotCommand.hostEnv(paths, noSeccomp(c)))
        env.remove("LD_PRELOAD")
        return pb.start()
    }

    /** Runs a command and streams every output line. Returns the exit code. */
    fun run(c: Context, command: String, onProcess: (Process) -> Unit = {}, onLine: (String) -> Unit): Int {
        val p = start(c, command)
        onProcess(p)
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return p.waitFor()
    }

    /** Writes a file inside Ubuntu; a symlink at that place is replaced, never followed. */
    fun writeGuestFile(root: java.nio.file.Path, rel: String, text: String, executable: Boolean = false) {
        val target = root.resolve(rel)
        var cur = root
        for (part in rel.split('/').dropLast(1)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw java.io.IOException("Unsafe path $rel")
            if (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cur)
        }
        Files.deleteIfExists(target)
        Files.write(target, text.toByteArray())
        if (executable) target.toFile().setExecutable(true, false)
    }

    /** Deletes the built-in Ubuntu (only the app's own Cloud PC folder; phone files are never touched). */
    fun remove(c: Context) {
        val b = base(c).toPath()
        val inside = c.filesDir.toPath()
        SafeFiles.deleteTree(b, inside)
    }
}

/** What the Cloud PC screen shows. Updated from the background service. */
data class EngineState(
    val busy: Boolean = false,
    val title: String = "",
    /** 0..1, or -1 for "working…" without a known amount. */
    val progress: Float = -1f,
    val error: String? = null,
    val version: Int = 0,
    /** The desktop (Xvnc + XFCE) process is running / its screen socket is ready. */
    val desktopRunning: Boolean = false,
    val desktopReady: Boolean = false,
)

object CloudPcBus {
    private const val MAX_LINES = 400
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val pending = ArrayList<String>()
    private var lastFlush = 0L

    fun update(f: (EngineState) -> EngineState) {
        synchronized(this) { _state.value = f(_state.value) }
    }

    /** Adds a log line; flushed to the screen at most ~8 times a second (apt prints a lot). */
    fun line(s: String, force: Boolean = false) {
        synchronized(this) {
            pending += s.take(400)
            val now = System.currentTimeMillis()
            if (force || now - lastFlush > 120) flushLocked(now)
        }
    }

    fun flush() = synchronized(this) { flushLocked(System.currentTimeMillis()) }

    private fun flushLocked(now: Long) {
        if (pending.isEmpty()) return
        val all = _log.value + pending
        pending.clear()
        _log.value = if (all.size > MAX_LINES) all.takeLast(MAX_LINES) else all
        lastFlush = now
    }

    fun clearLog() = synchronized(this) {
        pending.clear()
        _log.value = emptyList()
    }
}
