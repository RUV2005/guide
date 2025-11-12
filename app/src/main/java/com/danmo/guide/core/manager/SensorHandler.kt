package com.danmo.guide.core.manager

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.danmo.guide.feature.camera.CameraManager
import com.danmo.guide.feature.fall.FallDetector
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 传感器处理模块
 * 处理加速度计、光照传感器等传感器事件
 */
class SensorHandler(
    private val sensorManager: SensorManager,
    private val fallDetector: FallDetector,
    private val cameraManager: CameraManager,
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val FILTER_WINDOW_SIZE = 5
        private const val MOVEMENT_THRESHOLD = 2.5
        private const val MOVEMENT_WINDOW = 5000L
        private const val SHAKE_THRESHOLD = 25.0
        private const val SHAKE_COOLDOWN = 5000L
    }

    private val accelerationHistory = mutableListOf<Double>()
    private var lastMovementStateChange = 0L
    private var lastLightValue = 0f
    private var lastShakeTime = 0L
    private var isUserMoving = false
    private var lastMovementTimestamp = 0L

    /**
     * 注册传感器监听
     */
    fun registerSensors() {
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI,
            100_000
        )
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT),
            SensorManager.SENSOR_DELAY_UI
        )
    }

    /**
     * 注销传感器监听
     */
    fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                fallDetector.onSensorChanged(event)
                handleShakeDetection(event)
                handleMovementDetection(event)
            }
            Sensor.TYPE_LIGHT -> handleLightSensor(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理
    }

    /**
     * 处理摇晃检测
     */
    private fun handleShakeDetection(event: SensorEvent) {
        val acceleration = event.values.let {
            sqrt(
                it[0].toDouble().pow(2.0) +
                        it[1].toDouble().pow(2.0) +
                        it[2].toDouble().pow(2.0)
            )
        }
        if (acceleration > SHAKE_THRESHOLD && System.currentTimeMillis() - lastShakeTime > SHAKE_COOLDOWN) {
            lastShakeTime = System.currentTimeMillis()
            onShakeDetected()
        }
    }

    /**
     * 处理移动检测
     */
    private fun handleMovementDetection(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        val rawAcceleration = sqrt(
            event.values[0].toDouble().pow(2) +
                    event.values[1].toDouble().pow(2) +
                    event.values[2].toDouble().pow(2)
        )

        val filteredAcceleration = applyLowPassFilter(rawAcceleration)
        val dynamicThreshold = when {
            lastLightValue < 10 -> MOVEMENT_THRESHOLD * 0.9
            else -> MOVEMENT_THRESHOLD
        }

        if (filteredAcceleration > dynamicThreshold) {
            if ((currentTime - lastMovementTimestamp) < 1000) {
                lastMovementTimestamp = currentTime
            }
        }

        val shouldBeMoving = (currentTime - lastMovementTimestamp) < MOVEMENT_WINDOW

        if (isUserMoving != shouldBeMoving) {
            if (System.currentTimeMillis() - lastMovementStateChange > 2000) {
                isUserMoving = shouldBeMoving
                lastMovementStateChange = System.currentTimeMillis()
                Log.d("Movement", "移动状态变更: $isUserMoving")
            }
        }
    }

    /**
     * 处理光照传感器
     */
    private fun handleLightSensor(event: SensorEvent) {
        val currentLight = event.values[0]
        if (abs(currentLight - lastLightValue) > 1) {
            lastLightValue = currentLight
            cameraManager.enableTorchMode(currentLight < 10.0f)
        }
    }

    /**
     * 应用低通滤波
     */
    private fun applyLowPassFilter(currentValue: Double): Double {
        accelerationHistory.add(currentValue)
        if (accelerationHistory.size > FILTER_WINDOW_SIZE) {
            accelerationHistory.removeAt(0)
        }
        return accelerationHistory.average()
    }
}

