package com.aircursor.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aircursor.service.Permissions

private class Step(
    val title: String,
    val body: String,
    val done: Boolean?,
    val optional: Boolean,
    val actionLabel: String?,
    val action: () -> Unit,
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val cameraOk = remember(tick) { Permissions.camera(context) }
    val notifOk = remember(tick) { Permissions.notifications(context) }
    val a11yOk = remember(tick) { Permissions.accessibility(context) }
    val batteryOk = remember(tick) { Permissions.batteryUnrestricted(context) }

    val steps = buildList {
        add(
            Step(
                "Welcome to AirCursor",
                "AirCursor puts a PC-style mouse pointer on your phone. You move it in the air with your " +
                    "index finger in front of the front camera, and pinch to click.\n\n" +
                    "Privacy: all hand tracking runs on this phone. No image or data is ever sent to the internet.\n\n" +
                    "The next few steps give AirCursor the permissions it needs.",
                null, true, null, {},
            ),
        )
        add(
            Step(
                "1. Camera",
                "AirCursor needs the front camera to see your hand. Frames are processed instantly and never saved.",
                cameraOk, false, "Allow camera",
            ) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Step(
                    "2. Notifications",
                    "Shows a small notification while AirCursor is on, with Pause, Recalibrate and Turn off buttons.",
                    notifOk, true, "Allow notifications",
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            add(
                Step(
                    "3. Allow restricted settings",
                    "If you installed AirCursor as an APK file (not from Play Store), Android 13+ greys out the " +
                        "Accessibility switch for it and says \"Restricted setting\".\n\n" +
                        "Fix it once:\n" +
                        "• First try the next step. If the switch is grey, come back here.\n" +
                        "• Tap \"Open App info\" below.\n" +
                        "• Tap the ⋮ (three dots) menu at the top right.\n" +
                        "• Tap \"Allow restricted settings\" and confirm with your PIN/fingerprint.\n" +
                        "• Go back and continue.\n\n" +
                        "(The ⋮ option only appears after you have tried to turn the accessibility switch on once.)",
                    if (a11yOk) true else null, true, "Open App info",
                ) {
                    Intents.appInfo(context)
                },
            )
        }
        add(
            Step(
                "${if (Build.VERSION.SDK_INT >= 33) 4 else 2}. Accessibility service",
                "AirCursor uses an Accessibility service to draw the cursor over other apps and to tap, " +
                    "swipe, go Back/Home and open notifications for you. It does not read your screen content.\n\n" +
                    "Tap the button → find \"AirCursor\" (sometimes under \"Installed apps\" or \"Downloaded apps\") → " +
                    "turn it On → Allow.",
                a11yOk, false, "Open Accessibility settings",
            ) {
                Intents.accessibility(context)
            },
        )
        add(
            Step(
                "${if (Build.VERSION.SDK_INT >= 33) 5 else 3}. Battery",
                "Allow AirCursor to run without battery restrictions so Android does not stop the camera " +
                    "service while you use other apps.",
                batteryOk, true, "Remove battery restriction",
            ) {
                Intents.battery(context)
            },
        )
        add(
            Step(
                "All set!",
                "Turn AirCursor on from the big switch, the Quick Settings tile, or the notification.\n\n" +
                    "Tip: add the AirCursor tile: pull down Quick Settings → ✎ edit → drag \"AirCursor\" up.",
                null, true, null, {},
            ),
        )
    }

    var index by rememberSaveable { mutableIntStateOf(0) }
    if (index > steps.lastIndex) index = steps.lastIndex
    val step = steps[index]

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Step ${index + 1} of ${steps.size}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (index + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Card(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(step.body, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    when (step.done) {
                        true -> Text("✓ Done", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        false -> Text(
                            if (step.optional) "Not done (optional)" else "Not done yet",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        null -> Unit
                    }
                    if (step.actionLabel != null && step.done != true) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = step.action, modifier = Modifier.fillMaxWidth()) { Text(step.actionLabel) }
                    }
                    if (step.title.contains("Camera") && step.done == false) {
                        TextButton(onClick = { Intents.appInfo(context) }) {
                            Text("Denied before? Open App info → Permissions → Camera")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { if (index > 0) index-- else onFinish() }) {
                    Text(if (index > 0) "Back" else "Skip")
                }
                val last = index == steps.lastIndex
                val canGo = step.done != false || step.optional
                Button(
                    enabled = canGo,
                    onClick = { if (last) onFinish() else index++ },
                ) {
                    Text(
                        when {
                            last -> "Finish"
                            step.done == false && step.optional -> "Skip"
                            else -> "Next"
                        },
                    )
                }
            }
        }
    }
}
