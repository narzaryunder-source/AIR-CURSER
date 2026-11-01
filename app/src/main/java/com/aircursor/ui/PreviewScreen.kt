package com.aircursor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.core.HandIdx
import com.aircursor.service.HandTrackingService
import com.aircursor.service.Permissions
import com.aircursor.service.TrackingBus

/** Live camera preview with the detected hand skeleton and the active box, for testing. */
@Composable
fun PreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by TrackingBus.status.collectAsStateWithLifecycle()
    val frame by TrackingBus.preview.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        TrackingBus.previewRequested = true
        onDispose {
            TrackingBus.previewRequested = false
            TrackingBus.postPreview(null)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Live preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))

            val f = frame
            if (!status.running) {
                Text("AirCursor is off. Turn it on to see the camera and hand tracking here.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    if (Permissions.camera(context)) HandTrackingService.start(context)
                }) { Text("Turn on AirCursor") }
            } else if (f == null) {
                Text(if (status.screenOff) "Camera paused (screen off)" else "Waiting for camera…")
            } else {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(f.bitmap.width.toFloat() / f.bitmap.height),
            ) {
                val w = size.width
                val h = size.height
                val image = f.bitmap.asImageBitmap()
                withTransform({
                    // Show the front camera like a mirror (same as cursor movement).
                    if (f.mirrorX) scale(-1f, 1f, pivot = Offset(w / 2f, h / 2f))
                }) {
                    drawImage(image, dstSize = IntSize(w.toInt(), h.toInt()))
                    f.landmarks?.let { lms ->
                        for ((a, b) in HandIdx.CONNECTIONS) {
                            drawLine(
                                Color(0xFF00E676),
                                Offset(lms[a].x * w, lms[a].y * h),
                                Offset(lms[b].x * w, lms[b].y * h),
                                strokeWidth = 4f,
                            )
                        }
                        lms.forEachIndexed { i, p ->
                            val c = when (i) {
                                HandIdx.INDEX_TIP -> Color.Red
                                HandIdx.THUMB_TIP -> Color.Yellow
                                else -> Color.White
                            }
                            drawCircle(c, radius = if (i == HandIdx.INDEX_TIP) 10f else 6f, center = Offset(p.x * w, p.y * h))
                        }
                    }
                }
                // Active box (mapped to the full screen), in mirrored/user space.
                val m = f.margin
                val left = (m + f.offsetX) * w
                val top = (m + f.offsetY) * h
                drawRect(
                    Color(0xFFFFD54F),
                    topLeft = Offset(left, top),
                    size = Size((1f - 2f * m) * w, (1f - 2f * m) * h),
                    style = Stroke(width = 4f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("FPS: %.1f".format(f.fps))
            Text("Hand: ${if (f.landmarks != null) "detected" else "not found"}")
            Text("Pose: ${f.pose?.name ?: "-"}")
            Text(
                "Pinch distance: " + if (f.pinchRatio.isNaN()) "-" else "%.2f".format(f.pinchRatio),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Yellow box = active area. Your fingertip (red dot) inside this box covers the whole screen. " +
                    "Use Recalibrate to centre the box on your finger.",
                style = MaterialTheme.typography.bodySmall,
            )
            }
        }
    }
}
