package com.aircursor.cloudpc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aircursor.air.AirUi

/**
 * The first-start tour of AirCursor PC: five short cards (Hindi or English) over the desktop.
 * Shown once; again from the AirCursor PC menu → "How to use".
 */
@SuppressLint("ViewConstructor")
class PcTour(private val activity: Activity, private val onDone: () -> Unit) : FrameLayout(activity) {
    companion object {
        private const val PREFS = "air_shell"
        fun done(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("tour_done", false)
        private fun setDone(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("tour_done", true).apply()
    }

    private class Page(val icon: String, val en: Pair<String, String>, val hi: Pair<String, String>)

    private val pages = listOf(
        Page("💻",
            "Welcome to your PC" to "A real Ubuntu desktop on your phone. Your files, apps and projects stay on this phone – private to you.",
            "आपके PC में स्वागत है" to "आपके फ़ोन पर असली Ubuntu कंप्यूटर। आपकी फ़ाइलें, ऐप्स और प्रोजेक्ट इसी फ़ोन में रहते हैं – सिर्फ़ आपके।"),
        Page("🧭",
            "Menu bar and dock" to "Top: the AirCursor PC menu, the app in front, search, battery and time. Bottom: the dock – tap an app to open it; a dot means it is open. Hold an icon for more.",
            "मेन्यू बार और डॉक" to "ऊपर: AirCursor PC मेन्यू, सामने वाला ऐप, सर्च, बैटरी और समय। नीचे: डॉक – ऐप खोलने के लिए टैप करें; डॉट का मतलब ऐप खुला है। ज़्यादा विकल्प के लिए आइकन दबाकर रखें।"),
        Page("🧩",
            "All apps and Air Store" to "The first dock icon shows every app with search. Air Store adds more: VS Code, LibreOffice, games, web apps – one tap.",
            "सभी ऐप्स और Air Store" to "डॉक का पहला आइकन सारे ऐप्स सर्च के साथ दिखाता है। Air Store से और ऐप्स जोड़ें: VS Code, LibreOffice, गेम्स, वेब ऐप्स – एक टैप में।"),
        Page("👆",
            "Touch, keys and hand control" to "Tap = click · hold = right-click · swipe = scroll. The keyboard is in the menu bar. With hand control you move the pointer in the air – pinch to click.",
            "टच, कीबोर्ड और हाथ से कंट्रोल" to "टैप = क्लिक · दबाकर रखें = राइट-क्लिक · स्वाइप = स्क्रॉल। कीबोर्ड मेन्यू बार में है। हाथ से कंट्रोल में पॉइंटर हवा में चलाएँ – चुटकी (pinch) से क्लिक।"),
        Page("🚀",
            "Speed, games and backup" to "AirBoost (menu → AirBoost) makes games and big apps faster. TS Station is your game console. Back up your PC any time: AirCursor app → AirCursor PC → Back up.",
            "स्पीड, गेम्स और बैकअप" to "AirBoost (मेन्यू → AirBoost) गेम्स और बड़े ऐप्स तेज़ करता है। TS Station आपका गेम कंसोल है। कभी भी बैकअप लें: AirCursor ऐप → AirCursor PC → Back up।"),
    )

    private val ui = AirUi(activity)
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var hindi = prefs.getBoolean("tour_hindi", java.util.Locale.getDefault().language == "hi")
    private var index = 0
    private val icon = TextView(activity)
    private val title = TextView(activity)
    private val body = TextView(activity)
    private val dots = LinearLayout(activity)
    private val next = TextView(activity)
    private val back = TextView(activity)
    private val lang = TextView(activity)

    init {
        isClickable = true
        setBackgroundColor(0x99000000.toInt())
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.u(26), ui.u(18), ui.u(26), ui.u(18))
            background = GradientDrawable().apply {
                cornerRadius = ui.uf(22)
                setColor(0xF21C1D22.toInt())
                setStroke(ui.u(1).coerceAtLeast(1), 0x26FFFFFF)
            }
            elevation = ui.uf(16)
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lang.apply {
            setTextColor(AirUi.ACCENT)
            textSize = 13f
            setPadding(ui.u(8), ui.u(6), ui.u(8), ui.u(6))
            background = ui.ripple(null, 8)
            setOnClickListener {
                hindi = !hindi
                prefs.edit().putBoolean("tour_hindi", hindi).apply()
                show()
            }
        }
        val skip = TextView(activity).apply {
            setTextColor(AirUi.MUTED)
            textSize = 13f
            setPadding(ui.u(8), ui.u(6), ui.u(8), ui.u(6))
            background = ui.ripple(null, 8)
            setOnClickListener { finish() }
        }
        skip.tag = "skip"
        top.addView(lang)
        top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(skip)
        card.addView(top, LinearLayout.LayoutParams(-1, -2))
        icon.textSize = 40f
        icon.gravity = Gravity.CENTER
        card.addView(icon, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4) })
        title.apply { setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
        body.apply { setTextColor(0xFFC9CCD4.toInt()); textSize = 14.5f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
        dots.gravity = Gravity.CENTER
        card.addView(dots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(16) })
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((b, primary) in listOf(back to false, next to true)) {
            b.textSize = 15f
            b.gravity = Gravity.CENTER
            b.setPadding(ui.u(22), 0, ui.u(22), 0)
            b.setTextColor(if (primary) Color.WHITE else AirUi.TEXT)
            b.background = GradientDrawable().apply {
                cornerRadius = ui.uf(20)
                setColor(if (primary) AirUi.ACCENT else 0x26FFFFFF)
            }
            buttons.addView(b, LinearLayout.LayoutParams(-2, ui.u(40)).apply { marginStart = ui.u(6); marginEnd = ui.u(6) })
        }
        back.setOnClickListener { if (index > 0) { index--; show() } }
        next.setOnClickListener { if (index < pages.size - 1) { index++; show() } else finish() }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(14) })
        addView(card, LayoutParams(ui.u(420).coerceAtMost(activity.resources.displayMetrics.widthPixels - ui.u(32)), -2, Gravity.CENTER))
        show()
    }

    private fun show() {
        val p = pages[index]
        val (t, b) = if (hindi) p.hi else p.en
        icon.text = p.icon
        title.text = t
        body.text = b
        lang.text = if (hindi) "English" else "हिंदी"
        (findViewWithTag<TextView>("skip"))?.text = if (hindi) "छोड़ें" else "Skip"
        back.text = if (hindi) "पीछे" else "Back"
        back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        next.text = when {
            index < pages.size - 1 -> if (hindi) "आगे" else "Next"
            else -> if (hindi) "शुरू करें" else "Start"
        }
        dots.removeAllViews()
        for (i in pages.indices) {
            dots.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == index) Color.WHITE else 0x4DFFFFFF)
                }
            }, LinearLayout.LayoutParams(ui.u(7), ui.u(7)).apply { marginStart = ui.u(4); marginEnd = ui.u(4) })
        }
    }

    fun finish() {
        setDone(activity)
        animate().alpha(0f).setDuration(180).withEndAction { (parent as? FrameLayout)?.removeView(this); onDone() }.start()
    }
}
