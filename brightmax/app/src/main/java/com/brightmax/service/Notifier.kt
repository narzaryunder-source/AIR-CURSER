package com.brightmax.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.brightmax.R
import com.brightmax.safety.StopReason
import com.brightmax.ui.MainActivity
import kotlin.math.roundToInt

class Notifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    fun createChannels() {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_status_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_alerts_desc)
            },
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        ctx,
        requestCode,
        Intent(ctx, BoostService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The persistent foreground-service notification. */
    fun ongoing(state: BoostState): Notification {
        val builder = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

        if (state.active) {
            val pct = (state.effectiveLevel * 100).roundToInt()
            val title = if (state.hdrActive) {
                ctx.getString(R.string.notif_title_on_hdr, pct)
            } else {
                ctx.getString(R.string.notif_title_on, pct)
            }
            builder.setContentTitle(title)
                .setContentText(
                    if (state.thermalReduced) ctx.getString(R.string.notif_text_reduced) else ctx.getString(R.string.notif_text_on),
                )
                // Live countdown to auto-off without re-posting every second.
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + state.remainingMs)
                .setShowWhen(true)
                .addAction(0, ctx.getString(R.string.action_off), serviceAction(BoostService.ACTION_OFF, 1))
        } else {
            builder.setContentTitle(ctx.getString(R.string.notif_title_standby))
                .setContentText(ctx.getString(R.string.notif_text_standby))
                .setShowWhen(false)
                .addAction(0, ctx.getString(R.string.action_on), serviceAction(BoostService.ACTION_START, 2))
                .addAction(0, ctx.getString(R.string.action_close), serviceAction(BoostService.ACTION_CLOSE, 3))
        }
        return builder.build()
    }

    fun updateOngoing(state: BoostState) = post(ID_ONGOING, ongoing(state))

    fun stopReasonText(reason: StopReason, tempC: Float?): String = when (reason) {
        StopReason.USER -> ctx.getString(R.string.reason_user)
        StopReason.TIMER -> ctx.getString(R.string.reason_timer)
        StopReason.OVERHEAT -> ctx.getString(R.string.reason_overheat, tempC ?: 0f)
        StopReason.THERMAL_SEVERE -> ctx.getString(R.string.reason_thermal)
        StopReason.LOW_BATTERY -> ctx.getString(R.string.reason_low_battery)
        StopReason.SCREEN_OFF -> ctx.getString(R.string.reason_screen_off)
        StopReason.PERMISSION_MISSING -> ctx.getString(R.string.reason_permission)
        StopReason.SERVICE_STOPPED -> ctx.getString(R.string.reason_service_stopped)
    }

    /** High-priority alert + toast (the toast also covers users who denied notifications). */
    fun alert(title: String, text: String, id: Int = ID_ALERT) {
        Toast.makeText(ctx, "$title\n$text", Toast.LENGTH_LONG).show()
        post(
            id,
            NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    fun boostEnded(reason: StopReason, tempC: Float?) =
        alert(ctx.getString(R.string.alert_boost_off_title), stopReasonText(reason, tempC))

    fun heatReduced(tempC: Float?) = alert(
        ctx.getString(R.string.alert_reduced_title),
        ctx.getString(R.string.alert_reduced_text, tempC ?: 0f),
    )

    fun timerWarning() = alert(
        ctx.getString(R.string.alert_timer_title),
        ctx.getString(R.string.alert_timer_text),
        ID_WARNING,
    )

    fun cancelWarning() = nm.cancel(ID_WARNING)

    fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val CH_STATUS = "boost_status"
        const val CH_ALERTS = "safety_alerts"
        const val ID_ONGOING = 1
        const val ID_ALERT = 2
        const val ID_WARNING = 3
    }
}
