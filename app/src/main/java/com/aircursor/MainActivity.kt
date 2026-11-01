package com.aircursor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aircursor.data.AppLock
import com.aircursor.data.Security
import com.aircursor.service.Permissions
import com.aircursor.ui.AirCursorTheme
import com.aircursor.ui.CloudPcScreen
import com.aircursor.ui.HomeScreen
import com.aircursor.ui.LockScreen
import com.aircursor.ui.OnboardingScreen
import com.aircursor.ui.PreviewScreen
import com.aircursor.ui.ProScreen
import com.aircursor.ui.SecurityScreen

class MainActivity : ComponentActivity() {
    companion object {
        /** Opens a screen directly, e.g. "pro" from the Cloud PC icon when the plan has ended. */
        const val EXTRA_OPEN = "open"
    }

    private val requested = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested.value = intent?.getStringExtra(EXTRA_OPEN)
        val needsSetup = !Permissions.camera(this) || !Permissions.accessibility(this)
        setContent {
            AirCursorTheme {
                var screen by rememberSaveable { mutableStateOf(if (needsSetup) "setup" else "home") }
                val want = requested.value
                LaunchedEffect(want) {
                    if (want != null && !needsSetup) screen = want
                    requested.value = null
                }
                // Re-checked every time the app comes back to the front.
                var lockTick by remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    AppLock.onAppShown()
                    lockTick++
                }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { AppLock.onAppHidden() }
                val locked = remember(lockTick) { Security(this).hasPin && !AppLock.isUnlocked() }
                var unlockedNow by remember(lockTick) { mutableStateOf(false) }

                if (locked && !unlockedNow) {
                    LockScreen(onUnlocked = { unlockedNow = true })
                } else {
                    BackHandler(enabled = screen != "home") { screen = "home" }
                    when (screen) {
                        "setup" -> OnboardingScreen(onFinish = { screen = "home" })
                        "preview" -> PreviewScreen(onBack = { screen = "home" })
                        "security" -> SecurityScreen(onBack = { screen = "home" })
                        "pro" -> ProScreen(onBack = { screen = "home" })
                        "cloudpc" -> CloudPcScreen(onBack = { screen = "home" })
                        else -> HomeScreen(
                            onOpenSetup = { screen = "setup" },
                            onOpenPreview = { screen = "preview" },
                            onOpenSecurity = { screen = "security" },
                            onOpenPro = { screen = "pro" },
                            onOpenCloudPc = { screen = "cloudpc" },
                        )
                    }
                }
            }
        }
    }
}
