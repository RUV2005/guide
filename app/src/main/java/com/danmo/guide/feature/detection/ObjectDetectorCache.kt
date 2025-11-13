package com.danmo.guide.feature.detection

import android.content.Context
import android.os.Build
import android.util.Log
import com.danmo.guide.core.device.DeviceCapability
import com.danmo.guide.feature.powermode.PowerMode
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

object ObjectDetectorCache {
    private val cache = mutableMapOf<Int, ObjectDetector>()
    private var deviceInfo: DeviceCapability.DeviceInfo? = null

    /**
     * 初始化设备信息（应在应用启动时调用）
     */
    fun initDeviceInfo(context: Context) {
        if (deviceInfo == null) {
            deviceInfo = DeviceCapability.detect(context)
        }
    }

    fun get(context: Context, mode: PowerMode = PowerMode.BALANCED): ObjectDetector {
        // 确保设备信息已初始化
        if (deviceInfo == null) {
            initDeviceInfo(context)
        }
        
        val key = mode.ordinal
        return cache[key] ?: synchronized(this) {
            cache[key] ?: create(context, mode).also { cache[key] = it }
        }
    }

    private fun create(context: Context, mode: PowerMode): ObjectDetector {
        val info = deviceInfo ?: DeviceCapability.detect(context)
        
        // 根据功耗模式和设备性能确定线程数
        val threads = when (mode) {
            PowerMode.LOW_POWER -> 1
            PowerMode.BALANCED -> info.recommendedThreads.coerceAtMost(2)
            PowerMode.HIGH_ACCURACY -> info.recommendedThreads
        }
        
        // 根据设备性能和功耗模式选择delegate
        val delegateType = when (mode) {
            PowerMode.LOW_POWER -> {
                // 低功耗模式：优先NNAPI（省电），否则CPU
                if (info.hasNnapi) DeviceCapability.DelegateType.NNAPI else DeviceCapability.DelegateType.CPU
            }
            PowerMode.BALANCED -> {
                // 平衡模式：根据设备性能选择
                DeviceCapability.getRecommendedDelegate(info.tier, info.hasGpu, info.hasNnapi)
            }
            PowerMode.HIGH_ACCURACY -> {
                // 高精度模式：优先GPU（性能），其次NNAPI
                if (info.hasGpu) DeviceCapability.DelegateType.GPU 
                else if (info.hasNnapi) DeviceCapability.DelegateType.NNAPI 
                else DeviceCapability.DelegateType.CPU
            }
        }
        
        val base = BaseOptions.builder().apply {
            setNumThreads(threads)
            
            // 根据delegate类型配置
            try {
                when (delegateType) {
                    DeviceCapability.DelegateType.GPU -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            useGpu()
                            Log.d("ObjectDetectorCache", "使用GPU delegate，线程数: $threads")
                        } else {
                            Log.w("ObjectDetectorCache", "Android版本过低，无法使用GPU delegate，降级为CPU")
                        }
                    }
                    DeviceCapability.DelegateType.NNAPI -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            useNnapi()
                            Log.d("ObjectDetectorCache", "使用NNAPI delegate，线程数: $threads")
                        } else {
                            Log.w("ObjectDetectorCache", "Android版本过低，无法使用NNAPI delegate，降级为CPU")
                        }
                    }
                    DeviceCapability.DelegateType.CPU -> {
                        Log.d("ObjectDetectorCache", "使用CPU delegate，线程数: $threads")
                    }
                }
            } catch (e: Exception) {
                Log.e("ObjectDetectorCache", "配置delegate失败，降级为CPU", e)
            }
        }.build()

        return ObjectDetector.createFromFileAndOptions(
            context,
            ObjectDetectorHelper.MODEL_FILE,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setMaxResults(5)
                .setScoreThreshold(ObjectDetectorHelper.confidenceThreshold)
                .build()
        )
    }
    
    /**
     * 清理缓存（在内存紧张时调用）
     */
    fun clearCache() {
        synchronized(this) {
            cache.values.forEach { it.close() }
            cache.clear()
        }
    }
}