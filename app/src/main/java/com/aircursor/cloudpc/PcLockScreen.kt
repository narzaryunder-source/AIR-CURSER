package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aircursor.R
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The lock screen in front of AirCursor PC (Mac-like): the PC's own wallpaper, softly blurred,
 * the date and a big clock at the top, the person's picture, name and password box at the bottom
 * – or the first-start question "Protect your PC with a password".
 * The desktop keeps starting behind it, so after signing in it is (nearly) ready.
 */
@SuppressLint("ViewConstructor")
class PcLockScreen(
    context: Context,
    private val mode: Mode,
    private val listener: Listener,
) : FrameLayout(context) {
    enum class Mode { SIGN_IN, SETUP, SET_PASSWORD }

    interface Listener {
        fun onUnlocked()
        fun onForgot()
    }

    private val dp = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).roundToInt()
    private val wallpaper = ImageView(context)
    private val clock = TextView(context)
    private val date = TextView(context)
    private val clockBox = LinearLayout(context)
    private val statusLine = TextView(context)
    private var error: TextView? = null
    private var attempts = 0
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.getTimeFormat(context).format(now)
            date.text = DateFormat.format("EEEE, d MMMM", now)
            val st = CloudPcBus.state.value
            statusLine.text = when {
                st.desktopReady -> "AirCursor PC is ready"
                st.desktopRunning -> "AirCursor PC is starting…"
                else -> ""
            }
            postDelayed(this, 1000)
        }
    }

    init {
        isClickable = true   // nothing reaches the desktop behind
        isFocusable = true
        setBackgroundColor(0xFF0B1020.toInt())
        wallpaper.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaper.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E2A5A.toInt(), 0xFF0B1020.toInt(), 0xFF3A1F4F.toInt()),
        )
        addView(wallpaper, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0x33000000, 0x99000000.toInt()),
            )
        }, LayoutParams(-1, -1))
        loadWallpaper()

        val scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(40))
        }
        scroll.addView(column, LayoutParams(-1, -1))
        addView(scroll, LayoutParams(-1, -1))
        // The on-screen keyboard: keep the boxes above it (the window itself is not resized here).
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            scroll.setPadding(0, 0, 0, ime)
            if (ime > 0) compactForKeyboard()
            insets
        }

        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.gravity = Gravity.CENTER_HORIZONTAL
        // Date small above a big clock, like a Mac.
        clock.style(68f, bold = true)
        clock.alpha = 0.92f
        date.style(17f, bold = true)
        date.alpha = 0.9f
        clockBox.addView(date)
        clockBox.addView(clock)
        column.addView(clockBox, LinearLayout.LayoutParams(-2, -2))
        // The sign-in sits near the bottom; the space between grows with the screen.
        if (mode == Mode.SIGN_IN) column.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f).apply { topMargin = dp(18) })

        when (mode) {
            Mode.SIGN_IN -> buildSignIn(column)
            Mode.SETUP, Mode.SET_PASSWORD -> buildSetup(column)
        }

        statusLine.style(12f)
        statusLine.alpha = 0.75f
        addView(statusLine, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        if (mode != Mode.SIGN_IN) clockBox.visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ---- sign in ----

    private fun buildSignIn(column: LinearLayout) {
        column.addView(avatar(PcLock.name(context)))
        column.addView(TextView(context).apply { style(17f, bold = true); text = PcLock.name(context) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x38FFFFFF)
                setStroke(dp(1), 0x4DFFFFFF)
            }
            setPadding(dp(16), 0, dp(4), 0)
        }
        val field = passwordField("Password")
        field.background = null
        val go = ImageButton(context).apply {
            setImageResource(R.drawable.ic_ui_right)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
            contentDescription = "Sign in"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(go, LinearLayout.LayoutParams(dp(36), dp(36)))
        column.addView(row, LinearLayout.LayoutParams(dp(250), -2))
        val err = TextView(context).apply { style(13f); setTextColor(0xFFFFB4B4.toInt()); visibility = View.GONE; gravity = Gravity.CENTER }
        error = err
        column.addView(err, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(10) })
        column.addView(link("Forgot password?") { listener.onForgot() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val submit: () -> Unit = {
            val pw = field.text.toString()
            if (pw.isNotEmpty()) {
                go.isEnabled = false
                Thread {
                    val ok = PcLock.check(context, pw)   // a deliberately slow hash: off the main thread
                    post {
                        go.isEnabled = true
                        if (ok) {
                            hideKeyboard(field)
                            listener.onUnlocked()
                        } else {
                            attempts++
                            field.setText("")
                            val hint = PcLock.hint(context)
                            showError(
                                "The password is incorrect. Try again." +
                                    if (attempts >= 2 && hint.isNotEmpty()) "\nHint: $hint" else "",
                            )
                        }
                    }
                }.start()
            }
        }
        go.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit(); true
            } else false
        }
        field.setOnFocusChangeListener { _, has -> if (has) compactForKeyboard() }
        field.setOnClickListener { compactForKeyboard() }
    }

    // ---- first start / set a password ----

    private fun buildSetup(column: LinearLayout) {
        val first = mode == Mode.SETUP
        column.addView(TextView(context).apply {
            style(24f, bold = true)
            text = if (first) "Protect your PC" else "Set a password"
        })
        column.addView(TextView(context).apply {
            style(14f)
            alpha = 0.85f
            gravity = Gravity.CENTER
            text = "Set a password so only you can open AirCursor PC on this phone. Your PC, apps and " +
                "projects live only on this phone. You can change it later: toolbar → Lock."
        }, LinearLayout.LayoutParams(dp(340), -2).apply { topMargin = dp(6); bottomMargin = dp(14) })

        val name = textField("Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            setText(PcLock.name(context).takeIf { it != "User" }.orEmpty())
        }
        val pw = passwordField("Password (at least ${PcLock.MIN_LENGTH} characters)")
        val pw2 = passwordField("Confirm password")
        val hint = textField("Password hint (optional)", InputType.TYPE_CLASS_TEXT)
        for (f in listOf(name, pw, pw2, hint)) {
            column.addView(f, LinearLayout.LayoutParams(dp(320), dp(46)).apply { bottomMargin = dp(8) })
            f.setOnFocusChangeListener { _, has -> if (has) compactForKeyboard() }
        }
        val err = TextView(context).apply { style(13f); setTextColor(0xFFFFB4B4.toInt()); visibility = View.GONE; gravity = Gravity.CENTER }
        error = err
        column.addView(err, LinearLayout.LayoutParams(dp(320), -2).apply { bottomMargin = dp(6) })

        val dontShow = CheckBox(context).apply {
            text = "Don't show me again"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val skip = button(if (first) "Skip" else "Cancel", primary = false) {
            if (first && dontShow.isChecked) PcLock.setNoSetup(context, true)
            hideKeyboard(pw)
            listener.onUnlocked()
        }
        val set = button("Set password", primary = true) {
            val p1 = pw.text.toString()
            when {
                !PcLock.isValid(p1) -> showError("Use at least ${PcLock.MIN_LENGTH} characters.")
                p1 != pw2.text.toString() -> showError("The two passwords are not the same.")
                hint.text.toString().isNotBlank() && hint.text.toString().contains(p1) ->
                    showError("The hint must not contain the password.")
                else -> {
                    name.text.toString().trim().takeIf { it.isNotEmpty() }?.let { PcLock.setName(context, it) }
                    PcLock.setPassword(context, p1, hint.text.toString())
                    hideKeyboard(pw)
                    listener.onUnlocked()
                }
            }
        }
        buttons.addView(skip, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(10) })
        buttons.addView(set, LinearLayout.LayoutParams(-2, dp(44)))
        column.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        if (first) column.addView(dontShow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    // ---- pieces ----

    /** The keyboard covers half of a landscape screen: drop the big clock so the box stays visible. */
    private fun compactForKeyboard() {
        clockBox.visibility = View.GONE
    }

    private fun showError(msg: String) {
        error?.apply {
            text = msg
            visibility = View.VISIBLE
        }
    }

    private fun TextView.style(sp: Float, bold: Boolean = false, light: Boolean = false) {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = when {
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        setShadowLayer(8f, 0f, 1f, 0x80000000.toInt())
    }

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun textField(hintText: String, type: Int) = EditText(context).apply {
        hint = hintText
        inputType = type
        setSingleLine()
        setTextColor(Color.WHITE)
        setHintTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        background = pill(0x33FFFFFF, 0x66FFFFFF)
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun passwordField(hintText: String) =
        textField(hintText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFF0B1020.toInt() else Color.WHITE)
        background = if (primary) pill(Color.WHITE, Color.WHITE) else pill(0x33FFFFFF, 0x66FFFFFF)
        setPadding(dp(22), 0, dp(22), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun link(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        style(13f)
        alpha = 0.85f
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun avatar(name: String) = TextView(context).apply {
        text = name.take(1).uppercase()
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF5B8CFF.toInt(), 0xFF8E5BFF.toInt())).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), 0x59FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
    }

    private fun hideKeyboard(v: View) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    fun focusPassword() {
        (findFocusableEdit(this))?.let { f ->
            f.requestFocus()
        }
    }

    private fun findFocusableEdit(v: View): EditText? {
        if (v is EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findFocusableEdit(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---- wallpaper: the PC's own desktop picture ----

    private fun loadWallpaper() {
        val c = context.applicationContext
        val w = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        Thread {
            val bmp = try {
                wallpaperFile(c)?.let { decode(it, if (android.os.Build.VERSION.SDK_INT >= 31) w else w / 24) }
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) post {
                wallpaper.setImageBitmap(bmp)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    wallpaper.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        28 * dp, 28 * dp, android.graphics.Shader.TileMode.CLAMP))
                }
                wallpaper.alpha = 0f
                wallpaper.animate().alpha(1f).setDuration(250).start()
            }
        }.start()
    }

    private fun decode(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    companion object {
        private val LAST_IMAGE = Regex("""name="last-image" type="string" value="([^"]+)"""")

        /** The desktop's picture inside the PC (links resolved inside the PC, never outside it). */
        fun wallpaperFile(c: Context): File? {
            val root = CloudPcEngine.rootfs(c)
            val xml = File(root, "root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml")
            val wanted = ArrayList<String>()
            if (xml.isFile) LAST_IMAGE.findAll(xml.readText()).forEach { wanted += it.groupValues[1] }
            wanted += "/usr/share/xfce4/backdrops/xubuntu-wallpaper.png"
            for (g in wanted.distinct()) resolve(root, g)?.let { return it }
            return null
        }

        private fun resolve(root: File, guest: String): File? {
            val base = root.toPath().toAbsolutePath().normalize()
            var p = guest
            repeat(6) {
                if (!p.startsWith("/")) return null
                val host = base.resolve(p.removePrefix("/")).normalize()
                if (!host.startsWith(base)) return null
                if (Files.isSymbolicLink(host)) {
                    val t = Files.readSymbolicLink(host).toString()
                    p = if (t.startsWith("/")) t else Paths.get(p).parent.resolve(t).normalize().toString()
                } else {
                    return if (Files.isRegularFile(host, LinkOption.NOFOLLOW_LINKS)) host.toFile() else null
                }
            }
            return null
        }
    }
}
