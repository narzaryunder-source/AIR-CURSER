package com.aircursor.ui

import android.app.Activity
import android.app.KeyguardManager
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.core.PinHasher
import com.aircursor.data.AppLock
import com.aircursor.data.AppSettings
import com.aircursor.data.Prefs
import com.aircursor.data.Security
import com.aircursor.service.HandTrackingService
import com.aircursor.service.OwnerBus
import com.aircursor.service.Permissions
import com.aircursor.service.TrackingBus

// ---------------------------------------------------------------- PIN widgets

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Launcher for the phone's own screen lock (fingerprint / pattern / PIN). */
@Composable
fun rememberDeviceCredential(onSuccess: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onSuccess()
    }
    return {
        val km = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = km?.createConfirmDeviceCredentialIntent(
            "AirCursor",
            "Confirm it's you to reset the AirCursor PIN",
        )
        if (intent == null) {
            Toast.makeText(
                context,
                "This phone has no screen lock. Clear AirCursor's app data to reset the PIN.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            launcher.launch(intent)
        }
    }
}

/** Shown when the app is opened and a PIN is set. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val security = remember { Security(context) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val forgot = rememberDeviceCredential {
        security.clearPinAndLock()
        AppLock.unlock()
        Toast.makeText(context, "PIN removed. Set a new one in Security.", Toast.LENGTH_LONG).show()
        onUnlocked()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🔒 AirCursor is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            PinField(pin, { pin = it; error = null }, "Enter PIN")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    when {
                        security.isLockedOut() -> error = "Too many tries. Wait ${security.lockOutSecondsLeft()} s"
                        security.checkPin(pin) -> {
                            AppLock.unlock()
                            onUnlocked()
                        }
                        else -> {
                            pin = ""
                            error = "Wrong PIN"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
            TextButton(onClick = forgot) { Text("Forgot PIN? Use phone fingerprint / screen lock") }
        }
    }
}

/** Asks for the current PIN before a protected action. */
@Composable
private fun ConfirmPinDialog(title: String, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val context = LocalContext.current
    val security = remember { Security(context) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PinField(pin, { pin = it; error = null }, "Current PIN")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    security.isLockedOut() -> error = "Too many tries. Wait ${security.lockOutSecondsLeft()} s"
                    security.checkPin(pin) -> onConfirmed()
                    else -> {
                        pin = ""
                        error = "Wrong PIN"
                    }
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Security screen

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val security = remember { Security(context) }
    val prefs = remember { Prefs(context) }
    var version by remember { mutableIntStateOf(0) } // bump to re-read stored values
    val hasPin = remember(version) { security.hasPin }
    val profile = remember(version) { security.ownerProfile }
    val lockOn = remember(version) { security.ownerLock }
    val status by TrackingBus.status.collectAsStateWithLifecycle()
    val check by OwnerBus.check.collectAsStateWithLifecycle()
    val enroll by OwnerBus.enroll.collectAsStateWithLifecycle()

    // Pending protected action waiting for the PIN.
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var changingPin by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            OwnerBus.enrollRequested = false
            OwnerBus.postEnroll(null)
        }
    }
    if (enroll?.done == true) {
        // Profile was saved by the service: refresh once.
        LaunchedOnce(enroll) { version++ }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Security", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (!hasPin || changingPin) {
                NewPinCard(
                    title = if (changingPin) "Choose a new PIN" else "Step 1: Create a PIN",
                    onSaved = {
                        security.setPin(it)
                        AppLock.unlock()
                        changingPin = false
                        version++
                        Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
                    },
                )
                if (changingPin) TextButton(onClick = { changingPin = false }) { Text("Cancel") }
                Text(
                    "The PIN is needed to open AirCursor, re-register your hand or turn the lock off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                ProtectedSections(
                    security = security,
                    prefs = prefs,
                    profileHandedness = profile?.handedness,
                    lockOn = lockOn,
                    running = status.running,
                    checkState = check.state,
                    checkScore = check.score,
                    enroll = enroll,
                    onConfirm = { confirm = it },
                    onChangePin = { changingPin = true },
                    refresh = { version++ },
                )
            }
        }
    }

    confirm?.let { (title, action) ->
        ConfirmPinDialog(title, onDismiss = { confirm = null }) {
            confirm = null
            action()
        }
    }
}

@Composable
private fun ProtectedSections(
    security: Security,
    prefs: Prefs,
    profileHandedness: String?,
    lockOn: Boolean,
    running: Boolean,
    checkState: String,
    checkScore: Float,
    enroll: com.aircursor.service.EnrollState?,
    onConfirm: (Pair<String, () -> Unit>) -> Unit,
    onChangePin: () -> Unit,
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    Column {

            // ---- Owner hand
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("✋ Owner hand lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (profileHandedness == null) "No hand registered yet."
                        else "Registered: $profileHandedness hand",
                    )
                    Spacer(Modifier.height(8.dp))

                    val e = enroll
                    if (e != null && !e.done) {
                        Text("Registering… ${(e.progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
                        Text(e.hint)
                        TextButton(onClick = {
                            OwnerBus.enrollRequested = false
                            OwnerBus.postEnroll(null)
                        }) { Text("Cancel") }
                    } else {
                        if (e?.done == true) Text("✓ ${e.hint}", color = Color(0xFF2E7D32))
                        Button(
                            onClick = {
                                val start = {
                                    if (!running && Permissions.camera(context)) HandTrackingService.start(context)
                                    OwnerBus.postEnroll(EnrollState0)
                                    OwnerBus.enrollRequested = true
                                }
                                if (profileHandedness == null) start() else onConfirm("Re-register hand" to start)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (profileHandedness == null) "Register my hand" else "Re-register my hand") }
                        Text(
                            "Hold your hand 30–60 cm from the camera, open palm, fingers straight, good light.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (profileHandedness != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Only my hand can control")
                                Text(
                                    "Other people's hands are ignored.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = lockOn, onCheckedChange = { want ->
                                if (want) {
                                    security.ownerLock = true
                                    refresh()
                                } else {
                                    onConfirm("Turn off hand lock" to {
                                        security.ownerLock = false
                                        refresh()
                                    })
                                }
                            })
                        }
                        StrictnessSlider(prefs)
                        if (lockOn && running) {
                            val (label, color) = when (checkState) {
                                "OWNER" -> "✓ Owner's hand" to Color(0xFF2E7D32)
                                "STRANGER" -> "✗ Not the owner's hand" to MaterialTheme.colorScheme.error
                                "CHECKING" -> "… checking" to MaterialTheme.colorScheme.onSurface
                                else -> "No hand in view" to MaterialTheme.colorScheme.onSurface
                            }
                            Text("Live test: $label (score %.1f)".format(checkScore), color = color)
                            Text(
                                "Lower score = more like your hand. Ask someone else to try: if their hand " +
                                    "shows as owner, set strictness to Strict.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            onConfirm("Delete registered hand" to {
                                security.removeOwnerProfile()
                                refresh()
                            })
                        }) { Text("Delete registered hand") }
                    }
                }
            }

            // ---- PIN
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🔑 PIN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("AirCursor asks for the PIN when it is opened.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onConfirm("Change PIN" to onChangePin) }) {
                            Text("Change PIN")
                        }
                        OutlinedButton(onClick = {
                            onConfirm("Remove PIN and lock" to {
                                security.clearPinAndLock()
                                refresh()
                            })
                        }) { Text("Remove PIN") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Note: the hand check compares bone proportions of your hand. It is not as strong as a " +
                    "fingerprint – a very similar hand may pass. Everything stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

private val EnrollState0 = com.aircursor.service.EnrollState(0f, "Starting camera…")

@Composable
private fun LaunchedOnce(key: Any?, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}

@Composable
private fun NewPinCard(title: String, onSaved: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            PinField(a, { a = it; error = null }, "New PIN (4–8 digits)")
            PinField(b, { b = it; error = null }, "Repeat PIN")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                error = when {
                    !PinHasher.isValidPin(a) -> "Use 4 to 8 digits"
                    a != b -> "The two PINs are different"
                    else -> null
                }
                if (error == null) onSaved(a)
            }, modifier = Modifier.fillMaxWidth()) { Text("Save PIN") }
        }
    }
}

@Composable
private fun StrictnessSlider(prefs: Prefs) {
    val s = AppSettings.OWNER_STRICTNESS
    var value by remember { mutableFloatStateOf(prefs.get(s)) }
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(s.label, Modifier.weight(1f))
            Text(s.format(value), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            valueRange = s.min..s.max,
            steps = s.steps,
            onValueChange = {
                value = it
                prefs.set(s, it)
            },
        )
    }
}
