package com.danmo.guide.feature.feedback
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
/**
 * 振动反馈管理器，封装不同Android版本的振动功能
 * Vibration feedback manager encapsulating vibration features across Android versions
 */
class VibrationManager(context: Context) {
    // 延迟初始化振动器实例（兼容不同Android版本） / Lazily initialized vibrator (cross-version compatible)
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 使用VibratorManager / For Android 12+ use VibratorManager
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator // 旧版本直接获取振动器 / Legacy vibrator access
        }
    }
    companion object {
        @Volatile
        private var instance: VibrationManager? = null // 单例实例 / Singleton instance
        /**
         * 获取单例实例
         * Get singleton instance
         * @param context 上下文用于获取系统服务 / Context for system services
         */
        fun getInstance(context: Context): VibrationManager {
            return instance ?: synchronized(this) {
                instance ?: VibrationManager(context.applicationContext).also { instance = it }
            }
        }
    }
    /**
     * 执行振动模式
     * Execute vibration pattern
     * @param pattern 振动模式数组（[等待时间，振动时间，等待时间...]） / Pattern array ([wait, vibrate, wait...])
     */
    fun vibrate(pattern: LongArray) {
        vibrator?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0+ 使用振动效果API / Use VibrationEffect API for Android 8.0+
                    it.vibrate(VibrationEffect.createWaveform(pattern, -1)) // -1表示不重复 / -1 for no repeat
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, -1) // 旧版本振动方法 / Legacy vibration method
                }
            } catch (e: Exception) {
                Log.e("Vibration", "振动失败 Vibration failed: ${e.message}")
            }
        } ?: Log.d("Vibration", "设备不支持振动功能 No vibration capability")
    }
}