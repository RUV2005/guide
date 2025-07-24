package com.danmo.guide.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import org.tensorflow.lite.task.vision.detector.Detection

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // 画笔
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    // 数据
    private var detections: List<Detection> = emptyList()
    private var rotationDegrees = 0
    private var modelW = 1
    private var modelH = 1

    // 双模式
    private var isStreamMode = false
    private val streamRect = RectF()

    // 对外接口
    fun updateDetections(list: List<Detection>, rotation: Int) {
        detections = list
        rotationDegrees = rotation
        invalidate()
    }

    fun setModelInputSize(w: Int, h: Int) {
        modelW = w
        modelH = h
    }

    fun setStreamDisplayRect(rect: RectF) {
        streamRect.set(rect)
        isStreamMode = true
    }

    fun resetToCameraMode() {
        isStreamMode = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        // 1️⃣ 根据模式计算缩放
        if (isStreamMode) {
            // 外置视频流：按实际显示区域缩放
            scaleX = streamRect.width() / modelW
            scaleY = streamRect.height() / modelH
            offsetX = streamRect.left
            offsetY = streamRect.top
        } else {
            // 内置摄像头：全屏缩放
            scaleX = width.toFloat() / modelW
            scaleY = height.toFloat() / modelH
            offsetX = 0f
            offsetY = 0f
        }

        // 2️⃣ 绘制框
        detections.forEach { det ->
            val box = RectF(det.boundingBox)

            // 3️⃣ 旋转映射（90/180/270°）
            val mapped = when (rotationDegrees) {
                90  -> RectF(offsetX + box.top    * scaleX,
                    offsetY + (modelH - box.right) * scaleY,
                    offsetX + box.bottom * scaleX,
                    offsetY + (modelH - box.left)  * scaleY)

                180 -> RectF(offsetX + (modelW - box.right) * scaleX,
                    offsetY + (modelH - box.bottom) * scaleY,
                    offsetX + (modelW - box.left)  * scaleX,
                    offsetY + (modelH - box.top)    * scaleY)

                270 -> RectF(offsetX + (modelW - box.bottom) * scaleX,
                    offsetY + box.left     * scaleY,
                    offsetX + (modelW - box.top)    * scaleX,
                    offsetY + box.right    * scaleY)

                else -> RectF(offsetX + box.left   * scaleX,
                    offsetY + box.top    * scaleY,
                    offsetX + box.right  * scaleX,
                    offsetY + box.bottom * scaleY)
            }

            canvas.drawRect(mapped, boxPaint)
            det.categories.firstOrNull()?.label?.let {
                canvas.drawText(it, mapped.left, mapped.top - 10, textPaint)
            }
        }

        // 4️⃣ 读屏提示
        if (detections.isNotEmpty()) {
            announceForAccessibility("检测到物体")
        }
    }

    // 计算外置视频流在 Overlay 中的实际显示区域（4:3 → 任意 View）
    fun calculateStreamDisplayRect(vw: Int, vh: Int): RectF {
        val ratio = 4f / 3f
        val viewRatio = vw.toFloat() / vh
        return if (viewRatio > ratio) {
            val h = vw / ratio
            val top = (vh - h) / 2
            RectF(0f, top, vw.toFloat(), top + h)
        } else {
            val w = vh * ratio
            val left = (vw - w) / 2
            RectF(left, 0f, left + w, vh.toFloat())
        }
    }
}