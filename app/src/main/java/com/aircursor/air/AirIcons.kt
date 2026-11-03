package com.aircursor.air

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Real site logos for tabs, bookmarks and shortcuts: fetched once per site and kept on the phone.
 * Callbacks run on the main thread; null means "no logo" (the caller shows a letter or the eagle).
 */
object AirIcons {
    private val mem = LruCache<String, Bitmap>(160)
    private val missing = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** The logo URL a page (new tab page, Air Store) can use directly. */
    fun url(host: String, size: Int = 64) =
        "https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$host&size=$size"

    fun get(c: Context, pageUrl: String, done: (Bitmap?) -> Unit) {
        val host = AirData.host(pageUrl)
        if (host.isEmpty() || host == "127.0.0.1" || host == "localhost" || !pageUrl.startsWith("http")) {
            done(null)
            return
        }
        mem.get(host)?.let {
            done(it)
            return
        }
        if (host in missing) {
            done(null)
            return
        }
        waiting[host]?.let {
            it += done
            return
        }
        waiting[host] = mutableListOf(done)
        val dir = File(c.applicationContext.cacheDir, "air-icons").apply { mkdirs() }
        pool.execute {
            val f = File(dir, "$host.png")
            var bmp: Bitmap? = null
            if (f.isFile && System.currentTimeMillis() - f.lastModified() < 14L * 24 * 3600 * 1000) {
                bmp = BitmapFactory.decodeFile(f.path)
            }
            if (bmp == null) {
                try {
                    val conn = URL(url(host)).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "AirCursor-Air/1.0")
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) f.writeBytes(bytes)
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
            val result = bmp
            main.post {
                if (result != null) mem.put(host, result) else missing += host
                waiting.remove(host)?.forEach { it(result) }
            }
        }
    }
}
