package com.brightmax.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brightmax.brightness.SystemBrightness
import com.brightmax.data.AppPrefs
import com.brightmax.data.UserSettings
import com.brightmax.hdr.HdrController
import com.brightmax.hdr.HdrInfo
import com.brightmax.safety.BatteryInfo
import com.brightmax.safety.BatteryMonitor
import com.brightmax.safety.SafetyPolicy
import com.brightmax.safety.StopReason
import com.brightmax.service.BoostRepository
import com.brightmax.service.BoostService
import com.brightmax.service.BoostState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class Permissions(
    val canWriteSettings: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val notificationsGranted: Boolean = true,
)

/** Which explanation dialog is open. */
enum class DialogType { DISCLAIMER, WRITE_SETTINGS, OVERLAY, NOTIFICATIONS }

data class UiState(
    val boost: BoostState = BoostState(),
    val settings: UserSettings = UserSettings(),
    val permissions: Permissions = Permissions(),
    val battery: BatteryInfo = BatteryInfo(null, null, false),
    val hdr: HdrInfo = HdrInfo.NONE,
    val hdrRatio: Float? = null,
    /** PowerManager thermal status (0 = none … 6 = shutdown), null below Android 10. */
    val thermalStatus: Int? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs.get(app)
    private val brightness = SystemBrightness(app)
    private val hdrInfo = HdrController.info(app)

    private val permissions = MutableStateFlow(readPermissions())

    private val _dialog = MutableStateFlow(if (prefs.settings.value.disclaimerAccepted) null else DialogType.DISCLAIMER)
    val dialog: StateFlow<DialogType?> = _dialog.asStateFlow()

    /** One-off message for a snackbar (blocked start). */
    private val _message = MutableStateFlow<StopReason?>(null)
    val message: StateFlow<StopReason?> = _message.asStateFlow()

    private val batteryAndThermal = combine(BatteryMonitor.updates(app), BatteryMonitor.thermalStatus(app)) { b, t -> b to t }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BatteryMonitor.read(app) to BatteryMonitor.currentThermalStatus(app),
        )

    private val hdrRatio = HdrController.headroomRatio(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui: StateFlow<UiState> = combine(
        BoostRepository.state,
        prefs.settings,
        permissions,
        batteryAndThermal,
        hdrRatio,
    ) { boost, settings, perms, (batt, thermal), ratio ->
        UiState(boost, settings, perms, batt, hdrInfo, ratio, thermal)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(hdr = hdrInfo))

    private fun readPermissions(): Permissions {
        val app = getApplication<Application>()
        return Permissions(
            canWriteSettings = brightness.canWrite(),
            canDrawOverlays = Settings.canDrawOverlays(app),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    /** Called on every resume (the user may come back from a settings screen). */
    fun refreshPermissions() {
        permissions.value = readPermissions()
        val open = _dialog.value
        if (open == DialogType.WRITE_SETTINGS && permissions.value.canWriteSettings) _dialog.value = null
        if (open == DialogType.OVERLAY && permissions.value.canDrawOverlays) _dialog.value = null
    }

    fun onToggle() {
        if (BoostRepository.state.value.active) {
            BoostService.off(getApplication())
            return
        }
        refreshPermissions()
        when {
            !prefs.settings.value.disclaimerAccepted -> _dialog.value = DialogType.DISCLAIMER
            !permissions.value.canWriteSettings -> _dialog.value = DialogType.WRITE_SETTINGS
            else -> {
                val batt = BatteryMonitor.read(getApplication())
                val blocker = SafetyPolicy.startBlocker(
                    batt.tempC,
                    batt.pct,
                    BatteryMonitor.currentThermalStatus(getApplication()),
                )
                if (blocker != null) {
                    _message.value = blocker
                } else {
                    BoostRepository.clearLastStop()
                    startService { BoostService.start(getApplication()) }
                }
            }
        }
    }

    fun setLevel(level: Float) = prefs.setLevel(level)
    fun setMax() = prefs.setLevel(SafetyPolicy.MAX_LEVEL)
    fun setTimer(minutes: Int) = prefs.setTimerMinutes(minutes)
    fun setSunlightMode(enabled: Boolean) = prefs.setSunlightMode(enabled)
    /** The HDR switch: on a display that reports HDR it is the normal on/off, otherwise "try anyway". */
    fun setHdrSwitch(on: Boolean) {
        if (hdrInfo.isHdr) {
            prefs.setHdrEnabled(on)
        } else {
            prefs.setHdrTryAnyway(on)
            if (on) prefs.setHdrEnabled(true)
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        refreshPermissions()
        if (enabled && !permissions.value.canDrawOverlays) {
            _dialog.value = DialogType.OVERLAY
            return
        }
        prefs.setOverlayEnabled(enabled)
        // The running service reacts to the setting; if nothing runs yet, start in standby.
        if (enabled && !BoostRepository.state.value.serviceRunning && prefs.settings.value.disclaimerAccepted) {
            startService { BoostService.standby(getApplication()) }
        }
    }

    fun closeAll() = BoostService.close(getApplication())

    fun acceptDisclaimer() {
        prefs.acceptDisclaimer()
        _dialog.value = null
    }

    fun showDialog(type: DialogType) {
        _dialog.value = type
    }

    fun dismissDialog() {
        // The disclaimer cannot be skipped: the boost stays locked until it is accepted.
        _dialog.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun clearLastStop() = BoostRepository.clearLastStop()

    private inline fun startService(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not start service", e)
            _message.value = StopReason.SERVICE_STOPPED
        }
    }
}
