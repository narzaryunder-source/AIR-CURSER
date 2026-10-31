package com.brightmax.hdr

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import java.util.function.Consumer

/** What the built-in display reports about HDR. */
data class HdrInfo(
    val isHdr: Boolean,
    val hdrTypes: List<String>,
    /** HdrCapabilities.desiredMaxLuminance in nits, if reported. */
    val maxLuminanceNits: Float?,
    /** Display.isHdrSdrRatioAvailable() (Android 14+): the panel can raise brightness above SDR white. */
    val headroomReported: Boolean,
    /** The OS has the HDR window APIs this app uses (Android 14+). */
    val osSupported: Boolean,
) {
    /** HDR Boost can be used: Android 14+ and an HDR-capable display. */
    val boostAvailable: Boolean get() = osSupported && isHdr

    companion object {
        val NONE = HdrInfo(false, emptyList(), null, false, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }
}

/**
 * HDR Boost through public APIs only:
 *  - Android 14+: window colorMode = COLOR_MODE_HDR, plus a tiny HDR (gain-mapped) image so HDR content
 *    is really on screen; the display then raises its peak brightness (headroom) for it.
 *  - Android 15+: also Window/LayoutParams.setDesiredHdrHeadroom() at the API maximum; the system clamps
 *    it to what the panel supports.
 */
object HdrController {
    /** Upper bound accepted by setDesiredHdrHeadroom(); the system clamps it to the panel's real maximum. */
    const val MAX_HEADROOM = 10_000f
    /** Gain stored in the HDR image (tone-mapped down to the available headroom). */
    private const val IMAGE_GAIN = 16f

    fun display(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    fun info(context: Context): HdrInfo {
        val display = display(context) ?: return HdrInfo.NONE
        val osSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val caps = runCatching { display.hdrCapabilities }.getOrNull()
        val types = if (osSupported) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            caps?.supportedHdrTypes ?: IntArray(0)
        }
        val maxNits = caps?.desiredMaxLuminance?.takeIf { it > 0f && it.isFinite() }
        return HdrInfo(
            isHdr = display.isHdr,
            hdrTypes = types.distinct().map(::hdrTypeName),
            maxLuminanceNits = maxNits,
            headroomReported = osSupported && display.isHdrSdrRatioAvailable,
            osSupported = osSupported,
        )
    }

    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "#$type"
    }

    /** Live HDR/SDR ratio of the display (1.0 = no extra headroom in use). Null when not reported. */
    fun headroomRatio(context: Context): Flow<Float?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return flowOf(null)
        val display = display(context) ?: return flowOf(null)
        if (!display.isHdrSdrRatioAvailable) return flowOf(null)
        return callbackFlow {
            val listener = Consumer<Display> { trySend(it.hdrSdrRatio) }
            trySend(display.hdrSdrRatio)
            display.registerHdrSdrRatioChangedListener(ContextCompat.getMainExecutor(context), listener)
            awaitClose { display.unregisterHdrSdrRatioChangedListener(listener) }
        }.distinctUntilChanged()
    }

    /** Turns HDR mode on/off for an activity window. */
    fun applyToWindow(window: Window, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        window.colorMode = if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 0 resets to the system default.
            window.desiredHdrHeadroom = if (enabled) MAX_HEADROOM else 0f
        }
    }

    /** Same for an overlay window's LayoutParams (caller must call updateViewLayout). */
    fun applyToParams(lp: WindowManager.LayoutParams, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        lp.colorMode = if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            lp.desiredHdrHeadroom = if (enabled) MAX_HEADROOM else 0f
        }
    }

    @Volatile private var cachedBitmap: Bitmap? = null

    /**
     * A small white Ultra-HDR style bitmap (SDR base + gain map). Drawn in an HDR window it renders brighter
     * than SDR white, which makes the display unlock its HDR headroom. Null below Android 14.
     */
    fun hdrBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return cachedBitmap ?: createHdrBitmap().also { cachedBitmap = it }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createHdrBitmap(size: Int = 8): Bitmap {
        val base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val gain = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        base.gainmap = Gainmap(gain).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(IMAGE_GAIN, IMAGE_GAIN, IMAGE_GAIN)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(IMAGE_GAIN)
        }
        return base
    }
}
