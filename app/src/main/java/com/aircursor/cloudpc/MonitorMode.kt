package com.aircursor.cloudpc

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.aircursor.core.RemoteInput.Cmd
import kotlin.math.roundToInt

/**
 * Monitor mode: AirCursor PC on a TV or monitor (USB-C to HDMI cable, a dock, or wireless
 * "screen cast" that Android offers as a second screen). The monitor shows the PC full screen;
 * this phone becomes its touchpad and keyboard.
 *
 * The monitor gets its own picture from the same private desktop (the VNC server is shared, nothing
 * goes on the network). The desktop takes the monitor's shape while it is used and goes back after.
 */
class MonitorMode(
    private val activity: Activity,
    private val socketPath: String,
    private val listener: Listener,
) {
    interface Listener {
        /** A second screen was connected (offer monitor mode) or removed. */
        fun monitorAvailable(name: String?)
        /** Monitor mode started (the PC picture on the monitor) or stopped. */
        fun monitorChanged(on: Boolean)
        /** Text copied in the desktop / markers from the PC (the monitor's picture brings them while the phone's is off). */
        fun desktopClipboard(text: String)
    }

    private val dm = activity.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var presentation: Screen? = null

    /** The PC picture on the monitor while monitor mode is on. */
    val view: RfbView? get() = presentation?.rfb
    val isOn get() = presentation != null

    /** A second screen that can show the PC, or null. */
    fun display(): Display? = try {
        dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.isValid }
    } catch (_: Exception) {
        null
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = listener.monitorAvailable(display()?.name)
        override fun onDisplayChanged(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) {
            val p = presentation
            if (p != null && p.display.displayId == id) stop()
            listener.monitorAvailable(display()?.name)
        }
    }

    fun register() = dm?.registerDisplayListener(displays, main)
    fun unregister() = dm?.unregisterDisplayListener(displays)

    /** Monitor's pixel size (real mode), for the desktop's shape. */
    private fun size(d: Display): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    fun start(sharpness: Float, fpsLimit: Int): Boolean {
        if (presentation != null) return true
        val d = display() ?: return false
        val (mw, mh) = size(d)
        if (mw <= 0 || mh <= 0) return false
        val p = try {
            Screen(activity, d, socketPath).also {
                it.setOnDismissListener { _ -> if (presentation === it) finish() }
                it.show()
            }
        } catch (e: WindowManager.InvalidDisplayException) {
            return false
        } catch (e: Exception) {
            return false
        }
        p.rfb.sharpness = sharpness
        p.rfb.fpsLimit = fpsLimit
        p.rfb.onDesktopClipboard = { listener.desktopClipboard(it) }
        presentation = p
        shape = mw to mh
        listener.monitorChanged(true)
        return true
    }

    fun stop() {
        val p = presentation ?: return
        presentation = null
        p.rfb.releaseAll()
        p.rfb.release()
        try {
            p.dismiss()
        } catch (_: Exception) {
        }
        finish()
    }

    private fun finish() {
        if (shape == null) return
        presentation?.rfb?.release()
        presentation = null
        shape = null
        listener.monitorChanged(false)
    }

    /** Touchpad and keyboard commands from this phone, for the monitor's picture. */
    fun apply(cmd: Cmd) {
        val v = view ?: return
        when (cmd) {
            is Cmd.Move -> v.remoteMove(cmd.dx, cmd.dy)
            is Cmd.Button -> v.remoteButton(cmd.button, cmd.down)
            is Cmd.Click -> v.remoteClick(cmd.button)
            is Cmd.Wheel -> v.remoteWheel(cmd.dy, cmd.dx)
            is Cmd.Key -> v.sendKey(cmd.keysym)
            is Cmd.KeyState -> v.remoteKeyState(cmd.keysym, cmd.down)
            is Cmd.Text -> v.typeText(cmd.text)
            Cmd.Ping -> Unit
        }
    }

    /** The monitor's window: only the PC picture, full screen. */
    private class Screen(outer: Context, display: Display, socketPath: String) : Presentation(outer, display) {
        val rfb = RfbView(context, socketPath)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(rfb)
            rfb.connect()
        }
    }

    companion object {
        /** The monitor's width and height while monitor mode is on (the desktop takes its shape). */
        @Volatile var shape: Pair<Int, Int>? = null
            private set

        /** Desktop size with the monitor's shape: [width] wide, even numbers for the VNC server. */
        fun desktopSize(width: Int): Pair<Int, Int>? {
            val (mw, mh) = shape ?: return null
            val h = ((width * mh.toFloat() / mw) / 2f).roundToInt() * 2
            return width to h.coerceIn(360, 2160)
        }
    }
}
