package com.aircursor.air

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Air's look: dark, compact, laptop-sized like Brave in the PC. One unit = one CSS pixel. */
@SuppressLint("ViewConstructor")
class AirUi(val c: Context) {
    companion object {
        const val FRAME = 0xFF0F1014.toInt()        // tab strip / title bar
        const val TOOLBAR = 0xFF1D1F25.toInt()      // active tab, toolbar
        const val FIELD = 0xFF2A2C33.toInt()        // address bar
        const val FIELD_FOCUS = 0xFF33363F.toInt()
        const val PANEL = 0xFF191B21.toInt()
        const val PANEL_ROW = 0xFF22252C.toInt()
        const val LINE = 0x1AFFFFFF
        const val TEXT = 0xFFECEDF1.toInt()
        const val MUTED = 0xFF9DA2AE.toInt()
        const val FAINT = 0xFF6E7380.toInt()
        const val ACCENT = 0xFF6D7BFF.toInt()
        const val ACCENT_2 = 0xFF9A6BFF.toInt()
        const val SHIELD = 0xFFFF6B3D.toInt()
        const val GOOD = 0xFF3DDC97.toInt()
        const val BAD = 0xFFFF5D5D.toInt()
    }

    /** Device pixels per unit – the same scale Gecko uses for pages. */
    val s: Float = AirEngine.scale(c)

    fun u(v: Number): Int = (v.toFloat() * s).roundToInt()
    fun uf(v: Number): Float = v.toFloat() * s

    fun round(color: Int, radius: Number, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = uf(radius)
        setColor(color)
        if (stroke != 0) setStroke(u(1).coerceAtLeast(1), stroke)
    }

    fun ripple(base: Drawable?, radius: Number = 6): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, round(Color.WHITE, radius))

    fun text(t: CharSequence = "", size: Number = 13, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(
        res: Int,
        description: String,
        box: Number = 30,
        size: Number = 17,
        tint: Int = TEXT,
        onClick: (View) -> Unit,
    ) = ImageButton(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = ((box.toFloat() - size.toFloat()) / 2f * s).roundToInt()
        setPadding(p, p, p, p)
        background = ripple(null, box.toFloat() / 2f)
        contentDescription = description
        tooltipText = description
        layoutParams = LinearLayout.LayoutParams(u(box), u(box))
        setOnClickListener { onClick(it) }
    }

    fun toast(msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

    // ---- dropdown menus (main menu, right click, tab menu) ----

    class Item(
        val label: String,
        val icon: Int? = null,
        val shortcut: String? = null,
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        val separatorBefore: Boolean = false,
        val action: () -> Unit,
    )

    /** A Brave-style menu at [x],[y] (window coordinates) inside [anchor]'s window. */
    fun menu(anchor: View, x: Int, y: Int, items: List<Item>, width: Number = 250): PopupWindow {
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, u(5), 0, u(5))
        }
        val pop = PopupWindow(c)
        for (it in items) {
            if (it.separatorBefore) {
                col.addView(View(c).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, u(1).coerceAtLeast(1)).apply {
                    topMargin = u(4); bottomMargin = u(4)
                })
            }
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(12), 0, u(14), 0)
                background = ripple(null, 4)
                alpha = if (it.enabled) 1f else 0.4f
                isEnabled = it.enabled
                setOnClickListener { _ ->
                    pop.dismiss()
                    it.action()
                }
            }
            val ic = ImageView(c).apply {
                if (it.icon != null) setImageResource(it.icon)
                imageTintList = ColorStateList.valueOf(MUTED)
            }
            row.addView(ic, LinearLayout.LayoutParams(u(16), u(16)).apply { marginEnd = u(12) })
            row.addView(text(it.label, 13), LinearLayout.LayoutParams(0, -2, 1f))
            when {
                it.checked != null -> row.addView(text(if (it.checked) "✓" else "", 13, ACCENT, bold = true))
                it.shortcut != null -> row.addView(text(it.shortcut, 11.5, FAINT))
            }
            col.addView(row, LinearLayout.LayoutParams(-1, u(30)))
        }
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
            background = round(PANEL, 8, 0x26FFFFFF)
            elevation = uf(10)
        }
        pop.contentView = scroll
        pop.width = u(width)
        pop.height = ViewGroup.LayoutParams.WRAP_CONTENT
        pop.isFocusable = true
        pop.isOutsideTouchable = true
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.elevation = uf(10)
        val loc = IntArray(2)
        anchor.rootView.getLocationInWindow(loc)
        val maxX = anchor.rootView.width - u(width) - u(4)
        pop.showAtLocation(anchor, Gravity.TOP or Gravity.START, x.coerceAtMost(maxX).coerceAtLeast(0), y)
        return pop
    }

    /** A panel popup (Shields) under [anchor], right-aligned with it. */
    fun panel(anchor: View, content: View, width: Number): PopupWindow {
        val frame = FrameLayout(c).apply {
            background = round(PANEL, 10, 0x26FFFFFF)
            elevation = uf(10)
            addView(content)
        }
        val pop = PopupWindow(frame, u(width), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pop.isOutsideTouchable = true
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.elevation = uf(10)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + anchor.width - u(width)).coerceAtLeast(u(4))
        pop.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, loc[1] + anchor.height + u(4))
        return pop
    }
}
