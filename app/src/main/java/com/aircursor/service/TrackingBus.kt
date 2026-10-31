package com.aircursor.service

import android.graphics.Bitmap
import com.aircursor.core.HandPose
import com.aircursor.core.Landmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TrackingStatus(
    val running: Boolean = false,
    val paused: Boolean = false,
    val screenOff: Boolean = false,
    val handPresent: Boolean = false,
    val fps: Float = 0f,
    val accessibilityConnected: Boolean = false,
    val error: String? = null,
)

/** One frame for the in-app test preview. Bitmap is the upright, un-mirrored camera frame. */
data class PreviewFrame(
    val bitmap: Bitmap,
    val landmarks: List<Landmark>?,
    val pose: HandPose?,
    val pinchRatio: Float,
    val fps: Float,
    val margin: Float,
    val offsetX: Float,
    val offsetY: Float,
    val mirrorX: Boolean,
)

/** In-process state shared between the services and the UI. */
object TrackingBus {
    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val _preview = MutableStateFlow<PreviewFrame?>(null)
    val preview: StateFlow<PreviewFrame?> = _preview.asStateFlow()

    /** Set by the preview screen; the tracking service only builds preview frames while true. */
    @Volatile
    var previewRequested = false

    fun update(block: (TrackingStatus) -> TrackingStatus) = _status.update(block)

    fun postPreview(frame: PreviewFrame?) {
        _preview.value = frame
    }
}

/** Performance numbers for the debug overlay. */
data class TrackingStats(
    val trackingFps: Float = 0f,
    val cameraFps: Float = 0f,
    val inferenceMs: Float = 0f,
    val resolution: String = "-",
    val delegate: String = "-",
)

/** Live owner-hand check result, for the security screen. */
data class OwnerCheck(val enabled: Boolean = false, val state: String = "-", val score: Float = 0f)

/** Hand registration progress: 0..1, [done] when the profile was saved. */
data class EnrollState(val progress: Float = 0f, val hint: String = "", val done: Boolean = false)

object OwnerBus {
    private val _check = kotlinx.coroutines.flow.MutableStateFlow(OwnerCheck())
    val check: kotlinx.coroutines.flow.StateFlow<OwnerCheck> = _check

    private val _enroll = kotlinx.coroutines.flow.MutableStateFlow<EnrollState?>(null)
    val enroll: kotlinx.coroutines.flow.StateFlow<EnrollState?> = _enroll

    /** Set by the UI to start registering the hand; cleared by the service when finished. */
    @Volatile
    var enrollRequested = false

    fun postCheck(c: OwnerCheck) {
        if (_check.value != c) _check.value = c
    }

    fun postEnroll(e: EnrollState?) {
        _enroll.value = e
    }
}
