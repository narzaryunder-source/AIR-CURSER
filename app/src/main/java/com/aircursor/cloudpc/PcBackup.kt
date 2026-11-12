package com.aircursor.cloudpc

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup and restore of AirCursor PC: the Home folder (documents, projects, settings, browser
 * profiles) and the list of installed apps, as one .tar.gz file in a place the person picks
 * (phone storage, SD card, Google Drive…).
 *
 * The archive goes through a named pipe: tar inside the PC writes, the app copies to the chosen
 * file at the same time – no second copy on the phone. Restore reads the file into tar the same
 * way, only into /root and the app shortcuts, then installs the apps that were in the backup.
 * Run off the main thread; the desktop should be stopped first (settings are written on exit).
 */
object PcBackup {
    private const val BACKUP_PIPE = "tmp/cloudpc-backup.fifo"
    private const val RESTORE_PIPE = "tmp/cloudpc-restore.fifo"

    /** Things that are not worth saving or must not travel (caches, the Pro key, Air Drive's access). */
    private val EXCLUDE = listOf(
        "root/.cache", "root/.local/share/Trash", "root/.config/cloudpc/pro-license",
        "root/.config/cloudpc/airdrive.conf", "root/.config/cloudpc/airdrive.conf.state", "root/.npm/_cacache",
        "root/.vscode-server/data/CachedExtensionVSIXs",
    )

    class Result(val ok: Boolean, val bytes: Long, val message: String)

    fun backup(c: Context, target: Uri, progress: (Long) -> Unit, line: (String) -> Unit): Result {
        val out = c.contentResolver.openOutputStream(target, "w") ?: return Result(false, 0, "Cannot write to that place")
        val pipe = makePipe(c, BACKUP_PIPE)
        val excludes = EXCLUDE.joinToString(" ") { "--exclude='$it'" }
        val cmd = """
            mkdir -p /root/.config/cloudpc /usr/local/share/applications
            apt-mark showmanual > /root/.config/cloudpc/backup-packages.txt 2>/dev/null
            date -u +%Y-%m-%dT%H:%MZ > /root/.config/cloudpc/backup-date
            cd / && tar -czf /$BACKUP_PIPE --warning=no-file-changed --warning=no-file-removed $excludes \
                root usr/local/share/applications
            rc=${'$'}?; [ ${'$'}rc -le 1 ] && echo BACKUP_OK || echo "BACKUP_FAILED ${'$'}rc"
        """.trimIndent()
        return pump(c, cmd, pipe, line, okMark = "BACKUP_OK") { copied ->
            FileInputStream(pipe).use { input -> out.use { copy(input, it, copied, progress) } }
        }
    }

    fun restore(c: Context, source: Uri, progress: (Long) -> Unit, line: (String) -> Unit): Result {
        val input = c.contentResolver.openInputStream(source) ?: return Result(false, 0, "Cannot open that file")
        val pipe = makePipe(c, RESTORE_PIPE)
        val cmd = """
            cd / && tar -xzf /$RESTORE_PIPE --no-same-owner --warning=no-timestamp root usr/local/share/applications
            rc=${'$'}?
            if [ ${'$'}rc -ne 0 ]; then echo "RESTORE_FAILED ${'$'}rc"; exit 1; fi
            echo "Files restored. Installing the apps from the backup…"
            L=/root/.config/cloudpc/backup-packages.txt
            if [ -s "${'$'}L" ]; then
                missing=${'$'}(while read -r p; do dpkg -s "${'$'}p" >/dev/null 2>&1 || echo "${'$'}p"; done < "${'$'}L")
                if [ -n "${'$'}missing" ]; then
                    apt-get update >/dev/null 2>&1
                    avail=${'$'}(echo ${'$'}missing | xargs -r apt-cache show --no-all-versions 2>/dev/null | awk '/^Package:/ {print ${'$'}2}' | sort -u)
                    [ -n "${'$'}avail" ] && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${'$'}avail
                fi
            fi
            command -v cloudpc-apps >/dev/null 2>&1 && cloudpc-apps >/dev/null 2>&1
            echo RESTORE_OK
        """.trimIndent()
        return pump(c, cmd, pipe, line, okMark = "RESTORE_OK") { copied ->
            FileOutputStream(pipe).use { out -> input.use { copy(it, out, copied, progress) } }
        }
    }

    // ---- the pipe between the app and tar in the PC ----

    private fun makePipe(c: Context, rel: String): File {
        val f = File(CloudPcEngine.rootfs(c), rel)
        f.parentFile?.mkdirs()
        f.delete()
        Os.mkfifo(f.path, 384) // 0600
        return f
    }

    private fun pump(c: Context, cmd: String, pipe: File, line: (String) -> Unit, okMark: String,
                     transfer: (LongArray) -> Unit): Result {
        val copied = LongArray(1)
        var copyError: Exception? = null
        val copier = Thread {
            try {
                transfer(copied)
            } catch (e: Exception) {
                copyError = e
            }
        }
        var ok = false
        var last = ""
        try {
            copier.start()
            CloudPcEngine.run(c, cmd) { l ->
                if (l.contains(okMark)) ok = true
                if (l.isNotBlank()) {
                    last = l
                    line(l)
                }
            }
            // tar ended without opening the pipe (an error): free the copy side so it can finish.
            if (copier.isAlive) unblock(pipe)
            copier.join(30_000)
        } finally {
            pipe.delete()
        }
        return when {
            copyError != null -> Result(false, copied[0], copyError?.message ?: "Copy failed")
            !ok -> Result(false, copied[0], last.ifBlank { "Did not finish" })
            else -> Result(true, copied[0], "Done")
        }
    }

    private fun unblock(pipe: File) {
        try {
            // Opening the other end lets a waiting open() return; closing it ends the stream.
            Thread { try { FileOutputStream(pipe).close() } catch (_: Exception) {} }.start()
            Thread { try { FileInputStream(pipe).close() } catch (_: Exception) {} }.start()
        } catch (_: Exception) {
        }
    }

    private fun copy(input: InputStream, out: OutputStream, copied: LongArray, progress: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            copied[0] += n
            if (copied[0] - lastReport > 4L * 1024 * 1024) {
                lastReport = copied[0]
                progress(copied[0])
            }
        }
        out.flush()
        progress(copied[0])
    }
}
