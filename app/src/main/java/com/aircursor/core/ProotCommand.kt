package com.aircursor.core

/** Folders the Cloud PC engine uses (all inside the app's private storage, except [nativeLibDir]). */
data class ProotPaths(
    /** Where Android unpacked libproot.so & co. (the only place an app may start programs from). */
    val nativeLibDir: String,
    val rootfs: String,
    /** Scratch folder for proot itself. */
    val tmpDir: String,
    /** Holds the libtalloc.so.2 -> libtalloc.so link that proot needs. */
    val libLinkDir: String,
    /** Replacement files for /proc entries Android does not let apps read. */
    val fakeProcDir: String,
)

/** Builds the proot command line that runs a shell command inside the Ubuntu files. */
object ProotCommand {
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** /proc files that Android hides from apps; a fake copy is bound over them when unreadable. */
    val FAKE_PROC = listOf(
        "loadavg", "stat", "uptime", "version", "vmstat",
        "sys/kernel/cap_last_cap", "sys/fs/inotify/max_user_watches",
    )

    fun args(
        p: ProotPaths,
        command: String,
        fakeProc: Collection<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> {
        val a = ArrayList<String>()
        a += "${p.nativeLibDir}/libproot.so"
        a += "--kill-on-exit"
        a += "--link2symlink"
        a += "--sysvipc"
        a += "-0"
        a += "--rootfs=${p.rootfs}"
        a += "--cwd=/root"
        a += "--bind=/dev"
        a += "--bind=/dev/urandom:/dev/random"
        a += "--bind=/proc"
        a += "--bind=/proc/self/fd:/dev/fd"
        a += "--bind=/proc/self/fd/0:/dev/stdin"
        a += "--bind=/proc/self/fd/1:/dev/stdout"
        a += "--bind=/proc/self/fd/2:/dev/stderr"
        a += "--bind=/sys"
        a += "--bind=${p.rootfs}/tmp:/dev/shm"
        for (f in fakeProc) a += "--bind=${p.fakeProcDir}/$f:/proc/$f"
        // A clean environment inside Ubuntu (nothing from Android leaks in).
        a += "/usr/bin/env"
        a += "-i"
        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "PATH" to GUEST_PATH,
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "DEBIAN_FRONTEND" to "noninteractive",
        )
        env.putAll(extraEnv)
        for ((k, v) in env) a += "$k=$v"
        a += "/bin/bash"
        a += "-lc"
        a += command
        return a
    }

    /** Environment for the proot process itself (outside Ubuntu). */
    fun hostEnv(p: ProotPaths, noSeccomp: Boolean): Map<String, String> {
        val e = linkedMapOf(
            "PROOT_TMP_DIR" to p.tmpDir,
            "PROOT_LOADER" to "${p.nativeLibDir}/libproot-loader.so",
            "LD_LIBRARY_PATH" to "${p.libLinkDir}:${p.nativeLibDir}",
        )
        if (noSeccomp) e["PROOT_NO_SECCOMP"] = "1"
        return e
    }

    /** Fake /proc contents (same idea as proot-distro). */
    fun fakeProcContent(name: String): String = when (name) {
        "loadavg" -> "0.12 0.07 0.02 2/165 765\n"
        "stat" -> "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
            "cpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n" +
            "ctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n" +
            "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n"
        "uptime" -> "124.08 932.80\n"
        "version" -> "Linux version 6.2.1-CloudPC (cloudpc@aircursor) (gcc 12) #1 SMP PREEMPT_DYNAMIC\n"
        "vmstat" -> "nr_free_pages 136029\nnr_zone_inactive_anon 196699\nnr_zone_active_anon 2\n" +
            "nr_zone_inactive_file 0\nnr_zone_active_file 0\nnr_zone_unevictable 0\nnr_mlock 0\n" +
            "pgpgin 890508\npgpgout 0\npswpin 0\npswpout 0\npgfault 176973\npgmajfault 488\n"
        "sys/kernel/cap_last_cap" -> "40\n"
        "sys/fs/inotify/max_user_watches" -> "4096\n"
        else -> ""
    }
}
