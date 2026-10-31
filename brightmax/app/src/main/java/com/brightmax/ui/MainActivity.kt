package com.brightmax.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.brightmax.hdr.HdrController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var originalWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        originalWindowBrightness = window.attributes.screenBrightness

        // Window-level brightness + HDR mode follow the boost while this screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui
                    .map { Triple(it.boost.active, it.boost.effectiveLevel, it.boost.hdrActive) }
                    .distinctUntilChanged()
                    .collect { (active, level, hdr) -> applyWindow(active, level, hdr) }
            }
        }

        setContent {
            BrightMaxTheme {
                BrightMaxScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    private fun applyWindow(active: Boolean, level: Float, hdr: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (active) level else originalWindowBrightness
        }
        HdrController.applyToWindow(window, hdr)
    }

    override fun onDestroy() {
        // Leave the window exactly as it was.
        applyWindow(active = false, level = 0f, hdr = false)
        super.onDestroy()
    }
}
