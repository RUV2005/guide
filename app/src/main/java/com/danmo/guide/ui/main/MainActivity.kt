package com.danmo.guide.ui.main
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.ImageAnalysis
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.danmo.guide.feature.update.UpdateChecker
import com.danmo.guide.feature.weather.WeatherManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.View
import com.danmo.guide.feature.update.VersionComparator
import kotlin.math.sqrt
class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    SensorEventListener, LocationManager.LocationCallback, FallDetector.WeatherCallback,
    RecognitionListener {
    // region 新增语音控制变量
    private var speechInitialized = false
    private val speechHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 2
    // endregion
    private companion object {
        const val UPDATE_URL = "https://guangji.online/downloads/app_latest.apk"
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private var lastSpeakTime = 0L
        const val REQUEST_RECORD_AUDIO_PERMISSION = 1002
        private const val INIT_DELAY = 300L
        private const val RETRY_DELAY = 1000L
        private const val FILTER_WINDOW_SIZE = 5
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
    private val updateChecker = UpdateChecker()
    private var isCheckingUpdate = false
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
        // binding.volumeView.updateLevel(rmsdB)
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
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
    }
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.all { it.value }
            if (!allGranted) showToast("部分功能可能受限")
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        initBasicComponents()
        initFallDetection()
        bindTtsService()
        speechHandler.postDelayed({ initializeSpeechRecognizer() }, INIT_DELAY)
        binding.fabVoice.setOnClickListener {
            handleVoiceCommand()
        }
    }
        // region 改进的语音控制模块
        private fun initializeSpeechRecognizer() {
            if (speechInitialized) return
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    showToast("该设备不支持语音识别")
                    return
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(this@MainActivity)
                }
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
                }
                speechInitialized = true
                Log.d("Speech", "语音模块初始化完成")
            } catch (e: Exception) {
                Log.e("VoiceControl", "语音初始化失败", e)
                showToast("语音功能初始化失败，请重启应用")
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
        checkAutoUpdate()
        initVoiceControl()  // 新增语音初始化
    }
    private fun handleVoiceCommand() {
        when {
            !speechInitialized -> {
                showToast("语音模块正在初始化，请稍后")
                initializeSpeechRecognizer()
            }
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                toggleVoiceRecognition()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) -> {
                showPermissionExplanationDialog()
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
    private fun showPermissionExplanationDialog() {
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
    // 修改运动检测处理逻辑
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
                // 状态变更处理...
            }
        }
    }
    // 新增取消延迟检查的方法
    private fun cancelPendingUpdateChecks() {
        speechHandler.removeCallbacksAndMessages(null)
    }
    // 修改自动检查逻辑
    private fun checkAutoUpdate() {
        if (!isNetworkAvailable()) {
            ttsService?.speak("网络不可用，跳过自动更新检查")
            return
        }
        lifecycleScope.launch {
            when {
                isUserMoving -> {
                    if (!hasAnnouncedDelay) {
                        ttsService?.speak("检测到移动，更新推迟至静止后")
                        hasAnnouncedDelay = true
                    }
                    // 使用指数退避策略安排检查
                    val delay = (MOVEMENT_WINDOW * 2.0.pow(retryCount.coerceAtMost(5).toDouble())).toLong()
                    scheduleDelayedUpdateCheck(delay)
                    retryCount++
                }
                else -> {
                    retryCount = 0
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        checkUpdate(silent = false)
                    }
                }
            }
        }
    }
    private fun initVoiceControl() {
        try {
            // 检查设备是否支持语音识别
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                showToast("该设备不支持语音识别")
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@MainActivity)
            }
            // 添加更多识别参数
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)  // 设置最大结果数
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
            }
            // 添加按钮状态反馈
            binding.fabVoice.setOnClickListener {
                if (checkVoicePermission()) {
                    it.announceForAccessibility("麦克风按钮已点击")
                    toggleVoiceRecognition()
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceControl", "语音初始化失败", e)
            showToast("语音功能初始化失败")
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
    private fun checkUpdate(silent: Boolean = false) {
        lifecycleScope.launch {
            try {
                val currentVersion = getCurrentVersion()
                Log.d("Update", "当前版本: $currentVersion")
                val serverVersion = updateChecker.getServerVersion()
                Log.d("Update", "服务器版本: $serverVersion")
                val changelog = updateChecker.getChangelog() ?: getString(R.string.default_changelog)
                Log.d("Update", "更新日志: $changelog")
                when {
                    serverVersion == null -> {
                        ttsService?.speak("无法获取更新信息")
                    }
                    VersionComparator.compareVersions(serverVersion, currentVersion) > 0 -> {
                        showUpdateDialog(changelog, silent)
                    }
                    else -> {
                        if (!silent) ttsService?.speak("当前已是最新版本")
                    }
                }
            } catch (e: Exception) {
                ttsService?.speak("更新检查失败，请重试")
            } finally {
                isCheckingUpdate = false // 确保标志重置
            }
        }
    }
    private fun showUpdateDialog(changelog: String, silent: Boolean = false) {
        lastSpeakTime = 0L
        val shortChangelog = if (changelog.length > 100) {
            changelog.take(100) + "...（详细内容请查看弹窗）"
        } else {
            changelog
        }
        announceForAccessibility("发现新版本更新，更新内容：$shortChangelog")
        if (!silent) {
            ttsService?.speak("""
            用户您好，光迹发现新版本，更新内容包括：
            ${shortChangelog.replace("\n", "。")}
            弹窗已打开，请选择立即更新或稍后
        """.trimIndent())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("光迹发现新版本")
            .setMessage(changelog)
            .setPositiveButton("立即更新") { _, _ -> startDownload() }
            .setNegativeButton("稍后") { _, _ -> }
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.announceForAccessibility("更新弹窗已打开，$shortChangelog")
        }
        dialog.show()
    }
    @SuppressLint("NewApi", "Range")
    private fun startDownload() {
        if (!isNetworkAvailable()) {
            ttsService?.speak("当前无网络连接，无法下载更新")
            return
        }
        ttsService?.speak("开始下载更新包，下载过程可能需要几分钟，请保持网络连接")
        val request = DownloadManager.Request(Uri.parse(UPDATE_URL)).apply {
            setTitle("光迹下载更新")
            setDescription("正在下载新版本")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "guide_update.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            allowScanningByMediaScanner()
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        Log.d("DownloadManager", "Download ID: $downloadId")
        lifecycleScope.launch {
            while (true) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val totalSize = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    Log.d("DownloadProgress", "Downloaded: $bytesDownloaded, Total: $totalSize, Status: $status")
                    when (status) {
                        DownloadManager.STATUS_RUNNING -> {
                            if (System.currentTimeMillis() - lastSpeakTime > 5000) {
                                val progress = (bytesDownloaded.toFloat() / totalSize * 100).toInt()
                                // 将进度播报加入队列（非即时）
                                ttsService?.speak("下载进度${progress}%")
                                lastSpeakTime = System.currentTimeMillis()
                            }
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // 立即播报完成通知（插队到队列最前）
                            ttsService?.speak("下载完成，准备安装更新", immediate = true)
                            installApk()
                            break
                        }
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }
    private fun installApk() {
        val apkFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "guide_update.apk"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = contentUri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } else {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        }
        ttsService?.speak("更新包下载完成，请按照提示安装")
    }
    private fun announceForAccessibility(text: String) {
        binding.statusText.post {
            binding.statusText.text = text
            binding.statusText.announceForAccessibility(text)
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
        runOnUiThread {
            location?.let {
                if (!isWeatherButton) {
                    // 仅处理非天气相关的位置更新
                    binding.statusText.text = "定位成功：${it.address}"
                }
            }
        }
    }
    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        runOnUiThread {
            val errorLog = "定位失败 (错误码: $errorCode): ${errorInfo ?: "未知错误"}"
            Log.e("LocationError", errorLog)
            showToast(errorLog)
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
    private fun getCurrentVersion(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e("Version", "获取当前版本失败", e)
            "1.0.0"
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
        checkFallPermissions()
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
            SensorManager.SENSOR_DELAY_UI,  // 改为更适合移动检测的采样率
            100_000  // 最大报告延迟微秒
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
            binding.statusText.announceForAccessibility(binding.statusText.text)
        }
    }
    private val requestCallPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            triggerEmergencyCall()
        } else {
            showToast("需要电话权限进行紧急呼叫")
            ttsService?.speak("未获得电话权限，无法进行紧急呼叫")
        }
    }
    // 保留唯一的权限检查方法
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
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }
    private fun checkFallPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.RECORD_AUDIO // 添加录音权限检查
        )
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }
    }
    // 修改现有的传感器处理
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                fallDetector.onSensorChanged(event)
                handleShakeDetection(event)
                handleMovementDetection(event) // 新增运动检测
            }
            Sensor.TYPE_LIGHT -> handleLightSensor(event)
        }
    }
    // 修改延迟检查方法
    private fun scheduleDelayedUpdateCheck(delay: Long = MOVEMENT_WINDOW + 2000) {
        Log.d("Update", "安排延迟检查，延迟时间: ${delay}ms")
        speechHandler.postDelayed({
            if (!isUserMoving && !isCheckingUpdate) {
                checkAutoUpdate()
            }
        }, delay)
    }
    // 修复摇晃检测
    private fun handleShakeDetection(event: SensorEvent) {
        val acceleration = event.values.let {
            kotlin.math.sqrt(
                it[0].toDouble().pow(2.0) +
                        it[1].toDouble().pow(2.0) +
                        it[2].toDouble().pow(2.0)
            )
        }
        if (acceleration > 15.0 && System.currentTimeMillis() - lastShakeTime > 2000) {
            lastShakeTime = System.currentTimeMillis()
            if (checkVoicePermission()) {
                toggleVoiceRecognition()
            }
        }
        if (acceleration > 25.0 && System.currentTimeMillis() - lastShakeTime > 5000) {
            lastShakeTime = System.currentTimeMillis()
            triggerEmergencyCall()
        }
    }
    private fun toggleVoiceRecognition() {
        if (!isListening) {
            startVoiceRecognition()
        } else {
            stopVoiceRecognition()
        }
    }
    private fun startVoiceRecognition() {
        try {
            if (!speechInitialized) {
                initializeSpeechRecognizer()
                speechHandler.postDelayed({ startVoiceRecognition() }, INIT_DELAY)
                return
            }
            speechHandler.removeCallbacksAndMessages(null)
            speechRecognizer.cancel()
            speechHandler.postDelayed({
                speechRecognizer.startListening(recognizerIntent)
                isListening = true
                binding.statusText.text = "正在聆听..."
                ttsService?.speak("用户您好，语音识别已激活，您请说", immediate = true)
                retryCount = 0
            }, 200)
        } catch (e: Exception) {
            handleRecognitionError(e)
        }
    }
    private fun stopVoiceRecognition() {
        try {
            speechRecognizer.stopListening()
            isListening = false
            binding.statusText.text = "语音识别已停止"
            retryCount = 0
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
            // 优先处理紧急指令
            processed.contains("我需要帮助") || processed.contains("救命") || processed.contains("sos") -> {
                triggerEmergencyCall()
            }
            processed.contains("天气") || processed.contains("今日天气")-> handleWeatherCommand()
            processed.contains("位置") || processed.contains("定位") -> handleLocationCommand()
            processed.contains("更新") || processed.contains("升级") -> handleUpdateCommand()
            processed.contains("帮助") || processed.contains("获取帮助")-> showVoiceHelp() // 普通帮助指令
            processed.contains("设置") || processed.contains("打开设置")-> openSettings()
            processed.contains("退出") || processed.contains("退出应用")-> finish()
            processed.contains("开始检测") || processed.contains("启动检测") -> startDetection()
            processed.contains("暂停检测") || processed.contains("停止检测") -> pauseDetection()
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
    private fun pauseDetection() {
        isDetectionActive = false
        runOnUiThread {
            binding.statusText.text = "障碍物检测已暂停"
            overlayView.visibility = View.INVISIBLE
        }
        ttsService?.speak("已暂停障碍物检测")
    }
    private fun showVoiceHelp() {
        // 更新TTS提示
        ttsService?.speak("""
                    可用语音指令：
                    · 天气 - 获取当前天气
                    · 位置 - 获取当前位置
                    · 更新 - 检查应用更新
                    · 设置 - 打开设置界面
                    · 退出 - 退出应用
                    · 开始检测 - 启动障碍物检测
                    · 暂停检测 - 暂停障碍物检测
                    · 我需要帮助 - 触发紧急求助
        新增障碍物控制指令：可以说开始检测启动识别，说暂停检测停止识别。
        遇到紧急情况时请说'我需要帮助'，系统将自动联系紧急联系人。
    """.trimIndent())
    }
    private fun handleWeatherCommand() {
        ttsService?.speak("正在获取天气信息")
        binding.fabWeather.performClick()
    }
    private fun handleLocationCommand() {
        ttsService?.speak("正在获取位置信息")
        binding.fabLocation.performClick()
    }
    private fun handleUpdateCommand() {
        ttsService?.speak("正在检查更新")
        checkUpdate()
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
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (retryCount < maxRetries) {
                    retryCount++
                    speechHandler.postDelayed({ startVoiceRecognition() }, RETRY_DELAY)
                } else {
                    retryCount = 0
                    isListening = false
                }
            }
        }
    }
    private fun handleRecognitionError(e: Exception) {
        Log.e("Speech", "启动识别失败: ${e.message}")
        showToast("语音识别启动失败，请重试")
        speechInitialized = false
        initializeSpeechRecognizer()
    }
    override fun onDestroy() {
        super.onDestroy()
        // 释放语音资源
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.apply {
                stopListening()
                destroy()
            }
        }
        speechHandler.removeCallbacksAndMessages(null)
        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        locationManager.destroy()
        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        cameraExecutor.shutdown()
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
            binding.statusText.announceForAccessibility(binding.statusText.text)
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
        cameraManager.initializeCamera(binding.previewView.surfaceProvider)
    }
    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            cameraExecutor.submit {
                try {
                    if (!isDetectionActive) {  // 添加状态检查
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
        lifecycleScope.launch {
            try {
                if (abs(lat) < 0.1 || abs(lon) < 0.1) {
                    showToast("无法获取有效位置")
                    return@launch
                }
                val weatherData = weatherManager.getWeather(lat, lon)
                weatherData?.let {
                    val speechText = weatherManager.generateSpeechText(it, cityName)
                    speakWeather(speechText)
                }
            } catch (e: Exception) {
                Log.e("Weather", "获取天气失败", e)
                showToast("天气获取失败")
            }
        }
    }
    override fun speakWeather(message: String) {
        Log.d("Weather", "播报天气: $message")
        if (ttsService == null) {
            Log.w("TTS", "TTS服务未初始化")
            showToast("语音服务不可用")
        } else {
            ttsService?.speak(message)
            Log.d("TTS", "通过TTS播报天气")
        }
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
    private var lastStatusUpdateTime = 0L
    private fun updateStatusUI(results: List<Detection>) {
        if (System.currentTimeMillis() - lastStatusUpdateTime < 500) return
        lastStatusUpdateTime = System.currentTimeMillis()
        val filtered = results.filter {
            (it.categories.maxByOrNull { c -> c.score }?.score
                ?: 0f) >= DetectionProcessor.confidenceThreshold
        }
        runOnUiThread {
            binding.statusText.text = when {
                filtered.isEmpty() -> getString(R.string.no_objects_detected)
                else -> "检测到: ${filtered.joinToString { DetectionProcessor.getInstance(this).getChineseLabel(it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown") }}"
            }
            binding.statusText.announceForAccessibility(binding.statusText.text)
        }
    }
    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}