package com.aircursor.air

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aircursor.R
import com.aircursor.cloudpc.CloudPcEngine
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Air's downloads: every file can go into the PC (its Downloads folder), onto the phone
 * (Downloads/AirCursor, seen by the phone's Files app) or both. Air asks each time until the
 * person picks "always". The file streams to a private temporary file while they choose.
 */
object AirDownloads {
    const val PC = "pc"
    const val PHONE = "phone"
    const val BOTH = "both"

    private val main = Handler(Looper.getMainLooper())
    private class Job(val item: AirData.Download, val tmp: File, val mime: String) {
        var dest: String? = null
        var finished = false
        var ok = false
        @Volatile var cancelled = false
    }

    private val jobs = HashMap<Long, Job>()

    /** Opens a file from the PC: the activity runs it there (xdg-open). */
    var openInPc: ((String) -> Unit)? = null

    fun pcHome(c: Context) = File(CloudPcEngine.rootfs(c), "root").takeIf { it.isDirectory }

    /** A download from a page (link, button, "Save image"). */
    fun start(a: Activity, response: WebResponse) {
        val body = response.body
        if (body == null) {
            Toast.makeText(a, "This download is empty", Toast.LENGTH_SHORT).show()
            return
        }
        // Big files over mobile data can pause for a while: do not give up after Gecko's short default.
        response.setReadTimeoutMillis(5 * 60 * 1000L)
        val headers = response.headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        val type = headers["content-type"]?.substringBefore(';')?.trim()
        val name = cleanName(URLUtil.guessFileName(response.uri, headers["content-disposition"], type))
        val total = headers["content-length"]?.toLongOrNull() ?: -1L
        stream(a, body, name, total, type)
    }

    /** Any stream (a page saved as PDF, a blob) saved the same way. */
    fun stream(a: Activity, input: InputStream, rawName: String, total: Long, type: String?) {
        AirData.load(a)
        val name = cleanName(rawName)
        val mime = type?.takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val item = AirData.Download(System.nanoTime(), name, total)
        AirData.downloads.add(0, item)
        val tmp = File(File(a.cacheDir, "air-dl").apply { mkdirs() }, "${item.id}.part")
        val job = Job(item, tmp, mime)
        jobs[item.id] = job
        AirData.saveDownloads()

        val remembered = AirData.prefs(a).getString(AirData.KEY_DOWNLOAD_TO, "") ?: ""
        val hasPc = pcHome(a) != null
        when {
            !hasPc -> choose(a, job, PHONE)
            remembered.isNotEmpty() -> choose(a, job, remembered)
            else -> ask(a, name, total) { dest -> if (dest == null) cancel(item) else choose(a, job, dest) }
        }

        Thread {
            var done = 0L
            var last = 0L
            val ok = try {
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (!job.cancelled) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 350) {
                                last = now
                                val d = done
                                main.post {
                                    item.done = d
                                    AirData.saveDownloadsLater()
                                }
                            }
                        }
                    }
                }
                !job.cancelled
            } catch (_: Exception) {
                false
            }
            val d = done
            main.post {
                item.done = d
                if (item.size <= 0) item.size = d
                job.finished = true
                job.ok = ok
                if (!ok) {
                    tmp.delete()
                    if (item.state == "running") item.state = if (job.cancelled) "cancelled" else "failed"
                    jobs.remove(item.id)
                    AirData.saveDownloads()
                    if (!job.cancelled) Toast.makeText(a, "Download failed: $name – check the internet and try again", Toast.LENGTH_LONG).show()
                } else {
                    finish(a, job)
                }
            }
        }.start()
    }

    fun cancel(item: AirData.Download) {
        val job = jobs[item.id] ?: return
        job.cancelled = true
        item.state = "cancelled"
        if (job.finished) {
            job.tmp.delete()
            jobs.remove(item.id)
        }
        AirData.saveDownloads()
    }

    private fun choose(a: Activity, job: Job, dest: String) {
        job.dest = dest
        if (job.finished && job.ok) finish(a, job)
    }

    /** Both known – the whole file and where it goes: put it there. */
    private fun finish(a: Activity, job: Job) {
        val dest = job.dest ?: return   // still asking
        if (!job.finished || job.cancelled) return
        jobs.remove(job.item.id)
        val app = a.applicationContext
        Thread {
            var pcPath: String? = null
            var phone: String? = null
            try {
                if (dest == PHONE || dest == BOTH) phone = saveToPhone(app, job.tmp, job.item.name, job.mime)
                if (dest == PC || dest == BOTH) {
                    val home = pcHome(app)
                    if (home != null) {
                        val dir = File(home, "Downloads").apply { mkdirs() }
                        val f = unique(dir, job.item.name)
                        if (!job.tmp.renameTo(f)) job.tmp.copyTo(f, overwrite = false)
                        pcPath = "Downloads/" + f.name
                    }
                }
            } catch (_: Exception) {
            }
            job.tmp.delete()
            main.post {
                job.item.pcPath = pcPath
                job.item.phoneUri = phone
                job.item.state = if (pcPath != null || phone != null) "done" else "failed"
                AirData.saveDownloads()
                val where = listOfNotNull(pcPath?.let { "the PC ($it)" }, phone?.let { "the phone (Downloads/AirCursor)" })
                Toast.makeText(
                    a,
                    if (where.isEmpty()) "Could not save ${job.item.name}" else "Saved in " + where.joinToString(" and "),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    private fun saveToPhone(c: Context, src: File, name: String, mime: String): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirCursor")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val r = c.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                r.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } } ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                r.update(uri, values, null, null)
            } catch (e: Exception) {
                r.delete(uri, null, null)
                throw e
            }
            return uri.toString()
        }
        // Android 8–9: the app's own Downloads folder (Android/data/com.aircursor/files/Download).
        val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val f = unique(dir, name)
        src.copyTo(f)
        return Uri.fromFile(f).toString()
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    private fun cleanName(n: String) = n.replace('/', '_').replace('\\', '_').trim().trimStart('.').ifBlank { "download" }.take(150)

    fun size(b: Long) = when {
        b < 0 -> ""
        b >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", b / 1e9)
        b >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", b / 1e6)
        else -> "${(b / 1000).coerceAtLeast(1)} KB"
    }

    /** Opens a finished download: in the PC, or with a phone app. */
    fun open(a: Activity, d: AirData.Download, onPhone: Boolean) {
        if (!onPhone) {
            d.pcPath?.let { openInPc?.invoke("/root/$it") }
            return
        }
        val u = d.phoneUri ?: return
        val uri = Uri.parse(u)
        if (uri.scheme == "file") {
            Toast.makeText(a, "Saved in the phone: ${uri.path}", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val type = a.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(d.name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(a, "No app on the phone opens this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** "Where should this go?" – PC, phone or both, with "always do this". */
    private fun ask(a: Activity, name: String, total: Long, done: (String?) -> Unit) {
        val dp = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).roundToInt()
        var answered = false
        lateinit var dialog: AlertDialog
        val always = CheckBox(a).apply {
            text = "Always do this (change it in Air → Settings)"
            setTextColor(0xFFB9BECA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        fun option(icon: Int, title: String, sub: String, dest: String) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF23262E.toInt())
            }
            addView(ImageView(a).apply {
                setImageResource(icon)
                setColorFilter(0xFF8EA2FF.toInt())
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(a).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                addView(TextView(a).apply {
                    text = sub
                    setTextColor(0xFF9AA0AC.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            })
            setOnClickListener {
                answered = true
                if (always.isChecked) AirData.prefs(a).edit().putString(AirData.KEY_DOWNLOAD_TO, dest).apply()
                dialog.dismiss()
                done(dest)
            }
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            setBackgroundColor(0xFF16181D.toInt())
            addView(TextView(a).apply {
                text = "Save download"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            })
            addView(TextView(a).apply {
                text = name + (if (total > 0) "  ·  " + size(total) else "")
                setTextColor(0xFF9AA0AC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, dp(14))
                maxLines = 2
            })
            val lp = { LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
            addView(option(R.drawable.ic_ui_desktop, "In the PC", "AirCursor PC → Downloads", PC), lp())
            addView(option(R.drawable.ic_ui_phone, "On the phone", "Phone storage → Downloads/AirCursor", PHONE), lp())
            addView(option(R.drawable.ic_ui_copy, "Both", "A copy in the PC and on the phone", BOTH), lp())
            addView(always)
        }
        dialog = AlertDialog.Builder(a)
            .setView(box)
            .setNegativeButton("Cancel download") { _, _ -> if (!answered) { answered = true; done(null) } }
            .setOnCancelListener { if (!answered) { answered = true; done(null) } }
            .create()
        dialog.show()
    }
}
