package com.aircursor.data

import android.content.Context
import android.content.SharedPreferences
import com.aircursor.core.GestureConfig
import com.aircursor.core.PointerMode
import kotlin.math.roundToInt

data class FloatSetting(
    val key: String,
    val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val steps: Int = 0,
    val format: (Float) -> String,
)

data class BoolSetting(
    val key: String,
    val label: String,
    val default: Boolean,
    val description: String = "",
)

object AppSettings {
    val MARGIN = FloatSetting("margin", "Active box margin (box mode)", 0.15f, 0f, 0.35f) { "${(it * 100).roundToInt()}%" }
    val SENSITIVITY = FloatSetting("sensitivity", "Cursor speed", 1f, 0.4f, 2.5f) { "%.2fx".format(it) }
    val MIN_CUTOFF = FloatSetting("min_cutoff2", "Smoothing (min cutoff)", 0.8f, 0.1f, 5f) { "%.2f Hz".format(it) }
    val BETA = FloatSetting("beta", "Responsiveness (beta)", 0.01f, 0f, 0.05f) { "%.3f".format(it) }
    val DEAD_ZONE = FloatSetting("dead_zone2", "Anti-shake", 6f, 0f, 20f) { "${it.roundToInt()} px" }
    val PINCH = FloatSetting("pinch", "Pinch distance", 0.28f, 0.15f, 0.45f) { "%.2f".format(it) }
    val SCROLL_SPEED = FloatSetting("scroll_speed", "Scroll speed", 1.2f, 0.4f, 3f) { "%.1fx".format(it) }
    val CURSOR_SIZE = FloatSetting("cursor_size", "Cursor size", 1f, 0.6f, 2f) { "%.1fx".format(it) }
    val PREDICTION = FloatSetting("prediction2", "Lag compensation (prediction)", 0.5f, 0f, 1f) {
        "${(it * 100).roundToInt()}%"
    }
    val TARGET_FPS = FloatSetting("max_fps", "Max tracking FPS (battery saver)", 60f, 15f, 60f, steps = 8) {
        if (it >= 59.5f) "Unlimited" else "${it.roundToInt()} fps"
    }
    val RESOLUTION = FloatSetting("resolution2", "Camera resolution", 2f, 0f, 2f, steps = 1) {
        when (it.roundToInt()) {
            0 -> "Low 320p (fastest)"
            1 -> "Medium 480p"
            else -> "High 640p (far hand)"
        }
    }

    val SLIDERS = listOf(
        MARGIN, SENSITIVITY, PREDICTION, MIN_CUTOFF, BETA, DEAD_ZONE, PINCH, SCROLL_SPEED, CURSOR_SIZE,
    )
    val PROCESSOR = FloatSetting("processor", "Hand tracking runs on", 0f, 0f, 2f, steps = 1) {
        when (it.roundToInt()) {
            0 -> "Auto (fastest)"
            1 -> "GPU"
            else -> "CPU"
        }
    }

    val CAMERA_COLOURS = FloatSetting("camera_colours", "Camera image", 0f, 0f, 2f, steps = 1) {
        when (it.roundToInt()) {
            0 -> "Auto (fixes stripes)"
            1 -> "Colour"
            else -> "Black & white (fastest)"
        }
    }

    val PERFORMANCE_SLIDERS = listOf(PROCESSOR, CAMERA_COLOURS, RESOLUTION, TARGET_FPS)

    val G_TAP = BoolSetting("g_tap", "Tap (quick pinch)", true)
    val G_LONG = BoolSetting("g_long", "Long press (pinch hold)", true)
    val G_DRAG = BoolSetting("g_drag", "Drag (pinch + move)", true)
    val G_SCROLL = BoolSetting("g_scroll", "Scroll (two fingers)", true)
    val G_BACK = BoolSetting("g_back", "Back (palm swipe left)", true)
    val G_HOME = BoolSetting("g_home", "Home (palm hold 1s)", true)
    val G_NOTIF = BoolSetting("g_notif", "Notifications (palm swipe down)", true)
    val G_RECENTS = BoolSetting("g_recents", "Recent apps (palm swipe up)", true)
    val G_FIST = BoolSetting("g_fist", "Pause/Resume (fist hold 1.5s)", true)

    val GESTURES = listOf(G_TAP, G_LONG, G_DRAG, G_SCROLL, G_BACK, G_HOME, G_NOTIF, G_RECENTS, G_FIST)

    val REVERSE_SCROLL = BoolSetting("reverse_scroll", "Reverse scroll direction", false)
    val AUTO_PAUSE = BoolSetting(
        "auto_pause", "Auto-pause when screen is off", true,
        "Camera stops when the screen turns off and starts again after unlock.",
    )
    val SHOW_FPS = BoolSetting("show_fps", "Debug overlay (FPS, latency)", false)
    val HAPTICS = BoolSetting("haptics", "Vibrate on click / gesture", true)
    val CLICK_ONLY = BoolSetting(
        "click_only", "Simple mode: cursor + click only", true,
        "Only move and pinch-to-click. Scroll, palm and fist gestures are off, so nothing stops the cursor.",
    )
    val MOUSE_MODE = BoolSetting(
        "mouse_mode", "Mouse mode (recommended)", true,
        "Cursor moves like a PC mouse and feels the same near or far from the camera. " +
            "Off = the yellow box in the camera is mapped to the screen.",
    )
    val STABLE_30 = BoolSetting(
        "stable30", "Stable 30 fps camera", true,
        "Stops the camera dropping to 15 fps indoors. Turn off if the preview shows stripes.",
    )
    val HIGH_FPS_CAMERA = BoolSetting(
        "fps60_experimental", "60 fps camera (experimental)", false,
        "Only some phones support it. If the preview shows stripes or colours, turn it off.",
    )

    val OPTIONS = listOf(REVERSE_SCROLL, HAPTICS, AUTO_PAUSE, SHOW_FPS)
    val PERFORMANCE_OPTIONS = listOf(STABLE_30, HIGH_FPS_CAMERA)
}

class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("aircursor", Context.MODE_PRIVATE)

    fun get(s: FloatSetting): Float = sp.getFloat(s.key, s.default).coerceIn(s.min, s.max)
    fun get(s: BoolSetting): Boolean = sp.getBoolean(s.key, s.default)
    fun set(s: FloatSetting, v: Float) = sp.edit().putFloat(s.key, v).apply()
    fun set(s: BoolSetting, v: Boolean) = sp.edit().putBoolean(s.key, v).apply()

    fun resetAll() = sp.edit().clear().apply()

    fun toGestureConfig(screenWidth: Int, screenHeight: Int) = GestureConfig(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        pointerMode = if (get(AppSettings.MOUSE_MODE)) PointerMode.RELATIVE else PointerMode.ABSOLUTE,
        clickOnly = get(AppSettings.CLICK_ONLY),
        margin = get(AppSettings.MARGIN),
        sensitivity = get(AppSettings.SENSITIVITY),
        minCutoff = get(AppSettings.MIN_CUTOFF).toDouble(),
        beta = get(AppSettings.BETA).toDouble(),
        deadZonePx = get(AppSettings.DEAD_ZONE),
        pinchEnter = get(AppSettings.PINCH),
        scrollSpeed = get(AppSettings.SCROLL_SPEED),
        reverseScroll = get(AppSettings.REVERSE_SCROLL),
        enableTap = get(AppSettings.G_TAP),
        enableLongPress = get(AppSettings.G_LONG),
        enableDrag = get(AppSettings.G_DRAG),
        enableScroll = get(AppSettings.G_SCROLL),
        enableBack = get(AppSettings.G_BACK),
        enableHome = get(AppSettings.G_HOME),
        enableNotifications = get(AppSettings.G_NOTIF),
        enableRecents = get(AppSettings.G_RECENTS),
        enableFistPause = get(AppSettings.G_FIST),
    )
}
