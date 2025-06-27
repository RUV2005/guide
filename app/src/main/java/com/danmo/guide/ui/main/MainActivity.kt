package com.danmo.guide.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
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
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.feature.weather.WeatherManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import com.danmo.guide.ui.voicecall.VoiceCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    SensorEventListener, LocationManager.LocationCallback, FallDetector.WeatherCallback,
    RecognitionListener {

    // 视频流相关变量
    private enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

    private var connectionState = ConnectionState.DISCONNECTED
    private var connectionStartTime = 0L
    private var isStreaming = false
    private val streamHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private lateinit var boundary: String
    private val TAG = "MjpegStream"
    private var leftoverData = ByteArray(0)
    private var streamingJob: Job? = null
    private var isCameraMode = true // true 表示内置摄像头模式，false 表示外置摄像头模式
    private var hasStreamReadyDialogShown = false
    private var timerJob: Job? = null
    private val RECONNECT_DELAY = 5000L
    private val MAX_RETRIES = 5
    private var retryCount = 0 // 添加重试计数器变量

    private companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private var lastSpeakTime = 0L
        const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
        private const val INIT_DELAY = 300L
        private const val RETRY_DELAY = 1000L
        private const val FILTER_WINDOW_SIZE = 5
        private const val STREAM_URL = "http://192.168.4.1:81/stream" // 视频流地址
    }

    // 新增滤波相关变量
    private val accelerationHistory = mutableListOf<Double>()

    // 在类变量区添加
    private var lastMovementStateChange = 0L

    // 新增语音控制相关变量
    private var isDetectionActive = true // 初始状态为开启检测
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
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
    private var isWeatherAnnounced = false
    private lateinit var fallDetector: FallDetector
    private lateinit var sensorManager: SensorManager
    private var ttsService: TtsService? = null
    private var isTtsBound = false
    private var lastLightValue = 0f
    private var falseAlarmCancelCount = 0
    private val accessibilityManager by lazy {
        getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    private var isUserMoving = false
    private var lastMovementTimestamp = 0L
    private val MOVEMENT_THRESHOLD = 2.5 // 移动检测阈值（m/s²）
    private val MOVEMENT_WINDOW = 5L // 移动判断时间窗口（5秒）
    private var hasAnnouncedDelay = false

    private fun applyLowPassFilter(currentValue: Double): Double {
        accelerationHistory.add(currentValue)
        if (accelerationHistory.size > Companion.FILTER_WINDOW_SIZE) {
            accelerationHistory.removeAt(0)
        }
        return accelerationHistory.average()
    }

    // region 语音识别回调方法
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("Speech", "语音识别准备就绪")
        binding.statusText.text = "请开始说话"
    }

    override fun onBeginningOfSpeech() {
        Log.d("Speech", "开始说话")
        binding.statusText.text = "正在聆听..."
    }

    override fun onRmsChanged(rmsdB: Float) {
        // 可在此处添加音量波动动画
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        Log.d("Speech", "收到音频缓冲")
    }

    override fun onEndOfSpeech() {
        Log.d("Speech", "结束说话")
        isListening = false
        binding.statusText.text = "正在分析指令..."
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        Log.d("Speech", "识别事件: $eventType")
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
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

    // 创建单独的麦克风权限请求
    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startVoiceRecognition()
            } else {
                showToast("需要麦克风权限才能使用语音功能")
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

    private fun checkMicrophonePermission(callback: () -> Unit) {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> callback()

            else -> requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            Log.d("MainActivity", "TTS服务已连接")
            fallDetector.ttsService = ttsService
            // 服务连接后处理可能存在的队列
            ttsService?.processNextInQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            Log.d("MainActivity", "TTS服务已断开")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        initBasicComponents()
        initFallDetection()
        bindTtsService()
        initializeSpeechRecognizer()

        // 添加摄像头切换按钮
        binding.fabSwitchCamera.setOnClickListener {
            switchCameraMode()
        }

        binding.fabVoice.setOnClickListener {
            handleVoiceCommand()
        }

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
        binding.fabVoiceCall.setOnClickListener {
            startVoiceCallActivity()
        }
    }

    // 添加 handleVoiceCommand 方法
    private fun handleVoiceCommand() {
        when {
            !SpeechRecognizer.isRecognitionAvailable(this) -> {
                showToast("该设备不支持语音识别")
            }
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

    private fun handleVoiceCallButton() {
        if (isTtsBound) {
            ttsService?.speak("正在启动语音通话")
        }
        binding.statusText.text = "正在启动语音通话..."

        // 延迟300ms确保语音播报完成
        Handler(Looper.getMainLooper()).postDelayed({
            startVoiceCallActivity()
        }, 300)
    }


    private fun initializeSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(this@MainActivity)
                }
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            } else {
                showToast("该设备不支持语音识别")
            }
        } catch (e: Exception) {
            Log.e("VoiceControl", "语音初始化失败", e)
            showToast("语音功能初始化失败")
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
            lastLightValue < 10 -> MOVEMENT_THRESHOLD * 0.9
            else -> MOVEMENT_THRESHOLD
        }
        // 调整触发条件（需连续两次超过阈值）
        if (filteredAcceleration > dynamicThreshold) {
            if ((currentTime - lastMovementTimestamp) < 1000) {
                lastMovementTimestamp = currentTime
            }
        }
        val shouldBeMoving = (currentTime - lastMovementTimestamp) < MOVEMENT_WINDOW
        // 添加状态变化缓冲
        if (isUserMoving != shouldBeMoving) {
            if (System.currentTimeMillis() - lastMovementStateChange > 2000) {
                isUserMoving = shouldBeMoving
                lastMovementStateChange = System.currentTimeMillis()
                Log.d("Movement", "移动状态变更: $isUserMoving")
            }
        }
    }


    private fun checkVoicePermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            false
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
            binding.statusText.text = "定位失败"
            ttsService?.speak("定位失败，请重试")
            return
        }
        Log.d("定位Debug", "address=${location.address}, district=${location.district}, city=${location.city}, street=${location.street}, latitude=${location.latitude}, longitude=${location.longitude}, errorCode=${location.errorCode}, errorInfo=${location.errorInfo}")

        val posText = listOfNotNull(location.district, location.street, location.city, location.address)
            .firstOrNull { !it.isNullOrBlank() } ?: "未知位置"
        if (!isWeatherButton) {
            binding.statusText.text = "定位成功：$posText"
            ttsService?.speak("当前位置：$posText")
        } else {
            binding.statusText.text = "正在获取 $posText 的天气..."
            getWeatherAndAnnounce(location.latitude, location.longitude, posText)
        }
    }

    suspend fun getAddressFromLatLng(lat: Double, lon: Double): String {
        val key = "你的高德Key"
        val url = "https://restapi.amap.com/v3/geocode/regeo?location=$lon,$lat&key=$key"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string() ?: return "未知位置"
            val obj = JSONObject(json)
            val regeocode = obj.optJSONObject("regeocode")
            val formatted = regeocode?.optString("formatted_address")
            return formatted ?: "未知位置"
        }
        return "未知位置"
    }

    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        Log.e("LocationManager", "onLocationFailure errorCode=$errorCode, errorInfo=$errorInfo")
        // 使用 runOnUiThread 确保在主线程更新UI
        runOnUiThread {
            val errorLog = "定位失败 (错误码: $errorCode): ${errorInfo ?: "未知错误"}"
            Log.e("LocationError", errorLog)
            showToast(errorLog)
            ttsService?.speak("定位失败，请检查设置")

            binding.statusText.text = "定位失败"
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
                    showToast("需要位置权限获取天气")
                }
            }

            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecognition()
                } else {
                    showToast("需要麦克风权限才能使用语音功能")
                }
            }
        }
    }

    private fun initBasicComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView
        objectDetectorHelper = ObjectDetectorHelper(this)
        feedbackManager = FeedbackManager(this)
        cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
        weatherManager = WeatherManager(this)
        checkCameraPermission()
        locationManager = LocationManager.instance!!
        locationManager.initialize(this)
        locationManager.callback = this
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun initFallDetection() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fallDetector = FallDetector(
            context = this,
            locationManager = locationManager,
            weatherCallback = this,
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
    }

    override fun onPause() {
        super.onPause()
        locationManager.stopLocation()
        fallDetector.stopListening()
    }

    override fun onEmergencyDetected() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.fall_detected_warning)
            ttsService?.speak(getString(R.string.fall_alert_voice))
            triggerEmergencyCall()
        }
    }

    private val requestCallPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                triggerEmergencyCall()
            } else {
                showToast("需要电话权限进行紧急呼叫")
                ttsService?.speak("未获得电话权限，无法进行紧急呼叫")
            }
        }

    private fun checkCallPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED

    private fun triggerEmergencyCall() {
        if (checkCallPermission()) {
            fallDetector.triggerEmergencyCall()
            ttsService?.speak("正在呼叫紧急联系人，请保持冷静")
        } else {
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
            ttsService?.speak("需要电话权限才能呼叫紧急联系人")
        }
    }

    // 计算并设置视频流显示区域
    private fun updateStreamDisplayRect() {
        val displayRect = binding.overlayView.calculateStreamDisplayRect(
            binding.streamView.width,
            binding.streamView.height
        )
        binding.overlayView.setStreamDisplayRect(
            displayRect.left,
            displayRect.top,
            displayRect.right,
            displayRect.bottom
        )
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
            kotlin.math.sqrt(
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

    private fun startVoiceRecognition() {
        try {
            speechRecognizer.startListening(recognizerIntent)
            isListening = true
            binding.statusText.text = "正在聆听..."
            ttsService?.speak("用户您好，语音识别已激活，您请说", true)
        } catch (e: Exception) {
            Log.e("Speech", "启动识别失败: ${e.message}")
            showToast("语音识别启动失败，请重试")
        }
    }

    private fun stopVoiceRecognition() {
        try {
            speechRecognizer.stopListening()
            isListening = false
            binding.statusText.text = "语音识别已停止"
        } catch (e: Exception) {
            Log.e("Speech", "停止识别失败: ${e.message}")
        }
    }

    override fun onResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
            if (it.isNotEmpty()) processVoiceCommand(it[0])
        }
    }

    private fun processVoiceCommand(command: String) {
        val processed = command.lowercase(Locale.getDefault())
        when {
            processed.contains("我需要帮助") || processed.contains("救命") || processed.contains("sos") -> {
                triggerEmergencyCall()
            }

            processed.contains("天气") || processed.contains("今日天气") -> handleWeatherCommand()
            processed.contains("位置") || processed.contains("定位") -> handleLocationCommand()
            processed.contains("帮助") || processed.contains("获取帮助") -> showVoiceHelp()
            processed.contains("设置") || processed.contains("打开设置") -> openSettings()
            processed.contains("退出") || processed.contains("退出应用") -> finish()
            processed.contains("开始检测") || processed.contains("启动检测") -> startDetection()
            processed.contains("暂停检测") || processed.contains("停止检测") -> pauseDetection()
            processed.contains("语音通话") || processed.contains("聊天") -> startVoiceCallActivity()
            else -> ttsService?.speak("未识别指令，请说'帮助'查看可用指令")
        }
    }

    private fun startDetection() {
        isDetectionActive = true
        runOnUiThread {
            binding.statusText.text = "障碍物检测已启动"
            overlayView.visibility = View.VISIBLE
        }
        ttsService?.speak("已开启障碍物检测，请注意周围环境")
    }

    // 新增跳转方法
    private fun startVoiceCallActivity() {
        binding.statusText.text = "正在启动语音通话..."

        // 添加过渡动画
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)

        // 启动语音通话活动
        startActivity(Intent(this, VoiceCallActivity::class.java))
    }
    private fun pauseDetection() {
        isDetectionActive = false
        runOnUiThread {
            binding.statusText.text = "障碍物检测已暂停"
            overlayView.visibility = View.INVISIBLE
        }
        ttsService?.speak("已暂停障碍物检测")
    }

    private fun startChat(){

    }
    private fun showVoiceHelp() {
        ttsService?.speak(
            """
            可用语音指令：
            · 天气 - 获取当前天气
            · 位置 - 获取当前位置
            · 设置 - 打开设置界面
            · 退出 - 退出应用
            · 开始检测 - 启动障碍物检测
            · 暂停检测 - 暂停障碍物检测
            · 我需要帮助 - 触发紧急求助
        """.trimIndent()
        )
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

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
            if (it.isNotEmpty()) {
                binding.statusText.text = "正在识别: ${it[0]}"
            }
        }
    }

    override fun onError(error: Int) {
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到内容，请重试"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时，请重试"
            else -> "识别错误 (代码: $error)"
        }
        runOnUiThread {
            binding.statusText.text = errorMsg
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCameraMode) {
            cameraManager.shutdown()
        } else {
            stopStream()
        }
        cameraExecutor.shutdown()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.apply {
                stopListening()
                destroy()
            }
        }
        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        locationManager.destroy()
        stopStream()
    }

    private fun handleLightSensor(event: SensorEvent) {
        val currentLight = event.values[0]
        if (abs(currentLight - lastLightValue) > 1) {
            lastLightValue = currentLight
            cameraManager.enableTorchMode(currentLight < 10.0f)
            binding.statusText.text = if (currentLight < 10.0f) {
                getString(R.string.low_light_warning)
            } else {
                getString(R.string.normal_light_status)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBackPressed() {
        if (fallDetector.isFallDetected()) {
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

    private fun startCamera() {
        // 确保相机资源已初始化
        if (::cameraManager.isInitialized) {
            cameraManager.initializeCamera(binding.previewView.surfaceProvider)
        }
    }

    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (!::cameraExecutor.isInitialized || cameraExecutor.isShutdown) {
                imageProxy.close()
                return@Analyzer
            }

            cameraExecutor.submit {
                try {
                    if (!isDetectionActive) {
                        imageProxy.close()
                        return@submit
                    }
                    val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return@submit
                    val tensorImage = TensorImage.fromBitmap(bitmap)
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val results = objectDetectorHelper.detect(tensorImage, rotationDegrees)
                    overlayView.setModelInputSize(tensorImage.width, tensorImage.height)
                    updateOverlayView(results, rotationDegrees)
                    updateStatusUI(results)
                    handleDetectionResults(results)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    override fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 在IO线程执行网络请求
                val weatherData = weatherManager.getWeather(lat, lon)

                // 切换到主线程更新UI
                withContext(Dispatchers.Main) {
                    if (weatherData == null) {
                        showToast("天气获取失败")
                        ttsService?.speak("获取天气信息失败")
                        binding.statusText.text = "天气获取失败"
                        return@withContext
                    }

                    val speechText = weatherManager.generateSpeechText(weatherData, cityName)
                    speakWeather(speechText)
                    binding.statusText.text = "$cityName 天气获取成功"
                }
            } catch (e: Exception) {
                // 切换到主线程处理错误
                withContext(Dispatchers.Main) {
                    Log.e("Weather", "获取天气失败", e)
                    showToast("天气获取失败: ${e.message}")
                    ttsService?.speak("天气服务异常，请稍后重试")
                    binding.statusText.text = "天气获取失败"
                }
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

    private fun updateStatusUI(results: List<Detection>) {
        runOnUiThread {
            binding.statusText.text = when {
                results.isEmpty() -> getString(R.string.no_objects_detected)
                else -> "检测到: ${
                    results.joinToString {
                        DetectionProcessor.getInstance(this)
                            .getChineseLabel(it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown")
                    }
                }"
            }
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
    private fun startStream() {
        if (isStreaming || !isCameraMode) return // 确保在正确模式下启动
        isStreaming = true

        streamingJob = lifecycleScope.launch(Dispatchers.IO) {
            var retryCount = 0
            while (isStreaming) {
                try {
                    updateConnectionState(ConnectionState.CONNECTING)
                    retryCount = 0
                    val request = Request.Builder()
                        .url(STREAM_URL) // 直接使用STREAM_URL常量
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Unexpected code ${response.code}")
                        }
                        updateConnectionState(ConnectionState.CONNECTED)

                        leftoverData = ByteArray(0)

                        val contentType = response.header("Content-Type") ?: ""
                        boundary = contentType.split("boundary=").last().trim()
                        Log.d(TAG, "Using boundary: --$boundary")

                        response.body?.byteStream()?.let { stream ->
                            parseMjpegStream(stream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error: ${e.message}")

                    if (++retryCount > MAX_RETRIES) {
                        updateConnectionState(ConnectionState.DISCONNECTED)
                        showToast("Max retries reached")
                        isStreaming = false
                        return@launch
                    }

                    if (isStreaming) {
                        showToast("正在重连... Attempt $retryCount/$MAX_RETRIES")
                        delay(RECONNECT_DELAY)
                    }
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

    // 解析MJPEG流
    private fun parseMjpegStream(stream: InputStream) {
        val readBuffer = ByteArray(4096)
        if (!isStreaming) return
        try {
            while (isStreaming) {
                val bytesRead = stream.read(readBuffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "End of stream reached")
                    throw IOException("Stream ended unexpectedly")
                }

                val data = leftoverData + readBuffer.copyOfRange(0, bytesRead)
                processData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream read error: ${e.message}")
            throw e
        }
    }

    private fun processData(data: ByteArray) {
        var processedIndex = 0
        while (true) {
            val boundaryIndex = findBoundary(data, processedIndex)
            if (boundaryIndex == -1) break

            val sectionData = data.copyOfRange(boundaryIndex, data.size)
            val headerEndIndex = findHeaderEnd(sectionData)

            if (headerEndIndex == -1) {
                leftoverData = sectionData
                return
            }

            val headers = String(sectionData, 0, headerEndIndex)
            val contentLength = extractContentLength(headers)
            if (contentLength == -1) {
                Log.e(TAG, "Invalid Content-Length")
                return
            }

            val imageStart = headerEndIndex + 4
            val imageEnd = imageStart + contentLength

            if (imageEnd > sectionData.size) {
                leftoverData = sectionData
                return
            }

            val imageData = sectionData.copyOfRange(imageStart, imageEnd)
            if (isValidJpeg(imageData)) {
                displayImage(imageData)
                // 在这里调用图像识别逻辑
                if (!isCameraMode) {
                    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    bitmap?.let {
                        val tensorImage = TensorImage.fromBitmap(bitmap)
                        val results = objectDetectorHelper.detect(tensorImage, 0) // 假设视频流不需要旋转
                        handleDetectionResultsFromStream(results)
                    }
                }
            }

            processedIndex = boundaryIndex + imageEnd
        }
        leftoverData = data.copyOfRange(processedIndex, data.size)
    }

    private fun handleDetectionResultsFromStream(results: List<Detection>) {
        results
            .filter { it.categories.isNotEmpty() }
            .maxByOrNull {
                it.categories.maxByOrNull { c -> c.score }?.score ?: 0f
            }?.let {
                feedbackManager.handleDetectionResult(it)
                updateOverlayView(results, 0) // 假设视频流不需要旋转
                updateStatusUI(results)
                // 如果需要将结果传递给 DetectionProcessor，可以在这里调用
                results.forEach { detection ->
                    DetectionProcessor.getInstance(this).handleDetectionResult(detection)
                }
            }
    }

    // 显示图像帧
    private fun displayImage(imageData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            bitmap?.let {
                // 设置模型输入尺寸为视频流原始分辨率
                binding.overlayView.setModelInputSize(it.width, it.height)

                runOnUiThread {
                    binding.streamView.setImageBitmap(bitmap)
                }

                // 图像识别逻辑
                if (!isCameraMode && isDetectionActive) {
                    val tensorImage = TensorImage.fromBitmap(bitmap)
                    val results = objectDetectorHelper.detect(tensorImage, 0)
                    handleDetectionResultsFromStream(results)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}")
        }
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
                val minutes = (elapsed / 1000) / 60
                val seconds = (elapsed / 1000) % 60
                // 使用binding.timerText更新计时器
                binding.timerText.text = "%02d:%02d".format(minutes, seconds)
                delay(1000)
            }
        }
    }

    // 切换摄像头模式
// 修改 switchCameraMode 方法
    private fun switchCameraMode() {
        runOnUiThread {
            if (isCameraMode) {
                // 添加 layout listener
                binding.streamView.viewTreeObserver.addOnGlobalLayoutListener {
                    if (!isCameraMode) {
                        updateStreamDisplayRect()
                    }
                }
                // 切换到外置摄像头（视频流）模式
                binding.previewView.visibility = View.GONE
                binding.streamView.visibility = View.VISIBLE
                binding.timerText.visibility = View.VISIBLE

                // 完全关闭内置摄像头资源
                cameraManager.shutdown()
                cameraExecutor.shutdownNow()

                // 设置视频流显示区域
                updateStreamDisplayRect()

                // 设置模型输入尺寸（视频流原始分辨率）
                binding.overlayView.setModelInputSize(640, 480) // 根据实际视频流分辨率调整

                startStream()
                isCameraMode = false

                ttsService?.speak("已切换到外置摄像头模式")
            } else {
                // 如果不需要再监听，可以移除监听器
                binding.streamView.viewTreeObserver.removeOnGlobalLayoutListener {
                    if (!isCameraMode) {
                        updateStreamDisplayRect()
                    }
                }
                // 切换回内置摄像头
                binding.previewView.visibility = View.VISIBLE
                binding.streamView.visibility = View.GONE
                binding.timerText.visibility = View.GONE
                stopStream()

                // 重置为摄像头模式
                binding.overlayView.resetToCameraMode()

                // 重新初始化内置摄像头
                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
                checkCameraPermission()
                isCameraMode = true

                ttsService?.speak("已切换到内置摄像头模式")
            }
        }
    }
}