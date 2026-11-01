package com.aircursor.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aircursor.ubuntu.UbuntuMode

/** Home screen card: one tap starts Ubuntu desktop + Termux:X11 + hand cursor. */
@Composable
fun UbuntuCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val termux = remember(tick) { UbuntuMode.isInstalled(context, UbuntuMode.TERMUX) }
    val x11 = remember(tick) { UbuntuMode.isInstalled(context, UbuntuMode.X11) }
    val allowed = remember(tick) { UbuntuMode.hasPermission(context) }

    fun startNow() {
        val err = UbuntuMode.start(context)
        if (err != null) {
            Toast.makeText(
                context,
                "Termux ne command nahi li. Termux me ek baar chalao: bash fix.sh --keep",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(context, "Ubuntu start ho raha hai… (1-2 min)", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (ok) startNow()
        else Toast.makeText(context, "Permission ke bina Ubuntu Mode nahi chalega", Toast.LENGTH_LONG).show()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("🐧 Ubuntu Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Ek tap: Ubuntu desktop + Termux:X11 + haath se cursor", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Check("Termux installed", termux)
            Check("Termux:X11 installed", x11)
            Check("Permission: run commands in Termux", allowed)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = termux && x11,
                    onClick = { if (allowed) startNow() else permissionLauncher.launch(UbuntuMode.PERMISSION) },
                    modifier = Modifier.weight(1f),
                ) { Text("▶ Start") }
                OutlinedButton(
                    enabled = termux,
                    onClick = {
                        UbuntuMode.stop(context)
                        Toast.makeText(context, "Ubuntu band", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("■ Stop") }
            }
            Text(
                "Phone ki home screen par \"Ubuntu PC\" icon se bhi seedha khulta hai. " +
                    "Pehli baar ek hi baar: Termux me 'bash fix.sh --keep'. " +
                    "Termux:X11 → settings → Input → Touchscreen mode = Direct touch.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
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
