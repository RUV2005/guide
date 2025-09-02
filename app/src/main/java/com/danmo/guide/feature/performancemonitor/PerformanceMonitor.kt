package com.danmo.guide.feature.performancemonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class PerformanceMonitor(private val context: Context) {

    /* ========= 对外数据结构 ========= */
    data class Metrics(
        val fps: Int,
        val cpuTotal: Int,          // 0–100
        val cpuApp: Int,            // 0–100
        val memUsedMB: Int,
        val memMaxMB: Int,
        val batteryTemp: Float,
        val tfliteMs: Long = 0L,    // 滑动平均
        val gpuFrameMs: Int = 0,    // 滑动平均
        val powerContext: PowerGovernor.Context
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

    /* ========= 滑动窗口 ========= */
    private val tfliteWindow = ArrayDeque<Long>(1)
    private val gpuFrameWindow = ArrayDeque<Int>(5)

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

    /* 外部注入 TFLite/GPU 耗时（滑动窗口） */
    fun recordTflite(ms: Long) = handler.post {
        tfliteWindow.addLast(ms)
        if (tfliteWindow.size > 5) tfliteWindow.removeFirst()
    }

    fun recordGpuFrame(ms: Double) = handler.post {
        gpuFrameWindow.addLast(ms.toInt())
        if (gpuFrameWindow.size > 5) gpuFrameWindow.removeFirst()
    }

    /* ========= 采样任务 ========= */
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
                tfliteMs = tfliteWindow.average().toLong(),
                gpuFrameMs = gpuFrameWindow.average().toInt(),
                powerContext = PowerGovernor.Context(
                    cpuUsage = readCpuApp(),
                    memoryPressure = readUsedMemMB() * 100 / max(1, readMaxMemMB()),
                    batteryLevel = readBatteryLevel(),
                    temperature = readBatteryTemp(),
                    inferenceLatency = tfliteWindow.average().toLong()
                )
            )

            uiHandler.post { callback?.onMetrics(m) }
            handler.postDelayed(this, 1000)
        }
    }


    // 只清帧相关累计量
    fun resetFrameCounters() {
        FrameStats.frames.set(0)
        lastFrameCount = 0L
        lastFrameTimestamp = 0L
    }

    /* ========= 重置所有累计量 ========= */
    fun reset() {
        resetFrameCounters()
        lastCpuTotal = 0L
        lastCpuIdle = 0L
        lastCpuApp = 0L
    }

    /* ========= 读取各项数据（保持不变） ========= */
    private fun readFps(): Int {
        val frames = FrameStats.frames.get()
        val now = SystemClock.elapsedRealtime()
        val fps = if (lastFrameTimestamp == 0L) 0
        else ((frames - lastFrameCount) * 1000 / (now - lastFrameTimestamp)).toInt()
        lastFrameCount = frames
        lastFrameTimestamp = now
        return fps
    }

    private fun readCpuTotal(): Int = try {
        val parts = File("/proc/stat").readLines()[0].split("\\s+".toRegex())
        val idle = parts[4].toLong()
        val total = parts.drop(1).take(4).sumOf { it.toLong() }
        val diffIdle = idle - lastCpuIdle
        val diffTotal = total - lastCpuTotal
        val usage = if (diffTotal == 0L) 0 else ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
        lastCpuIdle = idle
        lastCpuTotal = total
        usage
    } catch (_: Exception) { -1 }

    private fun readCpuApp(): Int {
        val pid = Process.myPid()
        return try {
            val parts = File("/proc/$pid/stat").readLines()[0].split("\\s+".toRegex())
            val utime = parts[13].toLong()
            val stime = parts[14].toLong()
            val total = utime + stime
            val diff = total - lastCpuApp
            val hz = Os.sysconf(OsConstants._SC_CLK_TCK)
            val usage = (diff * 1000 / hz / 10).toInt()
            lastCpuApp = total
            usage.coerceIn(0, 100)
        } catch (_: Exception) { -1 }
    }

    private fun readUsedMemMB(): Int =
        (Debug.getNativeHeapAllocatedSize() / 1024 / 1024).toInt()

    private fun readMaxMemMB(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 1024).toInt()

    private fun readBatteryTemp(): Float =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("temperature", 0) ?: 0) / 10f

    private fun readBatteryLevel(): Int =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra("level", -1) ?: -1

    fun clearTfliteWindow() = handler.post { tfliteWindow.clear() }
    fun recordTfliteNow(ms: Long) {
        // 直接在当前线程操作，不经过 Handler 排队
        tfliteWindow.clear()
        tfliteWindow.addLast(ms)
    }
    /* ========= 帧计数器（供外部调用） ========= */
    object FrameStats {
        val frames = AtomicLong(0)
        fun addFrame() = frames.incrementAndGet()
    }

}