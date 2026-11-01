package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.aircursor.core.Keysyms
import com.aircursor.core.RfbClient
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Where the hand cursor sends mouse input while the Cloud PC desktop is on screen. */
interface DesktopTarget {
    fun hover(sx: Float, sy: Float)
    fun click(sx: Float, sy: Float, button: Int)
    fun press(sx: Float, sy: Float)
    fun moveTo(sx: Float, sy: Float)
    fun release(sx: Float, sy: Float)
    /** Air scroll: virtual finger velocity in px/s, called every tracking frame. */
    fun scroll(vx: Float, vy: Float)
    fun scrollEnd()
}

object DesktopInput {
    @Volatile
    var target: DesktopTarget? = null
}

/**
 * Shows the Cloud PC screen (VNC over the private unix socket) and turns touch, keyboard and
 * hand-cursor input into mouse/keyboard events for the desktop.
 */
@SuppressLint("ViewConstructor")
class RfbView(context: Context, private val socketPath: String) : View(context), DesktopTarget {
    var onStatus: ((String?) -> Unit)? = null

    /** Text copied inside the desktop (UI thread). */
    var onDesktopClipboard: ((String) -> Unit)? = null
    @Volatile private var lastSentClipboard: String? = null

    @Volatile private var client: RfbClient? = null
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var running = false
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val src = Rect()
    private val sender = Executors.newSingleThreadExecutor()
    private val dirty = Rect()
    private var dirtyPosted = false
    @Volatile private var resizePending = true

    // Pointer state (framebuffer coordinates)
    private var buttons = 0
    /** A hand drag (pinch + move) or a finger is down: only then may the left button be held. */
    private var handDragging = false
    private var fingerDown = false
    private var lastFbX = 0
    private var lastFbY = 0
    @Volatile private var pendingMove = false

    // Sticky modifiers from the toolbar (apply to the next key)
    val sticky = HashSet<Int>()
    var onStickyChanged: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    // ---- Connection ----

    @Volatile private var generation = 0

    fun connect() {
        if (running) return
        running = true
        val g = ++generation
        Thread({ connectLoop(g) }, "cloudpc-vnc").start()
    }

    fun disconnect() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        client = null
    }

    private fun connectLoop(g: Int) {
        val start = SystemClock.uptimeMillis()
        while (running && g == generation) {
            try {
                post { onStatus?.invoke("Starting AirCursor PC…\nThe first start can take up to a minute.") }
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                val c = RfbClient(s.inputStream, s.outputStream)
                c.onClipboard = { text ->
                    if (text.isNotEmpty() && text != lastSentClipboard) post { onDesktopClipboard?.invoke(text) }
                }
                c.minFrameIntervalMs = frameIntervalMs()
                c.handshake()
                client = c
                resizePending = true
                post { onStatus?.invoke(null) }
                readLoop(c, g)
            } catch (e: Exception) {
                if (!running || g != generation) break
                Log.i("RfbView", "connect: ${e.message}")
                client = null
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                if (SystemClock.uptimeMillis() - start > 420_000) {
                    post { onStatus?.invoke("The desktop did not start.\nClose it and try again, or see the log in AirCursor.") }
                    break
                }
                Thread.sleep(700)
            }
        }
    }

    private fun readLoop(c: RfbClient, g: Int) {
        while (running && g == generation) {
            val d = c.readMessage() ?: continue
            if (d.resized) resizePending = true
            if (d.w <= 0 || d.h <= 0) continue
            synchronized(dirty) {
                if (dirty.isEmpty) dirty.set(d.x, d.y, d.x + d.w, d.y + d.h) else dirty.union(d.x, d.y, d.x + d.w, d.y + d.h)
                if (!dirtyPosted) {
                    dirtyPosted = true
                    post { applyDirty() }
                }
            }
        }
    }

    /** Steady FPS for the desktop picture (0 = no limit). */
    var fpsLimit = 0
        set(value) {
            field = value
            client?.minFrameIntervalMs = frameIntervalMs()
        }

    private fun frameIntervalMs(): Long = if (fpsLimit > 0) 1000L / fpsLimit else 0L

    // Frames per second actually shown (AirBoost FPS meter).
    var showFps = false
        set(value) {
            field = value
            invalidate()
        }
    private var framesThisSecond = 0
    private var fpsWindowStart = 0L
    private var fps = 0
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val fpsBg = Paint().apply { color = 0x99000000.toInt() }

    /** UI thread: copy the new picture into the bitmap (one block copy) and redraw. */
    private fun applyDirty() {
        val c = client ?: return
        synchronized(dirty) {
            dirty.setEmpty()
            dirtyPosted = false
        }
        val w: Int
        val h: Int
        val fb: ByteArray
        synchronized(c.lock) {
            w = c.width
            h = c.height
            fb = c.rgba
        }
        if (w <= 0 || h <= 0 || fb.size != w * h * 4) return
        var b = bitmap
        if (b == null || b.width != w || b.height != h || resizePending) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = b
            resizePending = false
        }
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(fb))
        framesThisSecond++
        val now = SystemClock.uptimeMillis()
        if (now - fpsWindowStart >= 1000) {
            fps = framesThisSecond
            framesThisSecond = 0
            fpsWindowStart = now
        }
        invalidate()
    }

    /** AirBoost sharpening strength 0..1 (FSR-style, Android 13+; 0 = plain smooth scaling). */
    var sharpness = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private var sharpen: Any? = null // RuntimeShader (API 33+), kept as Any so older phones never load it
    private var sharpenSource: Bitmap? = null
    private var sharpenChild: android.graphics.BitmapShader? = null
    private val sharpenPaint = Paint()
    private val sharpenMatrix = android.graphics.Matrix()
    private val srcRectF = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        if (sharpness > 0f && android.os.Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated &&
            drawSharpened(canvas, b)
        ) {
            drawFps(canvas)
            return
        }
        src.set(0, 0, b.width, b.height)
        canvas.drawBitmap(b, src, dst, paint)
        drawFps(canvas)
    }

    private val fpsTick = Runnable { invalidate() }

    private fun drawFps(canvas: Canvas) {
        if (!showFps) return
        val d = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        if (now - fpsWindowStart >= 2000) {
            fps = 0
            framesThisSecond = 0
            fpsWindowStart = now
        }
        // VNC only sends changes: a still screen has few frames, which is not lag.
        val text = if (fps <= 2 && now - lastInputMs > 1500) "Idle – screen not changing" else "$fps FPS"
        removeCallbacks(fpsTick)
        postDelayed(fpsTick, 1000)   // keeps the meter current while nothing changes (one pending tick only)
        val tw = fpsPaint.measureText(text)
        canvas.drawRoundRect(dst.left + 8 * d, dst.top + 8 * d, dst.left + 20 * d + tw, dst.top + 34 * d, 8 * d, 8 * d, fpsBg)
        canvas.drawText(text, dst.left + 14 * d, dst.top + 27 * d, fpsPaint)
    }

    @android.annotation.TargetApi(33)
    private fun drawSharpened(canvas: Canvas, b: Bitmap): Boolean {
        return try {
            val rs = (sharpen as? android.graphics.RuntimeShader)
                ?: android.graphics.RuntimeShader(Sharpen.AGSL).also { sharpen = it }
            if (sharpenSource !== b) {
                sharpenChild = android.graphics.BitmapShader(
                    b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
                ).apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR }
                sharpenSource = b
            }
            rs.setInputShader("img", sharpenChild!!)
            rs.setFloatUniform("sharp", sharpness)
            srcRectF.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
            sharpenMatrix.setRectToRect(srcRectF, dst, android.graphics.Matrix.ScaleToFit.FILL)
            rs.setLocalMatrix(sharpenMatrix)
            sharpenPaint.shader = rs
            canvas.drawRect(dst, sharpenPaint)
            true
        } catch (e: Throwable) {
            Log.w("RfbView", "sharpening off: ${e.message}")
            sharpness = 0f
            false
        }
    }

    // ---- Coordinates ----

    private fun viewToFb(vx: Float, vy: Float): Pair<Int, Int> {
        val b = bitmap ?: return 0 to 0
        if (dst.width() <= 0f) return 0 to 0
        val x = ((vx - dst.left) / dst.width() * b.width).toInt().coerceIn(0, b.width - 1)
        val y = ((vy - dst.top) / dst.height() * b.height).toInt().coerceIn(0, b.height - 1)
        return x to y
    }

    private val loc = IntArray(2)

    private fun screenToFb(sx: Float, sy: Float): Pair<Int, Int> {
        getLocationOnScreen(loc)
        return viewToFb(sx - loc[0], sy - loc[1])
    }

    // ---- Sending (never on the UI thread: a busy desktop must not freeze the phone) ----

    /** Last time the person did something (the FPS meter tells "idle" from "slow"). */
    @Volatile private var lastInputMs = 0L

    private fun sendPointer(x: Int, y: Int, mask: Int) {
        lastInputMs = SystemClock.uptimeMillis()
        val c = client ?: return
        sender.execute {
            try {
                c.pointer(x, y, mask)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendMoveCoalesced(x: Int, y: Int) {
        lastInputMs = SystemClock.uptimeMillis()
        lastFbX = x
        lastFbY = y
        val c = client ?: return
        if (pendingMove) return
        pendingMove = true
        sender.execute {
            pendingMove = false
            try {
                c.pointer(lastFbX, lastFbY, buttons)
            } catch (_: Exception) {
            }
        }
    }

    private fun clickAt(x: Int, y: Int, button: Int) {
        lastFbX = x
        lastFbY = y
        sendPointer(x, y, buttons)
        sendPointer(x, y, buttons or button)
        sendPointer(x, y, buttons)
    }

    private fun wheel(x: Int, y: Int, mask: Int) {
        sendPointer(x, y, buttons or mask)
        sendPointer(x, y, buttons)
    }

    fun sendKey(keysym: Int) {
        lastInputMs = SystemClock.uptimeMillis()
        val c = client ?: return
        val mods = sticky.toList()
        sticky.clear()
        onStickyChanged?.invoke()
        sender.execute {
            try {
                for (m in mods) c.key(m, true)
                c.key(keysym, true)
                c.key(keysym, false)
                for (m in mods.asReversed()) c.key(m, false)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendKeyState(keysym: Int, down: Boolean) {
        val c = client ?: return
        sender.execute {
            try {
                c.key(keysym, down)
            } catch (_: Exception) {
            }
        }
    }

    /** Phone clipboard -> desktop clipboard, then Ctrl+V (Ctrl+Shift+V in terminals is up to the user). */
    fun pasteFromPhone(text: String) {
        val c = client ?: return
        lastSentClipboard = text
        // One line (a login code, a link, a password): type it – works everywhere, also in the
        // terminal where Ctrl+V does not paste. Longer text: desktop clipboard + Ctrl+V.
        if (text.length <= 400 && text.none { it == '\n' || it == '\r' }) {
            sender.execute {
                try {
                    c.clipboard(text)
                } catch (_: Exception) {
                }
            }
            typeText(text)
            return
        }
        sender.execute {
            try {
                c.clipboard(text)
                Thread.sleep(80)
                c.key(Keysyms.CONTROL_L, true)
                c.key('v'.code, true)
                c.key('v'.code, false)
                c.key(Keysyms.CONTROL_L, false)
            } catch (_: Exception) {
            }
        }
    }

    /** Activity left the screen: make sure no mouse button stays pressed in the desktop. */
    fun releaseAll() {
        handDragging = false
        fingerDown = false
        dragging = false
        if (buttons != 0) {
            buttons = 0
            sendPointer(lastFbX, lastFbY, 0)
        }
    }

    fun toggleSticky(keysym: Int) {
        if (!sticky.remove(keysym)) sticky += keysym
        onStickyChanged?.invoke()
    }

    // ---- Hand cursor (AirCursor) ----

    override fun hover(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
        // Safety: a lost "release" must never leave the button held (that turns every
        // hand movement into a selection box / drag on the desktop).
        if (buttons != 0 && !handDragging && !fingerDown) {
            buttons = 0
            sendPointer(x, y, 0)
        }
        if (x != lastFbX || y != lastFbY) sendMoveCoalesced(x, y)
    }

    override fun click(sx: Float, sy: Float, button: Int) {
        val (x, y) = screenToFb(sx, sy)
        clickAt(x, y, button)
    }

    override fun press(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
        handDragging = true
        lastFbX = x
        lastFbY = y
        sendPointer(x, y, buttons)
        buttons = buttons or RfbClient.BUTTON_LEFT
        sendPointer(x, y, buttons)
    }

    override fun moveTo(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
        sendMoveCoalesced(x, y)
    }

    override fun release(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
        handDragging = false
        buttons = buttons and RfbClient.BUTTON_LEFT.inv()
        sendPointer(x, y, buttons)
    }

    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var lastScrollMs = 0L

    override fun scroll(vx: Float, vy: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastScrollMs == 0L) 0f else ((now - lastScrollMs).coerceAtMost(100)) / 1000f
        lastScrollMs = now
        scrollAccX += vx * dt
        scrollAccY += vy * dt
        val step = 70f
        // Finger moving up (vy < 0) = page goes down = wheel down.
        while (scrollAccY <= -step) { wheel(lastFbX, lastFbY, RfbClient.WHEEL_DOWN); scrollAccY += step }
        while (scrollAccY >= step) { wheel(lastFbX, lastFbY, RfbClient.WHEEL_UP); scrollAccY -= step }
        while (scrollAccX <= -step) { wheel(lastFbX, lastFbY, WHEEL_RIGHT); scrollAccX += step }
        while (scrollAccX >= step) { wheel(lastFbX, lastFbY, WHEEL_LEFT); scrollAccX -= step }
    }

    override fun scrollEnd() {
        scrollAccX = 0f
        scrollAccY = 0f
        lastScrollMs = 0L
    }

    // ---- Touch: tap = click, hold = right click, drag = drag, two fingers = scroll ----

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var twoFinger = false
    private var lastTwoY = 0f
    private var lastTwoX = 0f
    private val longPress = Runnable {
        if (!dragging && !twoFinger) {
            longPressed = true
            val (x, y) = viewToFb(downX, downY)
            clickAt(x, y, RfbClient.BUTTON_RIGHT)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // ---- Physical mouse (USB / Bluetooth): moves, clicks and scrolls like on a PC ----

    private fun mouseMask(state: Int): Int {
        var m = 0
        if (state and MotionEvent.BUTTON_PRIMARY != 0) m = m or RfbClient.BUTTON_LEFT
        if (state and MotionEvent.BUTTON_SECONDARY != 0) m = m or RfbClient.BUTTON_RIGHT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) m = m or RfbClient.BUTTON_MIDDLE
        return m
    }

    private fun handleMouse(e: MotionEvent): Boolean {
        val (x, y) = viewToFb(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                buttons = mouseMask(e.buttonState)
                sendMoveCoalesced(x, y)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
                lastFbX = x
                lastFbY = y
                buttons = if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else mouseMask(e.buttonState)
                sendPointer(x, y, buttons)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                repeat(maxOf(1, abs(v).toInt()).coerceAtMost(5)) {
                    if (v > 0f) wheel(x, y, RfbClient.WHEEL_UP) else if (v < 0f) wheel(x, y, RfbClient.WHEEL_DOWN)
                }
                if (h > 0f) wheel(x, y, WHEEL_RIGHT) else if (h < 0f) wheel(x, y, WHEEL_LEFT)
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> Unit
            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            if (handleMouse(e)) return true
        }
        return super.onGenericMotionEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouse(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                requestFocus()
                downX = e.x
                downY = e.y
                dragging = false
                longPressed = false
                twoFinger = false
                val (x, y) = viewToFb(e.x, e.y)
                sendMoveCoalesced(x, y)
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (dragging) {
                    buttons = buttons and RfbClient.BUTTON_LEFT.inv()
                    sendPointer(lastFbX, lastFbY, buttons)
                    dragging = false
                }
                twoFinger = true
                lastTwoY = (e.getY(0) + e.getY(1)) / 2f
                lastTwoX = (e.getX(0) + e.getX(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    val y = (e.getY(0) + e.getY(1)) / 2f
                    val x = (e.getX(0) + e.getX(1)) / 2f
                    val step = 40f * resources.displayMetrics.density / 2f
                    while (y - lastTwoY >= step) { wheel(lastFbX, lastFbY, RfbClient.WHEEL_UP); lastTwoY += step }
                    while (lastTwoY - y >= step) { wheel(lastFbX, lastFbY, RfbClient.WHEEL_DOWN); lastTwoY -= step }
                    if (abs(x - lastTwoX) >= step * 2) lastTwoX = x
                } else if (!twoFinger && !longPressed) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        dragging = true
                        val (x0, y0) = viewToFb(downX, downY)
                        sendPointer(x0, y0, buttons)
                        buttons = buttons or RfbClient.BUTTON_LEFT
                        sendPointer(x0, y0, buttons)
                    }
                    if (dragging) {
                        val (x, y) = viewToFb(e.x, e.y)
                        sendMoveCoalesced(x, y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val (x, y) = viewToFb(e.x, e.y)
                when {
                    dragging -> {
                        buttons = buttons and RfbClient.BUTTON_LEFT.inv()
                        sendPointer(x, y, buttons)
                    }
                    !longPressed && !twoFinger -> clickAt(x, y, RfbClient.BUTTON_LEFT)
                }
                dragging = false
                fingerDown = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    buttons = buttons and RfbClient.BUTTON_LEFT.inv()
                    sendPointer(lastFbX, lastFbY, buttons)
                }
                dragging = false
                fingerDown = false
            }
        }
        return true
    }

    // ---- Keyboard ----

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                typeText(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) { sendKey(Keysyms.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event)
                return true
            }
        }
    }

    fun typeText(t: String) {
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            sendKey(Keysyms.forChar(cp))
            i += Character.charCount(cp)
        }
    }

    private fun special(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> Keysyms.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Keysyms.DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysyms.RETURN
        KeyEvent.KEYCODE_TAB -> Keysyms.TAB
        KeyEvent.KEYCODE_ESCAPE -> Keysyms.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> Keysyms.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keysyms.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Keysyms.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keysyms.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Keysyms.HOME
        KeyEvent.KEYCODE_MOVE_END -> Keysyms.END
        KeyEvent.KEYCODE_PAGE_UP -> Keysyms.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Keysyms.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> Keysyms.INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keysyms.F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }

    private fun modifier(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Keysyms.SHIFT_L
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Keysyms.CONTROL_L
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Keysyms.ALT_L
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> Keysyms.SUPER_L
        else -> null
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        special(event.keyCode)?.let {
            sendKey(it)
            return true
        }
        val ch = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (ch > 0) {
            sendKey(Keysyms.forChar(ch))
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        modifier(keyCode)?.let {
            sendKeyState(it, true)
            return true
        }
        return handleKeyDown(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        modifier(keyCode)?.let {
            sendKeyState(it, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun release() {
        disconnect()
        sender.shutdown()
    }

    companion object {
        const val WHEEL_LEFT = 32
        const val WHEEL_RIGHT = 64
    }
}

/**
 * FSR-style "robust contrast adaptive sharpening" (RCAS idea, AMD FidelityFX): sharpens the
 * upscaled desktop without halos – the sharpening is limited by the local min/max contrast.
 * Coordinates are desktop pixels (the shader's local matrix maps them onto the screen).
 */
private object Sharpen {
    const val AGSL = """
        uniform shader img;
        uniform float sharp;
        half4 main(float2 p) {
            half3 c = img.eval(p).rgb;
            half3 n = img.eval(p + float2(0.0, -1.0)).rgb;
            half3 s = img.eval(p + float2(0.0, 1.0)).rgb;
            half3 e = img.eval(p + float2(1.0, 0.0)).rgb;
            half3 w = img.eval(p + float2(-1.0, 0.0)).rgb;
            half3 mn = min(c, min(min(n, s), min(e, w)));
            half3 mx = max(c, max(max(n, s), max(e, w)));
            half3 amp = clamp(min(mn, half3(1.0) - mx) / max(mx, half3(0.0001)), half3(0.0), half3(1.0));
            half peak = -1.0 / mix(8.0, 4.5, half(sharp));
            half3 wgt = sqrt(amp) * peak;
            half3 r = (c + (n + s + e + w) * wgt) / (half3(1.0) + 4.0 * wgt);
            return half4(clamp(r, half3(0.0), half3(1.0)), 1.0);
        }
    """
}
