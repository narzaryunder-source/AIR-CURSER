package com.brightmax.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.brightmax.R
import com.brightmax.brightness.SystemBrightness
import com.brightmax.data.AppPrefs
import com.brightmax.ui.MainActivity
import com.brightmax.ui.TrampolineActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Quick Settings tile: one tap toggles the boost. */
class BoostTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch { BoostRepository.state.collect(::render) }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    private fun render(state: BoostState) {
        val tile = qsTile ?: return
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (state.active) {
                getString(R.string.tile_on, (state.effectiveLevel * 100).roundToInt())
            } else {
                getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        if (BoostRepository.state.value.active) {
            BoostService.off(this)
            return
        }
        // First run or missing permission: the app explains what is needed.
        if (!AppPrefs.get(this).settings.value.disclaimerAccepted || !SystemBrightness(this).canWrite()) {
            launch(Intent(this, MainActivity::class.java))
            return
        }
        try {
            BoostService.start(this)
        } catch (e: Exception) {
            // Android may refuse a foreground-service start from the tile; an invisible activity can start it.
            Log.w("BoostTile", "Direct start refused, using trampoline", e)
            launch(Intent(this, TrampolineActivity::class.java))
        }
    }

    // The Intent overload is the only one available below Android 14.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
