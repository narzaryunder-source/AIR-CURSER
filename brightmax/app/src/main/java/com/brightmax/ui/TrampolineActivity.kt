package com.brightmax.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.brightmax.service.BoostService

/** Invisible, instantly-finishing activity: starts the boost from the foreground when the tile cannot. */
class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { BoostService.start(this) }.onFailure { Log.w("Trampoline", "start failed", it) }
        finish()
    }
}
