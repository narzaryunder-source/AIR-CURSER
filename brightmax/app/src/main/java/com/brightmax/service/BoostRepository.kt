package com.brightmax.service

import com.brightmax.safety.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state of the boost, owned by [BoostService] and observed by the UI, overlay and tile. */
data class BoostState(
    /** The foreground service is running (boost on, or standby with the floating button). */
    val serviceRunning: Boolean = false,
    val active: Boolean = false,
    /** Level applied right now (0..1), after any thermal reduction. */
    val effectiveLevel: Float = 0f,
    val thermalReduced: Boolean = false,
    val hdrActive: Boolean = false,
    /**
     * Sunlight mode is active: auto-brightness stays on, so windows must NOT set their own brightness
     * override (an override switches auto-brightness and the phone's sunlight boost off).
     */
    val sunlightActive: Boolean = false,
    val remainingMs: Long = 0L,
    val warned: Boolean = false,
    val lastStop: StopReason? = null,
    val lastStopTempC: Float? = null,
)

object BoostRepository {
    private val _state = MutableStateFlow(BoostState())
    val state: StateFlow<BoostState> = _state.asStateFlow()

    fun update(block: (BoostState) -> BoostState) = _state.update(block)

    fun clearLastStop() = _state.update { it.copy(lastStop = null, lastStopTempC = null) }
}
