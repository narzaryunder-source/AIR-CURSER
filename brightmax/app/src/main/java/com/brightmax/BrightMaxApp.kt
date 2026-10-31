package com.brightmax

import android.app.Application
import com.brightmax.brightness.SystemBrightness
import com.brightmax.service.Notifier

class BrightMaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val brightness = SystemBrightness(this)

        // A fresh process never has an active boost. A saved backup here means the previous process was
        // killed or crashed while boosting (or the phone died): restore the user's brightness now.
        brightness.restore()

        // On a crash, restore the brightness before the process dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { brightness.restore() }
            previous?.uncaughtException(thread, error)
        }

        Notifier(this).createChannels()
    }
}
