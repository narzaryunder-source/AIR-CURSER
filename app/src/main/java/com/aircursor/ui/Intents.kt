package com.aircursor.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.aircursor.service.CursorAccessibilityService

object Intents {
    private fun launch(context: Context, vararg intents: Intent) {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(context, "Could not open settings on this phone", Toast.LENGTH_LONG).show()
    }

    fun appInfo(context: Context) = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    /** Opens Accessibility settings and (on most phones) highlights AirCursor. */
    fun accessibility(context: Context) {
        val component = ComponentName(context, CursorAccessibilityService::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", args)
        launch(context, intent, Intent(Settings.ACTION_SETTINGS))
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun battery(context: Context) = launch(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
}
