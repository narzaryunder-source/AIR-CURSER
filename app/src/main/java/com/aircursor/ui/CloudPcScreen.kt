package com.aircursor.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.cloudpc.CloudPcBus
import com.aircursor.cloudpc.CloudPcEngine
import com.aircursor.cloudpc.CloudPcService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

/** Built-in Cloud PC (no Termux): phone check, install with progress, command runner, log. */
@Composable
fun CloudPcScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CloudPcBus.state.collectAsStateWithLifecycle()
    val log by CloudPcBus.log.collectAsStateWithLifecycle()
    val supported = remember { CloudPcEngine.isSupported() }
    val ram = remember { CloudPcEngine.totalRam(context) }
    val extracted = remember(state.version, state.busy) { CloudPcEngine.isExtracted(context) }
    val ready = remember(state.version, state.busy) { CloudPcEngine.isReady(context) }
    var free by remember { mutableStateOf(0L) }
    var used by remember { mutableStateOf(0L) }
    var command by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(state.version, state.busy) {
        withContext(Dispatchers.IO) {
            free = CloudPcEngine.freeBytes(context)
            used = CloudPcEngine.usedBytes(context)
        }
    }

    fun run(cmd: String) = CloudPcService.send(context, CloudPcService.ACTION_RUN, cmd)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("☁️ Cloud PC – built-in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("BETA · Termux ki zaroorat nahi", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(
                "${CloudPcEngine.UBUNTU_LABEL} isi phone ke andar, AirCursor ki private jagah me. " +
                    "Har phone ka apna alag Cloud PC – kisi aur se files mix nahi hoti.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Phone check", fontWeight = FontWeight.Bold)
                    Check("64-bit phone (arm64)", supported)
                    Check("RAM: ${gb(ram)} (4 GB+ achha)", ram >= 3_000_000_000L)
                    Check("Khali jagah: ${gb(free)} (desktop ke liye 6 GB+)", free >= 1_500_000_000L)
                    if (used > 0) Text("Cloud PC abhi ${gb(used)} use kar raha hai", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(12.dp))
            val (statusText, statusColor) = when {
                ready -> "✅ Ready" to Color(0xFF2E7D32)
                extracted -> "⚠️ Files aa gayi, setup baaki – Install dabao" to Color(0xFFE65100)
                else -> "Install nahi hua" to MaterialTheme.colorScheme.onSurface
            }
            Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            if (state.busy) {
                Spacer(Modifier.height(8.dp))
                Text(state.title)
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = { CloudPcService.send(context, CloudPcService.ACTION_STOP) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("■ Stop") }
            } else {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (!ready) {
                    Button(
                        enabled = supported,
                        onClick = {
                            CloudPcBus.clearLog()
                            CloudPcService.send(context, CloudPcService.ACTION_INSTALL)
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (extracted) "▶ Setup poora karo (Retry)" else "⬇ Install Cloud PC (~30 MB + 100 MB)") }
                    Text(
                        "Wi-Fi par karo. Screen band ho jaye to bhi chalta rahega (notification dikhegi).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (ready && !state.busy) {
                Spacer(Modifier.height(12.dp))
                Text("Test & tools", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { run("cat /etc/os-release | head -n 4; uname -m; df -h / | tail -n 1; free -h") },
                        modifier = Modifier.weight(1f),
                    ) { Text("ℹ️ Info") }
                    OutlinedButton(
                        onClick = { run("apt-get update && apt-get -y upgrade") },
                        modifier = Modifier.weight(1f),
                    ) { Text("⟳ Update") }
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command (jaise: apt-get install -y htop)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = command.isNotBlank(),
                    onClick = { run(command) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("▶ Run") }
                Text(
                    "Desktop screen (XFCE + haath ka cursor) agle update me aayega.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (log.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("Log", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { CloudPcBus.clearLog() }) { Text("Clear") }
                }
                LogBox(log)
            }

            if ((extracted || ready) && !state.busy) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("🗑 Built-in Ubuntu hatao", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Built-in Ubuntu hatana hai?") },
            text = {
                Text(
                    "Cloud PC ke andar ki saari Ubuntu files aur installed apps hat jayengi (${gb(used)}). " +
                        "Phone ki photos, videos aur baaki files par KOI asar nahi.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    CloudPcBus.clearLog()
                    CloudPcService.send(context, CloudPcService.ACTION_REMOVE)
                }) { Text("Haan, hatao", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Nahi") } },
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
            .height(320.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101418)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(8.dp),
        ) {
            Text(
                lines.joinToString("\n"),
                color = Color(0xFFB9F6CA),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text(
        (if (ok) "✓ " else "✗ ") + label,
        color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
