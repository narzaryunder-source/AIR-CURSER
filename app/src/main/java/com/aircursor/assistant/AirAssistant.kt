package com.aircursor.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AirCursor's voice assistant in the PC. Tap the mic (or Win+A), say what you want – "YouTube kholo", "search cricket score",
 * "type hello" – and it happens. Its name is yours to choose (default "Air").
 */
@SuppressLint("SetTextI18n")
class AirAssistant(
    private val activity: Activity,
    private val root: FrameLayout,
    private val actions: Actions,
) {
    /** What the assistant can do in AirCursor PC (the activity does it). Each returns what to say. */
    interface Actions {
        fun openTarget(target: String): String
        fun closeApp(target: String): String
        fun search(query: String): String
        fun youtube(query: String): String
        fun typeText(text: String): String
        fun toggle(feature: String, on: Boolean?): String
        fun scroll(down: Boolean): String
        fun browser(action: String): String
        fun window(action: String): String
        fun screenshot(): String
        fun lock(): String
    }

    private val prefs = activity.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    val name: String get() = prefs.getString("name", "Air")?.ifBlank { "Air" } ?: "Air"
    private val talks get() = prefs.getBoolean("voice_replies", true)
    private val language get() = prefs.getString("language", "en-IN") ?: "en-IN"

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * d).roundToInt()

    private var panel: LinearLayout? = null
    private lateinit var orb: AssistantOrb
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var label: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private val hide = Runnable { dismiss() }

    val isShowing get() = panel != null

    fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            showPanel()
            answer("Voice typing isn't available on this phone. Install or turn on Google's speech services.", problem = true)
            return
        }
        showPanel()
        orb.state = AssistantOrb.State.LISTENING
        heard.text = "Listening…"
        heard.setTextColor(0xB3FFFFFF.toInt())
        reply.text = "Try: \"YouTube kholo\", \"search today's news\""
        reply.setTextColor(0x99FFFFFF.toInt())
        root.removeCallbacks(hide)
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        try {
            if (listening) r.cancel()
            r.startListening(intent)
            listening = true
        } catch (e: Exception) {
            answer("The microphone is busy – try again.", problem = true)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (panel != null) orb.level = ((rmsdB + 2f) / 12f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (panel != null) orb.state = AssistantOrb.State.THINKING
        }

        override fun onError(error: Int) {
            listening = false
            if (panel == null) return
            answer(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that – tap the mic and try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for voice right now."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for AirCursor first."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy – try again."
                    else -> "Something went wrong with voice – try again."
                },
                problem = true,
                speak = false,
            )
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (said.isEmpty()) {
                answer("I didn't catch that – tap the mic and try again.", problem = true, speak = false)
                return
            }
            heard.text = "“$said”"
            heard.setTextColor(Color.WHITE)
            run(said)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank() && panel != null) {
                heard.text = p
                heard.setTextColor(Color.WHITE)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Runs what was said (also used for typed commands). */
    fun run(said: String) {
        val cmd = AssistantBrain.parse(said, name)
        val text = try {
            when (cmd) {
                is Command.Open -> actions.openTarget(cmd.target)
                is Command.CloseApp -> actions.closeApp(cmd.target)
                is Command.Search -> actions.search(if (cmd.query in THIS) clipboard() ?: cmd.query else cmd.query)
                is Command.PlayYouTube -> actions.youtube(cmd.query)
                is Command.Type -> actions.typeText(cmd.text)
                is Command.Toggle -> actions.toggle(cmd.feature, cmd.on)
                is Command.Scroll -> actions.scroll(cmd.down)
                is Command.Browser -> actions.browser(cmd.action)
                is Command.Window -> actions.window(cmd.action)
                is Command.Volume -> volume(cmd.action)
                is Command.Rename -> {
                    prefs.edit().putString("name", cmd.name).apply()
                    label.text = cmd.name.uppercase(Locale.ROOT)
                    "Okay, I'm ${cmd.name} now."
                }
                Command.Screenshot -> actions.screenshot()
                Command.Time -> "It's " + DateFormat.getTimeFormat(activity).format(Date()) + "."
                Command.Date -> "Today is " + DateFormat.format("EEEE, d MMMM", Date()) + "."
                Command.Battery -> battery()
                Command.Lock -> actions.lock()
                Command.Help -> "Say: open YouTube · search anything · play a song · type a message · " +
                    "scroll down · new tab · screenshot · what's the time · volume up."
                Command.Nothing -> "Say something like \"open YouTube\"."
            }
        } catch (e: Exception) {
            "That didn't work: ${e.message ?: "unknown problem"}"
        }
        answer(text)
    }

    private fun clipboard(): String? = activity.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim()?.take(300)?.ifBlank { null }

    private fun battery(): String {
        val bm = activity.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        return if (pct < 0) "I can't read the battery." else "Battery is at $pct%" + (if (charging) ", charging." else ".")
    }

    private fun volume(action: String): String {
        val am = activity.getSystemService(android.media.AudioManager::class.java) ?: return "I can't change the volume."
        val s = android.media.AudioManager.STREAM_MUSIC
        when (action) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI)
        }
        return when (action) {
            "up" -> "Volume up."
            "down" -> "Volume down."
            "mute" -> "Muted."
            else -> "Sound on."
        }
    }

    private fun answer(text: String, problem: Boolean = false, speak: Boolean = true) {
        if (panel == null) showPanel()
        orb.state = if (problem) AssistantOrb.State.PROBLEM else AssistantOrb.State.ANSWER
        orb.level = 0f
        reply.text = text
        reply.setTextColor(if (problem) 0xFFFFC56B.toInt() else 0xFF8FF5D2.toInt())
        if (speak && talks) say(text)
        root.removeCallbacks(hide)
        root.postDelayed(hide, if (problem) 4500 else 3200)
    }

    private fun say(text: String) {
        val t = tts
        if (t == null) {
            tts = TextToSpeech(activity.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = if (language.startsWith("hi")) Locale("hi", "IN") else Locale("en", "IN")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "air")
                }
            }
            return
        }
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "air")
    }

    // ---- HUD ----

    private fun showPanel() {
        if (panel != null) {
            bringToFront()
            return
        }
        val p = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00B1530.toInt(), 0xF0121B3D.toInt())).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0x5535E0FF)
            }
            elevation = dp(20).toFloat()
            isClickable = true
            setOnClickListener { listen() }
            setOnLongClickListener {
                settings()
                true
            }
        }
        orb = AssistantOrb(activity)
        p.addView(orb, LinearLayout.LayoutParams(dp(74), dp(74)))
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        label = TextView(activity).apply {
            text = name.uppercase(Locale.ROOT)
            setTextColor(0xFF35E0FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        heard = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 2
            setPadding(0, dp(2), 0, dp(2))
        }
        reply = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 3
        }
        col.addView(label)
        col.addView(heard)
        col.addView(reply)
        p.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(activity).apply {
            text = "✕"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener { dismiss() }
        }
        p.addView(close)
        val w = (activity.resources.displayMetrics.widthPixels * 0.62f).roundToInt().coerceIn(dp(320), dp(680))
        root.addView(p, FrameLayout.LayoutParams(w, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
        p.alpha = 0f
        p.translationY = dp(30).toFloat()
        p.animate().alpha(1f).translationY(0f).setDuration(220).start()
        panel = p
    }

    fun bringToFront() {
        panel?.bringToFront()
    }

    fun dismiss() {
        root.removeCallbacks(hide)
        if (listening) {
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
            listening = false
        }
        val p = panel ?: return
        panel = null
        p.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(180).withEndAction { root.removeView(p) }.start()
    }

    fun release() {
        dismiss()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    /** Long-press the assistant: its name, voice replies and language. */
    fun settings() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val nameField = EditText(activity).apply {
            setText(name)
            hint = "Assistant name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(TextView(activity).apply { text = "Name (say it before a command, or don't – both work)" })
        box.addView(nameField)
        val voice = CheckBox(activity).apply {
            text = "Speak the answers"
            isChecked = talks
        }
        box.addView(voice)
        val hindi = CheckBox(activity).apply {
            text = "I speak mostly Hindi (हिंदी)"
            isChecked = language.startsWith("hi")
        }
        box.addView(hindi)
        AlertDialog.Builder(activity)
            .setTitle("Voice assistant")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("name", nameField.text.toString().trim().take(24).ifEmpty { "Air" })
                    .putBoolean("voice_replies", voice.isChecked)
                    .putString("language", if (hindi.isChecked) "hi-IN" else "en-IN")
                    .apply()
                if (panel != null) label.text = name.uppercase(Locale.ROOT)
                Toast.makeText(activity, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private val THIS = setOf("this", "it", "ye", "yeh", "isko", "ise", "isse", "that", "wo", "woh")
    }
}
