package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aircursor.core.Keysyms
import com.aircursor.core.RemoteInput.Cmd
import com.aircursor.core.RfbClient

/**
 * The pointer of "another phone as keyboard & touchpad", drawn above everything on this phone.
 *
 * The PC picture is only one layer: Air windows (YouTube, the browser…), the menu bar and the dock
 * are drawn by the phone over it. So the pointer is drawn here, on top, and every click, scroll and
 * key goes to what is under it: the phone's own windows get real taps, wheel events and keys; the
 * PC picture gets mouse and keyboard input as before.
 */
@SuppressLint("ViewConstructor")
class RemoteCursor(
    private val activity: Activity,
    private val root: FrameLayout,
    private val desktop: () -> RfbView?,
    /** True where the phone shows something over the PC picture (root coordinates). */
    private val overPhone: (Float, Float) -> Boolean,
    /** Called with the pointer's y after each move (the dock comes up at the bottom edge). */
    private val onMoved: (Float) -> Unit = {},
) : View(activity) {
    private val d = resources.displayMetrics.density

    // Target position (root coordinates) and the drawn one, which glides after it every frame:
    // smooth even when Wi-Fi delivers the moves in small bursts.
    private var tx = -1f
    private var ty = -1f
    private var drawX = 0f
    private var drawY = 0f
    private var lastMoveAt = 0L

    // A left button held on the phone's windows (drag / select there).
    private var phoneDownAt = 0L
    // A left button held on the PC picture.
    private var desktopDown = false
    /** The last click went to the phone's windows: keys go there too while they are still under it. */
    private var phoneFocusX = -1f
    private var phoneFocusY = -1f
    private var meta = 0
    private val charMap: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    /** No touches here: the overlay is only a picture. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    fun show(on: Boolean) {
        visibility = if (on) VISIBLE else GONE
        DesktopInput.handActive = on
        if (on) {
            if (tx < 0f) { tx = root.width / 2f; ty = root.height / 2f; drawX = tx; drawY = ty }
            bringToFront()
            invalidate()
        } else {
            release()
        }
        desktop()?.invalidate()
    }

    /** Lets go of everything held (connection lost, lock screen). */
    fun release() {
        if (phoneDownAt != 0L) touch(MotionEvent.ACTION_CANCEL)
        phoneDownAt = 0L
        if (desktopDown) desktop()?.release(screenX(), screenY())
        desktopDown = false
        meta = 0
    }

    private val loc = IntArray(2)
    private fun screenX(): Float { root.getLocationOnScreen(loc); return tx + loc[0] }
    private fun screenY(): Float { root.getLocationOnScreen(loc); return ty + loc[1] }
    private fun onPhone() = overPhone(tx, ty)

    fun apply(cmd: Cmd) {
        if (visibility != VISIBLE) show(true)
        // Air windows opened later are added above: stay the top layer.
        if (root.getChildAt(root.childCount - 1) !== this) bringToFront()
        when (cmd) {
            is Cmd.Move -> move(cmd.dx, cmd.dy)
            is Cmd.Button -> button(cmd.button, cmd.down)
            is Cmd.Click -> click(cmd.button)
            is Cmd.Wheel -> wheel(cmd.dy, cmd.dx)
            is Cmd.Key -> key(cmd.keysym)
            is Cmd.KeyState -> keyState(cmd.keysym, cmd.down)
            is Cmd.Text -> text(cmd.text)
            Cmd.Ping -> Unit
        }
    }

    // ---- pointer ----

    private fun move(dx: Float, dy: Float) {
        if (root.width == 0) return
        // A touchpad stroke of about 300 points crosses the screen (with the pad's acceleration).
        val k = root.width / 420f
        tx = (tx + dx * k).coerceIn(0f, root.width - 1f)
        ty = (ty + dy * k).coerceIn(0f, root.height - 1f)
        lastMoveAt = SystemClock.uptimeMillis()
        onMoved(ty)
        when {
            phoneDownAt != 0L -> touch(MotionEvent.ACTION_MOVE)
            desktopDown -> desktop()?.moveTo(screenX(), screenY())
            !onPhone() -> desktop()?.hover(screenX(), screenY())
        }
        postInvalidateOnAnimation()
    }

    private fun button(b: Char, down: Boolean) {
        if (b == 'l') {
            if (down) {
                if (onPhone()) {
                    focusPhone()
                    phoneDownAt = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN)
                } else {
                    focusDesktop()
                    desktopDown = true
                    desktop()?.press(screenX(), screenY())
                }
            } else {
                if (phoneDownAt != 0L) { touch(MotionEvent.ACTION_UP); phoneDownAt = 0L }
                if (desktopDown) { desktop()?.release(screenX(), screenY()); desktopDown = false }
            }
            return
        }
        if (onPhone()) { if (down) longPress() } else { desktop()?.hover(screenX(), screenY()); desktop()?.remoteButton(b, down) }
    }

    private fun click(b: Char) {
        if (onPhone()) {
            focusPhone()
            if (b == 'l') {
                phoneDownAt = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_UP)
                phoneDownAt = 0L
            } else {
                longPress()
            }
        } else {
            focusDesktop()
            val v = desktop() ?: return
            if (b == 'l') v.click(screenX(), screenY(), RfbClient.BUTTON_LEFT) else { v.hover(screenX(), screenY()); v.remoteClick(b) }
        }
    }

    /** Right click on the phone's windows = a long press. */
    private fun longPress() {
        val t = SystemClock.uptimeMillis()
        phoneDownAt = t
        touch(MotionEvent.ACTION_DOWN)
        postDelayed({
            if (phoneDownAt == t) { touch(MotionEvent.ACTION_UP); phoneDownAt = 0L }
        }, 650)
    }

    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(if (phoneDownAt != 0L) phoneDownAt else now, now, action, tx, ty, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun wheel(dy: Int, dx: Int) {
        if (onPhone()) {
            // A mouse wheel event for the phone's windows (Air pages, lists): positive = up.
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = tx; y = ty
                setAxisValue(MotionEvent.AXIS_VSCROLL, -dy.toFloat())
                setAxisValue(MotionEvent.AXIS_HSCROLL, dx.toFloat())
            })
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            root.dispatchGenericMotionEvent(e)
            e.recycle()
        } else {
            val v = desktop() ?: return
            v.hover(screenX(), screenY())
            v.remoteWheel(dy, dx)
        }
    }

    // ---- keys: to the phone's window that was clicked last, else to the PC ----

    private fun focusPhone() { phoneFocusX = tx; phoneFocusY = ty }
    private fun focusDesktop() { phoneFocusX = -1f; phoneFocusY = -1f }
    private fun keysToPhone() = phoneFocusX >= 0f && overPhone(phoneFocusX, phoneFocusY)

    private fun key(sym: Int) {
        if (!keysToPhone()) { desktop()?.sendKey(sym); return }
        val code = androidKey(sym)
        if (code != KeyEvent.KEYCODE_UNKNOWN) { pressAndroid(code, meta); return }
        val cp = if (sym and 0x01000000 != 0) sym and 0xFFFFFF else sym
        text(String(Character.toChars(cp)))
    }

    private fun keyState(sym: Int, down: Boolean) {
        val m = when (sym) {
            Keysyms.SHIFT_L -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            Keysyms.CONTROL_L -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            Keysyms.ALT_L -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            Keysyms.SUPER_L -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            else -> 0
        }
        meta = if (down) meta or m else meta and m.inv()
        // The PC keeps its own modifier state (Ctrl+C there works as before).
        desktop()?.remoteKeyState(sym, down)
    }

    private fun text(t: String) {
        if (!keysToPhone()) { desktop()?.typeText(t); return }
        val events = charMap.getEvents(t.toCharArray())
        if (events != null && meta == 0) {
            for (e in events) activity.dispatchKeyEvent(e)
        } else if (events != null) {
            // Ctrl+C and friends: the key with the held modifiers.
            for (e in events) if (e.action == KeyEvent.ACTION_DOWN) pressAndroid(e.keyCode, meta or e.metaState)
        } else {
            // Letters the key map does not have (Hindi, emoji): as one text event.
            activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), t, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    private fun pressAndroid(code: Int, metaState: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
    }

    private fun androidKey(sym: Int): Int = when (sym) {
        Keysyms.BACKSPACE -> KeyEvent.KEYCODE_DEL
        Keysyms.TAB -> KeyEvent.KEYCODE_TAB
        Keysyms.RETURN -> KeyEvent.KEYCODE_ENTER
        Keysyms.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        Keysyms.DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        Keysyms.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        Keysyms.END -> KeyEvent.KEYCODE_MOVE_END
        Keysyms.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Keysyms.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        Keysyms.UP -> KeyEvent.KEYCODE_DPAD_UP
        Keysyms.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Keysyms.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        Keysyms.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        Keysyms.INSERT -> KeyEvent.KEYCODE_INSERT
        0x20 -> KeyEvent.KEYCODE_SPACE
        in Keysyms.F1 until Keysyms.F1 + 12 -> KeyEvent.KEYCODE_F1 + (sym - Keysyms.F1)
        else -> if (meta != 0 && sym in 0x61..0x7a) KeyEvent.KEYCODE_A + (sym - 0x61)
        else if (meta != 0 && sym in 0x30..0x39) KeyEvent.KEYCODE_0 + (sym - 0x30)
        else KeyEvent.KEYCODE_UNKNOWN
    }

    // ---- drawing ----

    private val arrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        // Glide to the target: quick, but no jumps when moves arrive in bursts.
        val gx = tx - drawX
        val gy = ty - drawY
        if (kotlin.math.abs(gx) < 0.5f && kotlin.math.abs(gy) < 0.5f) { drawX = tx; drawY = ty } else {
            drawX += gx * 0.55f
            drawY += gy * 0.55f
            postInvalidateOnAnimation()
        }
        val shape = DesktopInput.cursorShape
        if (!onPhone() && shape != null && !shape.isRecycled) {
            // Over the PC: the PC's own pointer picture (text cursor, hand, resize arrows…).
            val s = DesktopInput.desktopScale
            val x = drawX - DesktopInput.cursorHotX * s
            val y = drawY - DesktopInput.cursorHotY * s
            rect.set(x, y, x + shape.width * s, y + shape.height * s)
            canvas.drawBitmap(shape, null, rect, bmpPaint)
            return
        }
        val u = d * 1.15f
        arrow.rewind()
        arrow.moveTo(drawX, drawY)
        arrow.lineTo(drawX, drawY + 17 * u)
        arrow.lineTo(drawX + 4.2f * u, drawY + 13.2f * u)
        arrow.lineTo(drawX + 7.2f * u, drawY + 19.5f * u)
        arrow.lineTo(drawX + 9.6f * u, drawY + 18.4f * u)
        arrow.lineTo(drawX + 6.7f * u, drawY + 12.3f * u)
        arrow.lineTo(drawX + 12 * u, drawY + 12.3f * u)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
    }
}
