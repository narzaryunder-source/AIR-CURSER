package com.aircursor.pc

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aircursor.ui.AirCursorTheme

/**
 * The PC desktop: wallpaper, icons, windows, start menu and taskbar, in landscape.
 * Keyboard: Win = start menu, Alt+Tab = next window, Alt+F4 = close, Win+D = show desktop.
 */
class PcActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_MENU = "start_menu"
    }

    private val state = DesktopState()
    private var metaUsedInCombo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PcMode.setEnabled(this, true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        state.startOpen = intent?.getBooleanExtra(EXTRA_START_MENU, false) == true
        setContent {
            AirCursorTheme {
                BackHandler(enabled = state.startOpen || state.menu != null || state.focused() != null) {
                    when {
                        state.menu != null -> state.menu = null
                        state.startOpen -> state.startOpen = false
                        else -> state.focused()?.let { state.minimize(it) }
                    }
                }
                PcDesktop(state, onExit = ::exitPcMode)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.startOpen = intent.getBooleanExtra(EXTRA_START_MENU, false)
    }

    override fun onResume() {
        super.onResume()
        PcMode.desktopVisible = true
    }

    override fun onPause() {
        PcMode.desktopVisible = false
        super.onPause()
    }

    private fun exitPcMode() {
        PcMode.setEnabled(this, false)
        PcMode.desktopVisible = false
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isMeta = code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isMeta) {
                metaUsedInCombo = false
                return true
            }
            if (event.isMetaPressed) metaUsedInCombo = true
            when {
                event.isAltPressed && code == KeyEvent.KEYCODE_TAB -> {
                    state.cycle()
                    return true
                }
                event.isAltPressed && code == KeyEvent.KEYCODE_F4 -> {
                    state.focused()?.let { state.close(it) }
                    return true
                }
                event.isMetaPressed && code == KeyEvent.KEYCODE_D -> {
                    state.minimizeAll()
                    return true
                }
                event.isMetaPressed && code == KeyEvent.KEYCODE_UP -> {
                    state.focused()?.maximized = true
                    return true
                }
                event.isMetaPressed && code == KeyEvent.KEYCODE_DOWN -> {
                    state.focused()?.let { if (it.maximized) it.maximized = false else state.minimize(it) }
                    return true
                }
                code == KeyEvent.KEYCODE_ESCAPE && (state.startOpen || state.menu != null) -> {
                    state.startOpen = false
                    state.menu = null
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && isMeta) {
            if (!metaUsedInCombo) state.startOpen = !state.startOpen
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
