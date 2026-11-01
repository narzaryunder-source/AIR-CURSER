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
                post { onStatus?.invoke("Desktop start ho raha hai… (pehli baar 30-60 s)") }
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                val c = RfbClient(s.inputStream, s.outputStream)
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
                if (SystemClock.uptimeMillis() - start > 180_000) {
                    post { onStatus?.invoke("Desktop se connect nahi hua. Cloud PC screen par log dekho.") }
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

    /** UI thread: copy changed pixels into the bitmap and redraw. */
    private fun applyDirty() {
        val c = client ?: return
        val r = Rect()
        synchronized(dirty) {
            r.set(dirty)
            dirty.setEmpty()
            dirtyPosted = false
        }
        synchronized(c.lock) {
            val w = c.width
            val h = c.height
            if (w <= 0 || h <= 0) return
            var b = bitmap
            if (b == null || b.width != w || b.height != h || resizePending) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = b
                resizePending = false
                r.set(0, 0, w, h)
            }
            r.intersect(0, 0, w, h)
            if (!r.isEmpty) b.setPixels(c.pixels, r.top * w + r.left, w, r.left, r.top, r.width(), r.height())
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        src.set(0, 0, b.width, b.height)
        canvas.drawBitmap(b, src, dst, paint)
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

    private fun sendPointer(x: Int, y: Int, mask: Int) {
        val c = client ?: return
        sender.execute {
            try {
                c.pointer(x, y, mask)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendMoveCoalesced(x: Int, y: Int) {
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

    fun toggleSticky(keysym: Int) {
        if (!sticky.remove(keysym)) sticky += keysym
        onStickyChanged?.invoke()
    }

    // ---- Hand cursor (AirCursor) ----

    override fun hover(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
        if (x != lastFbX || y != lastFbY) sendMoveCoalesced(x, y)
    }

    override fun click(sx: Float, sy: Float, button: Int) {
        val (x, y) = screenToFb(sx, sy)
        clickAt(x, y, button)
    }

    override fun press(sx: Float, sy: Float) {
        val (x, y) = screenToFb(sx, sy)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    buttons = buttons and RfbClient.BUTTON_LEFT.inv()
                    sendPointer(lastFbX, lastFbY, buttons)
                }
                dragging = false
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
