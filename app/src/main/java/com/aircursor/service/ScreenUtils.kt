package com.aircursor.service

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

object ScreenUtils {
    fun display(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** Full physical screen size in the current rotation (includes status and nav bars). */
    fun realSize(context: Context): Point {
        val p = Point()
        val d = display(context)
        if (d != null) {
            @Suppress("DEPRECATION")
            d.getRealSize(p)
        } else {
            val dm = context.resources.displayMetrics
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p
    }

    fun rotation(context: Context): Int = display(context)?.rotation ?: Surface.ROTATION_0
}
