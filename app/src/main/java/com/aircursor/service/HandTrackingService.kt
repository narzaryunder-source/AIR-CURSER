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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import java.util.concurrent.atomic.AtomicInteger
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
        private const val KEY_AUTO_DELEGATE = "auto_delegate"
        private const val BENCH_WARMUP = 10
        private const val BENCH_SAMPLES = 30

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

    // Performance
    private val inFlight = AtomicInteger(0)
    @Volatile private var lastCameraFrameMs = 0L
    @Volatile private var cameraFps = 0f
    @Volatile private var inferenceMs = 0f
    @Volatile private var resolutionLabel = "-"
    @Volatile private var delegateLabel = "-"
    private val converter = YuvConverter()
    private var previewCounter = 0

    // Auto processor choice: measure GPU, then CPU, keep the faster one (done once per install).
    @Volatile private var benchDelegate: Delegate? = null
    private val benchSamples = ArrayList<Float>()
    private var benchGpuMs = Float.MAX_VALUE
    private var receiverRegistered = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.PROCESSOR.key && isRunning) {
            // Picking "Auto" again re-measures which processor is faster.
            if (prefs.get(AppSettings.PROCESSOR).roundToInt() == 0) prefs.sp.edit().remove(KEY_AUTO_DELEGATE).apply()
            analysisExecutor.execute { setupLandmarker() }
        }
        val cameraKeys = setOf(AppSettings.RESOLUTION.key, AppSettings.HIGH_FPS_CAMERA.key, AppSettings.STABLE_30.key)
        if (key in cameraKeys && isRunning && !screenOff) {
            bindCamera()
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
        val colourMode = when (prefs.get(AppSettings.CAMERA_COLOURS).roundToInt()) {
            1 -> YuvConverter.Mode.COLOUR
            2 -> YuvConverter.Mode.GRAY
            else -> YuvConverter.Mode.AUTO
        }
        if (converter.mode != colourMode) converter.mode = colourMode
        val maxFps = prefs.get(AppSettings.TARGET_FPS)
        targetFps = if (maxFps >= 59.5f) 0 else maxFps.roundToInt().coerceIn(10, 60)
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
        val choice = prefs.get(AppSettings.PROCESSOR).roundToInt()
        benchDelegate = null
        val delegate = when (choice) {
            1 -> Delegate.GPU
            2 -> Delegate.CPU
            else -> when (prefs.sp.getString(KEY_AUTO_DELEGATE, null)) {
                "GPU" -> Delegate.GPU
                "CPU" -> Delegate.CPU
                else -> {
                    benchGpuMs = Float.MAX_VALUE
                    benchDelegate = Delegate.GPU
                    Delegate.GPU
                }
            }
        }
        loadLandmarker(delegate, auto = choice == 0)
    }

    /** Runs on [analysisExecutor]. */
    private fun loadLandmarker(wanted: Delegate, auto: Boolean) {
        landmarker?.close()
        landmarker = null
        var delegate = wanted
        landmarker = createLandmarker(delegate)
        if (landmarker == null && delegate == Delegate.GPU) {
            delegate = Delegate.CPU
            landmarker = createLandmarker(delegate)
            if (benchDelegate != null) {
                // No usable GPU: nothing to compare.
                benchDelegate = null
                prefs.sp.edit().putString(KEY_AUTO_DELEGATE, "CPU").apply()
            }
        }
        synchronized(benchSamples) { benchSamples.clear() }
        delegateLabel = when {
            benchDelegate != null -> "${delegate.name}, testing speed…"
            auto -> "${delegate.name}, auto"
            else -> delegate.name
        }
        inFlight.set(0)
        if (landmarker == null) {
            TrackingBus.update { it.copy(error = "Hand tracking model failed to load") }
        }
    }

    /** Called from the result thread with one latency sample while benchmarking. */
    private fun recordBenchmark(latencyMs: Float) {
        val current = benchDelegate ?: return
        val median = synchronized(benchSamples) {
            benchSamples += latencyMs
            if (benchSamples.size < BENCH_WARMUP + BENCH_SAMPLES) return
            val m = benchSamples.drop(BENCH_WARMUP).sorted()[BENCH_SAMPLES / 2]
            benchSamples.clear()
            m
        }
        if (current == Delegate.GPU) {
            benchGpuMs = median
            benchDelegate = Delegate.CPU
            analysisExecutor.execute { loadLandmarker(Delegate.CPU, auto = true) }
        } else {
            val best = if (benchGpuMs <= median) Delegate.GPU else Delegate.CPU
            Log.i(TAG, "Benchmark: GPU ${benchGpuMs}ms, CPU ${median}ms -> $best")
            prefs.sp.edit().putString(KEY_AUTO_DELEGATE, best.name).apply()
            benchDelegate = null
            if (best == Delegate.GPU) {
                analysisExecutor.execute { loadLandmarker(Delegate.GPU, auto = true) }
            } else {
                delegateLabel = "CPU, auto"
            }
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
            // Lower thresholds keep the hand tracked during fast moves and from further away.
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.3f)
            .setResultListener { result: HandLandmarkerResult, input: MPImage -> onResult(result, input) }
            .setErrorListener { e: RuntimeException ->
                inFlight.decrementAndGet().let { if (it < 0) inFlight.set(0) }
                Log.e(TAG, "MediaPipe error", e)
            }
            .build()
        HandLandmarker.createFromOptions(this, options)
    } catch (e: Exception) {
        Log.e(TAG, "HandLandmarker init failed ($delegate)", e)
        null
    }

    // ---- CameraX ----

    private fun analysisSize(): Size = when (prefs.get(AppSettings.RESOLUTION).roundToInt()) {
        0 -> Size(320, 240)
        1 -> Size(480, 360)
        else -> Size(640, 480)
    }

    /**
     * Frame rate range to request. Default: a fixed 30 fps so the camera does not drop to 15 fps
     * indoors. 60 fps only when the user enables it (some phones give broken frames at 60).
     */
    private fun chooseFpsRange(): Range<Int>? = try {
        val cm = getSystemService(CameraManager::class.java)
        val front = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }
        val ranges = front?.let {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }?.toList().orEmpty()
        val high = if (prefs.get(AppSettings.HIGH_FPS_CAMERA)) {
            ranges.filter { it.upper >= 60 }
                .sortedWith(compareByDescending<Range<Int>> { it.lower == it.upper }.thenByDescending { it.lower })
                .firstOrNull()
        } else {
            null
        }
        high ?: if (prefs.get(AppSettings.STABLE_30)) ranges.firstOrNull { it.lower == 30 && it.upper == 30 } else null
    } catch (e: Exception) {
        Log.w(TAG, "fps ranges unavailable", e)
        null
    }

    @OptIn(ExperimentalCamera2Interop::class)
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
                        ResolutionStrategy(analysisSize(), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(ScreenUtils.rotation(this))
                val range = chooseFpsRange()
                if (range != null) {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                }
                val analysis = builder.build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                } catch (e: Exception) {
                    if (range == null) throw e
                    // Some phones list 60 fps but refuse it: retry at the default frame rate.
                    Log.w(TAG, "High fps refused, retrying at default", e)
                    val fallback = ImageAnalysis.Builder()
                        .setResolutionSelector(selector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetRotation(ScreenUtils.rotation(this))
                        .build()
                    fallback.setAnalyzer(analysisExecutor, ::analyze)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, fallback)
                    imageAnalysis = fallback
                    return@addListener
                }
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
            if (lastCameraFrameMs > 0) {
                val inst = 1000f / (now - lastCameraFrameMs).coerceAtLeast(1)
                cameraFps = if (cameraFps == 0f) inst else cameraFps * 0.9f + inst * 0.1f
            }
            lastCameraFrameMs = now
            resolutionLabel = "${image.width}x${image.height}"

            val lm = landmarker
            if (lm == null || screenOff) return
            // Throttle only when paused (fist detection needs ~5 fps) or a limit is set.
            val interval = when {
                engine.paused -> 200L
                targetFps > 0 -> 1000L / targetFps - 4
                else -> 0L
            }
            if (now - lastSubmitMs < interval) return
            // One frame at a time: a second queued frame would only wait and double the delay.
            if (inFlight.get() >= 1) {
                if (now - lastSubmitMs < 500) return
                inFlight.set(0) // a result got lost; recover
            }
            lastSubmitMs = now

            val upright = converter.convert(image)
            val ts = max(now, lastTimestamp + 1)
            lastTimestamp = ts
            inFlight.incrementAndGet()
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            inFlight.set(0)
            Log.w(TAG, "analyze failed", e)
        } finally {
            image.close()
        }
    }

    /** MediaPipe result callback (background thread). */
    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        if (inFlight.decrementAndGet() < 0) inFlight.set(0)
        if (!isRunning) return
        val t = result.timestampMs()
        val latency = (SystemClock.uptimeMillis() - t).toFloat()
        inferenceMs = if (inferenceMs == 0f) latency else inferenceMs * 0.9f + latency * 0.1f
        if (benchDelegate != null && result.landmarks().isNotEmpty()) recordBenchmark(latency)
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

        // The preview is only for testing: every 3rd frame is enough and costs less.
        if (TrackingBus.previewRequested && previewCounter++ % 3 == 0) {
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
        val stats = TrackingStats(currentFps, cameraFps, inferenceMs, "$resolutionLabel ${converter.label}", delegateLabel)
        main.post {
            if (!isRunning) return@post
            CursorAccessibilityService.instance?.onEngineOutput(out, stats)
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
