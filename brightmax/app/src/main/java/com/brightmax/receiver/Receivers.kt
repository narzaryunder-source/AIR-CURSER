package com.brightmax.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brightmax.brightness.SystemBrightness

/** Phone is shutting down: put the original brightness back. */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SHUTDOWN || intent.action == "android.intent.action.QUICKBOOT_POWEROFF") {
            SystemBrightness(context).restore()
        }
    }
}

/** After a reboot (e.g. the battery died while boosted): restore anything left over. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SystemBrightness(context).restore()
        }
    }
}
