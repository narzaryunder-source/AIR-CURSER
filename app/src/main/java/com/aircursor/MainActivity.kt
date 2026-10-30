package com.aircursor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aircursor.service.Permissions
import com.aircursor.ui.AirCursorTheme
import com.aircursor.ui.HomeScreen
import com.aircursor.ui.OnboardingScreen
import com.aircursor.ui.PreviewScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needsSetup = !Permissions.camera(this) || !Permissions.accessibility(this)
        setContent {
            AirCursorTheme {
                var screen by rememberSaveable { mutableStateOf(if (needsSetup) "setup" else "home") }
                BackHandler(enabled = screen != "home") { screen = "home" }
                when (screen) {
                    "setup" -> OnboardingScreen(onFinish = { screen = "home" })
                    "preview" -> PreviewScreen(onBack = { screen = "home" })
                    else -> HomeScreen(
                        onOpenSetup = { screen = "setup" },
                        onOpenPreview = { screen = "preview" },
                    )
                }
            }
        }
    }
}
