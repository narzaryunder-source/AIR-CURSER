package com.brightmax.safety

import kotlin.math.roundToInt

/** Why a boost ended (or could not start). Shown to the user in the app and notifications. */
enum class StopReason {
    USER,
    TIMER,
    OVERHEAT,
    THERMAL_CRITICAL,
    LOW_BATTERY,
    SCREEN_OFF,
    PERMISSION_MISSING,
    SERVICE_STOPPED,
}

/** Result of one safety check while the boost is running. */
data class SafetyVerdict(
    /** Boost must be lowered by [SafetyPolicy.THERMAL_REDUCTION] (and HDR paused). */
    val reduced: Boolean,
    /** Non-null when the boost must be turned off right now. */
    val stop: StopReason?,
)

/**
 * All safety rules in one pure, Android-free place so they can be unit tested.
 *
 * Thermal status values follow android.os.PowerManager.THERMAL_STATUS_* (0 = none … 6 = shutdown).
 */
object SafetyPolicy {
    const val MIN_LEVEL = 0.5f
    const val MAX_LEVEL = 1.0f

    /** Battery temperature (°C) where the boost is reduced by 20 %. */
    const val REDUCE_TEMP_C = 40f
    /** Battery temperature (°C) where the boost is turned off. */
    const val CUTOFF_TEMP_C = 43f
    /** A reduced boost goes back to full only after cooling below this (hysteresis). */
    const val RECOVER_TEMP_C = 38f
    /** "Reduce the boost 20 %" multiplier. */
    const val THERMAL_REDUCTION = 0.8f

    /** Boost turns off below this battery percentage. */
    const val MIN_BATTERY_PCT = 15

    const val DEFAULT_TIMER_MIN = 10
    val TIMER_OPTIONS_MIN = listOf(5, 10, 15, 30, 60)
    /** How long before auto-off the user is warned. */
    const val WARNING_BEFORE_END_MS = 60_000L

    /**
     * Android thermal status levels used as an extra guard on top of the battery-temperature rules.
     * Many phones report MODERATE/SEVERE just from charging or a warm room, so those only *reduce*
     * the boost; the boost is blocked/stopped only at CRITICAL or worse.
     */
    const val THERMAL_STATUS_SEVERE = 3
    const val THERMAL_STATUS_CRITICAL = 4

    /** Can a boost start right now? Returns the blocking reason, or null if it may start. */
    fun startBlocker(tempC: Float?, batteryPct: Int?, thermalStatus: Int? = null): StopReason? = when {
        batteryPct != null && batteryPct < MIN_BATTERY_PCT -> StopReason.LOW_BATTERY
        tempC != null && tempC >= CUTOFF_TEMP_C -> StopReason.OVERHEAT
        thermalStatus != null && thermalStatus >= THERMAL_STATUS_CRITICAL -> StopReason.THERMAL_CRITICAL
        else -> null
    }

    /** Safety check for a running boost. */
    fun evaluate(
        tempC: Float?,
        batteryPct: Int?,
        currentlyReduced: Boolean,
        thermalStatus: Int? = null,
    ): SafetyVerdict {
        startBlocker(tempC, batteryPct, thermalStatus)?.let { return SafetyVerdict(reduced = currentlyReduced, stop = it) }

        val hot = (tempC != null && tempC >= REDUCE_TEMP_C) ||
            (thermalStatus != null && thermalStatus >= THERMAL_STATUS_SEVERE)
        val stillWarm = currentlyReduced && tempC != null && tempC > RECOVER_TEMP_C
        return SafetyVerdict(reduced = hot || stillWarm, stop = null)
    }

    fun clampLevel(level: Float): Float = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** Level actually applied after the thermal reduction. */
    fun effectiveLevel(level: Float, reduced: Boolean): Float =
        clampLevel(level) * if (reduced) THERMAL_REDUCTION else 1f

    /** 0..1 → Settings.System.SCREEN_BRIGHTNESS (1..255). */
    fun toSystemBrightness(level: Float): Int = (level.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)

    fun timerMs(minutes: Int): Long = minutes.coerceAtLeast(1) * 60_000L

    /** Should the "ends soon" warning fire now? */
    fun shouldWarn(remainingMs: Long, alreadyWarned: Boolean): Boolean =
        !alreadyWarned && remainingMs in 1..WARNING_BEFORE_END_MS
}
