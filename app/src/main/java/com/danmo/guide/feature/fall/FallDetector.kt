package com.danmo.guide.feature.fall
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.ui.main.MainActivity
import kotlin.math.abs
import kotlin.math.sqrt
class FallDetector(
var locationManager: LocationManager,
private val context: Context,
private val weatherCallback: WeatherCallback, // 添加回调
private val sosNumber: String = "123456789000000"
) : SensorEventListener, LocationManager.LocationCallback { // 添加接口实现
    interface EmergencyCallback {
        fun onEmergencyDetected()
    }
    fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String) {
        weatherCallback.getWeatherAndAnnounce(lat, lon, cityName)
    }
    interface WeatherCallback {
        fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String)
        fun speakWeather(message: String)
    }
    // 状态管理
    companion object {
        @JvmField
        var isFallDetected = false
        internal const val IMPACT_THRESHOLD = 30f // 提高阈值
        internal const val FREE_FALL_THRESHOLD = 2.5f // 提高阈值
        internal const val RECOVERY_TIME = 3000L
        internal const val CONFIRMATION_TIME = 1000L // 新增确认时间
        internal var lastAcceleration = 9.8f
        internal var fallStartTime = 0L
        internal var fallConfirmationCount = 0 // 新增确认计数器
        internal const val MAX_CONFIRMATION_COUNT = 3 // 新增最大确认次数
    }
    // TTS 服务
    var ttsService: TtsService? = null
    set(value) {
        field = value
        Log.d("com.danmo.guide.feature.fall.FallDetector", "TTS service set")
    }
    private var isServiceBound = false
    // 初始化
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var emergencyCallback: EmergencyCallback? = null
    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // 绑定 TTS 服务
        Intent(context, TtsService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }
    fun setEmergencyCallback(callback: EmergencyCallback) {
        this.emergencyCallback = callback
    }
    // 传感器监听
    fun startListening() {
        // 在 FallDetector 的 startListening 方法中添加
        if (accelerometer == null) {
            Toast.makeText(context, "该设备不支持加速度传感器", Toast.LENGTH_SHORT).show()
        }
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
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
    private var vibrator: Vibrator? = null
    private var vibrationHandler = Handler(Looper.getMainLooper())
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startEmergencyVibration() {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.let {
            if (!it.hasVibrator()) return@let
            // 立即开始持续震动（500ms 震动 + 500ms 静音）x 10次 = 10秒
            val pattern = longArrayOf(
                0,  // 立即开始震动
                500,  // 震动持续时间
                500   // 间隔时间
            )
            val effect = VibrationEffect.createWaveform(
                pattern,
                0  // 不重复，执行一次完整模式
            )
            // 循环执行震动模式直到10秒结束
            it.vibrate(effect)
            vibrationHandler.postDelayed({
                it.vibrate(effect)
            }, 1000) // 每隔1秒重复一次模式（500+500=1000ms）
            // 10秒后自动停止（匹配对话框超时时间）
            vibrationHandler.postDelayed({
                stopEmergencyVibration()
            }, 10000)
        }
    }
    private fun stopEmergencyVibration() {
        vibrator?.cancel()
        vibrationHandler.removeCallbacksAndMessages(null)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun detectImpactPhase(currentAccel: Float) {
        val delta = abs(currentAccel - lastAcceleration)
        if (delta > IMPACT_THRESHOLD &&
            System.currentTimeMillis() - fallStartTime < 1000) {
            // 新增确认计数逻辑
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
        if (isFallDetected &&
            System.currentTimeMillis() - fallStartTime > RECOVERY_TIME
        ) {
            resetFallState()
        }
    }
    // 紧急处理
    @RequiresApi(Build.VERSION_CODES.O)
    fun triggerFallDetection() {
        if (!isFallDetected) {
            isFallDetected = true
            startEmergencyVibration()
            speak("监测到手机跌落，您疑似跌倒，是否需要帮助？十秒内无操作将开启紧急呼叫")
            showToast("监测到手机跌落，您疑似跌倒，是否需要帮助？十秒内无操作将开启紧急呼叫")
            showFallConfirmationDialog()
            // 修正这里：传递 FallDetector 作为回调
            locationManager.startLocation(this@FallDetector, isWeatherButton = false)
        }
    }
    // 新增位置回调方法实现
    override fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean) {
        location?.let {
            if (isWeatherButton) {
                weatherCallback.getWeatherAndAnnounce(it.latitude, it.longitude, it.city)
            } else {
                // 紧急情况下播报位置信息
                val address = it.address ?: "未知位置"
                val message = "紧急位置：$address，经度${"%.4f".format(it.longitude)}，纬度${"%.4f".format(it.latitude)}"
                ttsService?.speak(message)
                Log.d("FallDetector", "位置信息已播报")
            }
        }
    }
    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        // 处理定位失败逻辑
        Log.e("FallDetector", "定位失败：$errorCode - $errorInfo")
    }
    fun isFallDetected() = isFallDetected
    fun triggerEmergencyCall() {
        if (checkCallPermission()) {
            startEmergencyCall()
        } else {
            showPermissionWarning()
        }
    }
    // 在 FallDetector.kt 中的 startEmergencyCall 方法中添加如下代码
    private fun startEmergencyCall() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // 调用紧急呼叫相关逻辑
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sosNumber"))
            context.startActivity(intent)
        } else {
            requestPhonePermissions()
        }
    }

    private fun requestPhonePermissions() {
        if (context is MainActivity) {
            (context as MainActivity).requestPhonePermissions.launch(
                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
                )
            )
        }
    }
    // 工具方法
    fun resetFallState() {
        fallStartTime = 0L
        isFallDetected = false
        fallConfirmationCount = 0 // 重置确认计数
    }
    private fun checkCallPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
    }

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
        override fun onServiceConnected(className: android.content.ComponentName, service: IBinder) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            Log.d("com.danmo.guide.feature.fall.FallDetector", "TTS service connected")
        }
        override fun onServiceDisconnected(arg0: android.content.ComponentName) {
            ttsService = null
            Log.d("com.danmo.guide.feature.fall.FallDetector", "TTS service disconnected")
        }
    }
    private fun showFallConfirmationDialog() {
        // 创建并显示确认对话框
        val builder = AlertDialog.Builder(context)
        builder.setTitle("紧急情况确认")
        builder.setMessage("监测到手机跌落，您疑似跌倒，是否需要帮助？")
        builder.setPositiveButton("是") { _, _ ->
            // 用户确认需要帮助，触发紧急呼叫
            triggerEmergencyCall()
        }
        builder.setNegativeButton("否") { _, _ ->
            // 用户拒绝帮助，重置状态
            resetFallState()
            speak("已取消紧急呼叫") // 调用 TTS 提示
            Toast.makeText(context, "已取消紧急呼叫", Toast.LENGTH_SHORT).show() // 调用 Toast 提示
            stopEmergencyVibration()
        }
        builder.setCancelable(false) // 禁止点击外部取消对话框
        val dialog = builder.create()
        dialog.show()
        // 添加倒计时逻辑
        val handler = Handler()
        val runnable = Runnable {
            if (dialog.isShowing) {
                // 如果对话框还在显示，自动触发紧急呼叫
                triggerEmergencyCall()
                speak("用户未确认安全,自动紧急呼叫中") // 调用 TTS 提示
                dialog.dismiss()
            }
        }
        // 10秒后执行自动触发逻辑
        handler.postDelayed(runnable, 10000)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}