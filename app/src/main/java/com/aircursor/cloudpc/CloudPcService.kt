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
        const val EXTRA_COMMAND = "command"

        fun send(c: Context, action: String, command: String? = null) {
            val i = Intent(c, CloudPcService::class.java).setAction(action)
            if (command != null) i.putExtra(EXTRA_COMMAND, command)
            if (action == ACTION_STOP) {
                c.startService(i)
            } else {
                ContextCompat.startForegroundService(c, i)
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var jobs = 0

    @Volatile
    private var installer: CloudPcInstaller? = null

    @Volatile
    private var running: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopCurrent()
            if (jobs == 0) stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        when (action) {
            ACTION_INSTALL -> submit("Cloud PC install") { install() }
            ACTION_RUN -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (cmd.isNotBlank()) submit(cmd.take(40)) { runCommand(cmd) }
            }
            ACTION_REMOVE -> submit("Removing") { remove() }
            else -> if (jobs == 0) stopSelf()
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
                    if (jobs == 0) {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
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
        executor.shutdown()
        releaseWake()
        super.onDestroy()
    }
}
