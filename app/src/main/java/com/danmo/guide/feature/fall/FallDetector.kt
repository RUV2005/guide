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
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.amap.api.location.AMapLocation
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.ui.main.MainActivity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 跌倒检测器类，用于通过加速度传感器检测跌倒事件，并在检测到跌倒时触发紧急处理逻辑。
 *
 * @param locationManager 用于获取位置信息的 LocationManager 实例
 * @param context 应用程序上下文
 * @param weatherCallback 用于天气回调的接口实例
 * @param sosNumber 紧急呼叫号码，默认为 "123456789000000"
 */
class FallDetector(
    var locationManager: LocationManager,
    private val context: Context,
    private val weatherCallback: WeatherCallback,
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
        internal const val IMPACT_THRESHOLD = 30f
        internal const val FREE_FALL_THRESHOLD = 6f
        internal const val RECOVERY_TIME = 3000L
        internal const val CONFIRMATION_TIME = 1000L
        internal var lastAcceleration = 9.8f
        internal var fallStartTime = 0L
        internal var fallConfirmationCount = 0
        internal const val MAX_CONFIRMATION_COUNT = 3

        /* ========== 新增：消除硬编码字符串 ========== */
        private const val FALL_PROMPT =
            "监测到手机跌落，您疑似跌倒，是否需要帮助？十秒内无操作将开启紧急呼叫"
    }

    var ttsService: TtsService? = null
        set(value) {
            field = value
            Log.d("FallDetector", "TTS service set")
        }

    private var isServiceBound = false
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var emergencyCallback: EmergencyCallback? = null

    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Intent(context, TtsService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }

    fun setEmergencyCallback(callback: EmergencyCallback) {
        this.emergencyCallback = callback
    }

    fun startListening() {
        if (accelerometer == null) {
            Toast.makeText(context, "该设备不支持加速度传感器", Toast.LENGTH_SHORT).show()
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
        }
        ttsService = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val (x, y, z) = event.values
            val acceleration = sqrt(x * x + y * y + z * z.toDouble()).toFloat()
            detectFreeFallPhase(acceleration)
            detectImpactPhase(acceleration)
            detectRecoveryPhase()
        }
    }

    private fun detectFreeFallPhase(currentAccel: Float) {
        if (currentAccel < FREE_FALL_THRESHOLD && fallStartTime == 0L) {
            fallStartTime = System.currentTimeMillis()
        }
    }

    /* ========== 1. 使用 Vibrator::class.java 消除 VIBRATOR_SERVICE 警告 ========== */
    private var vibrator: Vibrator? = null
    private var vibrationHandler = Handler(Looper.getMainLooper())   // 2. 消除 Handler() 警告

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startEmergencyVibration() {
        vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.let {
            if (!it.hasVibrator()) return@let
            val pattern = longArrayOf(0, 500, 500)
            val effect = VibrationEffect.createWaveform(pattern, 0)
            it.vibrate(effect)
            vibrationHandler.postDelayed({ it.vibrate(effect) }, 1000)
            vibrationHandler.postDelayed({ stopEmergencyVibration() }, 10000)
        }
    }

    private fun stopEmergencyVibration() {
        vibrator?.cancel()
        vibrationHandler.removeCallbacksAndMessages(null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun detectImpactPhase(currentAccel: Float) {
        val delta = abs(currentAccel - lastAcceleration)
        if (delta > IMPACT_THRESHOLD && System.currentTimeMillis() - fallStartTime < 1000) {
            if (System.currentTimeMillis() - fallStartTime < CONFIRMATION_TIME) {
                fallConfirmationCount++
                if (fallConfirmationCount >= MAX_CONFIRMATION_COUNT) {
                    triggerFallDetection()
                }
            }
        }
        lastAcceleration = currentAccel
    }

    private fun detectRecoveryPhase() {
        if (isFallDetected && System.currentTimeMillis() - fallStartTime > RECOVERY_TIME) {
            resetFallState()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun triggerFallDetection() {
        if (!isFallDetected) {
            isFallDetected = true
            startEmergencyVibration()
            /* ========== 3. 使用常量 FALL_PROMPT 消除固定字符串警告 ========== */
            speak(FALL_PROMPT)
            showToast(FALL_PROMPT)
            showFallConfirmationDialog()
            locationManager.startLocation(this, isWeatherButton = false)
        }
    }

    override fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean) {
        location?.let {
            if (isWeatherButton) {
                weatherCallback.getWeatherAndAnnounce(it.latitude, it.longitude, it.city)
            } else {
                val address = it.address ?: "未知位置"
                val message = "紧急位置：$address，经度${"%.4f".format(it.longitude)}，纬度${"%.4f".format(it.latitude)}"
                ttsService?.speak(message)
                Log.d("FallDetector", "位置信息已播报")
            }
        }
    }

    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        Log.e("FallDetector", "定位失败：$errorCode - $errorInfo")
    }

    fun isFallDetected() = isFallDetected

    fun triggerEmergencyCall() {
        if (checkCallPermission()) startEmergencyCall() else showPermissionWarning()
    }

    private fun startEmergencyCall() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL, "tel:$sosNumber".toUri())
            context.startActivity(intent)
        } else {
            requestPhonePermissions()
        }
    }

    private fun requestPhonePermissions() {
        if (context is MainActivity) {
            context.requestPhonePermissions.launch(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            )
        }
    }

    fun resetFallState() {
        fallStartTime = 0L
        isFallDetected = false
        fallConfirmationCount = 0
    }

    private fun checkCallPermission() =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionWarning() {
        Toast.makeText(context, "需要电话权限才能自动呼叫", Toast.LENGTH_LONG).show()
    }

    @Suppress("SameParameterValue")
    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun speak(message: String) {
        ttsService?.speak(message)
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
            ttsService = (service as TtsService.TtsBinder).getService()
            Log.d("FallDetector", "TTS service connected")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            ttsService = null
            Log.d("FallDetector", "TTS service disconnected")
        }
    }

    private fun showFallConfirmationDialog() {
        val builder = AlertDialog.Builder(context)
            .setTitle("紧急情况确认")
            .setMessage("监测到手机跌落，您疑似跌倒，是否需要帮助？")
            .setPositiveButton("是") { _, _ -> triggerEmergencyCall() }
            .setNegativeButton("否") { _, _ ->
                resetFallState()
                speak("已取消紧急呼叫")
                Toast.makeText(context, "已取消紧急呼叫", Toast.LENGTH_SHORT).show()
                stopEmergencyVibration()
            }
            .setCancelable(false)
            .create()
        builder.show()

        // 10 秒后自动触发
        Handler(Looper.getMainLooper()).postDelayed({
            if (builder.isShowing) {
                triggerEmergencyCall()
                speak("用户未确认安全，自动紧急呼叫中")
                builder.dismiss()
            }
        }, 10000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}