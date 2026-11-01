package com.aircursor.cloudpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aircursor.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs Cloud PC jobs (install, commands) in the background with a notification, so Android
 * does not stop them when the screen turns off or the user switches apps.
 */
class CloudPcService : Service() {
    companion object {
        private const val TAG = "CloudPcService"
        private const val CHANNEL = "cloudpc"
        private const val NOTIFICATION_ID = 77
        const val ACTION_INSTALL = "com.aircursor.cloudpc.INSTALL"
        const val ACTION_RUN = "com.aircursor.cloudpc.RUN"
        const val ACTION_STOP = "com.aircursor.cloudpc.STOP"
        const val ACTION_REMOVE = "com.aircursor.cloudpc.REMOVE"
        const val ACTION_DESKTOP_INSTALL = "com.aircursor.cloudpc.DESKTOP_INSTALL"
        const val ACTION_DESKTOP_START = "com.aircursor.cloudpc.DESKTOP_START"
        const val ACTION_DESKTOP_STOP = "com.aircursor.cloudpc.DESKTOP_STOP"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        fun startDesktop(c: Context, width: Int, height: Int) {
            ContextCompat.startForegroundService(
                c,
                Intent(c, CloudPcService::class.java).setAction(ACTION_DESKTOP_START)
                    .putExtra(EXTRA_WIDTH, width).putExtra(EXTRA_HEIGHT, height),
            )
        }

        fun send(c: Context, action: String, command: String? = null) {
            val i = Intent(c, CloudPcService::class.java).setAction(action)
            if (command != null) i.putExtra(EXTRA_COMMAND, command)
            if (action == ACTION_STOP || action == ACTION_DESKTOP_STOP) {
                c.startService(i)
            } else {
                ContextCompat.startForegroundService(c, i)
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var jobs = 0
    private var desktopActive = false

    private fun stopIfIdle() {
        if (jobs == 0 && !desktopActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Volatile
    private var installer: CloudPcInstaller? = null

    @Volatile
    private var running: Process? = null

    @Volatile
    private var desktop: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopCurrent()
            if (jobs == 0 && !desktopActive) stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_DESKTOP_STOP) {
            stopDesktop()
            if (jobs == 0 && !desktopActive) stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        when (action) {
            ACTION_INSTALL -> submit("Cloud PC install") { install() }
            ACTION_RUN -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (cmd.isNotBlank()) submit(cmd.take(40)) { runCommand(cmd) }
            }
            ACTION_REMOVE -> {
                stopDesktop()
                submit("Removing") { remove() }
            }
            ACTION_DESKTOP_INSTALL -> submit("Desktop install") { installDesktop() }
            ACTION_DESKTOP_START -> startDesktop(
                intent.getIntExtra(EXTRA_WIDTH, 1280),
                intent.getIntExtra(EXTRA_HEIGHT, 720),
            )
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun submit(label: String, job: () -> Unit) {
        jobs++
        CloudPcBus.update { it.copy(busy = true, error = null, title = label, progress = -1f) }
        executor.execute {
            acquireWake()
            try {
                job()
            } catch (e: CloudPcInstaller.Cancelled) {
                CloudPcBus.line("Ruk gaya (cancelled).", force = true)
                CloudPcBus.update { it.copy(error = "Cancelled – Install dobara dabao, jahan ruka wahin se chalega.") }
            } catch (e: Throwable) {
                Log.w(TAG, "job failed", e)
                CloudPcBus.line("ERROR: ${e.message}", force = true)
                CloudPcBus.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                CloudPcBus.flush()
                releaseWake()
                ContextCompat.getMainExecutor(this).execute {
                    jobs--
                    CloudPcBus.update { it.copy(busy = jobs > 0, version = it.version + 1) }
                    stopIfIdle()
                }
            }
        }
    }

    private fun install() {
        val inst = CloudPcInstaller(this)
        installer = inst
        try {
            inst.install()
        } finally {
            installer = null
        }
    }

    private fun runCommand(cmd: String) {
        if (!CloudPcEngine.isExtracted(this)) throw IllegalStateException("Pehle Install karo.")
        CloudPcBus.line("$ $cmd", force = true)
        CloudPcBus.update { it.copy(title = "Chal raha hai: ${cmd.take(40)}", progress = -1f) }
        val code = CloudPcEngine.run(this, cmd, onProcess = { running = it }) { CloudPcBus.line(it) }
        running = null
        CloudPcBus.line("[exit $code]", force = true)
        CloudPcBus.update { it.copy(title = if (code == 0) "Ho gaya ✅" else "Khatam (code $code)") }
    }

    private fun installDesktop() {
        if (!CloudPcEngine.isReady(this)) throw IllegalStateException("Pehle Cloud PC install karo.")
        CloudPcBus.update { it.copy(title = "Desktop install ho raha hai (5-15 min, ~400 MB)…", progress = -1f) }
        var ok = false
        val code = CloudPcEngine.run(this, CloudPcDesktop.INSTALL_SCRIPT, onProcess = { running = it }) { line ->
            if (line.contains("CLOUDPC_DESKTOP_OK")) ok = true
            CloudPcBus.line(line)
        }
        running = null
        if (code != 0 || !ok) throw java.io.IOException("Desktop install ruk gaya (code $code). Internet check karke dobara dabao.")
        java.io.File(CloudPcEngine.rootfs(this), CloudPcDesktop.MARK_DESKTOP).writeText("1\n")
        CloudPcBus.line("==> Desktop ready ✅", force = true)
        CloudPcBus.update { it.copy(title = "Desktop ready ✅", progress = 1f) }
    }

    /** Starts Xvnc + XFCE as a long-running process; the viewer connects to its private socket. */
    private fun startDesktop(width: Int, height: Int) {
        if (desktopActive) return
        if (!CloudPcDesktop.isInstalled(this)) {
            CloudPcBus.update { it.copy(error = "Pehle 'Desktop install' karo.") }
            stopIfIdle()
            return
        }
        desktopActive = true
        CloudPcBus.update { it.copy(desktopRunning = true, desktopReady = false, error = null) }
        Thread({
            try {
                CloudPcDesktop.writeStartScript(this)
                CloudPcBus.line("==> Desktop start ${width}x$height", force = true)
                val code = CloudPcEngine.run(
                    this, CloudPcDesktop.startCommand(width, height), onProcess = { desktop = it },
                ) { line ->
                    if (line.contains("CLOUDPC_VNC_READY")) CloudPcBus.update { it.copy(desktopReady = true) }
                    // XFCE prints a lot of harmless warnings: keep only the important ones in the log.
                    if (line.contains("CLOUDPC") || line.contains("rror") || line.contains("Xvnc")) CloudPcBus.line(line)
                }
                CloudPcBus.line("==> Desktop band (code $code)", force = true)
            } catch (e: Throwable) {
                Log.w(TAG, "desktop failed", e)
                CloudPcBus.line("Desktop ERROR: ${e.message}", force = true)
                CloudPcBus.update { it.copy(error = e.message) }
            } finally {
                desktop = null
                ContextCompat.getMainExecutor(this).execute {
                    desktopActive = false
                    CloudPcBus.update { it.copy(desktopRunning = false, desktopReady = false) }
                    stopIfIdle()
                }
            }
        }, "cloudpc-desktop").start()
    }

    private fun stopDesktop() {
        desktop?.let { p ->
            p.destroy()
            Thread {
                try {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    private fun remove() {
        CloudPcBus.update { it.copy(title = "Built-in Ubuntu hata rahe hain…") }
        CloudPcEngine.remove(this)
        CloudPcBus.line("Built-in Ubuntu hata diya. Phone ki files par koi asar nahi.", force = true)
        CloudPcBus.update { it.copy(title = "Removed") }
    }

    private fun stopCurrent() {
        installer?.let {
            it.cancelled = true
            it.process?.destroy()
        }
        running?.let { p ->
            p.destroy()
            executor.execute {
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cloud PC", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Cloud PC installs or runs something"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "cloudpc"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CloudPcService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.aircursor.R.drawable.ic_cloud_tile)
            .setContentTitle("☁️ Cloud PC")
            .setContentText("Kaam chal raha hai… (app me progress dekho)")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirCursor:CloudPc").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopCurrent()
        stopDesktop()
        executor.shutdown()
        releaseWake()
        super.onDestroy()
    }
}
