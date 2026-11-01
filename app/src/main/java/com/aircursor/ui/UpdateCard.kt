package com.aircursor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_API = "https://api.github.com/repos/narzaryunder-source/AIR-CURSER/releases/latest"
const val APK_URL = "https://github.com/narzaryunder-source/AIR-CURSER/releases/latest/download/AirCursor.apk"

@Suppress("DEPRECATION")
fun installedVersion(c: Context): Long = try {
    val info = c.packageManager.getPackageInfo(c.packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
} catch (e: Exception) {
    0L
}

/** Newest release number on GitHub ("v13" -> 13), or null without internet. Background thread only. */
private fun latestRelease(): Long? = try {
    val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
    conn.connectTimeout = 6000
    conn.readTimeout = 6000
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    val text = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    JSONObject(text).optString("tag_name").filter { it.isDigit() }.toLongOrNull()
} catch (e: Exception) {
    Log.i("UpdateCard", "update check failed: ${e.message}")
    null
}

/** Shows "New version" with a download button when GitHub has a newer AirCursor. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val current = remember { installedVersion(context) }
    var latest by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        latest = (withContext(Dispatchers.IO) { latestRelease() } ?: 0L).toInt()
    }
    if (latest <= current) {
        Text(
            "AirCursor v$current" + if (latest > 0) " ✓ latest" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🆕 Naya version v$latest aa gaya", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Aapke paas v$current hai. Download karke install karo – settings safe rahengi.")
            Button(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)))
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("⬇ Download update") }
        }
    }
}
