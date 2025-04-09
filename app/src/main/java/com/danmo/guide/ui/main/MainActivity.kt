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
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    SensorEventListener, LocationManager.LocationCallback, FallDetector.WeatherCallback {

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
                if (isWeatherButton) {
                    fallDetector.getWeatherAndAnnounce(it.latitude, it.longitude, it.city)
                } else {
                    val ttsMessage = """
                    当前位置：${it.address}
                    经度：${it.longitude}
                    纬度：${it.latitude}
                """.trimIndent()
                    ttsService?.speak(ttsMessage) ?: run {
                        Log.w("TTS", "TTS服务未初始化")
                        showToast("语音服务不可用")
                    }
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
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
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
    }

    override fun onPause() {
        super.onPause()
        locationManager.stopLocation()
        fallDetector.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.destroy()

        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        cameraExecutor.shutdown()
    }

    override fun onEmergencyDetected() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.fall_detected_warning)
            ttsService?.speak(getString(R.string.fall_alert_voice))
            triggerEmergencyCall()

            // 通知读屏器紧急状态已触发
            binding.statusText.announceForAccessibility(binding.statusText.text)
        }
    }

    private fun triggerEmergencyCall() {
        if (checkCallPermission()) {
            fallDetector.triggerEmergencyCall()
        } else {
            showToast(getString(R.string.permission_required))
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

            // 通知读屏器光线状态已更新
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
        Log.d("Weather", "播报天气: $message") // 添加日志
        // 强制通过 TTS 播报
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
            .filter { it.categories.isNotEmpty() } // 确保 categories 不为空
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
                filtered.isEmpty() == true -> getString(R.string.no_objects_detected)
                else -> "检测到: ${filtered.joinToString { DetectionProcessor.getInstance(this).getChineseLabel(it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown") }}"
            }

            // 通知读屏器内容已更新
            binding.statusText.announceForAccessibility(binding.statusText.text)
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}