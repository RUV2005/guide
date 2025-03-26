package com.danmo.guide.feature.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min

class DetectionProcessor {
    private val pendingDetections = ConcurrentLinkedQueue<Detection>()
    private val batchProcessor = Executors.newSingleThreadScheduledExecutor()
    private val contextMemory = ConcurrentHashMap<String, ObjectContext>()
    private lateinit var context: Context
    private val messageQueueManager: MessageQueueManager by lazy { MessageQueueManager.getInstance(context) }

    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var speedFactor: Float = 1.0f,
        var lastPosition: RectF? = null,
        var lastDirection: String = "",
        var lastDistance: String = ""
    )

    companion object {
        private const val BATCH_INTERVAL_MS = 50L
        private const val MIN_REPORT_INTERVAL_MS = 1500L
        private const val MIN_DIRECTION_CHANGE_INTERVAL = 800L
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")

        // 三维感知参数
        private const val FAR_DISTANCE_THRESHOLD = 0.15f   // 宽度小于15%视为远处
        private const val CLOSE_DISTANCE_THRESHOLD = 0.3f // 宽度大于30%视为近处
        private const val MOVEMENT_THRESHOLD = 0.02f       // 移动检测阈值

        // 方向分区阈值
        private const val COLUMN_LEFT = 0.33f
        private const val COLUMN_RIGHT = 0.77f
        private const val ROW_FAR = 0.25f
        private const val ROW_NEAR = 0.66f

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DetectionProcessor? = null
        private var _confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

        var confidenceThreshold: Float
            get() = _confidenceThreshold
            set(value) { _confidenceThreshold = value }

        fun getInstance(context: Context): DetectionProcessor {
            return instance ?: synchronized(this) {
                instance ?: DetectionProcessor().also {
                    instance = it
                    it.context = context.applicationContext
                }
            }
        }
    }

    init {
        batchProcessor.scheduleWithFixedDelay(
            ::processBatch, 0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    fun handleDetectionResult(result: Detection) {
        pendingDetections.add(result)
    }

    private fun processBatch() {
        try {
            val batch = mutableListOf<Detection>().apply {
                while (pendingDetections.isNotEmpty()) {
                    pendingDetections.poll()?.let { add(it) }
                }
            }
            mergeDetections(batch).forEach {
                try {
                    processSingleDetection(it)
                } catch (e: Exception) {
                    Log.e("ProcessBatch", "处理单个检测失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessBatch", "批处理失败", e)
        }
    }

    // 修复后的mergeDetections方法
    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        fun overlapRatio(a: RectF, b: RectF): Float {
            val interWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val interHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val interArea = interWidth * interHeight
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }

        // 正确初始化可变列表
        val merged: MutableList<Detection> = mutableListOf()

        detections.sortedByDescending { it.boundingBox.width() }.forEach { detection ->
            if (merged.none { existing ->
                    overlapRatio(existing.boundingBox, detection.boundingBox) > 0.6 &&
                            existing.categories.any { it.label == detection.categories.first().label }
                }) {
                merged.add(detection) // 现在可以正确调用 add()
            }
        }
        return merged
    }

    private fun processSingleDetection(result: Detection) {
        result.categories
            .takeIf { it.isNotEmpty() }
            ?.maxByOrNull { it.score }
            ?.takeIf { it.score >= confidenceThreshold }
            ?.let { category ->
                val label = getChineseLabel(category.label)
                buildDetectionMessage(result, label)?.let { (message, dir, pri, lbl) ->
                    messageQueueManager.enqueueMessage(
                        message = message,
                        direction = dir,
                        priority = pri,
                        label = lbl,
                        vibrationPattern = when (pri) {
                            MessageQueueManager.MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                            MessageQueueManager.MsgPriority.HIGH -> longArrayOf(0, 300, 100, 200)
                            MessageQueueManager.MsgPriority.NORMAL -> longArrayOf(0, 200)
                        }
                    )
                }
            }
    }

    private fun calculateDirectionInfo(currentBox: RectF, lastBox: RectF?): Triple<String, String, String> {
        // 距离判断
        val width = currentBox.width()
        val distanceLevel = when {
            width < FAR_DISTANCE_THRESHOLD -> "远处"
            width < CLOSE_DISTANCE_THRESHOLD -> "中距离"
            else -> "近距离"
        }

        // 方向判断
        val centerX = currentBox.centerX()
        val centerY = currentBox.centerY()
        val direction = when {
            centerX < COLUMN_LEFT -> when {
                centerY < ROW_FAR -> "左后远"
                centerY < ROW_NEAR -> "左后方"
                else -> "左前近"
            }
            centerX > COLUMN_RIGHT -> when {
                centerY < ROW_FAR -> "右后远"
                centerY < ROW_NEAR -> "右后方"
                else -> "右前近"
            }
            else -> when {
                centerY < ROW_FAR -> "正后方"
                centerY > ROW_NEAR -> "正前方"
                else -> if (centerX < 0.5) "前偏左" else "前偏右"
            }
        }

        // 运动趋势判断
        val trend = calculateMovementTrend(currentBox, lastBox)

        return Triple(direction, distanceLevel, trend)
    }

    private fun calculateMovementTrend(currentBox: RectF, lastBox: RectF?): String {
        if (lastBox == null) return "静止"

        val xMovement = currentBox.centerX() - lastBox.centerX()
        val sizeChange = currentBox.width() - lastBox.width()

        return when {
            sizeChange > 0.03 -> "快速接近"
            sizeChange < -0.03 -> "快速远离"
            xMovement > MOVEMENT_THRESHOLD -> "向右移动"
            xMovement < -MOVEMENT_THRESHOLD -> "向左移动"
            sizeChange > 0.01 -> "缓慢接近"
            sizeChange < -0.01 -> "缓慢远离"
            else -> "静止"
        }
    }

    private fun buildDetectionMessage(
        result: Detection,
        label: String
    ): Quadruple<String, String, MessageQueueManager.MsgPriority, String>? {
        if (shouldSuppressMessage(label)) return null

        val currentBox = result.boundingBox
        val context = contextMemory.compute(label) { _, v ->
            v?.apply {
                lastPosition = currentBox
            } ?: ObjectContext().apply {
                lastPosition = currentBox
            }
        }!!

        val (direction, distance, trend) = calculateDirectionInfo(currentBox, context.lastPosition)
        context.lastPosition = currentBox

        return when {
            isCriticalDetection(result, distance) -> {
                val alertLevel = if (distance == "近距离") "紧急" else "注意"
                Quadruple(
                    "${alertLevel}！${direction}检测到$label（$trend）",
                    getDirectionCategory(direction),
                    MessageQueueManager.MsgPriority.CRITICAL,
                    label
                )
            }
            shouldReport(context, direction, distance, trend) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.lastDirection = direction
                context.lastDistance = distance

                Quadruple(
                    generateDirectionMessage(label, direction, distance, trend),
                    getDirectionCategory(direction),
                    getPriorityLevel(direction, distance, trend),
                    label
                )
            }
            else -> null
        }
    }

    private fun getDirectionCategory(direction: String): String {
        return when {
            direction.contains("前") -> "front"
            direction.contains("后") -> "back"
            else -> "side"
        }
    }

    // 修复后的优先级判断逻辑
    private fun getPriorityLevel(direction: String, distance: String, trend: String): MessageQueueManager.MsgPriority {
        return when {
            direction == "正前方" && distance == "近距离" -> MessageQueueManager.MsgPriority.CRITICAL
            trend.contains("快速接近") -> MessageQueueManager.MsgPriority.HIGH
            direction in setOf("左前近", "右前近") && distance == "中距离" -> MessageQueueManager.MsgPriority.HIGH
            trend.contains("接近") -> MessageQueueManager.MsgPriority.NORMAL
            else -> MessageQueueManager.MsgPriority.NORMAL  // 将LOW改为NORMAL
        }
    }

    private fun isCriticalDetection(result: Detection, distance: String): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && (result.boundingBox.width() > 0.25f || distance == "近距离")
    }

    private fun shouldReport(
        context: ObjectContext,
        currentDirection: String,
        currentDistance: String,
        trend: String
    ): Boolean {
        val directionChanged = context.lastDirection != currentDirection
        val distanceChanged = context.lastDistance != currentDistance

        val baseInterval = when {
            trend.contains("快速") -> MIN_REPORT_INTERVAL_MS / 2
            trend.contains("接近") -> MIN_REPORT_INTERVAL_MS * 2 / 3
            else -> MIN_REPORT_INTERVAL_MS
        }

        return when {
            directionChanged && distanceChanged -> true
            distanceChanged -> true
            directionChanged -> System.currentTimeMillis() - context.lastReportTime > MIN_DIRECTION_CHANGE_INTERVAL
            else -> System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor.coerceIn(0.5f..2.0f))
        }
    }

    private fun generateDirectionMessage(label: String, direction: String, distance: String, trend: String): String {
        val templates = mapOf(
            "左后远" to listOf("左后方远距离$label", "${distance}${label}在左后方"),
            "左后方" to listOf("左后方检测到$label", "注意左后方的$label"),
            "左前近" to listOf("左前方近距离$label！", "注意！左前方有$label$trend"),
            "右后远" to listOf("右后方远距离$label", "${distance}${label}在右后方"),
            "右后方" to listOf("右后方检测到$label", "注意右后方的$label"),
            "右前近" to listOf("右前方近距离$label！", "注意！右前方有$label$trend"),
            "正后方" to listOf("正后方$distance$label", "身后检测到$label"),
            "正前方" to listOf("正前方${distance}${label}", "注意！正前方$label$trend"),
            "前偏左" to listOf("左前方${distance}${label}", "$label,位于左前方向"),
            "前偏右" to listOf("右前方${distance}${label}", "$label,位于右前方向")
        )

        return templates[direction]?.random()?.plus(if (trend != "静止") "，$trend" else "") ?: "检测到$label"
    }

    // 其余工具方法保持不变...
    private fun shouldSuppressMessage(label: String): Boolean {
        return label == "unknown" || label.contains("background")
    }

    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    fun getChineseLabel(original: String): String {
        return mapOf(
            "person" to "行人",
            "bicycle" to "自行车",
            "car" to "汽车",
            "motorcycle" to "摩托车",
            "airplane" to "飞机",
            "bus" to "公交车",
            "train" to "火车",
            "truck" to "卡车",
            "boat" to "船只",
            "traffic light" to "交通灯",
            "fire hydrant" to "消防栓",
            "stop sign" to "停车标志",
            "parking meter" to "停车计时器",
            "bench" to "长椅",
            "bird" to "鸟类",
            "cat" to "猫",
            "dog" to "狗",
            "horse" to "马",
            "sheep" to "羊",
            "cow" to "牛",
            "elephant" to "大象",
            "bear" to "熊",
            "zebra" to "斑马",
            "giraffe" to "长颈鹿",
            "backpack" to "背包",
            "umbrella" to "雨伞",
            "handbag" to "手提包",
            "tie" to "领带",
            "suitcase" to "行李箱",
            "frisbee" to "飞盘",
            "skis" to "滑雪板",
            "snowboard" to "滑雪单板",
            "sports ball" to "运动球类",
            "kite" to "风筝",
            "baseball bat" to "棒球棒",
            "baseball glove" to "棒球手套",
            "skateboard" to "滑板",
            "surfboard" to "冲浪板",
            "tennis racket" to "网球拍",
            "bottle" to "瓶子",
            "wine glass" to "酒杯",
            "cup" to "杯子",
            "fork" to "叉子",
            "knife" to "刀具",
            "spoon" to "勺子",
            "bowl" to "碗",
            "banana" to "香蕉",
            "apple" to "苹果",
            "sandwich" to "三明治",
            "orange" to "橙子",
            "broccoli" to "西兰花",
            "carrot" to "胡萝卜",
            "hot dog" to "热狗",
            "pizza" to "披萨",
            "donut" to "甜甜圈",
            "cake" to "蛋糕",
            "chair" to "椅子",
            "couch" to "沙发",
            "potted plant" to "盆栽",
            "bed" to "床",
            "dining table" to "餐桌",
            "toilet" to "马桶",
            "tv" to "电视",
            "laptop" to "笔记本",
            "mouse" to "鼠标",
            "remote" to "遥控器",
            "keyboard" to "键盘",
            "cell phone" to "手机",
            "microwave" to "微波炉",
            "oven" to "烤箱",
            "toaster" to "烤面包机",
            "sink" to "水槽",
            "refrigerator" to "冰箱",
            "book" to "书籍",
            "clock" to "时钟",
            "vase" to "花瓶",
            "scissors" to "剪刀",
            "teddy bear" to "玩偶",
            "hair drier" to "吹风机",
            "toothbrush" to "牙刷",
            "door" to "门",
            "window" to "窗户",
            "stairs" to "楼梯",
            "curtain" to "窗帘",
            "mirror" to "镜子"
        )[original.lowercase()] ?: original  // 未知标签返回原文 | Return original for unknown labels
    }

    fun shutdown() {
        batchProcessor.shutdown()
        contextMemory.clear()
    }
}