package com.aircursor.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to turn AirCursor on or off. */
class AirCursorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(HandTrackingService.isRunning)
    }

    override fun onClick() {
        super.onClick()
        if (HandTrackingService.isRunning) {
            HandTrackingService.stop(this)
            updateTile(false)
        } else if (isLocked) {
            unlockAndRun { launchStarter() }
        } else {
            launchStarter()
        }
    }

    /**
     * A camera foreground service may only start while the app is visible (Android 14),
     * so the tile opens a tiny transparent activity that starts the service and closes.
     */
    private fun launchStarter() {
        val intent = Intent(this, StartTrackingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "AirCursor"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (running) "On" else "Off"
        tile.updateTile()
    }
}
