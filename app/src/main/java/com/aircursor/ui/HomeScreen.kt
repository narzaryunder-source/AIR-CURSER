package com.aircursor.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.data.AppSettings
import com.aircursor.data.BoolSetting
import com.aircursor.data.FloatSetting
import com.aircursor.data.Prefs
import com.aircursor.service.HandTrackingService
import com.aircursor.service.Permissions
import com.aircursor.service.TrackingBus

@Composable
fun HomeScreen(onOpenSetup: () -> Unit, onOpenPreview: () -> Unit, onOpenSecurity: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val status by TrackingBus.status.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val cameraOk = remember(tick) { Permissions.camera(context) }
    val a11yOk = remember(tick, status.accessibilityConnected) { Permissions.accessibility(context) }
    val batteryOk = remember(tick) { Permissions.batteryUnrestricted(context) }
    var settingsVersion by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("AirCursor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Control your phone in the air with your finger", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            // Big on/off switch
            val on = status.running
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (on) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (on) "ON" else "OFF",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val line = when {
                            on && status.handPresent && !status.paused && !status.screenOff &&
                                status.accessibilityConnected ->
                                "Hand detected · %.0f fps tracking".format(status.fps)
                            !on -> "Tap the switch to start"
                            status.screenOff -> "Waiting for unlock"
                            status.paused -> "Paused – make a fist 1.5s to resume"
                            !status.accessibilityConnected -> "Accessibility service is off!"
                            status.handPresent -> "Hand detected · %.0f fps".format(status.fps)
                            else -> "Show your hand to the front camera"
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = on,
                        modifier = Modifier.scale(1.6f).padding(end = 12.dp),
                        onCheckedChange = { want ->
                            if (want) {
                                if (!cameraOk || !a11yOk) {
                                    Toast.makeText(context, "Finish the setup first", Toast.LENGTH_SHORT).show()
                                    onOpenSetup()
                                } else {
                                    HandTrackingService.start(context)
                                }
                            } else {
                                HandTrackingService.stop(context)
                            }
                        },
                    )
                }
            }
            status.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = on,
                    onClick = { HandTrackingService.send(context, HandTrackingService.ACTION_TOGGLE_PAUSE) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (status.paused) "Resume" else "Pause") }
                OutlinedButton(
                    enabled = on,
                    onClick = { HandTrackingService.send(context, HandTrackingService.ACTION_RECALIBRATE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Recalibrate") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPreview, modifier = Modifier.fillMaxWidth()) { Text("Test: live camera preview") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
                Text("🔒 Security: owner hand lock & PIN")
            }

            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    BoolRow(prefs, AppSettings.CLICK_ONLY)
                }
            }

            Spacer(Modifier.height(12.dp))
            UbuntuCard()

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { com.aircursor.pc.PcMode.openDesktop(context) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("🖥️  Open PC Mode", style = MaterialTheme.typography.titleMedium) }
            Text(
                "Desktop, taskbar, start menu, windows, Files, Notepad, Calculator, Paint. " +
                    "Exit from Start → \"Exit PC mode\". Your phone's files are never deleted.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Setup status
            Spacer(Modifier.height(16.dp))
            SectionTitle("Setup")
            StatusLine("Camera permission", cameraOk)
            StatusLine("Accessibility service", a11yOk)
            StatusLine("Battery unrestricted", batteryOk)
            TextButton(onClick = onOpenSetup) { Text("Open setup guide") }

            // Gesture cheat sheet
            Spacer(Modifier.height(8.dp))
            SectionTitle("Air gestures")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    GESTURE_HELP.forEach { (g, a) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(g, Modifier.weight(1.3f), fontWeight = FontWeight.SemiBold)
                            Text(a, Modifier.weight(1f))
                        }
                    }
                }
            }

            // Settings (re-created when reset so sliders reload their values)
            androidx.compose.runtime.key(settingsVersion) {
                Spacer(Modifier.height(16.dp))
                SectionTitle("Gestures on/off")
                AppSettings.GESTURES.forEach { BoolRow(prefs, it) }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Cursor movement")
                BoolRow(prefs, AppSettings.MOUSE_MODE)
                Text(
                    "Lag compensation predicts where your finger is going, so the cursor keeps up. " +
                        "Lower smoothing = steadier cursor. Higher responsiveness = less lag on fast moves.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AppSettings.SLIDERS.forEach { SliderRow(prefs, it) }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Performance")
                Text(
                    "Camera changes restart the camera instantly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AppSettings.PERFORMANCE_OPTIONS.forEach { BoolRow(prefs, it) }
                AppSettings.PERFORMANCE_SLIDERS.forEach { SliderRow(prefs, it) }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Options")
                AppSettings.OPTIONS.forEach { BoolRow(prefs, it) }
            }
            TextButton(onClick = {
                prefs.resetAll()
                settingsVersion++
            }) { Text("Reset all settings to default") }

            Spacer(Modifier.height(16.dp))
            Text(
                "All processing happens on this phone. Nothing is sent to the internet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val GESTURE_HELP = listOf(
    "☝️ Move index finger" to "Move cursor",
    "🤏 Quick pinch" to "Tap",
    "🤏 Pinch & hold 0.5s" to "Long press",
    "🤏 Pinch & move" to "Drag",
    "✌️ Two fingers up/down" to "Scroll",
    "✌️ Two fingers left/right" to "Swipe sideways",
    "✋ Palm swipe left" to "Back",
    "✋ Palm hold still 1s" to "Home",
    "✋ Palm swipe down" to "Notifications",
    "✋ Palm swipe up" to "Recent apps",
    "✊ Fist hold 1.5s" to "Pause / Resume",
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✗", color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun BoolRow(prefs: Prefs, setting: BoolSetting) {
    var checked by remember { mutableStateOf(prefs.get(setting)) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(setting.label)
            if (setting.description.isNotEmpty()) {
                Text(setting.description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = {
            checked = it
            prefs.set(setting, it)
        })
    }
    HorizontalDivider()
}

@Composable
private fun SliderRow(prefs: Prefs, setting: FloatSetting) {
    var value by remember { mutableFloatStateOf(prefs.get(setting)) }
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(setting.label, Modifier.weight(1f))
            Text(setting.format(value), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            valueRange = setting.min..setting.max,
            steps = setting.steps,
            onValueChange = {
                value = it
                prefs.set(setting, it)
            },
        )
    }
}
