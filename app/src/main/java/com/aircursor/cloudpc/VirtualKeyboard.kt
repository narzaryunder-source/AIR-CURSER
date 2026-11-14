package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.aircursor.core.Keysyms
import com.aircursor.core.RemoteInput.Cmd
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A full PC keyboard drawn on the phone (instead of the phone's own keyboard): Esc and F1–F12,
 * numbers and symbols, Tab, Caps, Shift, Ctrl, Win, Alt, arrows – every key goes to the PC as a real
 * key, so shortcuts like Ctrl+C, Alt+Tab or Ctrl+Shift+T work.
 *
 * Shift / Ctrl / Alt / Win: one tap = for the next key, two quick taps = stays on (tap again to
 * release). Holding ⌫, Del, the arrows or Space repeats them. 🌐 opens the phone's own keyboard
 * (voice typing, Hindi and other languages).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class VirtualKeyboard(
    context: Context,
    private val send: (Cmd) -> Unit,
    /** Landscape: the keyboard fills the height it is given, with bigger letters. */
    private val big: Boolean = false,
    private val phoneKeyboard: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).roundToInt()
    private val main = Handler(Looper.getMainLooper())

    private enum class Mod { OFF, ONCE, LOCKED }

    private val mods = linkedMapOf(Keysyms.SHIFT_L to Mod.OFF, Keysyms.CONTROL_L to Mod.OFF, Keysyms.ALT_L to Mod.OFF, Keysyms.SUPER_L to Mod.OFF)
    private val modKeys = HashMap<Int, MutableList<TextView>>()
    private val lastModTap = HashMap<Int, Long>()
    private var caps = false
    private var capsKey: TextView? = null

    /** Keys with a second symbol on Shift, and the letter keys (they follow Shift and Caps). */
    private val charKeys = ArrayList<Pair<TextView, Pair<Char, Char>>>()

    private val keyColor = 0xFF2A2F3D.toInt()
    private val specialColor = 0xFF1F2330.toInt()
    private val pressedColor = 0xFF4A5470.toInt()
    private val onceColor = 0xFF2F6BFF.toInt()
    private val lockedColor = 0xFF14B87A.toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF0B0D12.toInt())
        setPadding(dp(3f), dp(4f), dp(3f), dp(6f))
        row(0.78f) {
            special("Esc", Keysyms.ESCAPE, 1.2f)
            for (i in 0 until 12) special("F${i + 1}", Keysyms.F1 + i, 1f, small = true)
            special("Del", Keysyms.DELETE, 1.2f, repeat = true)
        }
        row(1f) {
            for ((n, s) in "`1234567890-=".zip("~!@#$%^&*()_+")) char(n, s)
            special("⌫", Keysyms.BACKSPACE, 1.6f, repeat = true)
        }
        row(1f) {
            special("Tab", Keysyms.TAB, 1.5f)
            for (c in "qwertyuiop") letter(c)
            for ((n, s) in "[]\\".zip("{}|")) char(n, s)
        }
        row(1f) {
            capsKey = special("Caps", 0, 1.8f) { toggleCaps() }
            for (c in "asdfghjkl") letter(c)
            for ((n, s) in ";'".zip(":\"")) char(n, s)
            special("Enter", Keysyms.RETURN, 2f)
        }
        row(1f) {
            modifier("Shift", Keysyms.SHIFT_L, 2.3f)
            for (c in "zxcvbnm") letter(c)
            for ((n, s) in ",./".zip("<>?")) char(n, s)
            special("↑", Keysyms.UP, 1.1f, repeat = true)
        }
        row(1f) {
            modifier("Ctrl", Keysyms.CONTROL_L, 1.4f)
            modifier("Win", Keysyms.SUPER_L, 1.2f)
            modifier("Alt", Keysyms.ALT_L, 1.2f)
            special("🌐", 0, 1.1f) { releaseMods(); phoneKeyboard() }
            special("Space", 0x20, 4.4f, repeat = true)
            special("←", Keysyms.LEFT, 1.1f, repeat = true)
            special("↓", Keysyms.DOWN, 1.1f, repeat = true)
            special("→", Keysyms.RIGHT, 1.1f, repeat = true)
        }
    }

    /** About half the screen height, never more than a real keyboard needs. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (big) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val screen = resources.displayMetrics.heightPixels
        val h = min(dp(300f), (screen * 0.5f).roundToInt())
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    // ---- building ----

    private var current: LinearLayout? = null

    private fun row(weight: Float, keys: () -> Unit) {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        current = r
        keys()
        addView(r, LayoutParams(-1, 0, weight))
    }

    private fun key(label: String, weight: Float, color: Int, small: Boolean = false): TextView {
        val t = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) { if (small) 13f else if (label.length > 2) 15f else 21f } else { if (small) 11f else if (label.length > 2) 13f else 17f })
            typeface = Typeface.DEFAULT
            isSingleLine = true
            background = pill(color)
            isHapticFeedbackEnabled = true
        }
        current?.addView(t, LayoutParams(0, -1, weight).apply { setMargins(dp(1.5f), dp(2f), dp(1.5f), dp(2f)) })
        return t
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(color) }

    /** A letter: Shift or Caps make it a capital. */
    private fun letter(c: Char) {
        val t = key(c.toString(), 1f, keyColor)
        charKeys += t to (c to c.uppercaseChar())
        press(t, keyColor, repeat = false) { typeChar(if (upper(true)) c.uppercaseChar() else c) }
    }

    /** A number or symbol key with its Shift symbol. */
    private fun char(normal: Char, shifted: Char) {
        val t = key(normal.toString(), 1f, keyColor)
        charKeys += t to (normal to shifted)
        press(t, keyColor, repeat = false) { typeChar(if (upper(false)) shifted else normal) }
    }

    private fun special(
        label: String,
        sym: Int,
        weight: Float,
        small: Boolean = false,
        repeat: Boolean = false,
        action: (() -> Unit)? = null,
    ): TextView {
        val t = key(label, weight, specialColor, small)
        press(t, specialColor, repeat) {
            if (action != null) action() else {
                send(Cmd.Key(sym))
                releaseOnce()
            }
        }
        return t
    }

    private fun modifier(label: String, sym: Int, weight: Float) {
        val t = key(label, weight, specialColor, small = false)
        modKeys.getOrPut(sym) { mutableListOf() } += t
        t.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                tapModifier(sym)
            }
            true
        }
    }

    /** Touch handling for a key: highlight, haptic, optional repeat while held. */
    private fun press(t: TextView, color: Int, repeat: Boolean, action: () -> Unit) {
        var repeater: Runnable? = null
        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = pill(pressedColor)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() {
                                action()
                                main.postDelayed(this, 55)
                            }
                        }
                        repeater = r
                        main.postDelayed(r, 420)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = pill(color)
                    repeater?.let { main.removeCallbacks(it) }
                    repeater = null
                }
            }
            true
        }
    }

    // ---- keys and modifiers ----

    private fun upper(letter: Boolean): Boolean {
        val shift = mods[Keysyms.SHIFT_L] != Mod.OFF
        return if (letter) shift != caps else shift
    }

    private fun typeChar(c: Char) {
        send(Cmd.Key(Keysyms.forChar(c.code)))
        releaseOnce()
    }

    private fun tapModifier(sym: Int) {
        val now = System.currentTimeMillis()
        val quick = now - (lastModTap[sym] ?: 0L) < 350
        lastModTap[sym] = now
        val next = when (mods[sym]) {
            Mod.OFF -> Mod.ONCE
            Mod.ONCE -> if (quick) Mod.LOCKED else Mod.OFF
            else -> Mod.OFF
        }
        setMod(sym, next)
    }

    private fun setMod(sym: Int, m: Mod) {
        val was = mods[sym] ?: Mod.OFF
        if (was == m) return
        mods[sym] = m
        if (was == Mod.OFF && m != Mod.OFF) send(Cmd.KeyState(sym, true))
        if (was != Mod.OFF && m == Mod.OFF) send(Cmd.KeyState(sym, false))
        val color = when (m) {
            Mod.OFF -> specialColor
            Mod.ONCE -> onceColor
            Mod.LOCKED -> lockedColor
        }
        modKeys[sym]?.forEach { it.background = pill(color) }
        if (sym == Keysyms.SHIFT_L) updateLabels()
    }

    /** After a normal key: one-tap modifiers end, locked ones stay. */
    private fun releaseOnce() {
        for ((sym, m) in mods.entries.toList()) if (m == Mod.ONCE) setMod(sym, Mod.OFF)
    }

    /** Everything up (the pad closes, the keyboard hides, or the phone keyboard takes over). */
    fun releaseMods() {
        for (sym in mods.keys.toList()) setMod(sym, Mod.OFF)
    }

    private fun toggleCaps() {
        caps = !caps
        capsKey?.background = pill(if (caps) lockedColor else specialColor)
        updateLabels()
    }

    private fun updateLabels() {
        for ((t, pair) in charKeys) {
            val letter = pair.first.isLetter()
            t.text = (if (upper(letter)) pair.second else pair.first).toString()
        }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        releaseMods()
        super.onDetachedFromWindow()
    }
}
