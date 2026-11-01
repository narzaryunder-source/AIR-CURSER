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
        const val ACTION_GPU = "com.aircursor.cloudpc.GPU"
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
    @Volatile private var desktopActive = false

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
            ACTION_INSTALL -> submit("Preparing…") { install() }
            ACTION_GPU -> Thread({ applyGpu() }, "cloudpc-gpu").start()
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
                CloudPcBus.line("Cancelled.", force = true)
                CloudPcBus.update { it.copy(error = "Cancelled. Tap Continue to resume where it stopped.") }
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

    /** One step for the user: base system (first 30%) + desktop and apps (the rest). */
    private fun install() {
        val inst = CloudPcInstaller(this)
        inst.scale = 0.3f
        installer = inst
        try {
            if (!CloudPcEngine.isReady(this)) inst.install()
            if (inst.cancelled) throw CloudPcInstaller.Cancelled()
            if (!CloudPcDesktop.isInstalled(this)) installDesktop(0.3f)
            CloudPcBus.line("==> AirCursor PC is ready", force = true)
            CloudPcBus.update { it.copy(title = "Ready", progress = 1f) }
        } finally {
            installer = null
        }
    }

    private fun runCommand(cmd: String) {
        if (!CloudPcEngine.isExtracted(this)) throw IllegalStateException("Install AirCursor PC first.")
        CloudPcBus.line("$ $cmd", force = true)
        CloudPcBus.update { it.copy(title = "Running: ${cmd.take(40)}", progress = -1f) }
        val code = CloudPcEngine.run(this, cmd, onProcess = { running = it }) { CloudPcBus.line(it) }
        running = null
        CloudPcBus.line("[exit $code]", force = true)
        CloudPcBus.update { it.copy(title = if (code == 0) "Done" else "Finished with code $code") }
    }

    /**
     * Desktop + apps (apt). Progress comes from apt's own output: the number of packages it will
     * install, then each download ("Get:") and each install ("Setting up").
     */
    private fun installDesktop(start: Float = 0f) {
        if (!CloudPcEngine.isReady(this)) throw IllegalStateException("Install the base system first.")
        CloudPcBus.update { it.copy(title = "Installing the desktop and apps (about 450 MB)…", progress = start) }
        var ok = false
        var total = 0
        var got = 0
        var setUp = 0
        val countRe = Regex("""(\d+) newly installed""")
        var best = start
        fun show() {
            if (total <= 0) return
            val f = (got.toFloat() / total * 0.5f + setUp.toFloat() / total * 0.5f).coerceIn(0f, 1f)
            best = maxOf(best, start + (0.98f - start) * f) // never jumps backwards
            CloudPcBus.update { it.copy(progress = best) }
        }
        val code = CloudPcEngine.run(this, CloudPcDesktop.INSTALL_SCRIPT, onProcess = { running = it }) { line ->
            if (line.contains("CLOUDPC_DESKTOP_OK")) ok = true
            countRe.find(line)?.let { m -> total += m.groupValues[1].toInt() }
            when {
                line.startsWith("Get:") -> { got++; show() }
                line.startsWith("Setting up ") -> {
                    setUp++
                    show()
                    CloudPcBus.update { it.copy(title = "Installing " + line.removePrefix("Setting up ").substringBefore(' ').substringBefore(':')) }
                }
                line.startsWith("==> ") -> CloudPcBus.update { it.copy(title = line.removePrefix("==> ")) }
            }
            CloudPcBus.line(line)
        }
        running = null
        if (installer?.cancelled == true) throw CloudPcInstaller.Cancelled()
        if (code != 0 || !ok) throw java.io.IOException("Desktop setup stopped (code $code). Check the internet connection and tap Continue.")
        java.io.File(CloudPcEngine.rootfs(this), CloudPcDesktop.MARK_DESKTOP).writeText("1\n")
        CloudPcBus.update { it.copy(title = "Desktop ready", progress = 1f) }
    }

    /** Starts Xvnc + XFCE as a long-running process; the viewer connects to its private socket. */
    private fun startDesktop(width: Int, height: Int) {
        if (desktopActive) return
        if (!CloudPcDesktop.isInstalled(this)) {
            CloudPcBus.update { it.copy(error = "Install AirCursor PC first.") }
            stopIfIdle()
            return
        }
        desktopActive = true
        CloudPcBus.update { it.copy(desktopRunning = true, desktopReady = false, error = null) }
        // Keep the CPU awake while the desktop runs (long app installs continue with the screen off);
        // released when the desktop stops, and at the latest after 3 hours.
        val deskWake = try {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirCursor:Desktop")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)
                }
        } catch (_: Exception) {
            null
        }
        Thread({
            try {
                CloudPcDesktop.writeStartScript(this)
                // Tell the PC whether Android's 32-program limit is lifted (the app launcher explains it),
                // and keep the game settings (steady FPS) in step with the app.
                val limit = when (ChildLimit.limitOff(this)) {
                    true -> "off"
                    false -> "on"
                    null -> "unknown"
                }
                CloudPcEngine.writeGuestFile(CloudPcEngine.rootfs(this).toPath(), "etc/cloudpc/child-limit", "$limit\n")
                AirBoost.writeGameConfig(this)
                applyGpu()
                if (CloudPcTools.needsSetup(this)) {
                    CloudPcBus.line("==> Updating desktop tools (App Store, layout, AI apps)…", force = true)
                    CloudPcEngine.run(this, CloudPcTools.PACKAGES_SCRIPT, onProcess = { desktop = it }) { CloudPcBus.line(it) }
                    CloudPcTools.writeFiles(this)
                }
                val light = AirBoost.current(this).light
                CloudPcBus.line("==> Starting the desktop ${width}x$height (AirBoost: ${AirBoost.current(this).label})", force = true)
                val code = CloudPcEngine.run(
                    this, CloudPcDesktop.startCommand(width, height, light), onProcess = { desktop = it },
                ) { line ->
                    if (line.contains("CLOUDPC_VNC_READY")) CloudPcBus.update { it.copy(desktopReady = true) }
                    // XFCE prints a lot of harmless warnings: keep only the important ones in the log.
                    if (line.contains("CLOUDPC") || line.contains("rror") || line.contains("Xvnc")) CloudPcBus.line(line)
                }
                CloudPcBus.line("==> Desktop stopped (code $code)", force = true)
            } catch (e: Throwable) {
                Log.w(TAG, "desktop failed", e)
                CloudPcBus.line("Desktop error: ${e.message}", force = true)
                CloudPcBus.update { it.copy(error = e.message) }
            } finally {
                desktop = null
                VirglServer.stop(this)
                VirglServer.writeMode(this, false)
                try {
                    deskWake?.takeIf { it.isHeld }?.release()
                } catch (_: Exception) {
                }
                ContextCompat.getMainExecutor(this).execute {
                    desktopActive = false
                    CloudPcBus.update { it.copy(desktopRunning = false, desktopReady = false) }
                    stopIfIdle()
                }
            }
        }, "cloudpc-desktop").start()
    }

    /** Phone GPU switch: starts or stops the VirGL server and tells the PC which one games use. */
    private fun applyGpu() {
        val want = AirBoost.phoneGpu(this) && desktopActive
        if (want) {
            val ok = VirglServer.start(this)
            CloudPcBus.line(
                if (ok) "==> Phone GPU (VirGL) is on – games use it from their next start"
                else "==> Phone GPU (VirGL) could not start on this phone – games use the CPU",
                force = true,
            )
        } else {
            VirglServer.stop(this)
        }
        VirglServer.writeMode(this, want)
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
        CloudPcBus.update { it.copy(title = "Removing AirCursor PC…") }
        CloudPcEngine.remove(this)
        CloudPcBus.line("AirCursor PC removed. Your phone's own files were not touched.", force = true)
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
            NotificationChannel(CHANNEL, "AirCursor PC", NotificationManager.IMPORTANCE_LOW).apply {
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
            .setSmallIcon(com.aircursor.R.drawable.ic_ui_desktop)
            .setContentTitle("AirCursor PC")
            .setContentText("Running – tap to open")
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
