package com.aircursor.cloudpc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.aircursor.air.AirUi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Desktop widgets for the Mac-like shell, drawn by the phone: a clock, the weather (Open-Meteo,
 * free, no account; the person types their city once), storage and battery. They sit on the
 * empty desktop at the top right and step aside whenever a window is open.
 */
class ShellWidgets(private val activity: Activity, host: FrameLayout, private val ui: AirUi, topInset: Int) {
    private val prefs = activity.getSharedPreferences("air_shell", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    val view = LinearLayout(activity)
    private val time = ui.text("", 34, AirUi.TEXT, bold = true)
    private val day = ui.text("", 12.5, AirUi.MUTED)
    private val weatherTemp = ui.text("", 26, AirUi.TEXT, bold = true)
    private val weatherText = ui.text("", 12, AirUi.MUTED)
    private val weatherCity = ui.text("", 12.5, AirUi.TEXT, bold = true)
    private val storageText = ui.text("", 12, AirUi.MUTED)
    private val storageBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val batteryText = ui.text("", 12, AirUi.MUTED)
    private val batteryBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private var weatherAt = 0L
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    val on get() = prefs.getBoolean("widgets", true)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.visibility = View.GONE
        view.addView(card(listOf(day, time)))
        val w = card(listOf(weatherCity, weatherTemp, weatherText))
        w.setOnClickListener { askCity() }
        view.addView(w)
        view.addView(card(listOf(ui.text("Storage", 12.5, AirUi.TEXT, bold = true), storageBar, storageText)))
        view.addView(card(listOf(ui.text("Battery", 12.5, AirUi.TEXT, bold = true), batteryBar, batteryText)))
        for (b in listOf(storageBar, batteryBar)) {
            b.max = 100
            b.progressTintList = android.content.res.ColorStateList.valueOf(AirUi.ACCENT)
            b.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            b.layoutParams = LinearLayout.LayoutParams(-1, ui.u(6)).apply { topMargin = ui.u(8); bottomMargin = ui.u(6) }
        }
        host.addView(view, FrameLayout.LayoutParams(ui.u(190), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = topInset + ui.u(14)
            rightMargin = ui.u(16)
        })
    }

    private fun card(rows: List<View>) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.u(14), ui.u(11), ui.u(14), ui.u(11))
        background = GradientDrawable().apply {
            cornerRadius = ui.uf(16)
            setColor(0xB31C1D22.toInt())
            setStroke(ui.u(1).coerceAtLeast(1), 0x26FFFFFF)
        }
        for (r in rows) addView(r)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.u(10) }
    }

    fun setOn(v: Boolean) = prefs.edit().putBoolean("widgets", v).apply()

    /** Called every second by the shell; [show] = the desktop is empty. */
    fun update(show: Boolean) {
        val want = show && on
        if (want != (view.visibility == View.VISIBLE)) {
            if (want) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(200).start()
            } else {
                view.visibility = View.GONE
            }
        }
        if (!want) return
        val now = Date()
        time.text = clockFmt.format(now)
        day.text = dayFmt.format(now)
        try {
            val st = StatFs(activity.filesDir.absolutePath)
            val total = st.totalBytes.coerceAtLeast(1)
            val free = st.availableBytes
            storageBar.progress = ((total - free) * 100 / total).toInt()
            storageText.text = "%.0f GB free of %.0f GB".format(free / 1e9, total / 1e9)
        } catch (_: Exception) {
        }
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct in 0..100) {
            batteryBar.progress = pct
            batteryText.text = "$pct%" + if (bm?.isCharging == true) " · charging" else ""
        }
        val city = prefs.getString("weather_city", null)
        if (city == null) {
            weatherCity.text = "Weather"
            weatherTemp.text = "—"
            weatherText.text = "Tap to set your city"
        } else if (System.currentTimeMillis() - weatherAt > 30 * 60 * 1000) {
            weatherAt = System.currentTimeMillis()
            weatherCity.text = city
            loadWeather()
        }
    }

    private fun askCity() {
        val field = EditText(activity).apply {
            hint = "City, e.g. Guwahati"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(prefs.getString("weather_city", ""))
        }
        AlertDialog.Builder(activity)
            .setTitle("Weather for which city?")
            .setView(FrameLayout(activity).apply { setPadding(ui.u(20), ui.u(6), ui.u(20), 0); addView(field) })
            .setPositiveButton("OK") { _, _ -> findCity(field.text.toString().trim()) }
            .setNeutralButton("Hide widgets") { _, _ -> setOn(false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun findCity(name: String) {
        if (name.isEmpty()) return
        Thread {
            val r = try {
                get("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=" + URLEncoder.encode(name, "UTF-8"))
                    .optJSONArray("results")?.optJSONObject(0)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (r == null) {
                    ui.toast("City not found (or no internet)")
                } else {
                    prefs.edit().putString("weather_city", r.optString("name", name))
                        .putString("weather_lat", r.optDouble("latitude").toString())
                        .putString("weather_lon", r.optDouble("longitude").toString()).apply()
                    weatherAt = 0
                }
            }
        }.start()
    }

    private fun loadWeather() {
        val lat = prefs.getString("weather_lat", null) ?: return
        val lon = prefs.getString("weather_lon", null) ?: return
        Thread {
            val cur = try {
                get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude=$lat&longitude=$lon")
                    .optJSONObject("current")
            } catch (_: Exception) {
                null
            }
            main.post {
                if (cur == null) {
                    weatherAt = System.currentTimeMillis() - 25 * 60 * 1000   // try again in 5 minutes
                    if (weatherTemp.text.isNullOrEmpty() || weatherTemp.text == "—") weatherText.text = "No internet"
                } else {
                    weatherTemp.text = "%.0f°".format(cur.optDouble("temperature_2m"))
                    weatherText.text = describe(cur.optInt("weather_code"))
                }
            }
        }.start()
    }

    /** WMO weather codes (Open-Meteo) in plain words. */
    private fun describe(code: Int) = when (code) {
        0 -> "☀️ Clear sky"
        1, 2 -> "🌤 Partly cloudy"
        3 -> "☁️ Cloudy"
        45, 48 -> "🌫 Fog"
        51, 53, 55, 56, 57 -> "🌦 Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧 Rain"
        71, 73, 75, 77, 85, 86 -> "🌨 Snow"
        95, 96, 99 -> "⛈ Thunderstorm"
        else -> "—"
    }
}
