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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.DownloadManager
import android.widget.Toast
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** Shows an "Update available" card with a download button when GitHub has a newer AirCursor. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val current = remember { installedVersion(context) }
    var latest by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        latest = (withContext(Dispatchers.IO) { latestRelease() } ?: 0L).toInt()
    }
    if (latest <= current) return
    // The download runs in Android's download service; the card shows its progress.
    var dl by remember { mutableStateOf(AppUpdater.state(context, latest)) }
    LaunchedEffect(latest) {
        while (true) {
            dl = AppUpdater.state(context, latest)
            delay(if (dl?.status == DownloadManager.STATUS_RUNNING || dl?.status == DownloadManager.STATUS_PENDING) 500 else 2000)
        }
    }
    SectionCard {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconBadge(com.aircursor.R.drawable.ic_ui_download, Brand.Success)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Version $latest is ready (you have $current). Your settings and files stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
        val s = dl
        when (s?.status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                val f = if (s.total > 0) s.done.toFloat() / s.total else 0f
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (s.status == DownloadManager.STATUS_PAUSED) "Waiting for the internet… it continues by itself"
                    else "Downloading… ${s.done / 1_000_000} of ${if (s.total > 0) s.total / 1_000_000 else "?"} MB " +
                        "(you can leave the app)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            DownloadManager.STATUS_SUCCESSFUL -> PrimaryButton(
                "Install update",
                onClick = {
                    if (!AppUpdater.install(context)) {
                        Toast.makeText(context, "Allow \"Install unknown apps\" for AirCursor, then tap Install again", Toast.LENGTH_LONG).show()
                    }
                },
                icon = com.aircursor.R.drawable.ic_ui_download,
                container = Brand.Success,
            )
            else -> {
                if (s?.status == DownloadManager.STATUS_FAILED) {
                    Text(
                        "The download stopped. Check the internet and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    if (s?.status == DownloadManager.STATUS_FAILED) "Try again" else "Download update",
                    onClick = {
                        if (s?.status == DownloadManager.STATUS_FAILED) AppUpdater.forget(context)
                        try {
                            AppUpdater.start(context, latest)
                            dl = AppUpdater.state(context, latest)
                        } catch (_: Exception) {
                            // No download service: the browser instead.
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)))
                            } catch (_: Exception) {
                            }
                        }
                    },
                    icon = com.aircursor.R.drawable.ic_ui_download,
                    container = Brand.Success,
                )
            }
        }
    }
}
