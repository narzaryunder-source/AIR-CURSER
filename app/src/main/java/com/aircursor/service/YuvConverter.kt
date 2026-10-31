package com.aircursor.service

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Converts a YUV_420_888 camera frame into an upright ARGB bitmap in one pass
 * (conversion + rotation together, all buffers reused).
 *
 * Some phones report wrong chroma layouts, which shows up as green/purple stripes.
 * In [Mode.AUTO] the first frames are checked for that pattern and, if found, only the
 * brightness (Y) channel is used: a clean black & white image tracks hands just fine.
 */
class YuvConverter {
    enum class Mode { AUTO, COLOUR, GRAY }

    @Volatile
    var mode = Mode.AUTO
        set(value) {
            field = value
            checks = 0
            stripeVotes = 0
            colourBroken = false
        }

    /** What is actually used right now, for the debug overlay. */
    val label: String
        get() = when {
            mode == Mode.GRAY -> "B&W"
            mode == Mode.AUTO && colourBroken -> "B&W auto"
            mode == Mode.AUTO && checks < CHECK_FRAMES -> "checking"
            else -> "colour"
        }

    private var yArr = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)
    private var pixels = IntArray(0)
    private val pool = arrayOfNulls<Bitmap>(3)
    private var poolIndex = 0

    private var checks = 0
    private var stripeVotes = 0
    private var colourBroken = false

    private companion object {
        const val CHECK_FRAMES = 12
    }

    fun convert(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        yArr = copy(yPlane.buffer, yArr)

        var gray = when (mode) {
            Mode.GRAY -> true
            Mode.COLOUR -> false
            Mode.AUTO -> colourBroken
        }
        if (!gray) {
            uArr = copy(uPlane.buffer, uArr)
            vArr = copy(vPlane.buffer, vArr)
            if (mode == Mode.AUTO && checks < CHECK_FRAMES) {
                checks++
                if (looksStriped(uArr, uPlane.rowStride, uPlane.pixelStride, w, h)) stripeVotes++
                if (checks == CHECK_FRAMES && stripeVotes * 2 >= CHECK_FRAMES) {
                    colourBroken = true
                    gray = true
                }
            }
        }

        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val px = pixels
        val ys = yArr
        val us = uArr
        val vs = vArr
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        for (y in 0 until h) {
            // Destination index of (0, y) and how it moves per source pixel, for each rotation.
            var d: Int
            val step: Int
            when (rotation) {
                90 -> { d = h - 1 - y; step = h }
                180 -> { d = (h - 1 - y) * w + (w - 1); step = -1 }
                270 -> { d = (w - 1) * h + y; step = -h }
                else -> { d = y * w; step = 1 }
            }
            var yi = y * yRow
            if (gray) {
                for (x in 0 until w) {
                    val l = ys[yi].toInt() and 0xFF
                    px[d] = -0x1000000 or (l shl 16) or (l shl 8) or l
                    yi += yPix
                    d += step
                }
            } else {
                val uBase = (y shr 1) * uRow
                val vBase = (y shr 1) * vRow
                for (x in 0 until w) {
                    val l = ys[yi].toInt() and 0xFF
                    val c = x shr 1
                    val u = (us[uBase + c * uPix].toInt() and 0xFF) - 128
                    val v = (vs[vBase + c * vPix].toInt() and 0xFF) - 128
                    var r = l + ((1436 * v) shr 10)
                    var g = l - ((352 * u + 731 * v) shr 10)
                    var b = l + ((1815 * u) shr 10)
                    r = if (r < 0) 0 else if (r > 255) 255 else r
                    g = if (g < 0) 0 else if (g > 255) 255 else g
                    b = if (b < 0) 0 else if (b > 255) 255 else b
                    px[d] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    yi += yPix
                    d += step
                }
            }
        }

        // A small pool: MediaPipe may still be reading the previous frame.
        poolIndex = (poolIndex + 1) % pool.size
        val out = pool[poolIndex]?.takeIf { it.width == ow && it.height == oh }
            ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { pool[poolIndex] = it }
        out.setPixels(px, 0, ow, 0, 0, ow, oh)
        return out
    }

    private fun copy(buffer: ByteBuffer, into: ByteArray): ByteArray {
        buffer.rewind()
        val n = buffer.remaining()
        val arr = if (into.size >= n) into else ByteArray(n)
        buffer.get(arr, 0, n)
        return arr
    }

    /**
     * Neighbouring chroma samples should be similar. If every sample differs from the next one
     * but matches the one after it, U and V are interleaved where the phone said they are not.
     */
    private fun looksStriped(u: ByteArray, rowStride: Int, pixelStride: Int, w: Int, h: Int): Boolean {
        val cw = w / 2
        val chh = h / 2
        var d1 = 0L
        var d2 = 0L
        var n = 0
        for (row in 1..5) {
            val base = (chh * row / 6) * rowStride
            var k = 0
            while (k < cw - 2 && k < 200) {
                val i0 = base + k * pixelStride
                val i2 = base + (k + 2) * pixelStride
                if (i2 >= u.size) break
                val a = u[i0].toInt() and 0xFF
                val b = u[base + (k + 1) * pixelStride].toInt() and 0xFF
                val c = u[i2].toInt() and 0xFF
                d1 += abs(a - b)
                d2 += abs(a - c)
                n++
                k++
            }
        }
        if (n == 0) return false
        return d1 > d2 * 2 + n * 2L
    }
}
