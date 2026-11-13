package com.aircursor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.auth.AuthManager
import com.aircursor.ui.AirCursorTheme
import com.aircursor.ui.CloudPcScreen
import com.aircursor.ui.HelpScreen
import com.aircursor.ui.HomeScreen
import com.aircursor.ui.LoginScreen

class MainActivity : ComponentActivity() {
    companion object {
        /** Opens a screen directly, e.g. "cloudpc" from the AirCursor PC icon. */
        const val EXTRA_OPEN = "open"
    }

    private val requested = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(this)
        com.aircursor.ui.UiPrefs.load(this)
        requested.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            AirCursorTheme {
                val user by AuthManager.user.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf("home") }
                val want = requested.value
                LaunchedEffect(want) {
                    if (want != null) screen = want
                    requested.value = null
                }
                when {
                    // Sign in once; the session stays on this phone.
                    user == null -> LoginScreen()
                    else -> {
                        val home = { screen = "home" }
                        BackHandler(enabled = screen != "home") { home() }
                        when (screen) {
                            "help" -> HelpScreen(onBack = home)
                            "cloudpc" -> CloudPcScreen(onBack = home)
                            else -> HomeScreen(
                                onOpenPc = { screen = "cloudpc" },
                                onOpenHelp = { screen = "help" },
                            )
                        }
                    }
                }
            }
        }
    }
}
