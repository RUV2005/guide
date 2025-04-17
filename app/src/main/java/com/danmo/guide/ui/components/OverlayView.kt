package com.danmo.guide.ui.components
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import org.tensorflow.lite.task.vision.detector.Detection
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var boxPaint: Paint? = null
    private var textPaint: Paint? = null
    private var detections: List<Detection>? = null
    private var rotationDegrees = 0
    private var width = 0
    private var height = 0
    private var modelInputWidth: Float = 0f
    private var modelInputHeight: Float = 0f
    init {
        initPaint()
    }
    private fun initPaint() {
        boxPaint = Paint().apply {
            color = -0xff0100
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        textPaint = Paint().apply {
            color = -0x1
            textSize = 50f
            isAntiAlias = true
        }
    }
    fun updateDetections(detections: List<Detection>, rotationDegrees: Int) {
        this.detections = detections
        this.rotationDegrees = rotationDegrees
        invalidate()
    }
    fun setModelInputSize(width: Int, height: Int) {
        modelInputWidth = width.toFloat()
        modelInputHeight = height.toFloat()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.width = w
        this.height = h
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections?.forEach { detection ->
            val boundingBox = detection.boundingBox
            adjustBoundingBoxForRotation(boundingBox)
            applyScalingToBoundingBox(boundingBox)
            drawDetection(canvas, boundingBox, detection)
        }
        // 如果有检测结果，通知读屏器
        if (detections?.isNotEmpty() == true) {
            announceForAccessibility("检测到物体")
        }
    }
    private fun adjustBoundingBoxForRotation(boundingBox: RectF) {
        when (rotationDegrees) {
            90 -> {
                val tempLeft = boundingBox.left
                boundingBox.left = boundingBox.top
                boundingBox.top = height - boundingBox.right
                boundingBox.right = boundingBox.bottom
                boundingBox.bottom = height - tempLeft
            }
            180 -> {
                boundingBox.left = width - boundingBox.right
                boundingBox.top = height - boundingBox.bottom
                boundingBox.right = width - boundingBox.left
                boundingBox.bottom = height - boundingBox.top
            }
            270 -> {
                val tempLeft = boundingBox.left
                boundingBox.left = width - boundingBox.bottom
                boundingBox.top = tempLeft
                boundingBox.right = width - boundingBox.top
                boundingBox.bottom = boundingBox.right
            }
        }
    }
    private fun applyScalingToBoundingBox(boundingBox: RectF) {
        if (modelInputWidth == 0f || modelInputHeight == 0f) {
            return
        }
        val scaleX = width / modelInputWidth
        val scaleY = height / modelInputHeight
        boundingBox.left *= scaleX
        boundingBox.top *= scaleY
        boundingBox.right *= scaleX
        boundingBox.bottom *= scaleY
    }
    private fun drawDetection(canvas: Canvas, rect: RectF, detection: Detection) {
        canvas.drawRect(rect, boxPaint!!)
        detection.categories.firstOrNull()?.label?.let { label ->
            canvas.drawText(label, rect.left, rect.top - 10, textPaint!!)
        }
    }
}