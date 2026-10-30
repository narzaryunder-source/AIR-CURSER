package com.aircursor.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.aircursor.MainActivity

/**
 * Invisible activity used by the Quick Settings tile. While it is resumed the app counts as
 * visible, which Android 14 requires before starting a camera foreground service.
 */
class StartTrackingActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean("handled") ?: false
    }

    override fun onResume() {
        super.onResume()
        if (handled) {
            finish()
            return
        }
        handled = true
        if (Permissions.camera(this) && Permissions.accessibility(this)) {
            HandTrackingService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("handled", handled)
    }
}
