package com.aircursor.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.R
import com.aircursor.auth.AppUser
import com.aircursor.auth.AuthManager
import com.aircursor.cloudpc.CloudPcBus
import com.aircursor.cloudpc.CloudPcDesktop
import com.aircursor.cloudpc.CloudPcDesktopActivity
import com.aircursor.cloudpc.CloudPcEngine
import com.aircursor.pro.ProAccess
import com.aircursor.service.HandTrackingService
import com.aircursor.service.Permissions
import com.aircursor.service.TrackingBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onOpenSetup: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPc: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val status by TrackingBus.status.collectAsStateWithLifecycle()
    val user by AuthManager.user.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val cameraOk = remember(tick) { Permissions.camera(context) }
    val a11yOk = remember(tick, status.accessibilityConnected) { Permissions.accessibility(context) }
    var showAccount by remember { mutableStateOf(false) }

    // Live plan settings (for later subscriptions) – quietly, in the background.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ProAccess.refresh(context) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AirCursor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        user?.let { "Hi, ${it.name.substringBefore(' ')}" } ?: "Touchless control",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable { showAccount = true },
                ) { Avatar(user?.name, size = 40.dp) }
            }

            if (!cameraOk || !a11yOk) SetupNeededCard(onOpenSetup)

            HandControlCard(enabledToStart = cameraOk && a11yOk, onNeedSetup = onOpenSetup)

            PcCard(onOpenPc)

            UpdateCard()

            SectionCard {
                NavRow(R.drawable.ic_ui_sliders, "Settings", "Cursor, gestures and performance", onClick = onOpenSettings)
                NavRow(R.drawable.ic_ui_help, "Help & guide", "How to use gestures, the PC, apps and AI", tint = Brand.Sky, onClick = onOpenHelp)
                NavRow(R.drawable.ic_ui_shield, "Security", "Owner hand lock and PIN", tint = Brand.Violet, onClick = onOpenSecurity)
                NavRow(R.drawable.ic_ui_camera, "Camera test", "See what the camera sees", tint = Brand.Success, onClick = onOpenPreview)
                NavRow(
                    R.drawable.ic_ui_check, "Permissions", "Camera, accessibility and battery",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    badge = if (!cameraOk || !a11yOk) "Action needed" else null,
                    onClick = onOpenSetup,
                )
            }

            Text(
                "Hand tracking runs only on this phone – camera images never leave it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showAccount) AccountDialog(user, onDismiss = { showAccount = false })
}

@Composable
private fun SetupNeededCard(onOpenSetup: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onOpenSetup)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UiIcon(R.drawable.ic_ui_alert, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Finish setup", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Allow camera and accessibility to use hand control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        UiIcon(R.drawable.ic_ui_chevron, tint = MaterialTheme.colorScheme.onErrorContainer, size = 18.dp)
    }
}

@Composable
private fun HandControlCard(enabledToStart: Boolean, onNeedSetup: () -> Unit) {
    val context = LocalContext.current
    val status by TrackingBus.status.collectAsStateWithLifecycle()
    val on = status.running
    val line = when {
        !on -> "Off"
        status.screenOff -> "Waiting for unlock"
        status.paused -> "Paused"
        !status.accessibilityConnected -> "Accessibility service is off"
        status.handPresent -> "Hand detected · %.0f fps".format(status.fps)
        else -> "On · show your hand to the front camera"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brand.handGradient)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { UiIcon(R.drawable.ic_ui_hand, tint = Color.White, size = 26.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Hand control", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(line, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = on,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Brand.Indigo,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.6f),
                ),
                onCheckedChange = { want ->
                    if (want) {
                        if (!enabledToStart) {
                            Toast.makeText(context, "Finish the setup first", Toast.LENGTH_SHORT).show()
                            onNeedSetup()
                        } else {
                            HandTrackingService.start(context)
                        }
                    } else {
                        HandTrackingService.stop(context)
                    }
                },
            )
        }
        status.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        if (on) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    if (status.paused) "Resume" else "Pause",
                    if (status.paused) R.drawable.ic_ui_play else R.drawable.ic_ui_pause,
                    onClick = { HandTrackingService.send(context, HandTrackingService.ACTION_TOGGLE_PAUSE) },
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    "Recenter", R.drawable.ic_ui_crosshair,
                    onClick = { HandTrackingService.send(context, HandTrackingService.ACTION_RECALIBRATE) },
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    "Auto-tune", R.drawable.ic_ui_target,
                    onClick = {
                        HandTrackingService.send(context, HandTrackingService.ACTION_AUTOTUNE)
                        Toast.makeText(context, "Hold your finger still for 2 seconds", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PcCard(onOpenPc: () -> Unit) {
    val context = LocalContext.current
    val state by CloudPcBus.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val installed = remember(tick, state.version, state.busy) {
        CloudPcEngine.isReady(context) && CloudPcDesktop.isInstalled(context)
    }
    val (pill, pillColor) = when {
        state.busy -> "Setting up" to Brand.Warning
        state.desktopRunning -> "Running" to Brand.Success
        installed -> "Ready" to Brand.Sky
        else -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(R.drawable.logo_pc, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("AirCursor PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Full Ubuntu desktop on your phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(pill, pillColor)
        }
        Spacer(Modifier.height(14.dp))
        if (state.busy) {
            Text(state.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (state.progress >= 0f) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("View progress", onClick = onOpenPc)
        } else if (installed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "Open desktop",
                    onClick = {
                        if (ProAccess.allowed(context)) {
                            context.startActivity(android.content.Intent(context, CloudPcDesktopActivity::class.java))
                        } else {
                            Toast.makeText(context, "Your plan has ended", Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = R.drawable.ic_ui_play,
                    modifier = Modifier.weight(1f),
                    container = Brand.Sky,
                )
                SecondaryButton("Manage", onClick = onOpenPc, modifier = Modifier.weight(0.6f))
            }
        } else {
            PrimaryButton("Set up AirCursor PC", onClick = onOpenPc, icon = R.drawable.ic_ui_download, container = Brand.Sky)
        }
    }
}

@Composable
private fun AccountDialog(user: AppUser?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your settings and AirCursor PC files stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onDismiss()
                    scope.launch { AuthManager.signOut(context) }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Avatar(user?.name, size = 56.dp) },
        title = { Text(user?.name ?: "Account") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val detail = user?.phone ?: user?.email
                val guest = user?.guest == true
                val provider = user?.provider
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        user == null -> ""
                        guest && AuthManager.configured -> "You are not signed in."
                        guest -> "Using AirCursor without an account."
                        provider == "google.com" -> "Signed in with Google"
                        provider == "phone" -> "Signed in with phone number"
                        else -> "Signed in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (user != null && !user.guest) {
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            } else if (AuthManager.configured) {
                TextButton(onClick = {
                    onDismiss()
                    scope.launch { AuthManager.signOut(context) }
                }) { Text("Sign in") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
