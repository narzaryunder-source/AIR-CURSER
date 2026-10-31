package com.aircursor.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Apps that come with PC mode and open as real windows on every phone. */
enum class BuiltIn(val title: String, val emoji: String) {
    FILES("Files", "📁"),
    NOTEPAD("Notepad", "📝"),
    CALCULATOR("Calculator", "🧮"),
    PAINT("Paint", "🎨"),
}

/** One window on the desktop. Position and size are in dp. */
class WinState(val id: Int, val app: BuiltIn, x: Float, y: Float, w: Float, h: Float) {
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
    var w by mutableFloatStateOf(w)
    var h by mutableFloatStateOf(h)
    var z by mutableIntStateOf(0)
    var minimized by mutableStateOf(false)
    var maximized by mutableStateOf(false)
}

/** A right-click / long-press menu at a position on the desktop (px). */
class MenuState(val x: Float, val y: Float, val items: List<Pair<String, () -> Unit>>)

class DesktopState {
    val windows = mutableStateListOf<WinState>()
    var startOpen by mutableStateOf(false)
    var menu by mutableStateOf<MenuState?>(null)
    var focusedId by mutableIntStateOf(-1)
    private var nextId = 1
    private var topZ = 0

    fun open(app: BuiltIn, areaW: Float, areaH: Float) {
        startOpen = false
        menu = null
        windows.firstOrNull { it.app == app }?.let {
            focus(it)
            return
        }
        val w = (areaW * 0.62f).coerceIn(280f, 720f)
        val h = (areaH * 0.78f).coerceIn(220f, 520f)
        val cascade = (windows.size % 5) * 28f
        val x = ((areaW - w) / 2f - 60f + cascade).coerceAtLeast(0f)
        val y = (16f + cascade).coerceAtMost((areaH - h).coerceAtLeast(0f))
        val win = WinState(nextId++, app, x, y, w, h)
        windows += win
        focus(win)
    }

    fun focus(w: WinState) {
        w.minimized = false
        w.z = ++topZ
        focusedId = w.id
    }

    fun minimize(w: WinState) {
        w.minimized = true
        if (focusedId == w.id) focusedId = windows.filter { !it.minimized }.maxByOrNull { it.z }?.id ?: -1
    }

    fun close(w: WinState) {
        windows.remove(w)
        if (focusedId == w.id) focusedId = windows.filter { !it.minimized }.maxByOrNull { it.z }?.id ?: -1
    }

    fun focused(): WinState? = windows.firstOrNull { it.id == focusedId && !it.minimized }

    /** Alt+Tab: bring the window that was used longest ago to the front. */
    fun cycle() {
        val target = windows.minByOrNull { it.z } ?: return
        focus(target)
    }

    /** Win+D: show the desktop. */
    fun minimizeAll() {
        windows.forEach { it.minimized = true }
        focusedId = -1
    }
}
