package com.danmo.guide.core.memory

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danmo.guide.core.device.DeviceCapability

/**
 * 内存优化工具
 * 根据设备性能自动调整图片压缩、缓存策略等
 */
object MemoryOptimizer {
    
    private var deviceInfo: DeviceCapability.DeviceInfo? = null
    
    /**
     * 初始化设备信息
     */
    fun initDeviceInfo(context: Context) {
        if (deviceInfo == null) {
            deviceInfo = DeviceCapability.detect(context)
        }
    }
    
    /**
     * 根据设备性能推荐图片压缩质量
     */
    fun getRecommendedQuality(): Int {
        val info = deviceInfo ?: return 80 // 默认值
        return when (info.tier) {
            DeviceCapability.PerformanceTier.LOW -> 60      // 低端机：高压缩
            DeviceCapability.PerformanceTier.MEDIUM -> 80  // 中端机：中等压缩
            DeviceCapability.PerformanceTier.HIGH -> 90    // 高端机：低压缩
        }
    }
    
    /**
     * 根据设备性能推荐图片缩放比例
     */
    fun getRecommendedScale(): Float {
        val info = deviceInfo ?: return 1.0f // 默认值
        return when (info.tier) {
            DeviceCapability.PerformanceTier.LOW -> 0.5f    // 低端机：缩小50%
            DeviceCapability.PerformanceTier.MEDIUM -> 0.75f // 中端机：缩小25%
            DeviceCapability.PerformanceTier.HIGH -> 1.0f   // 高端机：不缩放
        }
    }
    
    /**
     * 压缩Bitmap（根据设备性能自动调整）
     */
    fun compressBitmap(bitmap: Bitmap): Bitmap {
        val info = deviceInfo
        if (info == null) {
            Log.w("MemoryOptimizer", "设备信息未初始化，使用默认压缩")
            return bitmap
        }
        
        val scale = getRecommendedScale()
        if (scale >= 1.0f) {
            return bitmap // 不需要压缩
        }
        
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        
        return try {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (e: OutOfMemoryError) {
            Log.e("MemoryOptimizer", "压缩图片时内存不足", e)
            // 如果还是OOM，尝试更激进的压缩
            val fallbackScale = scale * 0.5f
            val fallbackWidth = (bitmap.width * fallbackScale).toInt()
            val fallbackHeight = (bitmap.height * fallbackScale).toInt()
            Bitmap.createScaledBitmap(bitmap, fallbackWidth, fallbackHeight, true)
        }
    }
    
    /**
     * 检查内存是否紧张
     */
    fun isMemoryLow(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        // 如果可用内存小于总内存的20%，认为内存紧张
        val availableRatio = memInfo.availMem.toFloat() / memInfo.totalMem.toFloat()
        return availableRatio < 0.2f || memInfo.lowMemory
    }
    
    /**
     * 获取推荐的最大缓存大小（MB）
     */
    fun getRecommendedCacheSizeMB(): Int {
        val info = deviceInfo ?: return 50 // 默认值
        return when (info.tier) {
            DeviceCapability.PerformanceTier.LOW -> 20      // 低端机：小缓存
            DeviceCapability.PerformanceTier.MEDIUM -> 50  // 中端机：中等缓存
            DeviceCapability.PerformanceTier.HIGH -> 100   // 高端机：大缓存
        }
    }
    
    /**
     * 建议是否应该清理缓存
     */
    fun shouldClearCache(context: Context): Boolean {
        return isMemoryLow(context) && deviceInfo?.tier == DeviceCapability.PerformanceTier.LOW
    }
}

