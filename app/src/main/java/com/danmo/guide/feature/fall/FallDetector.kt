package com.danmo.guide.feature.fall

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.amap.api.location.AMapLocation
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.ui.main.MainActivity
import kotlin.math.*

/**
 * 方案 B Final：姿态 + 高度融合跌倒检测（防抖 + 基线漂移修正）
 * 兼容 API 24
 */
class FallDetector(
    var locationManager: LocationManager,
    private val context: Context,
    private val sosNumber: String = "123456789000000"
) : SensorEventListener, LocationManager.LocationCallback {

    interface EmergencyCallback {
        fun onEmergencyDetected()
    }

    interface WeatherCallback {
        fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String)
        fun speakWeather(message: String)
    }

    companion object {
        @JvmField
        var isFallDetected = false
        private const val TAG = "FallDetectorB"

        /* ========== 可调参数 ========== */
        private const val IMPACT_THRESHOLD = 50f
        private const val TILT_DOWN_THRESHOLD = 70f
        private const val TILT_UP_THRESHOLD = 45f
        private const val HEIGHT_DROP_THRESHOLD = -0.35f
        private const val STILL_TIMEOUT_MS = 2000L
        private const val PRESSURE_TO_HEIGHT = -8.5f
        private const val FALL_PROMPT = "监测到疑似跌倒，是否需要帮助？十秒内无操作将开启紧急呼叫"
        private const val PRESSURE_RESET_INTERVAL = 30 * 60 * 1000L // 30 min
    }
    private var isServiceBound = false

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var gyroscope: Sensor? = null

    var ttsService: TtsService? = null
    private var emergencyCallback: EmergencyCallback? = null

    private var lastAcceleration = 9.8f
    private var lastTilt = 0f
    private var lastMovementTime = 0L
    private var pressureBase = Float.NaN
    private var lastPressureReset = 0L

    private var markTilt = false
    private var markHeight = false
    private var markShock = false

    private var vibrator: Vibrator? = null
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    /* ========== 生命周期 ========== */
    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val intent = Intent(context, TtsService::class.java)
        isServiceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun setEmergencyCallback(callback: EmergencyCallback) {
        this.emergencyCallback = callback
    }

    fun startListening() {
        if (accelerometer == null || pressureSensor == null) {
            Toast.makeText(context, "设备缺少必要传感器", Toast.LENGTH_SHORT).show()
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        debounceHandler.removeCallbacksAndMessages(null)

        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // 防止重复解绑或异常
                Log.w(TAG, "Service not registered", e)
            }
            isServiceBound = false
        }
    }

    /* ========== SensorEventListener ========== */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                handleAccelerometer(event)
                lastMovementTime = System.currentTimeMillis()
            }
            Sensor.TYPE_PRESSURE -> handlePressure(event)
            Sensor.TYPE_GYROSCOPE -> lastMovementTime = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ========== 姿态检测 ========== */
    private fun handleAccelerometer(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z)
        val tilt = acos(z / g).toFloat() * 180f / PI.toFloat()

        if (lastTilt < TILT_UP_THRESHOLD && tilt > TILT_DOWN_THRESHOLD) markTilt = true
        lastTilt = tilt

        val delta = abs(g - lastAcceleration)
        if (delta > IMPACT_THRESHOLD) markShock = true
        lastAcceleration = g

        evaluateFall()
    }

    /* ========== 高度检测 + 基线漂移修正 ========== */
    private fun handlePressure(event: SensorEvent) {
        val pressure = event.values[0]
        val now = System.currentTimeMillis()
        if (pressureBase.isNaN() || now - lastPressureReset > PRESSURE_RESET_INTERVAL) {
            pressureBase = pressure
            lastPressureReset = now
            return
        }
        val deltaH = (pressure - pressureBase) * PRESSURE_TO_HEIGHT
        if (deltaH < HEIGHT_DROP_THRESHOLD) markHeight = true
    }

    /* ========== 防抖综合判定 ========== */
    private fun evaluateFall() {
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            if (markTilt && markHeight && markShock && isUserStill()) {
                triggerFallDetection()
            }
        }
        debounceHandler.postDelayed(debounceRunnable!!, 500)
    }

    private fun isUserStill(): Boolean =
        System.currentTimeMillis() - lastMovementTime > STILL_TIMEOUT_MS

    /* ========== 触发逻辑（兼容 API 24） ========== */
    private fun triggerFallDetection() {
        if (isFallDetected) return
        isFallDetected = true

        startEmergencyVibrationCompat()
        speak(FALL_PROMPT)
        showToast(FALL_PROMPT)
        showFallConfirmationDialog()
        locationManager.startLocation(this, isWeatherButton = false)
    }

    /* ========== 振动兼容 API 24-25 ========== */
    @Suppress("DEPRECATION")
    private fun startEmergencyVibrationCompat() {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 500, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            vibrator?.vibrate(effect)
        } else {
            vibrator?.vibrate(pattern, 0)
        }
        Handler(Looper.getMainLooper()).postDelayed({ vibrator?.cancel() }, 10000)
    }

    /* ========== 其他接口（保持原样） ========== */
    override fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean) {
        location?.let {
            val address = it.address ?: "未知位置"
            val msg = "紧急位置：$address，经度${"%.4f".format(it.longitude)}，纬度${"%.4f".format(it.latitude)}"
            ttsService?.speak(msg)
        }
    }

    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        Log.e(TAG, "定位失败：$errorCode - $errorInfo")
    }

    fun triggerEmergencyCall() {
        if (checkCallPermission()) startEmergencyCall() else showPermissionWarning()
    }

    private fun startEmergencyCall() {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(Intent.ACTION_CALL, "tel:$sosNumber".toUri())
            context.startActivity(intent)
        } else {
            if (context is MainActivity) context.requestPhonePermissions.launch(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            )
        }
    }

    fun resetFallState() {
        isFallDetected = false
        markTilt = false
        markHeight = false
        markShock = false
        pressureBase = Float.NaN
        lastPressureReset = 0L
    }

    private fun checkCallPermission() =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionWarning() {
        Toast.makeText(context, "需要电话权限才能自动呼叫", Toast.LENGTH_LONG).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun speak(message: String) {
        ttsService?.speak(message)
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
            ttsService = (service as TtsService.TtsBinder).getService()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            ttsService = null
            isServiceBound = false
        }
    }

    private fun showFallConfirmationDialog() {
        val builder = AlertDialog.Builder(context)
            .setTitle("紧急情况确认")
            .setMessage("监测到疑似跌倒，是否需要帮助？")
            .setPositiveButton("是") { _, _ -> triggerEmergencyCall() }
            .setNegativeButton("否") { _, _ ->
                resetFallState()
                speak("已取消紧急呼叫")
                vibrator?.cancel()
            }
            .setCancelable(false)
            .create()
        builder.show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (builder.isShowing) {
                triggerEmergencyCall()
                speak("用户未确认安全，自动紧急呼叫中")
                builder.dismiss()
            }
        }, 10000)
    }
}