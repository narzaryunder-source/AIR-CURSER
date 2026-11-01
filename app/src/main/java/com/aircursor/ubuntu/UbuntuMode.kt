package com.aircursor.ubuntu

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.aircursor.pc.PcMode
import com.aircursor.service.HandTrackingService
import com.aircursor.service.Permissions

/**
 * One-tap "Ubuntu Mode": starts the Ubuntu desktop inside Termux (through Termux's
 * RUN_COMMAND service), opens Termux:X11 and turns on hand tracking.
 * Needs the commands installed by ubuntu/setup.sh or fix.sh (they also allow external apps).
 */
object UbuntuMode {
    const val TERMUX = "com.termux"
    const val X11 = "com.termux.x11"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val BIN = "/data/data/com.termux/files/usr/bin"
    private const val HOME = "/data/data/com.termux/files/home"

    fun isInstalled(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs a Termux command in the background. Returns an error message, or null when sent. */
    fun runInTermux(c: Context, command: String, args: Array<String> = emptyArray()): String? {
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(TERMUX, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "$BIN/$command")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
            null
        } catch (e: Exception) {
            Log.w("UbuntuMode", "RUN_COMMAND failed", e)
            e.message ?: "Termux did not accept the command"
        }
    }

    fun openX11(c: Context): Boolean = try {
        c.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(X11, "com.termux.x11.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        Log.w("UbuntuMode", "Termux:X11 not opened", e)
        false
    }

    /**
     * Must be called from a visible activity (Android only lets visible apps start the camera
     * service). [gpu]: true = VirGL, false = CPU, null = the saved AirBoost profile.
     */
    fun start(c: Context, gpu: Boolean? = null): String? {
        val args = when (gpu) {
            true -> arrayOf("--gpu")
            false -> arrayOf("--cpu")
            null -> emptyArray()
        }
        val error = runInTermux(c, "ubuntu-desktop", args)
        if (error != null) return error
        // PC mode's taskbar would cover the Ubuntu panel.
        PcMode.setEnabled(c, false)
        if (Permissions.camera(c) && Permissions.accessibility(c)) HandTrackingService.start(c)
        Handler(Looper.getMainLooper()).postDelayed({ openX11(c) }, 2500)
        return null
    }

    fun stop(c: Context) {
        runInTermux(c, "ubuntu-stop")
        HandTrackingService.stop(c)
    }
}
