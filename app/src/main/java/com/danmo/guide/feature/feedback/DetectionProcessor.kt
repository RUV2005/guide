package com.danmo.guide.feature.feedback

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
        var speedFactor: Float = 1.0f
    )

    companion object {
        private const val BATCH_INTERVAL_MS = 50L
        private const val MIN_REPORT_INTERVAL_MS = 500L
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")
        private const val FAR_LEFT_BOUNDARY = 0.15f
        private const val NEAR_LEFT_BOUNDARY = 0.3f
        private const val CENTER_LEFT = 0.4f
        private const val CENTER_RIGHT = 0.6f
        private const val NEAR_RIGHT_BOUNDARY = 0.7f
        private const val FAR_RIGHT_BOUNDARY = 0.85f

        @Volatile
        private var instance: DetectionProcessor? = null
        private var _confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

        var confidenceThreshold: Float
            get() = _confidenceThreshold
            set(value) {
                _confidenceThreshold = value
            }

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
            ::processBatch,
            0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    fun handleDetectionResult(result: Detection) {
        pendingDetections.add(result)
    }

    private fun processBatch() {
        try {
            val batch = mutableListOf<Detection>().apply {
                while (pendingDetections.isNotEmpty()) pendingDetections.poll()?.let { add(it) }
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

    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        fun overlapRatio(a: RectF, b: RectF): Float {
            val interArea = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                    max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }

        val merged = mutableListOf<Detection>()
        detections.sortedByDescending { it.boundingBox.width() }.forEach { detection ->
            if (merged.none { existing ->
                    overlapRatio(existing.boundingBox, detection.boundingBox) > 0.6 &&
                            existing.categories.any { it.label == detection.categories.first().label }
                }) {
                merged.add(detection)
            }
        }
        return merged
    }

    private fun processSingleDetection(result: Detection) {
        result.categories.takeIf { it.isNotEmpty() }?.maxByOrNull { it.score }
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

    private fun buildDetectionMessage(result: Detection, label: String): Quadruple<String, String, MessageQueueManager.MsgPriority, String>? {
        if (shouldSuppressMessage(label) || label.isEmpty()) return null

        val context = contextMemory.compute(label) { _, v ->
            v?.apply { speedFactor = max(0.5f, speedFactor * 0.9f) } ?: ObjectContext()
        }!!

        return when {
            isCriticalDetection(result) -> Quadruple(
                generateCriticalAlert(label),
                "center",
                MessageQueueManager.MsgPriority.CRITICAL,
                label
            )
            shouldReport(context, generateDirectionMessage(label, calculateDirection(result.boundingBox))) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Quadruple(
                    generateDirectionMessage(label, calculateDirection(result.boundingBox)),
                    "general",
                    MessageQueueManager.MsgPriority.HIGH,
                    label
                )
            }
            else -> null
        }
    }

    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private fun shouldSuppressMessage(label: String): Boolean {
        return label == "unknown" || label.contains("background")
    }

    private fun isCriticalDetection(result: Detection): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && result.boundingBox.width() > 0.25f
    }

    private fun shouldReport(context: ObjectContext, newMessage: String): Boolean {
        val baseInterval = when {
            newMessage.contains("注意！") -> MIN_REPORT_INTERVAL_MS / 2
            newMessage.contains("危险！") -> MIN_REPORT_INTERVAL_MS * 2 / 3
            else -> MIN_REPORT_INTERVAL_MS
        }
        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }

    private fun calculateDirection(box: RectF): String {
        val centerX = box.centerX()
        val widthFactor = box.width() * 0.35f

        return when {
            centerX - widthFactor < FAR_LEFT_BOUNDARY -> "最左侧"
            centerX < NEAR_LEFT_BOUNDARY -> when {
                box.right > NEAR_LEFT_BOUNDARY -> "左侧偏右"
                else -> "左侧"
            }
            centerX < CENTER_LEFT -> when {
                box.right > CENTER_LEFT + 0.05f -> "左前方偏右"
                box.left < NEAR_LEFT_BOUNDARY - 0.05f -> "左前方偏左"
                else -> "左前方"
            }
            centerX < CENTER_RIGHT -> when {
                box.width() > 0.3f -> "正前方(大范围)"
                box.left < CENTER_LEFT - 0.05f -> "正前方偏左"
                box.right > CENTER_RIGHT + 0.05f -> "正前方偏右"
                else -> "正前方"
            }
            centerX < NEAR_RIGHT_BOUNDARY -> when {
                box.left < CENTER_RIGHT - 0.05f -> "右前方偏左"
                box.right > NEAR_RIGHT_BOUNDARY + 0.05f -> "右前方偏右"
                else -> "右前方"
            }
            centerX + widthFactor > FAR_RIGHT_BOUNDARY -> "最右侧"
            else -> when {
                box.left < NEAR_RIGHT_BOUNDARY -> "右侧偏左"
                else -> "右侧"
            }
        }
    }

    private fun generateCriticalAlert(label: String): String {
        val templates = listOf("注意！正前方发现$label", "危险！$label,接近中", "紧急！$label,靠近")
        return templates.random()
    }

    private fun generateDirectionMessage(label: String, direction: String): String {
        val templates = mapOf(
            "最左侧" to listOf("注意！最左侧发现$label", "$label,位于最左边区域"),
            "左侧" to listOf("您的左侧有$label", "检测到左侧存在$label"),
            "左侧偏右" to listOf("左侧偏右位置检测到$label", "$label,在左侧靠右区域"),
            "左前方偏左" to listOf("左前方偏左位置有$label", "检测到左前方左侧存在$label"),
            "左前方" to listOf("左前方发现$label", "$label,位于左前方"),
            "左前方偏右" to listOf("左前方偏右位置检测到$label", "$label,在左前方靠右区域"),
            "正前方(大范围)" to listOf("正前方检测到大型$label", "大面积$label,位于正前方"),
            "正前方偏左" to listOf("正前方偏左位置有$label", "$label,在正前方靠左区域"),
            "正前方" to listOf("正前方发现$label", "检测到正前方存在$label"),
            "正前方偏右" to listOf("正前方偏右位置检测到$label", "$label,在正前方靠右区域"),
            "右前方偏左" to listOf("右前方偏左位置有$label", "检测到右前方左侧存在$label"),
            "右前方" to listOf("右前方发现$label", "$label,位于右前方"),
            "右前方偏右" to listOf("右前方偏右位置检测到$label", "$label,在右前方靠右区域"),
            "右侧偏左" to listOf("右侧偏左位置有$label", "检测到右侧靠左区域存在$label"),
            "右侧" to listOf("您的右侧有$label", "检测到右侧存在$label"),
            "最右侧" to listOf("注意！最右侧发现$label", "$label,位于最右边区域")
        )

        return templates[direction]?.random() ?: "检测到$label"
    }

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
        )[original.lowercase()] ?: original
    }

    fun shutdown() {
        batchProcessor.shutdown()
        contextMemory.clear()
    }
} 