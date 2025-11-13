package com.danmo.guide.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.roundToInt

/**
 * 设备性能检测工具
 * 根据CPU核心数、内存、GPU等信息自动识别设备性能等级
 */
object DeviceCapability {
    
    enum class PerformanceTier {
        LOW,      // 低端机：2-4核，<3GB内存
        MEDIUM,   // 中端机：4-6核，3-6GB内存
        HIGH      // 高端机：6+核，>6GB内存
    }
    
    data class DeviceInfo(
        val tier: PerformanceTier,
        val cpuCores: Int,
        val totalMemoryMB: Int,
        val availableMemoryMB: Int,
        val isLowRamDevice: Boolean,
        val hasGpu: Boolean,
        val hasNnapi: Boolean,
        val recommendedThreads: Int,
        val recommendedFps: Int,
        val recommendedResolution: String
    )
    
    /**
     * 检测设备性能等级
     */
    fun detect(context: Context): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        // 获取CPU核心数
        val cpuCores = getCpuCoreCount()
        
        // 获取内存信息
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMemoryMB = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availableMemoryMB = (memInfo.availMem / (1024 * 1024)).toInt()
        // 检查是否为低内存设备：使用 lowMemory 属性（API 16+）或 isLowRamDevice 方法（API 19+）
        val isLowRamDevice = memInfo.lowMemory || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && activityManager.isLowRamDevice)
        
        // 检测GPU支持（通过检查OpenGL ES版本）
        val hasGpu = checkGpuSupport()
        
        // 检测NNAPI支持（Android 8.0+）
        val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        
        // 根据硬件信息确定性能等级
        val tier = determineTier(cpuCores, totalMemoryMB, isLowRamDevice)
        
        // 根据性能等级推荐配置
        val (threads, fps, resolution) = getRecommendedConfig(tier, hasGpu, hasNnapi)
        
        val info = DeviceInfo(
            tier = tier,
            cpuCores = cpuCores,
            totalMemoryMB = totalMemoryMB,
            availableMemoryMB = availableMemoryMB,
            isLowRamDevice = isLowRamDevice,
            hasGpu = hasGpu,
            hasNnapi = hasNnapi,
            recommendedThreads = threads,
            recommendedFps = fps,
            recommendedResolution = resolution
        )
        
        Log.d("DeviceCapability", "设备检测完成: $info")
        return info
    }
    
    /**
     * 获取CPU核心数
     */
    private fun getCpuCoreCount(): Int {
        return try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Log.w("DeviceCapability", "无法获取CPU核心数", e)
            4 // 默认值
        }
    }
    
    /**
     * 检测GPU支持
     */
    private fun checkGpuSupport(): Boolean {
        return try {
            // 简单检测：检查是否支持OpenGL ES 3.0+
            // 实际GPU delegate支持需要运行时检测
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 根据硬件信息确定性能等级
     */
    private fun determineTier(cpuCores: Int, totalMemoryMB: Int, isLowRamDevice: Boolean): PerformanceTier {
        return when {
            isLowRamDevice || totalMemoryMB < 3000 || cpuCores <= 4 -> PerformanceTier.LOW
            totalMemoryMB >= 6000 && cpuCores >= 6 -> PerformanceTier.HIGH
            else -> PerformanceTier.MEDIUM
        }
    }
    
    /**
     * 根据性能等级和硬件能力推荐配置
     */
    private fun getRecommendedConfig(
        tier: PerformanceTier,
        hasGpu: Boolean,
        hasNnapi: Boolean
    ): Triple<Int, Int, String> {
        return when (tier) {
            PerformanceTier.LOW -> {
                // 低端机：单线程，低帧率，低分辨率
                Triple(1, 2, "320x240")
            }
            PerformanceTier.MEDIUM -> {
                // 中端机：2线程，中等帧率，中等分辨率
                Triple(2, 6, "480x360")
            }
            PerformanceTier.HIGH -> {
                // 高端机：4线程，高帧率，高分辨率
                Triple(4, 12, "640x480")
            }
        }
    }
    
    /**
     * 获取推荐的TensorFlow Lite delegate类型
     */
    fun getRecommendedDelegate(tier: PerformanceTier, hasGpu: Boolean, hasNnapi: Boolean): DelegateType {
        return when (tier) {
            PerformanceTier.LOW -> {
                // 低端机：优先使用NNAPI（如果可用），否则CPU
                if (hasNnapi) DelegateType.NNAPI else DelegateType.CPU
            }
            PerformanceTier.MEDIUM -> {
                // 中端机：尝试GPU，失败则NNAPI，最后CPU
                if (hasGpu) DelegateType.GPU else if (hasNnapi) DelegateType.NNAPI else DelegateType.CPU
            }
            PerformanceTier.HIGH -> {
                // 高端机：优先GPU，其次NNAPI
                if (hasGpu) DelegateType.GPU else if (hasNnapi) DelegateType.NNAPI else DelegateType.CPU
            }
        }
    }
    
    enum class DelegateType {
        CPU,
        GPU,
        NNAPI
    }
}

