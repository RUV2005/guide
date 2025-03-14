package com.danmo.guide.feature.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class VibrationManager(context: Context) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    companion object {
        @Volatile
        private var instance: VibrationManager? = null

        fun getInstance(context: Context): VibrationManager {
            return instance ?: synchronized(this) {
                instance ?: VibrationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun vibrate(pattern: LongArray) {
        vibrator?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION") it.vibrate(pattern, -1)
                }
            } catch (e: Exception) {
                Log.e("Vibration", "振动失败: ${e.message}")
            }
        } ?: Log.d("Vibration", "设备不支持振动功能")
    }

    fun cancel() {
        vibrator?.cancel()
    }
} 