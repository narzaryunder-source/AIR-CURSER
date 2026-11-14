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
        // Nothing under the status bar, the navigation bar or the camera notch.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        showFinder()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (table != null) closeTable() else finish()
            }
        })
    }

    // ---- Table keyboard (camera + hands) over the pad ----
    private var table: TableKeyboard? = null
    private val cameraPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) openTable() else Toast.makeText(this, "The table keyboard needs the camera", Toast.LENGTH_LONG).show()
    }

    private fun askTable() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) openTable() else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun openTable() {
        if (table != null || conn == null) return
        val t = TableKeyboard(this, { send(it) }, { dx, dy -> conn?.move(dx, dy) }) { closeTable() }
        table = t
        root.addView(t.view, FrameLayout.LayoutParams(-1, -1))
        t.start()
    }

    private fun closeTable() {
        val t = table ?: return
        table = null
        t.stop()
        root.removeView(t.view)
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        closeTable()
        stopDiscovery()
        conn?.close()
        conn = null
        super.onStop()
    }

    // ---- Finding the PC ---------------------------------------------------------------------

    private var list: LinearLayout? = null

    private fun showFinder() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullScreen(false)
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

    // ---- The keyboard and touchpad (shared with Monitor mode) ----

    private fun send(cmd: Cmd) = conn?.send(cmd)

    /** The pad is landscape (laptop layout) unless the user picked portrait. */
    private fun landscape() = !prefs.getBoolean("portrait", false)

    private fun fullScreen(on: Boolean) {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            if (on) {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun showPad(pcName: String) {
        closeTable()
        root.removeAllViews()
        val land = landscape()
        requestedOrientation = if (land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        fullScreen(true)
        val pad = PadControls(this, { send(it) }, { dx, dy -> conn?.move(dx, dy) })
        val extras = listOf(
            "📷  Table keyboard" to { askTable() },
            (if (land) "⟳  Portrait" else "⟳  Landscape") to {
                prefs.edit().putBoolean("portrait", land).apply()
                showPad(pcName)
            },
        )
        root.addView(pad.build(pcName, "Disconnect", extras, landscape = land) {
            prefs.edit().remove("name").apply()
            conn?.close(); conn = null; showFinder()
        }, FrameLayout.LayoutParams(-1, -1))
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
