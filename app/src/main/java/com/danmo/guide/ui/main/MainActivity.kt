package com.danmo.guide.ui.main

    import android.Manifest
    import android.annotation.SuppressLint
    import android.app.AlertDialog
    import android.content.BroadcastReceiver
    import android.content.ComponentName
    import android.content.Context
    import android.content.Intent
    import android.content.IntentFilter
    import android.content.ServiceConnection
    import android.content.pm.PackageManager
    import android.graphics.BitmapFactory
    import android.hardware.Sensor
    import android.hardware.SensorEvent
    import android.hardware.SensorEventListener
    import android.hardware.SensorManager
    import android.net.Uri
    import android.os.BatteryManager
    import android.os.Build
    import android.os.Bundle
    import android.os.Handler
    import android.os.HandlerThread
    import android.os.IBinder
    import android.os.Looper
    import android.os.SystemClock
    import android.util.Log
    import android.view.Choreographer
    import android.view.View
    import android.widget.Toast
    import androidx.activity.ComponentActivity
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.annotation.RequiresApi
    import androidx.camera.core.ImageAnalysis
    import androidx.core.app.ActivityCompat
    import androidx.core.content.ContextCompat
    import androidx.lifecycle.lifecycleScope
    import com.amap.api.location.AMapLocation
    import com.amap.api.location.AMapLocationClient
    import com.danmo.guide.R
    import com.danmo.guide.databinding.ActivityMainBinding
    import com.danmo.guide.feature.camera.CameraManager
    import com.danmo.guide.feature.camera.ImageProxyUtils
    import com.danmo.guide.feature.detection.ObjectDetectorHelper
    import com.danmo.guide.feature.fall.FallDetector
    import com.danmo.guide.feature.fall.TtsService
    import com.danmo.guide.feature.feedback.DetectionProcessor
    import com.danmo.guide.feature.feedback.FeedbackManager
    import com.danmo.guide.feature.init.InitManager
    import com.danmo.guide.feature.location.LocationManager
    import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
    import com.danmo.guide.feature.powermode.PowerMode
    import com.danmo.guide.feature.vosk.VoskRecognizerManager
    import com.danmo.guide.feature.weather.WeatherManager
    import com.danmo.guide.ui.components.OverlayView
    import com.danmo.guide.ui.read.ReadOnlineActivity
    import com.danmo.guide.ui.room.RoomActivity
    import com.danmo.guide.ui.settings.SettingsActivity
    import com.danmo.guide.ui.voicecall.VoiceCallActivity
    import com.google.firebase.Firebase
    import com.google.firebase.perf.performance
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import org.tensorflow.lite.task.vision.detector.Detection
    import org.vosk.android.RecognitionListener
    import java.io.IOException
    import java.io.InputStream
    import java.util.Locale
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors
    import kotlin.math.abs
    import kotlin.math.pow
    import kotlin.math.sqrt

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
        SensorEventListener, LocationManager.LocationCallback, FallDetector.WeatherCallback,
        RecognitionListener {  // 使用 Vosk 的 RecognitionListener

        // 视频流相关变量
        private enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
        private var connectionState = ConnectionState.DISCONNECTED
        private var connectionStartTime = 0L
        private var isStreaming = false
        private val client = OkHttpClient()
        private lateinit var boundary: String
        private val tag = "MjpegStream"
        private var leftoverData = ByteArray(0)
        private var streamingJob: Job? = null
        private var isCameraMode = true // true 表示内置摄像头模式，false 表示外置摄像头模式
        private var timerJob: Job? = null
        private val reconnectDelay = 5000L
        private val maxRetries = 5
        private val perfMonitor by lazy { PerformanceMonitor(this) }
        private var lastFrameNanos = 0L

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



        // 添加户外模式播报状态跟踪
        private var hasOutdoorModeAnnounced = false

        private companion object {
            const val LOCATION_PERMISSION_REQUEST_CODE = 1001
            const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
            private const val FILTER_WINDOW_SIZE = 5
            private const val STREAM_URL = "http://192.168.4.1:81/stream" // 视频流地址

            // 放在类内部，和 streamingJob、isStreaming 等同级
            private val renderHandler = Handler(HandlerThread("RenderThread").apply { start() }.looper)
        }

        // 新增滤波相关变量
        private val accelerationHistory = mutableListOf<Double>()

        // 在类变量区添加
        private var lastMovementStateChange = 0L

        // 新增语音控制相关变量
        private var isDetectionActive = true // 初始状态为开启检测
        private var isListening = false
        private var lastShakeTime = 0L
        private lateinit var locationManager: LocationManager
        private lateinit var binding: ActivityMainBinding
        private lateinit var cameraExecutor: ExecutorService
        private lateinit var objectDetectorHelper: ObjectDetectorHelper
        private lateinit var feedbackManager: FeedbackManager
        private lateinit var cameraManager: CameraManager
        private lateinit var overlayView: OverlayView
        private lateinit var weatherManager: WeatherManager
        private lateinit var fallDetector: FallDetector
        private lateinit var sensorManager: SensorManager
        var ttsService: TtsService? = null
        private var isTtsBound = false
        private var lastLightValue = 0f
        private var falseAlarmCancelCount = 0
        private var isUserMoving = false
        private var lastMovementTimestamp = 0L
        private val movementThreshold = 2.5 // 移动检测阈值（m/s²）
        private val movementWindow = 5L // 移动判断时间窗口（5秒）
        private var isRecognitionStarting = false   // 防止重复点击


        // 添加延迟播报处理器
        private val announcementHandler = Handler(Looper.getMainLooper())
        private val announcementRunnable = Runnable {
            announceOutdoorMode()
        }

        private fun applyLowPassFilter(currentValue: Double): Double {
            accelerationHistory.add(currentValue)
            if (accelerationHistory.size > FILTER_WINDOW_SIZE) {
                accelerationHistory.removeAt(0)
            }
            return accelerationHistory.average()
        }


        // 创建单独的摄像头权限请求
        private val requestCameraPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    startCamera()
                } else {
                    showToast("需要摄像头权限才能使用视觉功能")
                }
            }

    override fun onError(exception: Exception?) {
        Log.e("VOSK_ERROR", "语音识别出错: ${exception?.message}", exception)
        runOnUiThread {
            ttsService?.speak("语音识别出错，请重试")
        }
    }

        private fun checkCameraPermission() {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> startCamera()

                else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        private val ttsConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as TtsService.TtsBinder
                ttsService = binder.getService()
                Log.d("MainActivity", "TTS服务已连接")
                fallDetector.ttsService = ttsService

                // 确保在服务连接后播报
                ensureOutdoorModeAnnouncement()

                // 服务连接后处理可能存在的队列
                ttsService?.processNextInQueue()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ttsService = null
                Log.d("MainActivity", "TTS服务已断开")
            }
        }


        // 确保户外模式播报
        private fun ensureOutdoorModeAnnouncement() {
            // 取消之前的延迟任务（如果存在）
            announcementHandler.removeCallbacks(announcementRunnable)

            // 设置新的延迟任务（3秒后播报）
            announcementHandler.postDelayed(announcementRunnable, 0)
        }


        // 添加户外模式播报方法
        private fun announceOutdoorMode() {
            if (!hasOutdoorModeAnnounced && isTtsBound && ttsService != null) {
                runOnUiThread {
                }
                ttsService?.speak("当前为户外模式", true)
                hasOutdoorModeAnnounced = true
                Log.d("MainActivity", "户外模式播报完成")
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

            // 2. 隐私合规（不变）
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)

            // 3. 注册广播（不变）
            registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            // 4. 直接使用 Splash 预加载好的资源
            cameraExecutor = Executors.newSingleThreadExecutor()
            overlayView = binding.overlayView

            // 4-1 TFLite 模型：复用 InitManager 里的 Interpreter
            objectDetectorHelper = ObjectDetectorHelper(this)

            feedbackManager = FeedbackManager(this)
            cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
            weatherManager = WeatherManager()
            locationManager = LocationManager.instance!!
            locationManager.initialize(this)
            locationManager.callback = this

            // 4-2 Vosk 已预加载，直接设置状态即可
            lifecycleScope.launch {
                delay(3000) // 等 3 秒再检查
                if (!InitManager.voskReady) {
                    showToast("语音模型加载中，请稍后重试语音功能")
                }
            }

            // 4-3 缓存定位：如果 Splash 已拿到就立即显示
            com.danmo.guide.feature.init.InitManager.cachedLocation?.let {
                onLocationSuccess(it, isWeatherButton = false)
            }

            // 5. 其余初始化（不变）
            initFallDetection()
            bindTtsService()

            // 6. UI 事件绑定（不变）
            binding.fabSwitchCamera.setOnClickListener { switchCameraMode() }
            binding.fabVoice.setOnClickListener { handleVoiceCommand() }
            binding.fabLocation.setOnClickListener {
                startLocationDetection(isWeatherButton = false)
            }
            binding.fabWeather.setOnClickListener {
                if (checkLocationPermission()) {
                    showToast("正在获取天气...")
                    startLocationDetection(isWeatherButton = true)
                } else {
                    requestLocationPermission()
                }
            }
            binding.fabVoiceCall.setOnClickListener { startVoiceCallActivity() }
            binding.fabSettings.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            // 7. 摄像头权限检查（不变）
            checkCameraPermission()

            // 8. 延迟播报（不变）
            ensureOutdoorModeAnnouncement()


            // 启动监控并把结果贴到底部状态栏
            perfMonitor.start(object : PerformanceMonitor.Callback {
                override fun onMetrics(m: PerformanceMonitor.Metrics) {
                    binding.tvFps.text        = "FPS:${m.fps}"
                    binding.tvCpu.text        = "CPU:${m.cpuApp}%"
                    binding.tvMem.text        = "MEM:当前:${m.memUsedMB}/极限:${m.memMaxMB}MB"
                    binding.tvTemp.text       = "BAT:${m.batteryTemp}°C"
                    binding.tvInference.text  = "AI推理延迟:${m.tfliteMs}ms"
                    binding.tvGpu.text        = "GPU渲染时长:${m.gpuFrameMs}ms"
                }
            })

            // 结束插桩
            uiRenderTrace.stop()
            appStartTrace.stop()
            batteryTrace.stop()
        }

        // 添加 handleVoiceCommand 方法
        private fun handleVoiceCommand() {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startVoiceRecognition()
                }
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) -> {
                    AlertDialog.Builder(this)
                        .setTitle("需要麦克风权限")
                        .setMessage("语音功能需要访问麦克风，请允许权限后重试")
                        .setPositiveButton("去设置") { _, _ ->
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                                startActivity(this)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                else -> {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_RECORD_AUDIO_PERMISSION
                    )
                }
            }
        }



        private fun handleMovementDetection(event: SensorEvent) {
            val currentTime = System.currentTimeMillis()
            val rawAcceleration = sqrt(
                event.values[0].toDouble().pow(2) +
                        event.values[1].toDouble().pow(2) +
                        event.values[2].toDouble().pow(2)
            )
            // 应用低通滤波
            val filteredAcceleration = applyLowPassFilter(rawAcceleration)
            // 使用滤波后的值进行判断
            val dynamicThreshold = when {
                lastLightValue < 10 -> movementThreshold * 0.9
                else -> movementThreshold
            }
            // 调整触发条件（需连续两次超过阈值）
            if (filteredAcceleration > dynamicThreshold) {
                if ((currentTime - lastMovementTimestamp) < 1000) {
                    lastMovementTimestamp = currentTime
                }
            }
            val shouldBeMoving = (currentTime - lastMovementTimestamp) < movementWindow
            // 添加状态变化缓冲
            if (isUserMoving != shouldBeMoving) {
                if (System.currentTimeMillis() - lastMovementStateChange > 2000) {
                    isUserMoving = shouldBeMoving
                    lastMovementStateChange = System.currentTimeMillis()
                    Log.d("Movement", "移动状态变更: $isUserMoving")
                }
            }
        }


        private fun startLocationDetection(isWeatherButton: Boolean = false) {
            Log.d("LocationFlow", "尝试启动定位...")
            if (checkLocationPermission()) {
                Log.d("LocationFlow", "权限已授予，开始定位")
                locationManager.startLocation(this, isWeatherButton)
            } else {
                Log.w("LocationFlow", "缺少定位权限，正在请求权限")
                requestLocationPermission()
            }
        }

        override fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean) {
            if (location == null) {
                binding.statusText.text = getString(R.string.location_failed)
                ttsService?.speak(getString(R.string.location_failed))
                return
            }
            val pos = listOfNotNull(location.district, location.street, location.city, location.address)
                .firstOrNull { it.isNotBlank() } ?: getString(R.string.unknown_location)
            if (!isWeatherButton) {
                ttsService?.speak(getString(R.string.current_location, location.address))
            } else {
                getWeatherAndAnnounce(location.latitude, location.longitude, pos)
            }
        }

    override fun onPartialResult(hypothesis: String?) {
        // 如需实时提示，可留空或仅记录日志
        Log.d("VOSK_PARTIAL", hypothesis ?: "")
    }

        override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
            runOnUiThread {
                val msg = getString(R.string.location_failed) + " (错误码: $errorCode)"
                Log.e("LocationError", msg)
                showToast(msg)
                ttsService?.speak(getString(R.string.speak_location_error))
                binding.statusText.text = getString(R.string.location_failed)
            }
        }

        private fun checkLocationPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun requestLocationPermission() {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onRequestPermissionsResult(
                requestCode: Int,
                permissions: Array<String>,
                grantResults: IntArray
            ) {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
                when (requestCode) {
                    LOCATION_PERMISSION_REQUEST_CODE -> {
                        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                            locationManager.startLocation(this)
                        } else {
                            showToast(getString(R.string.permission_location_rationale))
                        }
                    }
                    REQUEST_RECORD_AUDIO_PERMISSION -> {
                        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecognition()
                        } else {
                            showToast(getString(R.string.permission_record_audio_rationale))
                            ttsService?.speak(getString(R.string.speak_no_mic_permission))
                        }
                    }
                }
            }

        // 1. 电池温度回调里设置电源模式
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                val newMode = when {
                    temperature < 36f -> PowerMode.HIGH_ACCURACY
                    temperature < 38f -> PowerMode.BALANCED
                    else -> PowerMode.LOW_POWER
                }
                objectDetectorHelper.setPowerMode(newMode)
            }
        }

        private fun initFallDetection() {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            fallDetector = FallDetector(
                locationManager = locationManager,
                context = this,
                // ← 补上
                sosNumber = "123456789000000"
            )
            fallDetector.init(this)
            fallDetector.setEmergencyCallback(this)
            fallDetector.locationManager = locationManager
        }

        private fun bindTtsService() {
            Intent(this, TtsService::class.java).also { intent ->
                bindService(intent, ttsConnection, BIND_AUTO_CREATE)
                isTtsBound = true
            }
        }


        override fun onResume() {
            super.onResume()
            fallDetector.startListening()
            sensorManager.registerListener(
                this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_UI,
                100_000
            )

            // 确保重新初始化摄像头
            if (isCameraMode) {
                Log.d("Camera", "重新初始化内置摄像头")
                initCameraResources()
                checkCameraPermission()
            } else {
                Log.d("Camera", "重新启动外置摄像头流")
                startStream()
            }
            // 确保在恢复时安排播报
            ensureOutdoorModeAnnouncement()
            // ★ 启动 GPU 帧耗时采样
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun initCameraResources() {
            val trace = Firebase.performance.newTrace("camera_initialization")
            trace.start()

            if (!::cameraExecutor.isInitialized) {
                cameraExecutor = Executors.newSingleThreadExecutor()
            }
            if (!::cameraManager.isInitialized) {
                cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
            } else {
                if (cameraManager.isShutdown()) {
                    cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
                }
            }
            Log.d("Camera", "摄像头资源初始化完成: executor=${cameraExecutor.isShutdown}, manager=${::cameraManager.isInitialized}")

            trace.stop()
        }


        override fun onPause() {
            super.onPause()
            locationManager.stopLocation()
            fallDetector.stopListening()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }

        override fun onEmergencyDetected() {
            runOnUiThread {
                binding.statusText.text = getString(R.string.fall_detected_warning)
                ttsService?.speak(getString(R.string.fall_alert_voice))
                triggerEmergencyCall()
            }
        }

        // 在类变量区定义
        private val requestCallPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    fallDetector.triggerEmergencyCall()
                    ttsService?.speak("正在呼叫紧急联系人，请保持冷静")
                } else {
                    showToast("需要电话权限进行紧急呼叫")
                    ttsService?.speak("未获得电话权限，无法进行紧急呼叫")
                }
            }
        private fun checkCallPermission() = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        // 在 MainActivity.kt 中添加如下代码
        val requestPhonePermissions =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val granted = permissions.entries.all { it.value }
                if (granted) {
                    fallDetector.triggerEmergencyCall()
                    ttsService?.speak("正在呼叫紧急联系人，请保持冷静")
                } else {
                    showToast("需要电话权限进行紧急呼叫")
                    ttsService?.speak("未获得电话权限，无法进行紧急呼叫")
                }
            }

        // 在MainActivity中的triggerEmergencyCall方法中添加如下代码
        private fun triggerEmergencyCall() {
            if (checkCallPermission()) {
                fallDetector.triggerEmergencyCall()
                ttsService?.speak("正在呼叫紧急联系人，请保持冷静")
            } else {
                // 添加电话权限请求
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CALL_PHONE)) {
                    AlertDialog.Builder(this)
                        .setTitle("需要电话权限")
                        .setMessage("紧急呼叫功能需要电话权限，请允许权限后重试")
                        .setPositiveButton("去设置") { _, _ ->
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                                startActivity(this)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    requestCallPermission.launch(Manifest.permission.CALL_PHONE)
                    ttsService?.speak("需要电话权限才能呼叫紧急联系人")
                }
            }
        }


        // 修改现有的传感器处理
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onSensorChanged(event: SensorEvent) {

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    fallDetector.onSensorChanged(event)
                    handleShakeDetection(event)
                    handleMovementDetection(event)
                }

                Sensor.TYPE_LIGHT -> handleLightSensor(event)
            }

        }

        // 在摇晃检测中
        private fun handleShakeDetection(event: SensorEvent) {
            val acceleration = event.values.let {
                sqrt(
                    it[0].toDouble().pow(2.0) +
                            it[1].toDouble().pow(2.0) +
                            it[2].toDouble().pow(2.0)
                )
            }
            if (acceleration > 25.0 && System.currentTimeMillis() - lastShakeTime > 5000) {
                lastShakeTime = System.currentTimeMillis()
                triggerEmergencyCall()
            }
        }

        /**
         * 启动语音识别（带权限检查、防重复初始化、异常捕获）
         */
        private fun startVoiceRecognition() {
            if (isRecognitionStarting || ttsService?.isSpeaking == true) return
            isRecognitionStarting = true
            lifecycleScope.launch(Dispatchers.Main) {
                val voiceRecognitionTrace = Firebase.performance.newTrace("voice_recognition_initialization")
                voiceRecognitionTrace.start()

                try {
                    VoskRecognizerManager.stopListening()
                    VoskRecognizerManager.destroy()
                    delay(300)
                    val ok = withContext(Dispatchers.IO) {
                        VoskRecognizerManager.initWithDownload(this@MainActivity)
                    }
                    if (!ok) {
                        showToast(getString(R.string.speak_model_download_failed))
                        return@launch
                    }
                    VoskRecognizerManager.startListening { result ->
                        runOnUiThread {
                            binding.statusText.text = getString(R.string.detected_objects, result)
                            processVoiceCommand(result)
                        }
                    }
                    isListening = true
                    binding.statusText.text = getString(R.string.detected_objects, getString(R.string.detected_objects, "正在聆听"))
                    ttsService?.speak(getString(R.string.speak_enter_read_mode))
                } catch (e: Exception) {
                    Log.e("Speech", "startVoiceRecognition", e)
                    showToast(getString(R.string.speak_speech_start_failed))
                } finally {
                    isRecognitionStarting = false
                    voiceRecognitionTrace.stop()
                }
            }
        }





        override fun onResult(hypothesis: String?) {
            hypothesis?.let {
                Log.d("VOSK_FINAL", "最终结果: $it")
                runOnUiThread {
                    processVoiceCommand(it)
                }
            }
        }



        override fun onTimeout() {
            runOnUiThread {
                Log.w("Vosk","识别超时")
            }
        }

        private fun processVoiceCommand(command: String) {
            val trimmed = command.lowercase(Locale.getDefault())
                .replace(" ", "")   // 去掉空格
            Log.d("COMMAND", "处理后指令：'$trimmed'")
            if (trimmed.isBlank()) return      // 直接丢弃空串
            Log.d("COMMAND", "处理后指令：'$trimmed'")
            when {
                trimmed.contains("我需要帮助") ||
                        trimmed.contains("救命") ||
                        trimmed.contains("sos") -> triggerEmergencyCall()

                trimmed.contains("天气") -> handleWeatherCommand()
                trimmed.contains("位置") ||
                        trimmed.contains("定位") -> handleLocationCommand()

                trimmed.contains("语音通话") ||
                        trimmed.contains("聊天") -> startVoiceCallActivity()

                trimmed.contains("场景描述") ||
                        trimmed.contains("室内模式") -> startRoomActivity()

                trimmed.contains("阅读模式") -> startReadOnlineActivity()

                trimmed.contains("设置") -> openSettings()
                trimmed.contains("退出") -> finish()

                trimmed.contains("开始检测") -> startDetection()
                trimmed.contains("暂停检测") -> pauseDetection()
                trimmed.contains("检测状态") -> {
                    val status = if (isDetectionActive) "正在运行" else "已暂停"
                    ttsService?.speak("障碍物检测$status")
                }

                trimmed.contains("帮助") -> showVoiceHelp()
                else -> ttsService?.speak("未识别指令，请说“帮助”查看可用指令")
            }
        }

        private fun startDetection() {
            isDetectionActive = true
            runOnUiThread {
                overlayView.visibility = View.VISIBLE
            }
            ttsService?.speak("已开启障碍物检测，请注意周围环境")

            // 添加视觉反馈动画
            binding.overlayView.animate()
                .alpha(0.8f)
                .setDuration(500)
                .withEndAction { binding.overlayView.alpha = 1f }
        }

        // 新增跳转方法
        @Suppress("DEPRECATION")
        private fun startVoiceCallActivity() {
            startActivity(Intent(this, VoiceCallActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        @Suppress("DEPRECATION")
        private fun startRoomActivity() {
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, RoomActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }, 300)
        }

        @Suppress("DEPRECATION")
        private fun startReadOnlineActivity() {
            ttsService?.speak(getString(R.string.speak_enter_read_mode))
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, ReadOnlineActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }, 300)
        }

        private fun pauseDetection() {
            isDetectionActive = false
            runOnUiThread {
                overlayView.visibility = View.INVISIBLE
            }
            ttsService?.speak("已暂停障碍物检测")

            // 添加视觉反馈
            binding.previewView.animate()
                .alpha(0.6f)
                .setDuration(500)
                .withEndAction { binding.previewView.alpha = 1f }
        }

        private fun showVoiceHelp() {
            // 将帮助文本分成多个段落
            val helpSegments = listOf(
                "可用语音指令：",
                "· 天气 - 获取当前天气",
                "· 位置 - 获取当前位置",
                "· 设置 - 打开设置界面",
                "· 退出 - 退出应用",
                "· 开始检测 - 启动障碍物检测",
                "· 暂停检测 - 暂停障碍物检测",
                "· 我需要帮助 - 触发紧急求助",
                "· 语音通话 - 启动语音通话功能",
                "· 场景描述 - 切换到室内场景描述模式",
                "· 阅读模式 - 进入在线阅读模式"
            )

            // 使用协程按顺序播报每个段落
            lifecycleScope.launch {
                for (segment in helpSegments) {
                    ttsService?.speak(segment) // 正确调用 speak 方法
                    delay(1000) // 添加1秒延迟确保语音清晰
                }
            }
        }
        private fun handleWeatherCommand() {
            ttsService?.speak("正在获取天气信息")
            binding.fabWeather.performClick()
        }

        private fun handleLocationCommand() {
            ttsService?.speak("正在获取位置信息")
            binding.fabLocation.performClick()
        }

        private fun openSettings() {
            ttsService?.speak("打开设置界面")
            binding.fabSettings.performClick()
        }


        override fun onDestroy() {

            // 统一释放资源
            releaseCameraResources()

            // 停止视频流（如果正在运行）
            stopStream()

            // 释放其他资源
            if (isTtsBound) {
                unbindService(ttsConnection)
                isTtsBound = false
            }
            locationManager.destroy()

            // 注销传感器监听
            sensorManager.unregisterListener(this)
            // 移除所有待处理的播报任务
            announcementHandler.removeCallbacks(announcementRunnable)
            // 释放 Vosk 资源
            VoskRecognizerManager.stopListening()
            VoskRecognizerManager.destroy()
            perfMonitor.stop()
            super.onDestroy()
        }

        // 2. 光照传感器回调里开关闪光灯
        private fun handleLightSensor(event: SensorEvent) {
            val currentLight = event.values[0]
            if (abs(currentLight - lastLightValue) > 1) {
                lastLightValue = currentLight
                cameraManager.enableTorchMode(currentLight < 10.0f)
            }
        }


        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
                fallDetector.resetFallState()
                ttsService?.speak(getString(R.string.false_alarm_canceled))
                falseAlarmCancelCount = 0
                binding.statusText.text = getString(R.string.normal_status)
            }
        }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis?.let {
            Log.d("VOSK_FINAL", "最终结果: $it")
            runOnUiThread {
                processVoiceCommand(it)
            }
        }
    }

        private fun startCamera() {
            // 确保相机资源已初始化
            if (::cameraManager.isInitialized) {
                try {
                    // 添加检查防止在无效状态下启动
                    if (!isFinishing && !isDestroyed) {
                        cameraManager.initializeCamera(binding.previewView.surfaceProvider)
                        Log.d("Camera", "摄像头成功启动")
                    } else {
                        Log.w("Camera", "Activity正在销毁，跳过摄像头启动")
                    }
                } catch (e: Exception) {
                    Log.e("Camera", "启动摄像头失败", e)
                    showToast("启动摄像头失败: ${e.message}")
                }
            } else {
                Log.w("Camera", "尝试启动未初始化的摄像头管理器")
                showToast("摄像头初始化失败，请重试")
            }
        }

    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            // 确保每一帧都调用 addFrame()
            PerformanceMonitor.FrameStats.addFrame()
            Log.d("FPS", "addFrame called in createAnalyzer")
            DetectionProcessor.getInstance(this@MainActivity)
                .updateImageDimensions(imageProxy.width, imageProxy.height)
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return@launch
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // ========= 记录 TFLite 耗时 =========
                val t0 = SystemClock.elapsedRealtime()
                val results = withContext(objectDetectorHelper.getGpuThread()) {
                    objectDetectorHelper.detect(bitmap, rotationDegrees)
                }
                val t1 = SystemClock.elapsedRealtime()
                Log.d("INFERENCE", "TFLite耗时: ${t1 - t0} ms")
                perfMonitor.recordTflite(SystemClock.elapsedRealtime() - t0)
                // ===================================

                withContext(Dispatchers.Main) {
                    val t2 = SystemClock.elapsedRealtime()
                    overlayView.setModelInputSize(bitmap.width, bitmap.height)
                    updateOverlayView(results, rotationDegrees)
                    handleDetectionResults(results)
                    updateStatusUI(results)
                    Log.d("INFERENCE", "UI更新耗时: ${SystemClock.elapsedRealtime() - t2} ms")
                }
                imageProxy.close()
            }
        }
    }

    override fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String) {
        val networkRequestTrace = Firebase.performance.newTrace("network_request_weather")
        networkRequestTrace.start()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 在IO线程执行网络请求
                val weatherData = weatherManager.getWeather(lat, lon)

                // 切换到主线程更新UI
                withContext(Dispatchers.Main) {
                    if (weatherData == null) {
                        showToast("天气获取失败")
                        ttsService?.speak("获取天气信息失败")
                        return@withContext
                    }

                    val speechText = weatherManager.generateSpeechText(weatherData, cityName)
                    speakWeather(speechText)
                }
            } catch (e: Exception) {
                // 切换到主线程处理错误
                withContext(Dispatchers.Main) {
                    Log.e("Weather", "获取天气失败", e)
                    showToast("天气获取失败: ${e.message}")
                    ttsService?.speak("天气服务异常，请稍后重试")
                }
            } finally {
                networkRequestTrace.stop()
            }
        }
    }

        override fun speakWeather(message: String) {
            Log.d("Weather", "播报天气: $message")
            ttsService?.speak(message)
        }

        private fun handleDetectionResults(results: List<Detection>) {
            results
                .filter { it.categories.isNotEmpty() }
                .maxByOrNull {
                    it.categories.maxByOrNull { c -> c.score }?.score ?: 0f
                }?.let {
                    feedbackManager.handleDetectionResult(it)
                }
        }

        private fun updateOverlayView(results: List<Detection>, rotation: Int) {
            runOnUiThread {
                overlayView.updateDetections(results, rotation)
            }
        }

        private fun updateStatusUI(results: List<Detection>) = runOnUiThread {
            val names = results.joinToString {
                DetectionProcessor.getInstance(this)
                    .getChineseLabel(it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown")
            }
            binding.statusText.text = if (names.isEmpty()) {
                getString(R.string.no_objects_detected)
            } else {
                getString(R.string.detected_objects, names)
            }
        }

        private fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }

        // ================== 外置摄像头视频流相关代码 ==================

        // 更新连接状态
        private fun updateConnectionState(newState: ConnectionState) {
            if (connectionState == newState) return
            connectionState = newState

            runOnUiThread {
                when (newState) {
                    ConnectionState.CONNECTING -> {
                        binding.CamStatusText.text = "正在连接..."
                    }

                    ConnectionState.CONNECTED -> {
                        binding.CamStatusText.text = "已连接"
                        connectionStartTime = System.currentTimeMillis()
                        startTimer()
                    }

                    ConnectionState.DISCONNECTED -> {
                        binding.CamStatusText.text = "未连接"
                        timerJob?.cancel()
                    }
                }
            }
        }

        // 启动视频流
        // 1. 启动流（保持不变）
        private fun startStream() {
            if (isStreaming || isCameraMode) return
            isStreaming = true

            streamingJob = lifecycleScope.launch(Dispatchers.IO) {
                var retryCount = 0
                while (isStreaming) {
                    try {
                        updateConnectionState(ConnectionState.CONNECTING)
                        retryCount = 0
                        val request = Request.Builder().url(STREAM_URL).build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
                            updateConnectionState(ConnectionState.CONNECTED)

                            leftoverData = ByteArray(0)
                            val contentType = response.header("Content-Type") ?: ""
                            boundary = contentType.split("boundary=").last().trim()

                            response.body?.byteStream()?.let { stream ->
                                parseMjpegStreamOptimized(stream)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Stream error: ${e.message}")
                        if (++retryCount > maxRetries) {
                            updateConnectionState(ConnectionState.DISCONNECTED)
                            showToast("Max retries reached")
                            isStreaming = false
                            return@launch
                        }
                        if (isStreaming) {
                            showToast("正在重连... $retryCount/$maxRetries")
                            delay(reconnectDelay)
                        }
                    }
                }
            }
        }

        // 2. 解析 MJPEG（后台线程）
        private fun parseMjpegStreamOptimized(stream: InputStream) {
            val videoStreamTrace = Firebase.performance.newTrace("video_stream_parsing")
            videoStreamTrace.start()

            val buffer = ByteArray(4096)
            leftoverData = ByteArray(0)
            while (isStreaming && !Thread.currentThread().isInterrupted) {
                val len = stream.read(buffer)
                if (len == -1) break
                val data = leftoverData + buffer.copyOfRange(0, len)
                leftoverData = processFrameBuffer(data)
            }

            videoStreamTrace.stop()
        }



        // 3. 处理一帧数据
        private fun processFrameBuffer(data: ByteArray): ByteArray {
            var pos = 0
            while (true) {
                val boundaryIdx = findBoundary(data, pos)
                if (boundaryIdx == -1) return data.copyOfRange(pos, data.size)

                val section = data.copyOfRange(boundaryIdx, data.size)
                val headerEnd = findHeaderEnd(section)
                if (headerEnd == -1) return section

                val headers = String(section, 0, headerEnd)
                val contentLen = extractContentLength(headers)
                if (contentLen <= 0) return section

                val imgStart = headerEnd + 4
                val imgEnd = imgStart + contentLen
                if (imgEnd > section.size) return section

                val imgData = section.copyOfRange(imgStart, imgEnd)
                if (isValidJpeg(imgData)) renderFrame(imgData)

                pos = boundaryIdx + imgEnd
            }
        }

        // 4. 解码 + 渲染（后台线程 → 主线程）
        private fun renderFrame(imgData: ByteArray) {
            renderHandler.post {
                PerformanceMonitor.FrameStats.addFrame()
                Log.d("FPS", "addFrame called in renderFrame")
                val bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.size) ?: return@post
                DetectionProcessor.getInstance(this@MainActivity)
                    .updateImageDimensions(bmp.width, bmp.height)

                lifecycleScope.launch(Dispatchers.IO) {
                    // ========= 记录 TFLite 耗时 =========
                    val t0 = SystemClock.elapsedRealtime()
                    val results = withContext(objectDetectorHelper.getGpuThread()) {
                        objectDetectorHelper.detect(bmp, 0)
                    }
                    perfMonitor.recordTflite(SystemClock.elapsedRealtime() - t0)
                    // ===================================

                    withContext(Dispatchers.Main) {
                        overlayView.setModelInputSize(bmp.width, bmp.height)
                        updateOverlayView(results, 0)
                        handleDetectionResults(results)
                        updateStatusUI(results)
                    }
                }
            }
        }


        // 停止视频流
        private fun stopStream() {
            isStreaming = false
            streamingJob?.cancel()
            updateConnectionState(ConnectionState.DISCONNECTED)
        }

        // 查找边界
        private fun findBoundary(data: ByteArray, startIndex: Int): Int {
            val boundaryPattern = "--$boundary".toByteArray()
            for (i in startIndex..data.size - boundaryPattern.size) {
                if (data.copyOfRange(i, i + boundaryPattern.size)
                        .contentEquals(boundaryPattern)
                ) return i
            }
            return -1
        }

        // 查找头结束位置
        private fun findHeaderEnd(data: ByteArray): Int {
            for (i in 0..data.size - 4) {
                if (data[i] == '\r'.code.toByte() &&
                    data[i + 1] == '\n'.code.toByte() &&
                    data[i + 2] == '\r'.code.toByte() &&
                    data[i + 3] == '\n'.code.toByte()
                ) return i
            }
            return -1
        }

        // 提取内容长度
        private fun extractContentLength(headers: String): Int {
            return Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headers)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: -1
        }

        // 验证JPEG格式
        private fun isValidJpeg(data: ByteArray): Boolean {
            return data.size >= 2 &&
                    data[0] == 0xFF.toByte() &&
                    data[1] == 0xD8.toByte() &&
                    data.last() == 0xD9.toByte()
        }

        // 启动计时器
        private fun startTimer() {
            timerJob?.cancel()
            timerJob = lifecycleScope.launch(Dispatchers.Main) {
                while (isStreaming && connectionState == ConnectionState.CONNECTED) {
                    val elapsed = System.currentTimeMillis() - connectionStartTime
                    val min = elapsed / 1000 / 60
                    val sec = elapsed / 1000 % 60
                    binding.timerText.text = getString(R.string.timer_format, min, sec)
                    delay(1000)
                }
            }
        }

        // 切换摄像头模式
    // 修改 switchCameraMode 方法
        private fun switchCameraMode() {
            runOnUiThread {
                if (isCameraMode) {
                    // 切换到外置摄像头模式
                    binding.previewView.visibility = View.GONE
                    binding.streamView.visibility = View.VISIBLE
                    binding.timerText.visibility = View.VISIBLE

                    // 释放内置摄像头资源
                    releaseCameraResources()

                    // 启动视频流
                    startStream()
                    isCameraMode = false

                    ttsService?.speak("已切换到外置摄像头模式")
                } else {
                    // 切换回内置摄像头
                    binding.previewView.visibility = View.VISIBLE
                    binding.streamView.visibility = View.GONE
                    binding.timerText.visibility = View.GONE
                    stopStream()

                    // 重新初始化内置摄像头资源
                    initCameraResources()
                    checkCameraPermission()
                    isCameraMode = true

                    ttsService?.speak("已切换到内置摄像头模式")
                }
            }
        }
        private fun releaseCameraResources() {
            try {
                if (::cameraManager.isInitialized) {
                    cameraManager.shutdown()  // 修复调用shutdown()
                }
                if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                    cameraExecutor.shutdown()
                }
                Log.d("Camera", "摄像头资源已释放")
            } catch (e: Exception) {
                Log.e("Camera", "释放摄像头资源失败", e)
            }
        }
    }