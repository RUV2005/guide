package com.danmo.guide.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.lifecycleScope
import com.danmo.guide.R
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.camera.CameraManager
import com.danmo.guide.feature.camera.ImageProxyUtils
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.fall.FallDetector
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
import com.danmo.guide.feature.performancemonitor.PowerGovernor
import com.danmo.guide.feature.powermode.PowerMode
import com.danmo.guide.feature.vosk.VoskRecognizerManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import com.danmo.guide.ui.voicecall.VoiceCallActivity
import com.danmo.guide.core.manager.CameraModeManager
import com.danmo.guide.core.manager.InitializationManager
import com.danmo.guide.core.manager.LocationWeatherManager
import com.danmo.guide.core.manager.PermissionManager
import com.danmo.guide.core.manager.SensorHandler
import com.danmo.guide.core.manager.StreamManager
import com.danmo.guide.core.manager.TtsServiceManager
import com.danmo.guide.core.manager.UIManager
import com.danmo.guide.core.manager.VoiceCommandHandler
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import org.vosk.android.RecognitionListener

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    RecognitionListener {

    private val perfMonitor by lazy { PerformanceMonitor(this) }
    private var lastFrameNanos = 0L
    private var reusableBitmap: Bitmap? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos != 0L) {
                val costMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                perfMonitor.recordGpuFrame(costMs)
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // 核心组件
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var falseAlarmCancelCount = 0
    private var latestMetrics: PerformanceMonitor.Metrics? = null

    // 管理模块
    private lateinit var initializationManager: InitializationManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var uiManager: UIManager
    private lateinit var ttsServiceManager: TtsServiceManager
    private lateinit var locationWeatherManager: LocationWeatherManager
    private lateinit var sensorHandler: SensorHandler
    private lateinit var voiceCommandHandler: VoiceCommandHandler
    private lateinit var streamManager: StreamManager
    private lateinit var cameraModeManager: CameraModeManager

    override fun onError(exception: Exception?) {
        Log.e("VOSK_ERROR", "语音识别出错: ${exception?.message}", exception)
        runOnUiThread {
            ttsServiceManager.ttsService?.speak("语音识别出错，请重试")
        }
    }



    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 插桩：监控应用启动时间
        val appStartTrace = Firebase.performance.newTrace("app_start")
        appStartTrace.start()

        // 插桩：监控 UI 渲染时间
        val uiRenderTrace = Firebase.performance.newTrace("ui_rendering")
        uiRenderTrace.start()

        // 插桩：监控功耗
        val batteryTrace = Firebase.performance.newTrace("battery_usage")
        batteryTrace.start()

        // 1. 布局绑定
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. 初始化管理模块
        permissionManager = PermissionManager(this)
        
        // 先创建 InitializationManager（不需要 uiManager）
        initializationManager = InitializationManager(this, binding, lifecycleScope, null)
        initializationManager.initializeBasicComponents()
        initializationManager.initializeFallDetection("123456789000000")
        
        // 然后创建 UIManager
        uiManager = UIManager(
            this,
            binding,
            initializationManager.overlayView,
            lifecycleScope
        )
        
        // 更新 InitializationManager 的 uiManager 引用（如果需要）
        // 注意：InitializationManager 目前不直接使用 uiManager，所以这里不需要更新

        ttsServiceManager = TtsServiceManager(
            this,
            initializationManager.fallDetector,
            { ttsService ->
                // TTS 服务连接后的回调
                // 设置 FeedbackManager 使用统一的 TTS 服务
                initializationManager.feedbackManager.setTtsService(ttsService)
            }
        )
        ttsServiceManager.bindService()

        locationWeatherManager = LocationWeatherManager(
            this,
            binding,
            lifecycleScope,
            initializationManager.locationManager,
            initializationManager.weatherManager,
            ttsServiceManager,
            uiManager
        )
        initializationManager.locationManager.callback = locationWeatherManager

        voiceCommandHandler = VoiceCommandHandler(
            this,
            binding,
            lifecycleScope,
            ttsServiceManager.ttsService,
            ::triggerEmergencyCall,
            ::handleWeatherCommand,
            ::handleLocationCommand
        )

        streamManager = StreamManager(
            this,
            binding,
            lifecycleScope,
            initializationManager.overlayView,
            initializationManager.objectDetectorHelper,
            perfMonitor,
            ::handleStreamDetectionResults
        )

        cameraModeManager = CameraModeManager(
            this,
            lifecycleScope,
            perfMonitor,
            ttsServiceManager.ttsService,
            uiManager,
            { streamManager.startStream() },
            { streamManager.stopStream() },
            { manager -> cameraManager = manager },
            { permissionManager.checkCameraPermission { startCamera() } }
        )

        // 3. 初始化摄像头
        cameraModeManager.initCameraResources({ createAnalyzer() }) { manager ->
            cameraManager = manager
            // 摄像头初始化后，初始化传感器处理器
            if (!::sensorHandler.isInitialized) {
                val sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
                sensorHandler = SensorHandler(
                    sensorManager,
                    initializationManager.fallDetector,
                    cameraManager,
                    ::triggerEmergencyCall
                )
            }
        }

        // 4. Vosk 检查
        initializationManager.checkVoskModelStatus(uiManager)

        // 5. 缓存定位
        initializationManager.handleCachedLocation { location ->
            locationWeatherManager.onLocationSuccess(location, false)
        }

        // 6. UI 事件绑定
        binding.fabSwitchCamera.setOnClickListener { cameraModeManager.switchCameraMode() }
        binding.fabVoice.setOnClickListener { handleVoiceCommand() }
        binding.fabLocation.setOnClickListener {
            permissionManager.checkLocationPermission {
                locationWeatherManager.startLocationDetection(false)
            }
        }
        binding.fabWeather.setOnClickListener {
            permissionManager.checkLocationPermission {
                uiManager.showToast("正在获取天气...")
                locationWeatherManager.startLocationDetection(true)
            }
        }
        binding.fabVoiceCall.setOnClickListener { startVoiceCallActivity() }
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 7. 摄像头权限检查
        permissionManager.checkCameraPermission { startCamera() }

        // 8. 延迟播报
        ttsServiceManager.ensureOutdoorModeAnnouncement()

        // 9. 启动性能监控
        perfMonitor.start(object : PerformanceMonitor.Callback {
            override fun onMetrics(metrics: PerformanceMonitor.Metrics) {
                latestMetrics = metrics
                uiManager.updatePerformanceMetrics(metrics)

                // 动态功耗决策
                val newMode = PowerGovernor.evaluate(metrics.powerContext)
                if (newMode != PowerGovernor.currentMode) {
                    PowerGovernor.currentMode = newMode
                    initializationManager.objectDetectorHelper.setPowerMode(newMode)

                    val fps = when (newMode) {
                        PowerMode.LOW_POWER -> 2
                        PowerMode.BALANCED -> 6
                        PowerMode.HIGH_ACCURACY -> 12
                    }
                    if (::cameraManager.isInitialized) {
                        cameraManager.setTargetFps(fps)
                    }
                }
            }
        })

        // 结束插桩
        uiRenderTrace.stop()
        appStartTrace.stop()
        batteryTrace.stop()
    }

    private fun handleStreamDetectionResults(results: List<Detection>) {
        uiManager.updateOverlayView(results, 0)
        handleDetectionResults(results)
        uiManager.updateDetectionStatus(results)
    }

    private fun handleVoiceCommand() {
        permissionManager.checkAudioPermission {
            voiceCommandHandler.startVoiceRecognition()
        }
    }





    override fun onPartialResult(hypothesis: String?) {
        // 如需实时提示，可留空或仅记录日志
        Log.d("VOSK_PARTIAL", hypothesis ?: "")
    }




    override fun onResume() {
        super.onResume()
        initializationManager.fallDetector.startListening()
        if (::sensorHandler.isInitialized) {
            sensorHandler.registerSensors()
        }

        // 确保重新初始化摄像头
        if (cameraModeManager.isBuiltInMode()) {
            Log.d("Camera", "重新初始化内置摄像头")
            cameraModeManager.initCameraResources({ createAnalyzer() }) { manager ->
                cameraManager = manager
                // 确保传感器处理器已初始化
                if (!::sensorHandler.isInitialized) {
                    val sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
                    sensorHandler = SensorHandler(
                        sensorManager,
                        initializationManager.fallDetector,
                        cameraManager,
                        ::triggerEmergencyCall
                    )
                }
                sensorHandler.registerSensors()
            }
            permissionManager.checkCameraPermission { startCamera() }
        } else {
            Log.d("Camera", "重新启动外置摄像头流")
            streamManager.startStream()
        }
        // 确保在恢复时安排播报
        ttsServiceManager.ensureOutdoorModeAnnouncement()
        // 启动 GPU 帧耗时采样
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }


    override fun onPause() {
        super.onPause()
        initializationManager.locationManager.stopLocation()
        initializationManager.fallDetector.stopListening()
        if (::sensorHandler.isInitialized) {
            sensorHandler.unregisterSensors()
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onEmergencyDetected() {
        runOnUiThread {
            uiManager.setStatusText(getString(R.string.fall_detected_warning))
            ttsServiceManager.ttsService?.speak(getString(R.string.fall_alert_voice))
            triggerEmergencyCall()
        }
    }

    private fun triggerEmergencyCall() {
        permissionManager.checkCallPermission {
            // 直接触发紧急呼叫
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:123456789000000")
            }
            try {
                startActivity(intent)
                ttsServiceManager.ttsService?.speak("正在呼叫紧急联系人，请保持冷静")
            } catch (e: Exception) {
                Log.e("MainActivity", "紧急呼叫失败", e)
                ttsServiceManager.ttsService?.speak("紧急呼叫失败，请检查电话权限")
            }
        }
    }







    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            Log.d("VOSK_FINAL", "最终结果: $it")
            runOnUiThread {
                voiceCommandHandler.processVoiceCommand(it)
            }
        }
    }

    override fun onTimeout() {
        runOnUiThread {
            Log.w("Vosk", "识别超时")
        }
    }

    private fun handleWeatherCommand() {
        ttsServiceManager.ttsService?.speak("正在获取天气信息")
        binding.fabWeather.performClick()
    }

    private fun handleLocationCommand() {
        ttsServiceManager.ttsService?.speak("正在获取位置信息")
        binding.fabLocation.performClick()
    }

    @Suppress("DEPRECATION")
    private fun startVoiceCallActivity() {
        startActivity(Intent(this, VoiceCallActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }


    override fun onDestroy() {
        reusableBitmap?.recycle()
        reusableBitmap = null
        
        // 统一释放资源
        cameraModeManager.releaseCameraResources()
        streamManager.stopStream()
        ttsServiceManager.cleanup()
        initializationManager.locationManager.destroy()
        
        if (::sensorHandler.isInitialized) {
            sensorHandler.unregisterSensors()
        }
        
        VoskRecognizerManager.stopListening()
        VoskRecognizerManager.destroy()
        perfMonitor.stop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (FallDetector.isFallDetected) {
            handleFalseAlarm()
        } else {
            super.onBackPressed()
        }
    }

    private fun handleFalseAlarm() {
        if (++falseAlarmCancelCount > 2) {
            initializationManager.fallDetector.resetFallState()
            ttsServiceManager.ttsService?.speak(getString(R.string.false_alarm_canceled))
            falseAlarmCancelCount = 0
            uiManager.setStatusText(getString(R.string.normal_status))
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis?.let {
            Log.d("VOSK_FINAL", "最终结果: $it")
            runOnUiThread {
                voiceCommandHandler.processVoiceCommand(it)
            }
        }
    }

    private fun startCamera() {
        // 确保摄像头资源已初始化（切换回内置摄像头时可能需要重新初始化）
        if (cameraModeManager.getCameraManager() == null || cameraModeManager.getCameraManager()?.isShutdown() == true) {
            Log.d("Camera", "摄像头资源未初始化，重新初始化")
            cameraModeManager.initCameraResources({ createAnalyzer() }) { manager ->
                cameraManager = manager
                // 摄像头初始化后，初始化传感器处理器
                if (!::sensorHandler.isInitialized) {
                    val sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
                    sensorHandler = SensorHandler(
                        sensorManager,
                        initializationManager.fallDetector,
                        cameraManager,
                        ::triggerEmergencyCall
                    )
                }
                // 初始化完成后启动摄像头
                cameraModeManager.startCamera(binding.previewView.surfaceProvider)
            }
        } else {
            // 摄像头资源已存在，直接启动
            cameraModeManager.startCamera(binding.previewView.surfaceProvider)
        }
    }

    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            PerformanceMonitor.FrameStats.addFrame()
            Log.d("FPS", "addFrame called in createAnalyzer")
            DetectionProcessor.getInstance(this@MainActivity)
                .updateImageDimensions(imageProxy.width, imageProxy.height)
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return@launch
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                val t0 = SystemClock.elapsedRealtime()
                val results = withContext(initializationManager.objectDetectorHelper.getGpuThread()) {
                    initializationManager.objectDetectorHelper.detect(bitmap, rotationDegrees)
                }
                val t1 = SystemClock.elapsedRealtime()
                Log.d("INFERENCE", "TFLite耗时: ${t1 - t0} ms")
                perfMonitor.recordTflite(SystemClock.elapsedRealtime() - t0)

                withContext(Dispatchers.Main) {
                    val t2 = SystemClock.elapsedRealtime()
                    initializationManager.overlayView.setModelInputSize(bitmap.width, bitmap.height)
                    uiManager.updateOverlayView(results, rotationDegrees)
                    handleDetectionResults(results)
                    uiManager.updateDetectionStatus(results)
                    Log.d("INFERENCE", "UI更新耗时: ${SystemClock.elapsedRealtime() - t2} ms")
                }
                imageProxy.close()
            }
        }
    }

    private fun handleDetectionResults(results: List<Detection>) {
        results
            .filter { it.categories.isNotEmpty() }
            .maxByOrNull {
                it.categories.maxByOrNull { c -> c.score }?.score ?: 0f
            }?.let {
                initializationManager.feedbackManager.handleDetectionResult(it)
            }
    }
    }