package com.aircursor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.aircursor.MainActivity
import com.aircursor.R
import com.aircursor.core.EngineOutput
import com.aircursor.core.GestureEngine
import com.aircursor.core.GestureEvent
import com.aircursor.core.HandFrame
import com.aircursor.core.Landmark
import com.aircursor.data.AppSettings
import com.aircursor.data.Prefs
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service (type camera) that runs CameraX + MediaPipe HandLandmarker on-device
 * and feeds the gesture engine. Results go to [CursorAccessibilityService].
 */
class HandTrackingService : LifecycleService() {

    companion object {
        private const val TAG = "AirCursorTracking"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.aircursor.action.START"
        const val ACTION_STOP = "com.aircursor.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.aircursor.action.TOGGLE_PAUSE"
        const val ACTION_RECALIBRATE = "com.aircursor.action.RECALIBRATE"

        @Volatile
        var isRunning = false
            private set

        /** Must be called while our app is visible (Android 14 rule for camera services). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HandTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun send(context: Context, action: String) {
            if (!isRunning) return
            try {
                context.startService(Intent(context, HandTrackingService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "send $action failed", e)
                if (action == ACTION_STOP) context.stopService(Intent(context, HandTrackingService::class.java))
            }
        }
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val engine = GestureEngine()
    private lateinit var analysisExecutor: ExecutorService

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var screenOff = false
    @Volatile private var targetFps = 30
    @Volatile private var lastSubmitMs = 0L
    @Volatile private var lastTimestamp = 0L
    @Volatile private var fps = 0f
    private var lastResultMs = 0L
    private var receiverRegistered = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.USE_GPU.key && isRunning) {
            analysisExecutor.execute { setupLandmarker() }
        }
        applyConfig()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            imageAnalysis?.targetRotation = ScreenUtils.rotation(this@HandTrackingService)
            applyConfig()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (prefs.get(AppSettings.AUTO_PAUSE)) {
                    screenOff = true
                    cameraProvider?.unbindAll()
                    TrackingBus.update { it.copy(screenOff = true, handPresent = false) }
                }
                Intent.ACTION_USER_PRESENT -> if (screenOff) {
                    screenOff = false
                    bindCamera()
                    TrackingBus.update { it.copy(screenOff = false) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE, ACTION_RECALIBRATE -> {
                if (!isRunning) {
                    // Stale notification button: nothing to control.
                    stopSelf()
                } else if (intent?.action == ACTION_TOGGLE_PAUSE) {
                    setPaused(!engine.paused)
                } else {
                    synchronized(engine) { engine.requestRecalibrate() }
                    CursorAccessibilityService.instance?.showLabel("Show your finger…")
                }
            }
            else -> startTracking()
        }
        // Do not let the system restart us in the background: a camera service may only start
        // while the app is visible on Android 14.
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (isRunning) return
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            TrackingBus.update { it.copy(running = false, error = "Could not start: open AirCursor and turn it on from the app.") }
            stopSelf()
            return
        }
        if (!Permissions.camera(this)) {
            TrackingBus.update { it.copy(error = "Camera permission missing") }
            stopTracking()
            return
        }
        isRunning = true
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        applyConfig()
        synchronized(engine) { engine.setPaused(false) }
        analysisExecutor.execute { setupLandmarker() }
        bindCamera()

        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        CursorAccessibilityService.instance?.onTrackingStarted()
        TrackingBus.update { it.copy(running = true, paused = false, screenOff = false, error = null) }
        refreshTile()
    }

    private fun stopTracking() {
        if (isRunning) {
            isRunning = false
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
            if (receiverRegistered) {
                unregisterReceiver(screenReceiver)
                receiverRegistered = false
            }
            cameraProvider?.unbindAll()
            imageAnalysis = null
            analysisExecutor.execute {
                landmarker?.close()
                landmarker = null
            }
            CursorAccessibilityService.instance?.onTrackingStopped()
        }
        TrackingBus.update { it.copy(running = false, paused = false, handPresent = false, fps = 0f) }
        TrackingBus.postPreview(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        refreshTile()
    }

    override fun onDestroy() {
        if (isRunning) stopTracking()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun applyConfig() {
        val size = ScreenUtils.realSize(this)
        val config = prefs.toGestureConfig(size.x, size.y)
        synchronized(engine) { engine.config = config }
        targetFps = prefs.get(AppSettings.TARGET_FPS).roundToInt().coerceIn(10, 30)
    }

    private fun setPaused(paused: Boolean) {
        synchronized(engine) { engine.setPaused(paused) }
        onPauseChanged(paused)
        CursorAccessibilityService.instance?.showLabel(if (paused) "Paused" else "Resumed")
    }

    private fun onPauseChanged(paused: Boolean) {
        TrackingBus.update { it.copy(paused = paused) }
        if (isRunning) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    // ---- MediaPipe ----

    /** Runs on [analysisExecutor]. */
    private fun setupLandmarker() {
        landmarker?.close()
        landmarker = null
        val useGpu = prefs.get(AppSettings.USE_GPU)
        landmarker = createLandmarker(if (useGpu) Delegate.GPU else Delegate.CPU)
            ?: if (useGpu) createLandmarker(Delegate.CPU) else null
        if (landmarker == null) {
            TrackingBus.update { it.copy(error = "Hand tracking model failed to load") }
        }
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, input: MPImage -> onResult(result, input) }
            .setErrorListener { e: RuntimeException -> Log.e(TAG, "MediaPipe error", e) }
            .build()
        HandLandmarker.createFromOptions(this, options)
    } catch (e: Exception) {
        Log.e(TAG, "HandLandmarker init failed ($delegate)", e)
        null
    }

    // ---- CameraX ----

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isRunning || screenOff) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(ScreenUtils.rotation(this))
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                imageAnalysis = analysis
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                TrackingBus.update { it.copy(error = "Camera error: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on [analysisExecutor]. */
    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            val lm = landmarker
            // Throttle: full rate when active, ~5 fps while paused (only the fist matters then).
            val interval = if (engine.paused) 200L else 1000L / targetFps
            if (lm == null || screenOff || now - lastSubmitMs < interval - 4) return
            lastSubmitMs = now

            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
            } else {
                raw
            }
            val ts = max(now, lastTimestamp + 1)
            lastTimestamp = ts
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
        } finally {
            image.close()
        }
    }

    /** MediaPipe result callback (background thread). */
    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        if (!isRunning) return
        val t = result.timestampMs()
        val hand = result.landmarks().firstOrNull()?.map { Landmark(it.x(), it.y(), it.z()) }
        val aspect = input.width.toFloat() / input.height.coerceAtLeast(1)

        val out: EngineOutput
        val margin: Float
        val mirror: Boolean
        synchronized(engine) {
            out = engine.process(HandFrame(t, hand, aspect))
            margin = engine.config.margin
            mirror = engine.config.mirrorX
        }

        if (lastResultMs > 0) {
            val dt = (t - lastResultMs).coerceAtLeast(1)
            val inst = 1000f / dt
            fps = if (fps == 0f) inst else fps * 0.9f + inst * 0.1f
        }
        lastResultMs = t

        if (TrackingBus.previewRequested) {
            try {
                val bmp = BitmapExtractor.extract(input)
                val scale = 360f / max(bmp.width, bmp.height)
                val small = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true,
                )
                TrackingBus.postPreview(
                    PreviewFrame(small, hand, out.pose, out.pinchRatio, fps, margin, out.offsetX, out.offsetY, mirror),
                )
            } catch (e: Exception) {
                Log.w(TAG, "preview failed", e)
            }
        }

        val currentFps = fps
        main.post {
            if (!isRunning) return@post
            CursorAccessibilityService.instance?.onEngineOutput(out, currentFps)
            out.events.filterIsInstance<GestureEvent.PauseChanged>().lastOrNull()?.let { onPauseChanged(it.paused) }
            val s = TrackingBus.status.value
            if (s.handPresent != out.handPresent || kotlin.math.abs(s.fps - currentFps) > 1f) {
                TrackingBus.update { it.copy(handPresent = out.handPresent, fps = currentFps) }
            }
        }
    }

    // ---- Notification + tile ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "AirCursor tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while AirCursor uses the camera"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun servicePending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, HandTrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val paused = engine.paused
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val a11yOk = CursorAccessibilityService.instance != null
        val text = when {
            !a11yOk -> "Accessibility service is off – tap to fix"
            paused -> "Paused · make a fist for 1.5s to resume"
            else -> "Point with your index finger · pinch to tap"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cursor)
            .setContentTitle(if (paused) "AirCursor paused" else "AirCursor is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", servicePending(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, "Recalibrate", servicePending(ACTION_RECALIBRATE, 2))
            .addAction(0, "Turn off", servicePending(ACTION_STOP, 3))
            .build()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, AirCursorTileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "tile refresh failed", e)
        }
    }
}
