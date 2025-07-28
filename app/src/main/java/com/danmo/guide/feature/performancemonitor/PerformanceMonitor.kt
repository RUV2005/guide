package com.danmo.guide.feature.performancemonitor


import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.system.Os
import android.system.OsConstants
import androidx.camera.core.processing.SurfaceProcessorNode.In
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用法：
 * 1. val monitor = PerformanceMonitor(context)
 * 2. monitor.start { data -> /* 更新 UI 或写日志 */ }
 * 3. monitor.stop()
 */
class PerformanceMonitor(private val context: Context) {

    /* ========= 对外数据结构 ========= */
    data class Metrics(
        val fps: Int,
        val cpuTotal: Int,          // 0-100
        val cpuApp: Int,            // 0-100
        val memUsedMB: Int,
        val memMaxMB: Int,
        val batteryTemp: Float,
        val tfliteMs: Long = 0L,   // 外部注入
        val gpuFrameMs: Int = 0 // 外部注入
    )

    /* ========= 回调 ========= */
    interface Callback {
        fun onMetrics(metrics: Metrics)
    }

    private var callback: Callback? = null
    private val started = AtomicBoolean(false)
    private val handlerThread = HandlerThread("Perf-Thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val uiHandler = Handler(Looper.getMainLooper())

    /* ========= 内部采样所需变量 ========= */
    private var lastFrameCount = 0L
    private var lastFrameTimestamp = 0L
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastCpuApp = 0L

    /* ========= API ========= */
    fun start(cb: Callback) {
        if (!started.compareAndSet(false, true)) return
        callback = cb
        handler.post(sampleTask)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        handler.removeCallbacks(sampleTask)
    }

    /* 外部注入 TFLite/GPU 耗时 */
    fun recordTflite(ms: Long) = handler.post { tfliteMs = ms }
    fun recordGpuFrame(ms: Double) = handler.post { gpuFrameMs = ms.toInt() }

    /* ========= 采样任务 ========= */
    private var tfliteMs: Long = 0
    private var gpuFrameMs: Int = 0

    private val sampleTask = object : Runnable {
        override fun run() {
            if (!started.get()) return

            val m = Metrics(
                fps = readFps(),
                cpuTotal = readCpuTotal(),
                cpuApp = readCpuApp(),
                memUsedMB = readUsedMemMB(),
                memMaxMB = readMaxMemMB(),
                batteryTemp = readBatteryTemp(),
                tfliteMs = tfliteMs,
                gpuFrameMs = gpuFrameMs
            )

            uiHandler.post { callback?.onMetrics(m) }

            handler.postDelayed(this, 1000)
        }
    }

    /* ========= 读取各项数据 ========= */
    private fun readFps(): Int {
        val frames = FrameStats.frames.get()
        val now = SystemClock.elapsedRealtime()
        val fps = if (lastFrameTimestamp == 0L) 0 else ((frames - lastFrameCount) * 1000 / (now - lastFrameTimestamp)).toInt()
        lastFrameCount = frames
        lastFrameTimestamp = now
        return fps
    }

    private fun readCpuTotal(): Int {
        return try {
            val parts = File("/proc/stat").readLines()[0].split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).take(4).sumOf { it.toLong() }
            val diffIdle = idle - lastCpuIdle
            val diffTotal = total - lastCpuTotal
            val usage = if (diffTotal == 0L) 0 else ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
            lastCpuIdle = idle
            lastCpuTotal = total
            usage
        } catch (e: Exception) { -1 }
    }

    private fun readCpuApp(): Int {
        val pid = Process.myPid()
        return try {
            val parts = File("/proc/$pid/stat").readLines()[0].split("\\s+".toRegex())
            val utime = parts[13].toLong()
            val stime = parts[14].toLong()
            val total = utime + stime
            val diff = total - lastCpuApp
            val hz = Os.sysconf(OsConstants._SC_CLK_TCK)
            val usage = (diff * 1000 / hz / 10).toInt() // 近似
            lastCpuApp = total
            usage.coerceIn(0, 100)
        } catch (e: Exception) { -1 }
    }

    private fun readUsedMemMB(): Int =
        (Debug.getNativeHeapAllocatedSize() / 1024 / 1024).toInt()

    private fun readMaxMemMB(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 1024).toInt()


    private fun readBatteryTemp(): Float {
        val temp = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("temperature", 0) ?: 0
        return temp / 10f
    }

    /* ========= 帧计数器（供外部调用） ========= */
    object FrameStats {
        val frames = java.util.concurrent.atomic.AtomicLong(0)
        fun addFrame() = frames.incrementAndGet()
    }
}