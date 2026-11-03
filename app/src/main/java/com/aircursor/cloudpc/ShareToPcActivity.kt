package com.aircursor.cloudpc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phone share sheet → "AirCursor PC": photos, videos, documents or text from any app are copied
 * straight into the PC (Downloads/From phone). Much faster than uploading them through a web page
 * or a file dialog inside the PC, and the phone's own files are never changed.
 */
class ShareToPcActivity : ComponentActivity() {
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        if (!CloudPcEngine.isReady(this)) {
            Toast.makeText(this, "Install AirCursor PC first (AirCursor app → AirCursor PC)", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val uris = ArrayList<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (uris.isEmpty() && sharedText == null) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val d = resources.displayMetrics.density
        val pad = (20 * d).roundToInt()
        label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "Sending to AirCursor PC…"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@ShareToPcActivity), LinearLayout.LayoutParams((32 * d).roundToInt(), (32 * d).roundToInt()))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pad })
        })
        // Copy while this screen is open: the permission to read shared files lasts as long as it.
        Thread {
            val dir = File(CloudPcEngine.rootfs(this), "root/Downloads/${CloudPcTools.FROM_PHONE}").apply { mkdirs() }
            var ok = 0
            var bytes = 0L
            if (sharedText != null) {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
                try {
                    uniqueFile(dir, "Shared text $stamp.txt").writeText(sharedText + "\n")
                    ok++
                } catch (_: Exception) {
                }
            }
            for ((i, uri) in uris.withIndex()) {
                runOnUiThread { label.text = "Sending to AirCursor PC… ${i + 1} of ${uris.size}" }
                try {
                    val target = uniqueFile(dir, displayName(uri) ?: "file-${System.currentTimeMillis()}")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> bytes += input.copyTo(out, 256 * 1024) }
                    }
                    ok++
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                val total = uris.size + (if (sharedText != null) 1 else 0)
                val msg = if (ok == total) "$ok sent to AirCursor PC → Downloads/${CloudPcTools.FROM_PHONE}"
                else "$ok of $total sent (some could not be read)"
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                if (ok > 0 && CloudPcBus.state.value.desktopRunning) {
                    CloudPcService.send(
                        this, CloudPcService.ACTION_RUN,
                        CloudPcDesktop.inSession(
                            "notify-send -a 'AirCursor PC' -i phone 'From your phone' " +
                                "'$ok file(s) in Downloads/${CloudPcTools.FROM_PHONE} (${mb(bytes)})'",
                        ),
                    )
                }
                finish()
            }
        }.start()
    }

    private fun mb(b: Long) = if (b >= 1_000_000) "${(b / 100_000) / 10.0} MB" else "${(b / 1000).coerceAtLeast(1)} KB"

    private fun displayName(uri: Uri): String? {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
        return name?.replace('/', '_')?.replace('\'', '_')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }
}
