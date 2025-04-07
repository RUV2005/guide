package com.danmo.guide.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
import com.danmo.guide.ui.guide.GuideActivity
import com.danmo.guide.ui.settings.SettingsActivity
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    SensorEventListener, LocationManager.LocationCallback {

    // 基础功能模块
    private var locationManager: LocationManager? = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraManager: CameraManager
    private lateinit var overlayView: OverlayView
    private lateinit var weatherManager: WeatherManager
    private var isWeatherAnnounced = false

    // 跌落检测模块
    private lateinit var fallDetector: FallDetector
    private lateinit var sensorManager: SensorManager

    // TTS 服务
    private var ttsService: TtsService? = null
    private var isTtsBound = false

    // 环境光传感器的上次值
    private var lastLightValue = 0f

    // 误报处理计数器
    private var falseAlarmCancelCount = 0

    // 权限请求
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.all { it.value }
            if (!allGranted) showToast("部分功能可能受限")
        }

    // 服务连接
    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            Log.d("MainActivity", "TTS服务已连接")
            // 将 TTS 服务传递给 com.danmo.guide.feature.fall.FallDetector
            fallDetector.ttsService = ttsService
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

        // 检查是否是首次启动
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)

        if (isFirstLaunch) {
            // 启动引导页
            val intent = Intent(this, GuideActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        // 初始化基础模块
        initBasicComponents()

        // 初始化跌倒检测
        initFallDetection()

        // 绑定TTS服务
        bindTtsService()

        // 初始化定位管理器
        locationManager = LocationManager.instance!!
        locationManager!!.initialize(this)

        // 设置定位测试按钮点击事件
        binding.fabLocation.setOnClickListener {
            startLocationDetection()
        }

    }

    // 在 startLocationDetection 方法中添加日志
    private fun startLocationDetection() {
        Log.d("LocationFlow", "尝试启动定位...")
        if (checkLocationPermission()) {
            Log.d("LocationFlow", "权限已授予，开始定位")
            locationManager?.startLocation(this)
        } else {
            Log.w("LocationFlow", "缺少定位权限，正在请求权限")
            requestLocationPermission()
        }
    }


    // 修改后的 onLocationSuccess 方法
    // 修改后的 onLocationSuccess 方法
// 修改后的 onLocationSuccess 方法
    override fun onLocationSuccess(location: AMapLocation?) {
        runOnUiThread {
            location?.let {
                // 添加定位精度和来源信息
                val logInfo = """
                AMapLocation 定位成功: 
                经度 = ${it.longitude}
                纬度 = ${it.latitude}
                精度 = ${it.accuracy}米
                定位来源 = ${getLocationTypeString(it.locationType)}
                地址 = ${it.address}
                城市 = ${it.city}
            """.trimIndent()

                // 记录详细定位日志
                Log.i("LocationSuccess", logInfo)

                // 新增TTS播报（添加精度信息）
                val ttsMessage = """
                当前位置：${it.address}
                定位精度：${it.accuracy}米
                经度：${it.longitude.toFloat()}
                纬度：${it.latitude.toFloat()}
            """.trimIndent()

                ttsService?.speak(ttsMessage) ?: run {
                    Log.w("TTS", "TTS服务未初始化")
                    showToast("语音服务不可用")
                }
            } ?: run {
                Log.w("LocationSuccess", "定位结果为空")
                showToast("定位失败：无定位信息")
            }
        }
    }


    // 新增定位来源类型转换方法
    private fun getLocationTypeString(locationType: Int): String {
        return when (locationType) {
            AMapLocation.LOCATION_TYPE_GPS -> "GPS定位"
            AMapLocation.LOCATION_TYPE_SAME_REQ -> "前次定位"
            AMapLocation.LOCATION_TYPE_FIX_CACHE -> "缓存定位"
            AMapLocation.LOCATION_TYPE_WIFI -> "WIFI定位"
            AMapLocation.LOCATION_TYPE_CELL -> "基站定位"
            AMapLocation.LOCATION_TYPE_AMAP -> "高精度定位"
            else -> "未知来源"
        }
    }

    // 修改后的 onLocationFailure 方法
    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        runOnUiThread {
            // 构建错误日志
            val errorLog = "定位失败 (错误码: $errorCode): ${errorInfo ?: "未知错误"}"

            // 输出到 Logcat
            Log.e("LocationError", errorLog)

            // 原有 Toast 提示
            showToast(errorLog)
        }
    }

    // 权限检查方法
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

    // 处理权限请求结果
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                locationManager?.handlePermissionsResult(requestCode, grantResults)
            }
            // 添加处理 READ_PHONE_STATE 权限的逻辑
            else -> {
                if (requestCode == REQUEST_FALL_PERMISSIONS) {
                    if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                        // 权限已授予，重新初始化跌倒检测
                        initFallDetection()
                    } else {
                        showToast("部分功能可能受限")
                    }
                }
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val REQUEST_FALL_PERMISSIONS = 1002 // 添加这个常量
    }


    private fun initBasicComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView
        objectDetectorHelper = ObjectDetectorHelper(this)
        feedbackManager = FeedbackManager(this)
        cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
        weatherManager = WeatherManager(this)

        checkCameraPermission()
        // 初始化天气按钮点击事件
        binding.fabWeather.setOnClickListener {
            getWeatherAndAnnounce()
        }

        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 初始化定位管理器
        locationManager = LocationManager.instance!!
        locationManager!!.initialize(this)
        locationManager!!.callback = this
    }

    private fun initFallDetection() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 初始化 FallDetector
        fallDetector = locationManager?.let {
            FallDetector(
                context = this,
                locationManager = it,
                sosNumber = "123456789000000"
            )
        }!!

        fallDetector.init(this)
        fallDetector.setEmergencyCallback(this)
        fallDetector.locationManager = locationManager as LocationManager

        checkFallPermissions() // 确保在初始化后检查权限
    }

    private fun bindTtsService() {
        Intent(this, TtsService::class.java).also { intent ->
            bindService(intent, ttsConnection, BIND_AUTO_CREATE)
            isTtsBound = true
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动 com.danmo.guide.feature.fall.FallDetector 的传感器监听
        fallDetector.startListening()
    }

    override fun onPause() {
        super.onPause()
        // 停止定位服务
        locationManager?.stopLocation()
        // 停止 com.danmo.guide.feature.fall.FallDetector 的传感器监听
        fallDetector.stopListening()
        feedbackManager.shutdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 销毁定位资源
        locationManager?.destroy()

        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        if (::cameraExecutor.isInitialized) {  // 检查初始化状态
            cameraExecutor.shutdown()
        }
    }

    // region 跌倒检测回调
    override fun onEmergencyDetected() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.fall_detected_warning)
            ttsService?.speak(getString(R.string.fall_alert_voice))
            triggerEmergencyCall()
        }
    }

    private fun triggerEmergencyCall() {
        if (checkCallPermission() && checkReadPhoneStatePermission()) { // 添加 READ_PHONE_STATE 权限检查
            fallDetector.triggerEmergencyCall()
        } else {
            showToast(getString(R.string.permission_required))
        }
    }

    private fun checkReadPhoneStatePermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    // endregion

    // region 权限管理
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
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.BODY_SENSORS
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkCallPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
    // endregion

    // region 传感器数据处理
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> fallDetector.onSensorChanged(event)
            Sensor.TYPE_LIGHT -> handleLightSensor(event)
        }
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
    // endregion

    // region 误报处理
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
    // endregion

    // region 摄像头相关
    private fun startCamera() {
        cameraManager.initializeCamera(binding.previewView.surfaceProvider)
    }

    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            cameraExecutor.submit {
                try {
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
    // endregion

    // region 通用工具方法
    private fun getWeatherAndAnnounce() {
        lifecycleScope.launch {
            if (isWeatherAnnounced) return@launch
            isWeatherAnnounced = true

            weatherManager.getLocationByIP()?.let { location ->
                weatherManager.getWeather(location.latitude, location.longitude)?.let { data ->
                    FeedbackManager.getInstance(this@MainActivity)
                        .enqueueWeatherAnnouncement(
                            weatherManager.generateSpeechText(data)
                        )
                }
            } ?: showToast(getString(R.string.location_failed))
        }
    }

    private fun handleDetectionResults(results: List<Detection>) {
        results.maxByOrNull { it.categories.firstOrNull()?.score ?: 0f }?.let {
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
                else -> "检测到: ${
                    filtered.joinToString {
                        DetectionProcessor.getInstance(this).getChineseLabel(
                            it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown"
                        )
                    }
                }"
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
    // endregion
}