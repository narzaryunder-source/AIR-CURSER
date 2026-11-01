package com.aircursor.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.R
import com.aircursor.cloudpc.AirBoost
import com.aircursor.cloudpc.ChildLimit
import com.aircursor.cloudpc.CloudPcBus
import com.aircursor.cloudpc.CloudPcDesktop
import com.aircursor.cloudpc.CloudPcDesktopActivity
import com.aircursor.cloudpc.CloudPcEngine
import com.aircursor.cloudpc.CloudPcService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

/** AirCursor PC: install (one step), open, AirBoost, tools and the log. */
@Composable
fun CloudPcScreen(onBack: () -> Unit, onNeedPro: () -> Unit = {}) {
    val context = LocalContext.current
    val state by CloudPcBus.state.collectAsStateWithLifecycle()
    val log by CloudPcBus.log.collectAsStateWithLifecycle()
    val supported = remember { CloudPcEngine.isSupported() }
    val ram = remember { CloudPcEngine.totalRam(context) }
    val extracted = remember(state.version, state.busy) { CloudPcEngine.isExtracted(context) }
    val ready = remember(state.version, state.busy) { CloudPcEngine.isReady(context) }
    val desktopInstalled = remember(state.version, state.busy) { CloudPcDesktop.isInstalled(context) }
    val installed = ready && desktopInstalled
    var free by remember { mutableLongStateOf(0L) }
    var used by remember { mutableLongStateOf(0L) }
    var boost by remember { mutableStateOf(AirBoost.current(context)) }
    var fpsLimit by remember { mutableStateOf(AirBoost.fpsLimit(context)) }
    var showLimitCard by remember { mutableStateOf(false) }
    var programs by remember { mutableStateOf(0) }
    var command by remember { mutableStateOf("") }
    var showCommand by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showTermux by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(state.version, state.busy, state.desktopRunning) {
        withContext(Dispatchers.IO) {
            free = CloudPcEngine.freeBytes(context)
            used = CloudPcEngine.usedBytes(context)
            showLimitCard = ChildLimit.shouldShow(context)
            programs = ChildLimit.runningPrograms()
        }
    }

    fun runCmd(cmd: String) {
        showLog = true
        CloudPcService.send(context, CloudPcService.ACTION_RUN, cmd)
    }

    ScreenScaffold("AirCursor PC", onBack) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(R.drawable.logo_pc, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ubuntu 24.04 LTS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Desktop, browser, apps and AI on your phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val (pill, color) = when {
                    state.busy -> "Working" to Brand.Warning
                    state.desktopRunning -> "Running" to Brand.Success
                    installed -> "Ready" to Brand.Sky
                    else -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                StatusPill(pill, color)
            }
            if (used > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Uses ${gb(used)} · ${gb(free)} free on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.busy -> SectionCard(title = "In progress") {
                Text(state.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can leave this screen – it continues in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Cancel", onClick = { CloudPcService.send(context, CloudPcService.ACTION_STOP) }, icon = R.drawable.ic_ui_stop)
            }
            !installed -> SectionCard(title = "Install") {
                CheckLine("64-bit phone", supported)
                CheckLine("Memory: ${gb(ram)} (4 GB or more works best)", ram >= 3_000_000_000L)
                CheckLine("Free space: ${gb(free)} (3 GB needed)", free >= 3_000_000_000L || extracted)
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    if (extracted) "Continue setup" else "Install AirCursor PC",
                    onClick = {
                        CloudPcBus.clearLog()
                        CloudPcService.send(context, CloudPcService.ACTION_INSTALL)
                    },
                    icon = R.drawable.ic_ui_download,
                    enabled = supported,
                    container = Brand.Sky,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "About 450 MB download and 5–15 minutes. Wi-Fi recommended. Everything stays on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> SectionCard {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton(
                    if (state.desktopRunning) "Return to desktop" else "Open desktop",
                    onClick = { context.startActivity(Intent(context, CloudPcDesktopActivity::class.java)) },
                    icon = R.drawable.ic_ui_play,
                    container = Brand.Sky,
                )
                if (state.desktopRunning) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        "Stop desktop",
                        onClick = { CloudPcService.send(context, CloudPcService.ACTION_DESKTOP_STOP) },
                        icon = R.drawable.ic_ui_stop,
                    )
                }
            }
        }

        if (installed && showLimitCard) {
            SectionCard(title = "Let big apps run (VS Code, games)") {
                Text(
                    "Android 12 and newer close an app's programs when it runs more than ${ChildLimit.LIMIT} of them. " +
                        "The desktop needs about 20; VS Code, Firefox with tabs or a 3D game push it over, and Android " +
                        "closes them at once. Lift this limit once:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ChildLimit.steps().forEachIndexed { i, step ->
                    Text(
                        "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (state.desktopRunning && programs > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Programs running now: $programs (Android limit ${ChildLimit.LIMIT})",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (programs >= ChildLimit.LIMIT - 4) Brand.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Open settings", { ChildLimit.openSettings(context) }, Modifier.weight(1f))
                    SecondaryButton("Done", {
                        ChildLimit.hide(context)
                        showLimitCard = false
                    }, Modifier.weight(1f))
                }
            }
        }

        if (installed) {
            SectionCard(title = "AirBoost") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in AirBoost.entries) {
                        SegmentOption(b.label, b == boost) {
                            boost = b
                            AirBoost.applyNow(context, b)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(boost.about, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "AirBoost draws the desktop with fewer pixels and sharpens it on the phone – more FPS, still clear. " +
                        "A new profile applies at once, even while the desktop is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Steady FPS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in AirBoost.FPS_CHOICES) {
                        SegmentOption(if (n > 0) "$n" else "Max", n == fpsLimit) {
                            fpsLimit = n
                            AirBoost.setFpsLimit(context, n)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Games and the desktop are held at this rate. A steady 30 or 45 feels smoother than jumping " +
                        "between 15 and 90, and the phone stays cooler, so it does not slow down after a few minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (extracted && !state.busy) {
            SectionCard(title = "Tools") {
                if (ready) {
                    NavRow(R.drawable.ic_ui_refresh, "Update system", "Install the latest Ubuntu updates") {
                        runCmd("apt-get update && apt-get -y upgrade")
                    }
                    NavRow(R.drawable.ic_ui_terminal, "Run a command", "For advanced users", tint = Brand.Violet) {
                        showCommand = !showCommand
                    }
                    if (showCommand) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("Command, e.g. apt-get install -y htop") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Run", onClick = { runCmd(command) }, enabled = command.isNotBlank(), icon = R.drawable.ic_ui_play)
                    }
                }
                NavRow(R.drawable.ic_ui_info, if (showLog) "Hide log" else "Show log", "Technical details", tint = Brand.Sky) {
                    showLog = !showLog
                }
                NavRow(
                    R.drawable.ic_ui_trash, "Remove AirCursor PC", "Deletes the desktop and its files from this phone",
                    tint = Brand.Danger,
                ) { confirmRemove = true }
            }
        }

        if ((showLog || state.busy || state.error != null) && log.isNotEmpty()) {
            LogBox(log)
        }

        SectionCard(title = "Advanced") {
            NavRow(
                R.drawable.ic_ui_terminal, "Termux mode", "Only for phones that were set up with Termux",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { showTermux = !showTermux }
            if (showTermux) UbuntuCard(onNeedPro = onNeedPro)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove AirCursor PC?") },
            text = {
                Text(
                    "This deletes the desktop, installed apps and files inside AirCursor PC (${gb(used)}). " +
                        "Your phone's own photos and files are not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    CloudPcBus.clearLog()
                    CloudPcService.send(context, CloudPcService.ACTION_REMOVE)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Card(
        Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1020)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            Text(
                lines.joinToString("\n"),
                color = Color(0xFFA5B4FC),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}
