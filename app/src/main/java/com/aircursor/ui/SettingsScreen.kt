package com.aircursor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aircursor.data.AppSettings
import com.aircursor.data.Prefs

/** All hand-control settings, grouped. Changes apply at once. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var version by remember { mutableIntStateOf(0) }

    ScreenScaffold("Settings", onBack) {
        key(version) {
            SectionCard(title = "Control style") {
                SettingSwitch(prefs, AppSettings.CLICK_ONLY)
                ThinDivider()
                SettingSwitch(prefs, AppSettings.MOUSE_MODE)
                ThinDivider()
                SettingSwitch(prefs, AppSettings.DWELL)
            }
            SectionCard(title = "Pointer") {
                for (s in AppSettings.SLIDERS) SettingSlider(prefs, s)
            }
            SectionCard(title = "Gestures") {
                Text(
                    "Used when Simple mode is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (g in AppSettings.GESTURES) SettingSwitch(prefs, g)
            }
            SectionCard(title = "Feedback") {
                SettingSwitch(prefs, AppSettings.HAPTICS)
                SettingSwitch(prefs, AppSettings.CLICK_SOUND)
                SettingSwitch(prefs, AppSettings.INVERT_X)
                SettingSwitch(prefs, AppSettings.REVERSE_SCROLL)
            }
            SectionCard(title = "Performance and battery") {
                for (o in AppSettings.PERFORMANCE_OPTIONS) SettingSwitch(prefs, o)
                SettingSwitch(prefs, AppSettings.AUTO_PAUSE)
                for (s in AppSettings.PERFORMANCE_SLIDERS) SettingSlider(prefs, s)
                SettingSwitch(prefs, AppSettings.SHOW_FPS)
            }
        }
        TextButton(onClick = {
            prefs.resetAll()
            version++
        }) { Text("Reset all settings") }
    }
}
