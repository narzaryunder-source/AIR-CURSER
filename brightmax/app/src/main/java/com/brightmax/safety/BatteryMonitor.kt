package com.brightmax.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

data class BatteryInfo(val tempC: Float?, val pct: Int?, val charging: Boolean)

object BatteryMonitor {

    fun parse(intent: Intent?): BatteryInfo {
        if (intent == null) return BatteryInfo(null, null, false)
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryInfo(
            tempC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
            pct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    /** Current battery state (ACTION_BATTERY_CHANGED is sticky, so this returns at once). */
    fun read(context: Context): BatteryInfo = parse(
        ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ),
    )

    /** Battery updates (temperature, level) for as long as the flow is collected. */
    fun updates(context: Context): Flow<BatteryInfo> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(parse(intent))
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (sticky != null) trySend(parse(sticky))
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged().conflate()

    fun currentThermalStatus(context: Context): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else {
            null
        }

    /** PowerManager thermal status (0 = none … 6 = shutdown). Android 10+; otherwise a single null. */
    fun thermalStatus(context: Context): Flow<Int?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return flowOf(null)
        val pm = context.getSystemService(PowerManager::class.java) ?: return flowOf(null)
        return callbackFlow {
            val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
            trySend(pm.currentThermalStatus)
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            awaitClose { pm.removeThermalStatusListener(listener) }
        }.distinctUntilChanged()
    }
}
