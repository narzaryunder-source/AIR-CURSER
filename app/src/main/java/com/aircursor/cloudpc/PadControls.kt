package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aircursor.core.Keysyms
import com.aircursor.core.RemoteInput
import com.aircursor.core.RemoteInput.Cmd
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touchpad + click buttons + keyboard + key row, as one screen. Used by "this phone as keyboard &
 * touchpad" (commands go over the network) and by Monitor mode (commands go to the PC picture on
 * the monitor). [send] gets every command; [move] gets pointer movement (already accelerated).
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class PadControls(
    private val activity: Activity,
    private val send: (Cmd) -> Unit,
    private val move: (Float, Float) -> Unit,
) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private val held = HashSet<Int>()   // Ctrl / Alt / Win waiting for the next key
    private val modButtons = HashMap<Int, Button>()

    private fun releaseMods() {
        for (k in held) send(Cmd.KeyState(k, false))
        held.clear()
        for (b in modButtons.values) b.alpha = 0.75f
    }

    private fun key(sym: Int) {
        send(Cmd.Key(sym))
        releaseMods()
    }

    /**
     * The whole pad. [title] on top with a [closeLabel] button that calls [onClose]; [extras] are
     * shortcut buttons in the top bar (Monitor mode: apps, browser, windows).
     */
    fun build(title: String, closeLabel: String, extras: List<Pair<String, () -> Unit>> = emptyList(), onClose: () -> Unit): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1016.toInt())
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(6))
        }
        top.addView(TextView(activity).apply {
            text = "● $title"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF3DDC97.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top)
        if (extras.isNotEmpty()) {
            val ex = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(4)) }
            for ((label, action) in extras) ex.addView(smallButton(label) { action() })
            col.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(ex) })
        }
        top.addView(smallButton(closeLabel) { onClose() })
        val keys = KeyCatcher()
        col.addView(Touchpad(), LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })
        val clicks = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        clicks.addView(holdButton("Left") { down -> send(Cmd.Button('l', down)) },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        clicks.addView(holdButton("Right") { down -> send(Cmd.Button('r', down)) },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
        clicks.addView(button("⌨  Keyboard") { keys.show() }, LinearLayout.LayoutParams(0, dp(54), 1.2f).apply { marginEnd = dp(10) })
        col.addView(clicks)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(10)) }
        fun k(label: String, sym: Int) = row.addView(smallButton(label) { key(sym) })
        fun mod(label: String, sym: Int) {
            val b = smallButton(label) {}
            b.alpha = 0.75f
            b.setOnClickListener {
                if (held.remove(sym)) { send(Cmd.KeyState(sym, false)); b.alpha = 0.75f }
                else { held += sym; send(Cmd.KeyState(sym, true)); b.alpha = 1f }
            }
            modButtons[sym] = b
            row.addView(b)
        }
        k("Esc", Keysyms.ESCAPE); k("Tab", Keysyms.TAB)
        mod("Ctrl", Keysyms.CONTROL_L); mod("Alt", Keysyms.ALT_L); mod("Win", Keysyms.SUPER_L)
        k("←", Keysyms.LEFT); k("↑", Keysyms.UP); k("↓", Keysyms.DOWN); k("→", Keysyms.RIGHT)
        k("⏎", Keysyms.RETURN); k("⌫", Keysyms.BACKSPACE); k("Del", Keysyms.DELETE)
        k("Home", Keysyms.HOME); k("End", Keysyms.END)
        for (i in 0 until 12) k("F${i + 1}", Keysyms.F1 + i)
        col.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        col.addView(keys, LinearLayout.LayoutParams(1, 1))
        return col
    }

    /** Touchpad: move = pointer, tap = click, tap then hold+move = drag, two fingers = scroll, two-finger tap = right click. */
    private inner class Touchpad : View(activity) {
        private var lastX = 0f
        private var lastY = 0f
        private var lastT = 0L
        private var downAt = 0L
        private var moved = 0f
        private var fingers = 0
        private var maxFingers = 0
        private var scrollAcc = 0f
        private var lastTapAt = 0L
        private var dragging = false
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF; textSize = 14 * d; textAlign = Paint.Align.CENTER
        }

        init {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A1D26.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawText("Touchpad · tap = click · two fingers = scroll / right click", width / 2f, height - 18 * d, hint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingers = 1; maxFingers = 1; moved = 0f; scrollAcc = 0f
                    lastX = e.x; lastY = e.y; lastT = SystemClock.uptimeMillis(); downAt = lastT
                    // Tap, then touch again quickly and move: drag (like a laptop touchpad).
                    if (downAt - lastTapAt < 260) {
                        dragging = true
                        send(Cmd.Button('l', true))
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    fingers = e.pointerCount; maxFingers = maxOf(maxFingers, fingers)
                    lastX = avg(e, true); lastY = avg(e, false)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    fingers = e.pointerCount - 1
                    lastX = avg(e, true, e.actionIndex); lastY = avg(e, false, e.actionIndex)
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = avg(e, true)
                    val y = avg(e, false)
                    val now = SystemClock.uptimeMillis()
                    val dx = (x - lastX) / d
                    val dy = (y - lastY) / d
                    val dt = (now - lastT).coerceAtLeast(1)
                    moved += abs(dx) + abs(dy)
                    if (fingers >= 2) {
                        scrollAcc += dy
                        while (scrollAcc >= 26f) { send(Cmd.Wheel(-1, 0)); scrollAcc -= 26f }   // fingers down = page up
                        while (scrollAcc <= -26f) { send(Cmd.Wheel(1, 0)); scrollAcc += 26f }
                    } else if (fingers == 1) {
                        val speed = hypot(dx, dy) / dt * 1000f
                        move(RemoteInput.accelerate(dx, speed) * 1.4f, RemoteInput.accelerate(dy, speed) * 1.4f)
                    }
                    lastX = x; lastY = y; lastT = now
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val now = SystemClock.uptimeMillis()
                    if (dragging) {
                        send(Cmd.Button('l', false))
                        dragging = false
                        lastTapAt = 0
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && moved < 12f && now - downAt < 260) {
                        if (maxFingers >= 2) send(Cmd.Click('r'))
                        else { send(Cmd.Click('l')); lastTapAt = now }
                    }
                    fingers = 0
                }
            }
            return true
        }

        private fun avg(e: MotionEvent, xAxis: Boolean, skip: Int = -1): Float {
            var s = 0f; var n = 0
            for (i in 0 until e.pointerCount) if (i != skip) { s += if (xAxis) e.getX(i) else e.getY(i); n++ }
            return if (n == 0) (if (xAxis) e.x else e.y) else s / n
        }
    }

    /** Invisible view that receives the phone keyboard's typing. */
    private inner class KeyCatcher : View(activity) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun show() {
            requestFocus()
            @Suppress("DEPRECATION")
            activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    if (text.isNotEmpty()) {
                        if (held.isNotEmpty()) {
                            // Ctrl+C and friends: one key with the held modifiers.
                            for (ch in text) send(Cmd.Key(Keysyms.forChar(ch.code)))
                            releaseMods()
                        } else {
                            send(Cmd.Text(text.toString()))
                        }
                    }
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceIn(1, 50)) { send(Cmd.Key(Keysyms.BACKSPACE)) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DEL -> key(Keysyms.BACKSPACE)
                            KeyEvent.KEYCODE_ENTER -> key(Keysyms.RETURN)
                            KeyEvent.KEYCODE_TAB -> key(Keysyms.TAB)
                            else -> {
                                val ch = event.unicodeChar
                                if (ch > 0) key(Keysyms.forChar(ch))
                            }
                        }
                    }
                    return true
                }
            }
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }

    private fun button(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = pill(0xFF2B3040.toInt())
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        minWidth = dp(52)
        minimumWidth = dp(52)
        setTextColor(Color.WHITE)
        background = pill(0xFF2B3040.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(3); marginEnd = dp(3) }
        setOnClickListener { onClick() }
    }

    private fun holdButton(label: String, onHold: (Boolean) -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = pill(0xFF3B4256.toInt())
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; onHold(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; onHold(false) }
            }
            true
        }
    }
}
