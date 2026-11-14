package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aircursor.core.HandGeometry
import com.aircursor.core.HandIdx
import com.aircursor.core.Keysyms
import com.aircursor.core.Landmark
import com.aircursor.core.PinchDetector
import com.aircursor.core.RemoteInput.Cmd
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Table keyboard": the phone stands on the table, its camera looks at the table, and the screen
 * shows the camera picture with a glowing keyboard and touchpad lying on the table (drawn in
 * perspective). Hands are tracked on the phone itself (MediaPipe; no picture leaves the phone).
 *
 * A dot follows each hand (between thumb and index finger). Pinch = press the key under the dot;
 * on the touchpad, pinch and move = move the pointer, short pinch = click, long still pinch =
 * right click. Keys can also be tapped on the screen. "Adjust" lets the four corners be dragged so
 * the keyboard sits on the table as the camera sees it.
 *
 * A single camera cannot tell whether a finger touches the table, so a key is pressed with a pinch,
 * not by touching the table.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TableKeyboard(
    private val activity: ComponentActivity,
    private val send: (Cmd) -> Unit,
    private val move: (Float, Float) -> Unit,
    private val onClose: () -> Unit,
) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private val prefs = activity.getSharedPreferences("table_keyboard", Context.MODE_PRIVATE)
    private val stage = Stage(activity)
    private val status = TextView(activity)
    private var adjustButton: Button? = null
    val view: View = build()

    // ---- camera + hand tracking ----
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var worker: ExecutorService? = null
    @Volatile private var landmarker: HandLandmarker? = null
    @Volatile private var running = false
    private var front = prefs.getBoolean("front", false)
    private var raw: Bitmap? = null
    private var rowBuffer: ByteBuffer? = null
    private val pool = arrayOfNulls<Bitmap>(3)
    private var poolIndex = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rotate = Matrix()
    @Volatile private var triedCpu = false

    fun start() {
        if (running) return
        running = true
        status.text = "Starting the camera…"
        val w = Executors.newSingleThreadExecutor()
        worker = w
        w.execute { landmarker = createLandmarker(Delegate.GPU) ?: createLandmarker(Delegate.CPU) }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!running) return@addListener
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                status.text = "The camera could not start: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            analysis?.let { provider?.unbind(it) }
        } catch (_: Exception) {
        }
        analysis = null
        stage.releaseAll()
        val l = landmarker
        landmarker = null
        worker?.execute {
            try {
                l?.close()
            } catch (_: Exception) {
            }
        }
        worker?.shutdown()
        worker = null
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, image ->
                val hands = result.landmarks().map { hand -> hand.map { Landmark(it.x(), it.y(), it.z()) } }
                val aspect = image.width.toFloat() / max(1, image.height)
                stage.post { stage.onHands(hands, aspect) }
            }
            .setErrorListener { e ->
                Log.w(TAG, "hand tracking error", e)
                // The GPU path fails on some phones: once, try again on the CPU.
                if (!triedCpu && running) {
                    triedCpu = true
                    worker?.execute {
                        try {
                            landmarker?.close()
                        } catch (_: Exception) {
                        }
                        landmarker = createLandmarker(Delegate.CPU)
                    }
                }
            }
            .build()
        HandLandmarker.createFromOptions(activity, options)
    } catch (e: Throwable) {
        Log.w(TAG, "hand tracking ($delegate) failed", e)
        null
    }

    private fun bind() {
        val p = provider ?: return
        analysis?.let { p.unbind(it) }
        @Suppress("DEPRECATION")
        val a = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        a.setAnalyzer(worker ?: return) { image -> image.use { onFrame(it) } }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.bindToLifecycle(activity, selector, a)
            analysis = a
            status.text = "Show a hand to the camera · pinch = press"
        } catch (e: Exception) {
            status.text = "This camera is not available"
        }
    }

    /** One camera frame (worker thread): upright copy for the screen and for the hand tracking. */
    private fun onFrame(image: ImageProxy) {
        if (!running) return
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        var r = raw
        if (r == null || r.width != w || r.height != h) {
            r = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw = r
        }
        val src = plane.buffer
        if (plane.rowStride == w * 4) {
            src.rewind()
            r.copyPixelsFromBuffer(src)
        } else {
            // Rows are padded: copy the picture part of each row.
            var tmp = rowBuffer
            if (tmp == null || tmp.capacity() != w * h * 4) {
                tmp = ByteBuffer.allocate(w * h * 4)
                rowBuffer = tmp
            }
            tmp.clear()
            val row = ByteArray(w * 4)
            for (y in 0 until h) {
                src.position(y * plane.rowStride)
                src.get(row, 0, w * 4)
                tmp.put(row)
            }
            tmp.rewind()
            r.copyPixelsFromBuffer(tmp)
        }
        val rot = image.imageInfo.rotationDegrees
        val uw = if (rot % 180 == 0) w else h
        val uh = if (rot % 180 == 0) h else w
        poolIndex = (poolIndex + 1) % pool.size
        var up = pool[poolIndex]
        if (up == null || up.width != uw || up.height != uh) {
            up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            pool[poolIndex] = up
        }
        rotate.reset()
        rotate.postTranslate(-w / 2f, -h / 2f)
        rotate.postRotate(rot.toFloat())
        if (front) rotate.postScale(-1f, 1f)   // like a mirror
        rotate.postTranslate(uw / 2f, uh / 2f)
        Canvas(up).drawBitmap(r, rotate, paint)
        stage.showFrame(up)
        try {
            landmarker?.detectAsync(BitmapImageBuilder(up).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "detect", e)
        }
    }

    // ---- screen ----

    private fun build(): View {
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(chip("←  Screen keyboard") { onClose() })
        bar.addView(chip("Flip camera") {
            front = !front
            prefs.edit().putBoolean("front", front).apply()
            if (running) bind()
        })
        adjustButton = chip("Adjust") {
            stage.adjust = !stage.adjust
            adjustButton?.text = if (stage.adjust) "Done" else "Adjust"
            status.text = if (stage.adjust) "Drag the four corners so the keyboard lies on the table"
            else "Show a hand to the camera · pinch = press"
            stage.invalidate()
        }
        bar.addView(adjustButton)
        status.apply {
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), 0, 0, 0)
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return root
    }

    private fun chip(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xB3202432.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private class TKey(val label: String, val shifted: String?, val sym: Int, val x: Float, val y: Float, val w: Float, val mod: Boolean) {
        var flashUntil = 0L
    }

    private class Hand {
        var x = 0f
        var y = 0f
        var seenAt = 0L
        val pinch = PinchDetector()
        var pinched = false
        var downAt = 0L
        var onPad = false
        var moved = 0f
        var longDone = false
    }

    /** The camera picture with the keyboard on the table, the hand dots, and all the key logic. */
    private inner class Stage(context: Context) : View(context) {
        var adjust = false
        @Volatile private var frame: Bitmap? = null
        private val keys = ArrayList<TKey>()
        private val hands = arrayOf(Hand(), Hand())
        private val heldMods = HashSet<Int>()

        // Keyboard plane: keys in x 0..15, touchpad x 15.6..21, five rows high.
        private val planeW = 21f
        private val planeH = 5f
        private val padLeft = 15.6f
        // Corners on the screen (fractions): top-left, top-right, bottom-right, bottom-left.
        private val corners = floatArrayOf(0.16f, 0.50f, 0.84f, 0.50f, 0.99f, 0.94f, 0.01f, 0.94f)
        private val toScreen = Matrix()
        private val toPlane = Matrix()
        private var dragCorner = -1

        private val frameMatrix = Matrix()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = 0x33000000 }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FFFFFF; strokeWidth = 5f * d }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1.6f * d }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val pts = FloatArray(8)

        init {
            prefs.getString("corners", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 8 }?.forEachIndexed { i, v -> corners[i] = v.coerceIn(0f, 1f) }
            fun row(y: Int, build: (add: (String, String?, Int, Float, Boolean) -> Unit) -> Unit) {
                var x = 0f
                build { l, s, sym, w, mod ->
                    keys += TKey(l, s, sym, x, y.toFloat(), w, mod)
                    x += w
                }
            }
            fun chars(add: (String, String?, Int, Float, Boolean) -> Unit, normal: String, shifted: String) {
                for ((n, s) in normal.zip(shifted)) add(n.toString(), s.toString(), 0, 1f, false)
            }
            row(0) { add -> chars(add, "`1234567890-=", "~!@#$%^&*()_+"); add("⌫", null, Keysyms.BACKSPACE, 2f, false) }
            row(1) { add ->
                add("Tab", null, Keysyms.TAB, 1.5f, false)
                chars(add, "qwertyuiop[]", "QWERTYUIOP{}")
                add("\\", "|", 0, 1.5f, false)
            }
            row(2) { add ->
                add("Esc", null, Keysyms.ESCAPE, 1.8f, false)
                chars(add, "asdfghjkl;'", "ASDFGHJKL:\"")
                add("Enter", null, Keysyms.RETURN, 2.2f, false)
            }
            row(3) { add ->
                add("Shift", null, Keysyms.SHIFT_L, 2.4f, true)
                chars(add, "zxcvbnm,./", "ZXCVBNM<>?")
                add("↑", null, Keysyms.UP, 1.2f, false)
                add("Del", null, Keysyms.DELETE, 1.4f, false)
            }
            row(4) { add ->
                add("Ctrl", null, Keysyms.CONTROL_L, 1.6f, true)
                add("Win", null, Keysyms.SUPER_L, 1.4f, true)
                add("Alt", null, Keysyms.ALT_L, 1.4f, true)
                add("", null, 0x20, 6.2f, false)
                add("←", null, Keysyms.LEFT, 1.4f, false)
                add("↓", null, Keysyms.DOWN, 1.5f, false)
                add("→", null, Keysyms.RIGHT, 1.5f, false)
            }
        }

        fun showFrame(b: Bitmap) {
            frame = b
            postInvalidateOnAnimation()
        }

        fun releaseAll() {
            for (m in heldMods) send(Cmd.KeyState(m, false))
            heldMods.clear()
            for (h in hands) { h.pinched = false; h.pinch.reset(); h.seenAt = 0 }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            updateMatrix()
            // The picture stays upright when the phone is turned.
            @Suppress("DEPRECATION")
            analysis?.targetRotation = activity.windowManager.defaultDisplay.rotation
        }

        private fun updateMatrix() {
            if (width == 0 || height == 0) return
            val src = floatArrayOf(0f, 0f, planeW, 0f, planeW, planeH, 0f, planeH)
            val dst = FloatArray(8) { i -> corners[i] * (if (i % 2 == 0) width else height) }
            toScreen.setPolyToPoly(src, 0, dst, 0, 4)
            toScreen.invert(toPlane)
        }

        // ---- hands ----

        fun onHands(list: List<List<Landmark>>, aspect: Float) {
            val now = SystemClock.uptimeMillis()
            val f = frame
            if (f == null || width == 0) return
            val scale = max(width.toFloat() / f.width, height.toFloat() / f.height)
            val ox = (width - f.width * scale) / 2f
            val oy = (height - f.height * scale) / 2f
            val used = BooleanArray(hands.size)
            for (lm in list.take(hands.size)) {
                if (lm.size < HandIdx.COUNT) continue
                val g = HandGeometry(lm, aspect)
                val px = (lm[HandIdx.THUMB_TIP].x + lm[HandIdx.INDEX_TIP].x) / 2f * f.width * scale + ox
                val py = (lm[HandIdx.THUMB_TIP].y + lm[HandIdx.INDEX_TIP].y) / 2f * f.height * scale + oy
                // The same hand keeps its dot: the nearest dot that is still alive, else a free one.
                var best = -1
                var bestD = Float.MAX_VALUE
                for (i in hands.indices) {
                    if (used[i]) continue
                    val h = hands[i]
                    val dist = if (now - h.seenAt < 600) hypot(h.x - px, h.y - py) else 1e6f + i
                    if (dist < bestD) { bestD = dist; best = i }
                }
                if (best < 0) continue
                used[best] = true
                val h = hands[best]
                val fresh = now - h.seenAt >= 600
                val oldX = h.x
                val oldY = h.y
                if (fresh) { h.x = px; h.y = py; h.pinch.reset(); h.pinched = false } else {
                    // Light smoothing: steady when still, quick when the hand moves.
                    val a = if (hypot(px - h.x, py - h.y) > 24 * d) 0.75f else 0.4f
                    h.x += (px - h.x) * a
                    h.y += (py - h.y) * a
                }
                h.seenAt = now
                val pinched = h.pinch.update(g)
                if (!adjust) handle(h, pinched, if (fresh) 0f else h.x - oldX, if (fresh) 0f else h.y - oldY, now)
                h.pinched = pinched
            }
            for (i in hands.indices) if (!used[i] && now - hands[i].seenAt >= 600) hands[i].pinched = false
            if (!adjust) {
                status.text = if (list.isEmpty()) "Show a hand to the camera · pinch = press" else "Pinch = press · touchpad: pinch and move"
            }
            invalidate()
        }

        private fun plane(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { toPlane.mapPoints(it) }

        private fun handle(h: Hand, pinched: Boolean, dx: Float, dy: Float, now: Long) {
            if (pinched && !h.pinched) {
                val p = plane(h.x, h.y)
                h.downAt = now
                h.moved = 0f
                h.longDone = false
                h.onPad = p[0] >= padLeft && p[0] <= planeW && p[1] in 0f..planeH
                if (!h.onPad) keyAt(p[0], p[1])?.let { press(it) }
            } else if (pinched && h.onPad) {
                h.moved += abs(dx) + abs(dy)
                if (h.moved > 6 * d) move(dx / d * 2.4f, dy / d * 2.4f)
                else if (!h.longDone && now - h.downAt > 800) {
                    h.longDone = true
                    click('r')
                }
            } else if (!pinched && h.pinched && h.onPad) {
                if (!h.longDone && h.moved <= 6 * d && now - h.downAt < 500) click('l')
            }
        }

        private fun click(button: Char) {
            send(Cmd.Click(button))
            playSoundEffect(SoundEffectConstants.CLICK)
        }

        private fun keyAt(x: Float, y: Float): TKey? {
            if (y < 0f || y >= planeH || x < 0f) return null
            val row = y.toInt().toFloat()
            return keys.firstOrNull { it.y == row && x >= it.x && x < it.x + it.w }
        }

        private fun press(k: TKey) {
            k.flashUntil = SystemClock.uptimeMillis() + 180
            playSoundEffect(SoundEffectConstants.CLICK)
            if (k.mod) {
                if (heldMods.remove(k.sym)) send(Cmd.KeyState(k.sym, false))
                else { heldMods += k.sym; send(Cmd.KeyState(k.sym, true)) }
                return
            }
            val shift = Keysyms.SHIFT_L in heldMods
            val sym = if (k.sym != 0) k.sym else Keysyms.forChar((if (shift && k.shifted != null) k.shifted else k.label)[0].code)
            send(Cmd.Key(sym))
            // Shift, Ctrl, Alt, Win are for one key.
            for (m in heldMods) send(Cmd.KeyState(m, false))
            heldMods.clear()
        }

        // ---- touch: drag the corners (Adjust), or tap a key ----

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (adjust) {
                        dragCorner = -1
                        var bestD = 70 * d
                        for (i in 0 until 4) {
                            val dist = hypot(corners[i * 2] * width - e.x, corners[i * 2 + 1] * height - e.y)
                            if (dist < bestD) { bestD = dist; dragCorner = i }
                        }
                    } else {
                        val p = plane(e.x, e.y)
                        if (p[0] < padLeft) keyAt(p[0], p[1])?.let { press(it); invalidate() }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (adjust && dragCorner >= 0) {
                    corners[dragCorner * 2] = (e.x / width).coerceIn(0f, 1f)
                    corners[dragCorner * 2 + 1] = (e.y / height).coerceIn(0f, 1f)
                    updateMatrix()
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (adjust && dragCorner >= 0) {
                    dragCorner = -1
                    prefs.edit().putString("corners", corners.joinToString(",")).apply()
                }
            }
            return true
        }

        // ---- drawing ----

        private fun quad(x: Float, y: Float, w: Float, h: Float, inset: Float): Path {
            pts[0] = x + inset; pts[1] = y + inset
            pts[2] = x + w - inset; pts[3] = y + inset
            pts[4] = x + w - inset; pts[5] = y + h - inset
            pts[6] = x + inset; pts[7] = y + h - inset
            toScreen.mapPoints(pts)
            path.rewind()
            path.moveTo(pts[0], pts[1]); path.lineTo(pts[2], pts[3]); path.lineTo(pts[4], pts[5]); path.lineTo(pts[6], pts[7])
            path.close()
            return path
        }

        override fun onDraw(canvas: Canvas) {
            val f = frame
            if (f != null && !f.isRecycled) {
                val scale = max(width.toFloat() / f.width, height.toFloat() / f.height)
                frameMatrix.reset()
                frameMatrix.postScale(scale, scale)
                frameMatrix.postTranslate((width - f.width * scale) / 2f, (height - f.height * scale) / 2f)
                canvas.drawBitmap(f, frameMatrix, bitmapPaint)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            }
            val now = SystemClock.uptimeMillis()
            val hover = HashSet<TKey>()
            var padHover = false
            if (!adjust) for (h in hands) if (now - h.seenAt < 600) {
                val p = plane(h.x, h.y)
                if (p[0] >= padLeft && p[0] <= planeW && p[1] in 0f..planeH) padHover = true else keyAt(p[0], p[1])?.let { hover += it }
            }
            val shift = Keysyms.SHIFT_L in heldMods
            var again = false
            for (k in keys) {
                val p = quad(k.x, k.y, k.w, 1f, 0.07f)
                val flash = now < k.flashUntil
                if (flash) again = true
                val held = k.mod && k.sym in heldMods
                if (flash || held || k in hover) {
                    fill.color = if (flash) 0xCC3DDC97.toInt() else if (held) 0xAA2F6BFF.toInt() else 0x55FFFFFF
                    canvas.drawPath(p, fill)
                }
                canvas.drawPath(p, glow)
                canvas.drawPath(p, line)
                val text = if (shift && k.shifted != null) k.shifted else k.label
                if (text.isNotEmpty()) {
                    val cx = (pts[0] + pts[2] + pts[4] + pts[6]) / 4f
                    val cy = (pts[1] + pts[3] + pts[5] + pts[7]) / 4f
                    val hgt = ((pts[7] - pts[1]) + (pts[5] - pts[3])) / 2f
                    label.textSize = (hgt * if (text.length > 1) 0.30f else 0.46f).coerceAtLeast(6f * d)
                    canvas.drawText(text, cx, cy + label.textSize * 0.35f, label)
                }
            }
            val pad = quad(padLeft, 0f, planeW - padLeft, planeH, 0.07f)
            if (padHover) { fill.color = 0x33FFFFFF; canvas.drawPath(pad, fill) }
            canvas.drawPath(pad, glow)
            canvas.drawPath(pad, line)
            if (adjust) {
                dot.color = 0xFFFFC53D.toInt()
                for (i in 0 until 4) canvas.drawCircle(corners[i * 2] * width, corners[i * 2 + 1] * height, 14 * d, dot)
            } else for (h in hands) if (now - h.seenAt < 600) {
                dot.color = if (h.pinched) 0xFF3DDC97.toInt() else 0xFFFFFFFF.toInt()
                canvas.drawCircle(h.x, h.y, (if (h.pinched) 9 else 12) * d, dot)
                dot.color = 0x66000000
                canvas.drawCircle(h.x, h.y, 4 * d, dot)
            }
            if (again) postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val TAG = "TableKeyboard"
    }
}
