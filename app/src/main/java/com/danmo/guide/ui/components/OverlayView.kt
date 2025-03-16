package com.danmo.guide.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection

/**
 * 实时检测结果覆盖层视图，用于绘制物体边界框和标签
 * Real-time detection overlay view for drawing object bounding boxes and labels
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    // 绘制参数配置 / Drawing parameters
    private var boxPaint: Paint? = null    // 边界框画笔 / Bounding box paint
    private var textPaint: Paint? = null   // 文本标签画笔 / Text label paint
    private var detections: List<Detection>? = null // 当前检测结果 / Current detection results
    private var rotationDegrees = 0        // 屏幕旋转角度 / Screen rotation degrees
    private var width = 0                  // 视图宽度 / View width
    private var height = 0                 // 视图高度 / View height
    private var modelInputWidth: Float = 0f  // 模型输入宽度 / Model input width
    private var modelInputHeight: Float = 0f // 模型输入高度 / Model input height

    init {
        initPaint()  // 初始化绘制工具 / Initialize drawing tools
    }

    // 初始化画笔样式 / Initialize paint styles
    private fun initPaint() {
        boxPaint = Paint().apply {
            color = -0xff0100   // 绿色边框 / Green border
            style = Paint.Style.STROKE  // 空心样式 / Stroke style
            strokeWidth = 5f           // 边框宽度5像素 / 5px border width
        }
        textPaint = Paint().apply {
            color = -0x1        // 白色文字 / White text
            textSize = 50f      // 50像素字号 / 50px font size
            isAntiAlias = true   // 抗锯齿 / Anti-aliasing
        }
    }

    /**
     * 更新检测结果并重绘
     * Update detections and redraw
     * @param detections 检测结果列表 / List of detection results
     * @param rotationDegrees 设备旋转角度（0/90/180/270） / Device rotation in degrees
     */
    fun updateDetections(detections: List<Detection>, rotationDegrees: Int) {
        this.detections = detections
        this.rotationDegrees = rotationDegrees
        invalidate()  // 触发视图重绘 / Trigger view redraw
    }

    /**
     * 设置模型输入尺寸（用于坐标换算）
     * Set model input dimensions (for coordinate conversion)
     * @param width 模型输入宽度 / Model input width
     * @param height 模型输入高度 / Model input height
     */
    fun setModelInputSize(width: Int, height: Int) {
        modelInputWidth = width.toFloat()
        modelInputHeight = height.toFloat()
    }

    // 视图尺寸变化回调 / View size change callback
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.width = w
        this.height = h
        Log.d("OverlayView", "视图尺寸更新 View Size: width=$w, height=$h")
    }

    // 绘制检测结果 / Draw detection results
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections?.forEach { detection ->
            val boundingBox = detection.boundingBox
            adjustBoundingBoxForRotation(boundingBox)  // 旋转坐标修正 / Rotation correction
            applyScalingToBoundingBox(boundingBox)     // 缩放适配视图 / Scaling to view
            drawDetection(canvas, boundingBox, detection) // 绘制元素 / Draw elements
        }
    }

    /**
     * 根据设备旋转调整边界框坐标
     * Adjust bounding box coordinates based on device rotation
     * @param boundingBox 原始边界框 / Original bounding box
     */
    private fun adjustBoundingBoxForRotation(boundingBox: RectF) {
        Log.d("OverlayView", "当前旋转角度 Current Rotation: $rotationDegrees°")
        when (rotationDegrees) {
            90 -> {  // 顺时针旋转90度处理 / 90° clockwise rotation
                val tempLeft = boundingBox.left
                boundingBox.left = boundingBox.top
                boundingBox.top = height - boundingBox.right
                boundingBox.right = boundingBox.bottom
                boundingBox.bottom = height - tempLeft
            }
            180 -> { // 倒置处理 / Upside down
                boundingBox.left = width - boundingBox.right
                boundingBox.top = height - boundingBox.bottom
                boundingBox.right = width - boundingBox.left
                boundingBox.bottom = height - boundingBox.top
            }
            270 -> { // 逆时针旋转90度处理 / 90° counter-clockwise rotation
                val tempLeft = boundingBox.left
                boundingBox.left = width - boundingBox.bottom
                boundingBox.top = tempLeft
                boundingBox.right = width - boundingBox.top
                boundingBox.bottom = boundingBox.right
            }
        }
    }

    /**
     * 将模型输出坐标适配到视图尺寸
     * Adapt model output coordinates to view dimensions
     * @param boundingBox 调整后的边界框 / Adjusted bounding box
     */
    private fun applyScalingToBoundingBox(boundingBox: RectF) {
        if (modelInputWidth == 0f || modelInputHeight == 0f) {
            Log.e("OverlayView", "模型输入尺寸未设置 Model input size not configured")
            return
        }

        // 计算缩放比例 / Calculate scaling factors
        val scaleX = width / modelInputWidth
        val scaleY = height / modelInputHeight

        // 应用缩放 / Apply scaling
        boundingBox.left *= scaleX
        boundingBox.top *= scaleY
        boundingBox.right *= scaleX
        boundingBox.bottom *= scaleY

        Log.d("OverlayView", """
            缩放参数 Scaling params - 
            模型输入 Model: ${modelInputWidth}x${modelInputHeight}, 
            视图尺寸 View: ${width}x$height
            边界框坐标 BBox: $boundingBox
        """.trimIndent())
    }

    /**
     * 绘制单个检测结果
     * Draw single detection result
     * @param canvas 画布对象 / Canvas object
     * @param rect 边界框坐标 / Bounding box coordinates
     * @param detection 检测结果数据 / Detection data
     */
    private fun drawDetection(canvas: Canvas, rect: RectF, detection: Detection) {
        // 绘制边界框 / Draw bounding box
        canvas.drawRect(rect, boxPaint!!)

        // 绘制标签（显示在框上方10像素） / Draw label (10px above box)
        detection.categories.firstOrNull()?.label?.let { label ->
            canvas.drawText(label, rect.left, rect.top - 10, textPaint!!)
        }
    }
}