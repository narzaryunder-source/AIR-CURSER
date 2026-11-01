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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aircursor.pro.ProAccess
import com.aircursor.ubuntu.UbuntuMode

/** Termux mode (older setup): starts the Termux Ubuntu desktop + Termux:X11 + hand cursor. */
@Composable
fun UbuntuCard(onNeedPro: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val termux = remember(tick) { UbuntuMode.isInstalled(context, UbuntuMode.TERMUX) }
    val x11 = remember(tick) { UbuntuMode.isInstalled(context, UbuntuMode.X11) }
    val allowed = remember(tick) { UbuntuMode.hasPermission(context) }

    var pending by remember { mutableStateOf<String?>(null) }

    fun startNow() {
        val err = UbuntuMode.start(context)
        Toast.makeText(
            context,
            if (err != null) "Termux did not accept the command. Try Repair." else "Starting the Termux desktop…",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun repairNow() {
        val err = UbuntuMode.repair(context)
        Toast.makeText(
            context,
            if (err != null) "Termux did not accept the command: $err" else "Repair and update running in the background (3–10 min).",
            Toast.LENGTH_LONG,
        ).show()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        val action = pending
        pending = null
        if (!ok) Toast.makeText(context, "Permission is needed for Termux mode", Toast.LENGTH_LONG).show()
        else if (action == "repair") repairNow()
        else startNow()
    }

    fun run(action: String) {
        if (action == "start" && !ProAccess.allowed(context)) {
            onNeedPro()
            return
        }
        if (!allowed) {
            pending = action
            permissionLauncher.launch(UbuntuMode.PERMISSION)
            return
        }
        if (action == "repair") repairNow() else startNow()
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        CheckLine("Termux installed", termux)
        CheckLine("Termux:X11 installed", x11)
        CheckLine("Permission to run commands in Termux", allowed)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Start", onClick = { run("start") }, enabled = termux && x11, modifier = Modifier.weight(1f))
            SecondaryButton(
                "Stop",
                onClick = {
                    UbuntuMode.stop(context)
                    Toast.makeText(context, "Termux desktop stopped", Toast.LENGTH_SHORT).show()
                },
                enabled = termux,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Repair and update", onClick = { run("repair") }, enabled = termux, icon = com.aircursor.R.drawable.ic_ui_refresh)
    }
}
