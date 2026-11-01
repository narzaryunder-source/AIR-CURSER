package com.brightmax.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brightmax.brightness.SystemBrightness
import com.brightmax.data.AppPrefs
import com.brightmax.data.UserSettings
import com.brightmax.hdr.HdrController
import com.brightmax.hdr.HdrInfo
import com.brightmax.safety.BatteryInfo
import com.brightmax.safety.BatteryMonitor
import com.brightmax.safety.SafetyPolicy
import com.brightmax.safety.StopReason
import com.brightmax.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the boost while other apps are open:
 * system brightness, the floating button (with window brightness + HDR), the auto-off timer and all
 * safety checks (battery temperature, Android thermal status, battery level, screen off).
 *
 * States: stopped → standby (floating button only, boost off) ↔ boosting.
 */
class BoostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: AppPrefs
    private lateinit var brightness: SystemBrightness
    private lateinit var notifier: Notifier
    private lateinit var overlay: FloatingOverlay
    private lateinit var hdrInfo: HdrInfo

    private var boostJobs: Job? = null
    private var endsAtElapsed = 0L
    private var lastBattery: BatteryInfo? = null
    private var lastThermal: Int? = null
    private var inForeground = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopBoost(StopReason.SCREEN_OFF)
        }
    }
    private var screenReceiverRegistered = false

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Device is powering off: put the original brightness back synchronously.
            brightness.restore()
        }
    }

    private val state get() = BoostRepository.state.value
    private val settings: UserSettings get() = prefs.settings.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs.get(this)
        brightness = SystemBrightness(this)
        notifier = Notifier(this).also { it.createChannels() }
        hdrInfo = HdrController.info(this)
        overlay = FloatingOverlay(
            this,
            onToggle = { if (state.active) stopBoost(StopReason.USER) else startBoost() },
            onOpenApp = ::openApp,
        )
        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        BoostRepository.update { it.copy(serviceRunning = true) }

        // React to setting changes made in the app while the service runs.
        scope.launch {
            var previous = settings
            prefs.settings.collect { now ->
                if (state.active &&
                    (
                        now.level != previous.level || now.hdrEnabled != previous.hdrEnabled ||
                            now.hdrTryAnyway != previous.hdrTryAnyway || now.sunlightMode != previous.sunlightMode
                        )
                ) {
                    applyBoost()
                }
                if (now.overlayEnabled != previous.overlayEnabled) {
                    if (now.overlayEnabled) {
                        if (overlay.show()) overlay.render(state)
                    } else {
                        overlay.hide()
                        if (!state.active) stopSelf()
                    }
                }
                previous = now
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // Restarted by the system after the process was killed. BrightMaxApp already restored the
            // original brightness; do not silently boost again.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            brightness.restore()
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startBoost()
            ACTION_TOGGLE -> if (state.active) stopBoost(StopReason.USER) else startBoost()
            ACTION_OFF -> stopBoost(StopReason.USER)
            ACTION_STANDBY -> if (!state.active) settleAfterStop()
            ACTION_CLOSE -> {
                stopBoost(StopReason.USER, settle = false)
                stopSelf()
            }
        }
        // Sticky so that, if the system kills the process, it is restarted and BrightMaxApp restores
        // the original brightness right away instead of leaving the screen at maximum.
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        return try {
            ServiceCompat.startForeground(
                this,
                Notifier.ID_ONGOING,
                notifier.ongoing(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            inForeground = true
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "startForeground failed", e)
            false
        }
    }

    // ---------------------------------------------------------------- boost on / off

    private fun startBoost() {
        if (state.active) return
        notifier.cancelWarning()

        if (!brightness.canWrite() || !settings.disclaimerAccepted) {
            endWithoutStarting(StopReason.PERMISSION_MISSING, null)
            return
        }
        val battery = BatteryMonitor.read(this)
        lastBattery = battery
        lastThermal = BatteryMonitor.currentThermalStatus(this)
        SafetyPolicy.startBlocker(battery.tempC, battery.pct, lastThermal)?.let {
            endWithoutStarting(it, battery.tempC)
            return
        }

        endsAtElapsed = SystemClock.elapsedRealtime() + SafetyPolicy.timerMs(settings.timerMinutes)
        val verdict = SafetyPolicy.evaluate(battery.tempC, battery.pct, currentlyReduced = false, thermalStatus = lastThermal)
        BoostRepository.update {
            it.copy(
                active = true,
                thermalReduced = verdict.reduced,
                warned = false,
                remainingMs = endsAtElapsed - SystemClock.elapsedRealtime(),
                lastStop = null,
                lastStopTempC = null,
            )
        }
        applyBoost()
        startMonitoring()
        if (settings.overlayEnabled) overlay.show()
        refreshUi()
    }

    /** Applies the current level / HDR state to the system, the overlay and the shared state. */
    private fun applyBoost() {
        val reduced = state.thermalReduced
        val level = SafetyPolicy.effectiveLevel(settings.level, reduced)
        val sunlight = settings.sunlightMode
        if (!brightness.apply(level, keepAutoBrightness = sunlight)) {
            stopBoost(StopReason.PERMISSION_MISSING)
            return
        }
        // HDR is the heaviest part, so it is paused while the boost is thermally reduced.
        val hdr = settings.hdrEnabled && hdrInfo.usable(settings.hdrTryAnyway) && !reduced
        BoostRepository.update { it.copy(effectiveLevel = level, hdrActive = hdr, sunlightActive = sunlight) }
        refreshUi()
    }

    fun stopBoost(reason: StopReason, tempC: Float? = lastBattery?.tempC, settle: Boolean = true) {
        val wasActive = state.active
        boostJobs?.cancel()
        boostJobs = null
        unregisterScreenReceiver()
        brightness.restore()
        notifier.cancelWarning()
        BoostRepository.update {
            it.copy(
                active = false,
                effectiveLevel = 0f,
                thermalReduced = false,
                hdrActive = false,
                sunlightActive = false,
                remainingMs = 0L,
                warned = false,
                lastStop = if (wasActive) reason else it.lastStop,
                lastStopTempC = if (wasActive) tempC else it.lastStopTempC,
            )
        }
        if (wasActive && reason != StopReason.USER) notifier.boostEnded(reason, tempC)
        refreshUi()
        if (settle) settleAfterStop()
    }

    private fun endWithoutStarting(reason: StopReason, tempC: Float?) {
        BoostRepository.update { it.copy(lastStop = reason, lastStopTempC = tempC) }
        notifier.alert(
            getString(com.brightmax.R.string.alert_cannot_start_title),
            notifier.stopReasonText(reason, tempC),
        )
        refreshUi()
        settleAfterStop()
    }

    /** Boost is off: keep the floating button (standby) if the user wants it, otherwise stop. */
    private fun settleAfterStop() {
        if (settings.overlayEnabled && overlay.show()) {
            overlay.render(state)
            if (inForeground) notifier.updateOngoing(state)
        } else {
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- safety + timer

    private fun startMonitoring() {
        boostJobs?.cancel()
        registerScreenReceiver()
        boostJobs = scope.launch {
            // Battery temperature / level and Android's thermal status.
            launch {
                combine(BatteryMonitor.updates(this@BoostService), BatteryMonitor.thermalStatus(this@BoostService)) { b, t -> b to t }
                    .collect { (battery, thermal) -> onSafetyInput(battery, thermal) }
            }
            // Auto-off timer with a warning one minute before the end.
            launch {
                while (isActive && state.active) {
                    val remaining = endsAtElapsed - SystemClock.elapsedRealtime()
                    if (remaining <= 0) {
                        stopBoost(StopReason.TIMER)
                        break
                    }
                    if (SafetyPolicy.shouldWarn(remaining, state.warned)) {
                        BoostRepository.update { it.copy(warned = true) }
                        notifier.timerWarning()
                    }
                    BoostRepository.update { it.copy(remainingMs = remaining) }
                    if (overlay.isShown) overlay.render(state)
                    delay(1_000L - (remaining % 1_000L).coerceAtLeast(0L) + 1L)
                }
            }
        }
    }

    private fun onSafetyInput(battery: BatteryInfo, thermal: Int?) {
        lastBattery = battery
        lastThermal = thermal
        if (!state.active) return
        val wasReduced = state.thermalReduced
        val verdict = SafetyPolicy.evaluate(battery.tempC, battery.pct, wasReduced, thermal)
        if (verdict.stop != null) {
            stopBoost(verdict.stop, battery.tempC)
            return
        }
        if (verdict.reduced != wasReduced) {
            BoostRepository.update { it.copy(thermalReduced = verdict.reduced) }
            applyBoost()
            if (verdict.reduced) notifier.heatReduced(battery.tempC)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenOffReceiver) }
        screenReceiverRegistered = false
    }

    // ---------------------------------------------------------------- UI plumbing

    private fun refreshUi() {
        if (overlay.isShown) overlay.render(state)
        if (inForeground) notifier.updateOngoing(state)
        TileService.requestListeningState(this, ComponentName(this, BoostTileService::class.java))
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    override fun onDestroy() {
        // Covers stopService(), "Close", and the system stopping the service: never leave the boost behind.
        boostJobs?.cancel()
        unregisterScreenReceiver()
        runCatching { unregisterReceiver(shutdownReceiver) }
        brightness.restore()
        overlay.hide()
        notifier.cancelWarning()
        val wasActive = state.active
        BoostRepository.update {
            it.copy(
                serviceRunning = false,
                active = false,
                effectiveLevel = 0f,
                thermalReduced = false,
                hdrActive = false,
                sunlightActive = false,
                remainingMs = 0L,
                warned = false,
                lastStop = if (wasActive) StopReason.SERVICE_STOPPED else it.lastStop,
            )
        }
        TileService.requestListeningState(this, ComponentName(this, BoostTileService::class.java))
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BoostService"
        const val ACTION_START = "com.brightmax.action.START"
        const val ACTION_TOGGLE = "com.brightmax.action.TOGGLE"
        const val ACTION_OFF = "com.brightmax.action.OFF"
        const val ACTION_STANDBY = "com.brightmax.action.STANDBY"
        const val ACTION_CLOSE = "com.brightmax.action.CLOSE"

        private fun intent(context: Context, action: String) =
            Intent(context, BoostService::class.java).setAction(action)

        /**
         * Starts the boost. If the service is already running (standby) it is simply told to boost; otherwise it is
         * started as a foreground service, which may throw ForegroundServiceStartNotAllowedException from the background.
         */
        fun start(context: Context) = send(context, ACTION_START)

        /** Shows the floating button without boosting. */
        fun standby(context: Context) = send(context, ACTION_STANDBY)

        private fun send(context: Context, action: String) {
            if (BoostRepository.state.value.serviceRunning) {
                context.startService(intent(context, action))
            } else {
                ContextCompat.startForegroundService(context, intent(context, action))
            }
        }

        /** Turns the boost off (no-op if the service is not running). */
        fun off(context: Context) {
            if (!BoostRepository.state.value.serviceRunning) return
            runCatching { context.startService(intent(context, ACTION_OFF)) }
        }

        /** Stops everything, including the floating button. */
        fun close(context: Context) {
            if (!BoostRepository.state.value.serviceRunning) return
            runCatching { context.startService(intent(context, ACTION_CLOSE)) }
        }
    }
}
