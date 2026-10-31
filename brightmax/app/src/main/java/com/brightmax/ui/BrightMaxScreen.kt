package com.brightmax.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brightmax.R
import com.brightmax.hdr.HdrController
import com.brightmax.safety.SafetyPolicy
import com.brightmax.safety.StopReason
import com.brightmax.service.FloatingOverlay
import kotlin.math.roundToInt

@Composable
fun BrightMaxScreen(viewModel: MainViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val messageText = message?.let { stopReasonText(it, state.battery.tempC) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header()

                if (!state.permissions.canWriteSettings) {
                    PermissionCard(
                        title = stringResource(R.string.perm_write_title),
                        text = stringResource(R.string.perm_write_card),
                        button = stringResource(R.string.perm_grant),
                        required = true,
                        onClick = { viewModel.showDialog(DialogType.WRITE_SETTINGS) },
                    )
                }
                if (!state.permissions.notificationsGranted) {
                    PermissionCard(
                        title = stringResource(R.string.perm_notif_title),
                        text = stringResource(R.string.perm_notif_card),
                        button = stringResource(R.string.perm_allow),
                        required = false,
                        onClick = { viewModel.showDialog(DialogType.NOTIFICATIONS) },
                    )
                }

                BigToggle(
                    active = state.boost.active,
                    reduced = state.boost.thermalReduced,
                    levelPct = (state.boost.effectiveLevel * 100).roundToInt(),
                    remaining = if (state.boost.active) FloatingOverlay.formatRemaining(state.boost.remainingMs) else null,
                    warned = state.boost.warned,
                    onClick = viewModel::onToggle,
                )

                state.boost.lastStop?.takeIf { it != StopReason.USER }?.let { reason ->
                    InfoBanner(
                        text = stringResource(R.string.last_stop_prefix) + " " + stopReasonText(reason, state.boost.lastStopTempC),
                        color = HotRed,
                        onDismiss = viewModel::clearLastStop,
                    )
                }
                if (state.boost.thermalReduced) {
                    InfoBanner(text = stringResource(R.string.reduced_banner), color = AmberDeep, onDismiss = null)
                }

                LevelCard(
                    level = state.settings.level,
                    onLevel = viewModel::setLevel,
                    onMax = viewModel::setMax,
                )

                HdrCard(
                    state = state,
                    onToggle = viewModel::setHdrEnabled,
                )

                TimerCard(
                    selected = state.settings.timerMinutes,
                    onSelect = viewModel::setTimer,
                )

                OverlayCard(
                    enabled = state.settings.overlayEnabled && state.permissions.canDrawOverlays,
                    serviceRunning = state.boost.serviceRunning,
                    boostActive = state.boost.active,
                    onToggle = viewModel::setOverlayEnabled,
                    onClose = viewModel::closeAll,
                )

                StatusCard(state)

                Text(
                    stringResource(R.string.footer_safety),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // The "invisible" HDR surface: a 1 dp HDR image keeps real HDR content on screen while boosting.
            if (state.boost.hdrActive) {
                HdrImage(Modifier.align(Alignment.BottomEnd).size(1.dp))
            }
        }
    }

    when (dialog) {
        DialogType.DISCLAIMER -> DisclaimerDialog(
            onAccept = {
                viewModel.acceptDisclaimer()
                if (!state.permissions.notificationsGranted) askNotifications()
            },
        )
        DialogType.WRITE_SETTINGS -> ExplainDialog(
            title = stringResource(R.string.perm_write_title),
            text = stringResource(R.string.perm_write_explain),
            confirm = stringResource(R.string.open_settings),
            onConfirm = { openWriteSettings(context) },
            onDismiss = viewModel::dismissDialog,
        )
        DialogType.OVERLAY -> ExplainDialog(
            title = stringResource(R.string.perm_overlay_title),
            text = stringResource(R.string.perm_overlay_explain),
            confirm = stringResource(R.string.open_settings),
            onConfirm = { openOverlaySettings(context) },
            onDismiss = viewModel::dismissDialog,
        )
        DialogType.NOTIFICATIONS -> ExplainDialog(
            title = stringResource(R.string.perm_notif_title),
            text = stringResource(R.string.perm_notif_explain),
            confirm = stringResource(R.string.perm_allow),
            onConfirm = {
                viewModel.dismissDialog()
                askNotifications()
            },
            onDismiss = viewModel::dismissDialog,
            secondary = stringResource(R.string.open_settings) to { openNotificationSettings(context) },
        )
        null -> Unit
    }
}

// ------------------------------------------------------------------ sections

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Amber,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigToggle(
    active: Boolean,
    reduced: Boolean,
    levelPct: Int,
    remaining: String?,
    warned: Boolean,
    onClick: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale",
    )
    val ring by animateColorAsState(
        when {
            !active -> Color(0xFF37414F)
            warned || reduced -> HotRed
            else -> Amber
        },
        label = "ring",
    )
    val brush = if (active) {
        Brush.radialGradient(
            if (reduced) listOf(Color(0xFFFF8A65), Color(0xFFD84315)) else listOf(Color(0xFFFFF59D), Amber, AmberDeep),
        )
    } else {
        Brush.radialGradient(listOf(Color(0xFF2A323D), Color(0xFF161B22)))
    }
    val cd = stringResource(if (active) R.string.toggle_cd_on else R.string.toggle_cd_off)

    Box(
        Modifier
            .padding(vertical = 8.dp)
            .size(220.dp)
            .scale(if (active) pulse else 1f)
            .clip(CircleShape)
            .background(brush)
            .border(4.dp, ring, CircleShape)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "☀",
                fontSize = 44.sp,
                color = if (active) Color(0xFF3E2700) else Color(0xFF8B96A5),
            )
            Text(
                stringResource(if (active) R.string.boost_on else R.string.boost_off),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = if (active) Color(0xFF2B1B00) else Color.White,
            )
            Text(
                if (active) "$levelPct%" + (remaining?.let { "  ·  $it" } ?: "") else stringResource(R.string.tap_to_start),
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) Color(0xFF3E2700) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelCard(level: Float, onLevel: (Float) -> Unit, onMax: () -> Unit) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) level else dragging
    SectionCard(stringResource(R.string.level_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(shown * 100).roundToInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(72.dp),
            )
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    if (!dragging.isNaN()) onLevel(dragging)
                    dragging = Float.NaN
                },
                valueRange = SafetyPolicy.MIN_LEVEL..SafetyPolicy.MAX_LEVEL,
                steps = 9,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onMax,
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
            ) { Text(stringResource(R.string.max), fontWeight = FontWeight.Black) }
        }
        Text(
            stringResource(R.string.level_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HdrCard(state: UiState, onToggle: (Boolean) -> Unit) {
    val hdr = state.hdr
    SectionCard(stringResource(R.string.hdr_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.hdr_switch),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        !hdr.osSupported -> stringResource(R.string.hdr_need_android14)
                        !hdr.isHdr -> stringResource(R.string.hdr_not_supported)
                        state.boost.thermalReduced && state.settings.hdrEnabled -> stringResource(R.string.hdr_paused_heat)
                        state.boost.hdrActive -> stringResource(R.string.hdr_active)
                        else -> stringResource(R.string.hdr_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hdr.boostAvailable) MaterialTheme.colorScheme.onSurfaceVariant else HotRed,
                )
            }
            Switch(
                checked = state.settings.hdrEnabled && hdr.boostAvailable,
                enabled = hdr.boostAvailable,
                onCheckedChange = onToggle,
            )
        }
        if (state.boost.hdrActive) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HdrImage(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.hdr_test_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerCard(selected: Int, onSelect: (Int) -> Unit) {
    SectionCard(stringResource(R.string.timer_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SafetyPolicy.TIMER_OPTIONS_MIN.forEach { minutes ->
                FilterChip(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    label = { Text(stringResource(R.string.minutes_short, minutes)) },
                )
            }
        }
        Text(
            stringResource(R.string.timer_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverlayCard(
    enabled: Boolean,
    serviceRunning: Boolean,
    boostActive: Boolean,
    onToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    SectionCard(stringResource(R.string.overlay_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.overlay_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (serviceRunning && !boostActive) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.overlay_close)) }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val temp = state.battery.tempC
    SectionCard(stringResource(R.string.status_title)) {
        StatusRow(
            stringResource(R.string.status_level),
            if (state.boost.active) {
                "${(state.boost.effectiveLevel * 100).roundToInt()}%" +
                    if (state.boost.thermalReduced) " " + stringResource(R.string.status_reduced_suffix) else ""
            } else {
                stringResource(R.string.status_off)
            },
        )
        StatusRow(
            stringResource(R.string.status_hdr_supported),
            stringResource(if (state.hdr.isHdr) R.string.yes else R.string.no) +
                if (state.hdr.hdrTypes.isNotEmpty()) " (${state.hdr.hdrTypes.joinToString()})" else "",
            valueColor = if (state.hdr.isHdr) CoolGreen else HotRed,
        )
        state.hdr.maxLuminanceNits?.let {
            StatusRow(stringResource(R.string.status_peak_nits), stringResource(R.string.nits, it.roundToInt()))
        }
        StatusRow(
            stringResource(R.string.status_headroom),
            state.hdrRatio?.let { "%.2f×".format(it) } ?: stringResource(R.string.not_reported),
        )
        StatusRow(
            stringResource(R.string.status_battery_temp),
            temp?.let { "%.1f °C".format(it) } ?: stringResource(R.string.not_reported),
            valueColor = when {
                temp == null -> MaterialTheme.colorScheme.onSurface
                temp >= SafetyPolicy.REDUCE_TEMP_C -> HotRed
                temp >= SafetyPolicy.RECOVER_TEMP_C -> AmberDeep
                else -> CoolGreen
            },
        )
        StatusRow(
            stringResource(R.string.status_battery),
            state.battery.pct?.let {
                "$it%" + if (state.battery.charging) " ⚡" else ""
            } ?: stringResource(R.string.not_reported),
            valueColor = if ((state.battery.pct ?: 100) < SafetyPolicy.MIN_BATTERY_PCT) HotRed else MaterialTheme.colorScheme.onSurface,
        )
        StatusRow(
            stringResource(R.string.status_remaining),
            if (state.boost.active) FloatingOverlay.formatRemaining(state.boost.remainingMs) else "—",
        )
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Amber)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = Color(0x14FFFFFF))
}

@Composable
private fun PermissionCard(title: String, text: String, button: String, required: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (required) Color(0xFF3A1D1D) else Color(0xFF2A2616)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun InfoBanner(text: String, color: Color, onDismiss: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    }
}

/** Gain-mapped HDR image (Android 14+). Rendered brighter than SDR white in an HDR window. */
@Composable
private fun HdrImage(modifier: Modifier) {
    val bitmap = remember { HdrController.hdrBitmap() } ?: return
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* must be accepted explicitly */ },
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = { Text(stringResource(R.string.disclaimer_text)) },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.disclaimer_accept)) }
        },
    )
}

@Composable
private fun ExplainDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirm) } },
        dismissButton = {
            Row {
                secondary?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun stopReasonText(reason: StopReason, tempC: Float?): String = when (reason) {
    StopReason.USER -> stringResource(R.string.reason_user)
    StopReason.TIMER -> stringResource(R.string.reason_timer)
    StopReason.OVERHEAT -> stringResource(R.string.reason_overheat, tempC ?: 0f)
    StopReason.THERMAL_SEVERE -> stringResource(R.string.reason_thermal)
    StopReason.LOW_BATTERY -> stringResource(R.string.reason_low_battery)
    StopReason.SCREEN_OFF -> stringResource(R.string.reason_screen_off)
    StopReason.PERMISSION_MISSING -> stringResource(R.string.reason_permission)
    StopReason.SERVICE_STOPPED -> stringResource(R.string.reason_service_stopped)
}

// ------------------------------------------------------------------ settings screens

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun openWriteSettings(context: Context) =
    context.startSafely(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))

private fun openOverlaySettings(context: Context) =
    context.startSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))

private fun openNotificationSettings(context: Context) =
    context.startSafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
