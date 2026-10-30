package com.aircursor.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations so you can feel a click even though you never touch the phone. */
class Haptics(context: Context) {
    var enabled = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun click() = play(VibrationEffect.EFFECT_CLICK, 18L, 120)
    fun tick() = play(VibrationEffect.EFFECT_TICK, 10L, 80)
    fun heavy() = play(VibrationEffect.EFFECT_HEAVY_CLICK, 35L, 200)

    private fun play(predefined: Int, fallbackMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        try {
            val effect = if (Build.VERSION.SDK_INT >= 29) {
                VibrationEffect.createPredefined(predefined)
            } else {
                VibrationEffect.createOneShot(fallbackMs, amplitude)
            }
            v.vibrate(effect)
        } catch (_: Exception) {
        }
    }
}
