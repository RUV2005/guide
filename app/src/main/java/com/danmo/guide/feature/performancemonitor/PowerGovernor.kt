package com.danmo.guide.feature.performancemonitor

import com.danmo.guide.feature.powermode.PowerMode
import kotlin.math.max

/**
 * 根据 PerformanceMonitor 输出的多维指标，计算当前应采用的 PowerMode。
 * 支持回滞区间，避免抖动。
 */
object PowerGovernor {

    /** 输入上下文：所有维度均已归一到 0–100 或原始单位 */
    data class Context(
        val cpuUsage: Int,          // 0–100
        val memoryPressure: Int,    // 0–100
        val batteryLevel: Int,      // 0–100（反向：越低越紧张）
        val temperature: Float,     // ℃
        val inferenceLatency: Long, // ms
        val networkLatency: Long? = null  // ms（外置摄像头 RTT，可选）
    )

    /* ========== 私有归一化函数 ========== */
    private fun cpuScore(c: Int)       = c.coerceIn(0, 100)
    private fun memScore(m: Int)       = m.coerceIn(0, 100)
    private fun batteryScore(b: Int)   = (100 - b).coerceIn(0, 100)
    private fun tempScore(t: Float)    = when {
        t < 35f  -> 0
        t > 45f  -> 100
        else     -> ((t - 35) * 10).toInt()
    }
    private fun tfliteScore(l: Long)   = when {
        l < 50   -> 0
        l > 250  -> 100
        else     -> ((l - 50) * 0.5).toInt()
    }
    private fun networkScore(l: Long?) = when (l) {
        null -> 0
        else -> (l.coerceIn(0, 1000) / 10)
    }

    /* ========== 权重配置（总和 = 1.0） ========== */
    private const val W_CPU   = 0.25
    private const val W_MEM   = 0.15
    private const val W_BAT   = 0.20
    private const val W_TEMP  = 0.20
    private const val W_INF   = 0.15
    private const val W_NET   = 0.05

    /* ========== 阈值 + 回滞区间 ========== */
    private const val TH_LOW  = 30
    private const val TH_HIGH = 70

    /** 公开评估入口 */
    fun evaluate(ctx: Context): PowerMode {
        val score = (
                cpuScore(ctx.cpuUsage)            * W_CPU +
                memScore(ctx.memoryPressure)      * W_MEM +
                batteryScore(ctx.batteryLevel)    * W_BAT +
                tempScore(ctx.temperature)        * W_TEMP +
                tfliteScore(ctx.inferenceLatency) * W_INF +
                networkScore(ctx.networkLatency)  * W_NET
                ).toInt()

        // 回滞逻辑：只在跨越阈值时才切换
        return when (currentMode) {
            PowerMode.LOW_POWER   -> if (score < TH_LOW)  PowerMode.LOW_POWER   else PowerMode.BALANCED
            PowerMode.HIGH_ACCURACY -> if (score > TH_HIGH) PowerMode.HIGH_ACCURACY else PowerMode.BALANCED
            else                  -> when {
                score <= TH_LOW  -> PowerMode.LOW_POWER
                score >= TH_HIGH -> PowerMode.HIGH_ACCURACY
                else             -> PowerMode.BALANCED
            }
        }
    }

    /* 记录上一次结果，防止抖动 */
    @Volatile
    var currentMode: PowerMode = PowerMode.BALANCED
}