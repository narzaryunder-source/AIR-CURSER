package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.aircursor.core.Keysyms
import com.aircursor.core.RemoteInput
import com.aircursor.core.RemoteInput.Cmd
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * This phone as keyboard and touchpad for AirCursor PC on another phone (same Wi-Fi, or this phone
 * on the other phone's hotspot). Finds the PC by itself; the first time it asks for the 6-digit
 * code shown on the PC's screen, then reconnects by itself.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class RemotePadActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("remote_pad", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, NsdServiceInfo>()
    private var conn: Connection? = null
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0E1016.toInt()) }
        setContentView(root)
        showFinder()
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        conn?.close()
        conn = null
        super.onStop()
    }

    // ---- Finding the PC ---------------------------------------------------------------------

    private var list: LinearLayout? = null

    private fun showFinder() {
        root.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        col.addView(text("Keyboard & touchpad", 24f, bold = true))
        col.addView(text("Use this phone to control AirCursor PC on your other phone.", 14f, muted = true).apply {
            setPadding(0, dp(6), 0, dp(18))
        })
        status = text("Looking for AirCursor PC on this Wi-Fi…", 14f, muted = true)
        col.addView(status)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        list = l
        col.addView(l)
        col.addView(text("How to connect", 16f, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })
        col.addView(text(
            "1. On the PC phone: AirCursor PC → AirCursor PC menu (top left) → \"Another phone as keyboard & touchpad\".\n" +
                "2. Both phones on the same Wi-Fi – or turn on the hotspot on one phone and join it with the other.\n" +
                "3. Tap the PC here and type the code it shows.", 14f, muted = true,
        ))
        col.addView(button("Connect by address (if it is not found)") { askAddress() }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(18)
        })
        root.addView(ScrollView(this).apply { addView(col) })
        refreshList()
    }

    private fun refreshList() {
        val l = list ?: return
        l.removeAllViews()
        for ((name, info) in found) {
            l.addView(button("💻  $name") { pick(info) }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
        }
        if (::status.isInitialized && conn == null) {
            status.text = if (found.isEmpty()) "Looking for AirCursor PC on this Wi-Fi…" else "Tap your PC:"
        }
    }

    private fun startDiscovery() {
        if (discovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    found[info.serviceName] = info
                    refreshList()
                    // The PC this phone was paired with: connect again by itself.
                    if (conn == null && info.serviceName == prefs.getString("name", null)) pick(info, prefs.getString("code", null))
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { found.remove(info.serviceName); refreshList() }
            }
        }
        discovery = l
        try {
            nsd?.discoverServices(RemoteInput.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            discovery = null
        }
    }

    private fun stopDiscovery() {
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
    }

    @Suppress("DEPRECATION")
    private fun pick(info: NsdServiceInfo, savedCode: String? = null) {
        status.text = "Connecting to ${info.serviceName}…"
        try {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                    main.post { status.text = "Could not reach ${info.serviceName} – try again" }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress ?: return
                    main.post {
                        if (savedCode != null) connect(host, i.port, savedCode, info.serviceName)
                        else askCode(host, i.port, info.serviceName)
                    }
                }
            })
        } catch (_: Exception) {
            status.text = "Busy – tap again"
        }
    }

    private fun askCode(host: String, port: Int, name: String) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6-digit code"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Code shown on the PC")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("Connect") { _, _ -> connect(host, port, field.text.toString().filter(Char::isDigit), name) }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun askAddress() {
        val addr = EditText(this).apply { hint = "Address shown on the PC, e.g. 192.168.1.5:41234"; setText(prefs.getString("addr", "")) }
        val code = EditText(this).apply { hint = "6-digit code"; inputType = InputType.TYPE_CLASS_NUMBER }
        android.app.AlertDialog.Builder(this)
            .setTitle("Connect by address")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(addr); addView(code)
            })
            .setPositiveButton("Connect") { _, _ ->
                val a = addr.text.toString().trim()
                val host = a.substringBeforeLast(':')
                val port = a.substringAfterLast(':').toIntOrNull()
                if (host.isEmpty() || port == null) Toast.makeText(this, "Write it like 192.168.1.5:41234", Toast.LENGTH_LONG).show()
                else connect(host, port, code.text.toString().filter(Char::isDigit), a)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(host: String, port: Int, code: String, name: String) {
        conn?.close()
        val c = Connection(host, port, code)
        conn = c
        status.text = "Connecting to $name…"
        c.open { ok, pcName ->
            main.post {
                if (conn !== c) return@post
                if (ok) {
                    prefs.edit().putString("name", name).putString("code", code).putString("addr", "$host:$port").apply()
                    showPad(pcName ?: name)
                } else {
                    conn = null
                    prefs.edit().remove("code").apply()
                    if (::status.isInitialized) status.text = if (pcName == "NO") "Wrong code – look at the PC and try again" else "Could not connect – same Wi-Fi?"
                }
            }
        }
        c.onClosed = {
            main.post {
                if (conn === c) {
                    conn = null
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
                    showFinder()
                }
            }
        }
    }

    private fun send(cmd: Cmd) = conn?.send(cmd)

    // ---- The keyboard and touchpad ----------------------------------------------------------

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

    private fun showPad(pcName: String) {
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(6))
        }
        top.addView(text("● $pcName", 14f).apply { setTextColor(0xFF3DDC97.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallButton("Disconnect") {
            prefs.edit().remove("name").apply()
            conn?.close(); conn = null; showFinder()
        })
        col.addView(top)
        val keys = KeyCatcher()
        col.addView(Touchpad(), LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })
        val clicks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clicks.addView(holdButton("Left") { down -> send(Cmd.Button('l', down)) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        clicks.addView(holdButton("Right") { down -> send(Cmd.Button('r', down)) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
        clicks.addView(button("⌨  Keyboard") { keys.show() }, LinearLayout.LayoutParams(0, dp(54), 1.2f).apply { marginEnd = dp(10) })
        col.addView(clicks)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(10)) }
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
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        col.addView(keys, LinearLayout.LayoutParams(1, 1))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    /** Touchpad: move = pointer, tap = click, tap then hold+move = drag, two fingers = scroll, two-finger tap = right click. */
    private inner class Touchpad : View(this@RemotePadActivity) {
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

        init {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1A1D26.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x55FFFFFF; textSize = 14 * d; textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText("Touchpad · tap = click · two fingers = scroll / right click", width / 2f, height - 18 * d, p)
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
                    lastX = avgX(e); lastY = avgY(e)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    fingers = e.pointerCount - 1
                    lastX = avgX(e, e.actionIndex); lastY = avgY(e, e.actionIndex)
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = avgX(e)
                    val y = avgY(e)
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
                        conn?.move(RemoteInput.accelerate(dx, speed) * 1.4f, RemoteInput.accelerate(dy, speed) * 1.4f)
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

        private fun avgX(e: MotionEvent, skip: Int = -1): Float {
            var s = 0f; var n = 0
            for (i in 0 until e.pointerCount) if (i != skip) { s += e.getX(i); n++ }
            return if (n == 0) e.x else s / n
        }

        private fun avgY(e: MotionEvent, skip: Int = -1): Float {
            var s = 0f; var n = 0
            for (i in 0 until e.pointerCount) if (i != skip) { s += e.getY(i); n++ }
            return if (n == 0) e.y else s / n
        }
    }

    /** Invisible view that receives the phone keyboard's typing and sends it to the PC. */
    private inner class KeyCatcher : View(this@RemotePadActivity) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun show() {
            requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
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

    // ---- small UI helpers ----

    private fun text(t: String, sp: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(if (muted) 0xFFA4A9B6.toInt() else Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = pill(0xFF2B3040.toInt())
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = dp(52)
        minimumWidth = dp(52)
        setTextColor(Color.WHITE)
        background = pill(0xFF2B3040.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(3); marginEnd = dp(3) }
        setOnClickListener { onClick() }
    }

    private fun holdButton(label: String, onHold: (Boolean) -> Unit) = Button(this).apply {
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

    /** The link to the PC phone: one writer thread, pointer moves merged, a ping every 4 s. */
    private class Connection(private val host: String, private val port: Int, private val code: String) {
        private val queue = LinkedBlockingQueue<String>()
        @Volatile private var sock: Socket? = null
        @Volatile private var closed = false
        private var mdx = 0f
        private var mdy = 0f
        private val lock = Object()
        var onClosed: (() -> Unit)? = null

        fun open(done: (Boolean, String?) -> Unit) {
            Thread({
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.tcpNoDelay = true
                    s.soTimeout = 6000
                    val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    out.write(RemoteInput.hello(code) + "\n"); out.flush()
                    val answer = input.readLine() ?: ""
                    if (!answer.startsWith("OK")) {
                        s.close()
                        done(false, if (answer == "NO") "NO" else null)
                        return@Thread
                    }
                    sock = s
                    done(true, answer.removePrefix("OK").trim().ifEmpty { null })
                    writeLoop(s, out)
                } catch (_: Exception) {
                    if (sock == null) done(false, null)
                } finally {
                    if (!closed) { closed = true; onClosed?.invoke() }
                }
            }, "remote-pad").start()
        }

        private fun writeLoop(s: Socket, out: OutputStreamWriter) {
            var lastPing = SystemClock.uptimeMillis()
            while (!closed) {
                val line = queue.poll(8, TimeUnit.MILLISECONDS)
                val move = synchronized(lock) {
                    if (mdx != 0f || mdy != 0f) Cmd.Move(mdx, mdy).also { mdx = 0f; mdy = 0f } else null
                }
                if (move != null) out.write(RemoteInput.encode(move) + "\n")
                if (line != null) out.write(line + "\n")
                val now = SystemClock.uptimeMillis()
                if (now - lastPing > 4000) { out.write("P\n"); lastPing = now }
                if (move != null || line != null || now == lastPing) out.flush()
            }
            s.close()
        }

        fun move(dx: Float, dy: Float) = synchronized(lock) { mdx += dx; mdy += dy }

        fun send(c: Cmd) { if (!closed) queue.offer(RemoteInput.encode(c)) }

        fun close() {
            closed = true
            try { sock?.close() } catch (_: Exception) {}
        }
    }
}
