package com.aircursor.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Downloads AirCursor updates inside the app (Android's download service: a notification, waits
 * for the network and continues by itself when the connection drops – better than a browser for a
 * big file) and then hands the file to Android's installer.
 */
object AppUpdater {
    private const val PREFS = "app_updater"

    data class State(val status: Int, val done: Long, val total: Long)

    private fun dm(c: Context) = c.getSystemService(DownloadManager::class.java)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context, version: Int) = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AirCursor-$version.apk")

    /** Starts (or keeps) the download of [version]. */
    fun start(c: Context, version: Int) {
        if (state(c, version)?.status in setOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_SUCCESSFUL)) return
        // Older update files are no longer needed.
        c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
            ?.filter { it.name.startsWith("AirCursor-") && it.name.endsWith(".apk") }?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("AirCursor update $version")
            .setDescription("Downloading the new version…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(file(c, version)))
        val id = dm(c)?.enqueue(req) ?: return
        prefs(c).edit().putLong("id", id).putInt("version", version).apply()
    }

    /** Progress of the download of [version], or null when there is none. */
    fun state(c: Context, version: Int): State? {
        val p = prefs(c)
        if (p.getInt("version", 0) != version) return null
        val id = p.getLong("id", -1L).takeIf { it >= 0 } ?: return null
        val cur = dm(c)?.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cur.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return State(status, done, total)
        }
    }

    fun forget(c: Context) {
        val id = prefs(c).getLong("id", -1L)
        if (id >= 0) dm(c)?.remove(id)
        prefs(c).edit().clear().apply()
    }

    /** Opens Android's installer for the downloaded file (asks once to allow installs from AirCursor). */
    fun install(c: Context): Boolean {
        val id = prefs(c).getLong("id", -1L).takeIf { it >= 0 } ?: return false
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = dm(c)?.getUriForDownloadedFile(id) ?: return false
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
