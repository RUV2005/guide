package com.danmo.guide.feature.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min

private const val POSITION_CHANGE_COOLDOWN = 2000L
private var imageWidth = 320f // 默认模型输入尺寸
private var imageHeight = 320f

class DetectionProcessor {
    private val pendingDetections = ConcurrentLinkedQueue<Detection>()
    private val batchProcessor = Executors.newSingleThreadScheduledExecutor()
    private val contextMemory = ConcurrentHashMap<String, ObjectContext>()
    private lateinit var context: Context
    private val messageQueueManager: MessageQueueManager by lazy { MessageQueueManager.getInstance(context) }

    // 物体位置记忆
    private val positionMemory = ConcurrentHashMap<String, String>()
    // 位置变化时间记录
    private val positionChangeTimes = ConcurrentHashMap<String, Long>()

    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var speedFactor: Float = 1.0f
    )
    // 在收到检测结果时更新图像尺寸
    fun updateImageDimensions(width: Int, height: Int) {
        imageWidth = width.toFloat()
        imageHeight = height.toFloat()
    }

    companion object {
        private const val BATCH_INTERVAL_MS = 500L
        private const val MIN_REPORT_INTERVAL_MS = 1000L
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck", "motorcycle", "bicycle","traffic light")

        // 大区域定义
        private val superRegions = mapOf(
            "左侧" to setOf("左远侧", "左近侧"),
            "正前方" to setOf("正远方", "正前方"),
            "右侧" to setOf("右前侧", "右近侧")
        )

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DetectionProcessor? = null

        var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

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
            0,
            BATCH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
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

            // 紧急目标单独处理
            batch.filter { isCriticalDetection(it) }
                .forEach { processSingleDetection(it) }

            // 非紧急目标合并处理
            val nonCritical = batch.filterNot { isCriticalDetection(it) }
            if (nonCritical.isNotEmpty()) {
                val mergedMessages = mergeRegionDetections(nonCritical)
                mergedMessages.forEach { (superRegion, labels) ->
                    if (labels.isNotEmpty()) {
                        processSuperRegion(superRegion, labels)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessBatch", "批处理失败", e)
        }
    }

    // 区域合并逻辑
    private fun mergeRegionDetections(detections: List<Detection>): Map<String, Set<String>> {
        val regionMap = mutableMapOf<String, MutableSet<String>>()

        detections.forEach { detection ->
            detection.categories.firstOrNull()?.takeIf { it.score >= confidenceThreshold }?.let { category ->
                val originalLabel = category.label.lowercase()
                if (originalLabel !in DANGEROUS_LABELS) return@let

                val label = getChineseLabel(originalLabel)
                val direction = calculateDirection(detection.boundingBox)

                // 查找所属大区域
                superRegions.forEach { (superRegion, subRegions) ->
                    if (subRegions.contains(direction)) {
                        regionMap.getOrPut(superRegion) { mutableSetOf() }.add(label)
                    }
                }
            }
        }
        return regionMap
    }

    // 处理单个检测
    private fun processSingleDetection(result: Detection) {
        result.categories
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= confidenceThreshold }
            ?.let { category ->
                val originalLabel = category.label.lowercase()
                if (originalLabel !in DANGEROUS_LABELS) return@let

                val label = getChineseLabel(originalLabel)
                val direction = calculateDirection(result.boundingBox)

                // 新增：检查是否为区域合并消息中的物体
                if (isPartOfSuperRegion(label, direction)) {
                    Log.d("RegionConflict", "跳过区域合并中的物体: $label ($direction)")
                    return@let
                }

                // 位置稳定性检查
                val lastPosition = positionMemory[label]
                val isAdjacentPosition = lastPosition?.let { isAdjacentRegions(it, direction) } == true
                val positionRecentlyChanged = positionChangeTimes[label]?.let {
                    System.currentTimeMillis() - it < POSITION_CHANGE_COOLDOWN
                } ?: false

                // 避免同一物体在相邻位置重复播报
                if (positionRecentlyChanged && isAdjacentPosition) {
                    Log.d("PositionStability", "忽略相邻位置变化: $label ($lastPosition → $direction)")
                    return@let
                }

                // 更新位置记忆
                positionMemory[label] = direction
                positionChangeTimes[label] = System.currentTimeMillis()

                buildDetectionMessage(result, label, direction)?.let { (message, dir, pri) ->
                    messageQueueManager.enqueueMessage(
                        message = message,
                        direction = dir,
                        priority = pri,
                        label = label,
                        vibrationPattern = when (pri) {
                            MessageQueueManager.MsgPriority.CRITICAL -> longArrayOf(0, 500)
                            MessageQueueManager.MsgPriority.HIGH -> longArrayOf(0, 300)
                            else -> longArrayOf(0, 200)
                        }
                    )
                }
            }
    }

    // 新增：检查物体是否属于大区域消息
    private fun isPartOfSuperRegion(label: String, direction: String): Boolean {
        return positionMemory[label]?.let { lastRegion ->
            // 检查当前方向是否属于大区域
            superRegions.any { (superRegion, subRegions) ->
                superRegion == lastRegion && direction in subRegions
            }
        } ?: false
    }

    // 区域相邻关系判断
    private fun isAdjacentRegions(pos1: String, pos2: String): Boolean {
        // 定义区域相邻关系
        val adjacencyMap = mapOf(
            "左远侧" to setOf("左近侧", "正远方"),
            "左近侧" to setOf("左远侧", "正前方"),
            "正远方" to setOf("左远侧", "正前方", "右前侧"),
            "正前方" to setOf("左近侧", "正远方", "右近侧"),
            "右前侧" to setOf("正远方", "右近侧"),
            "右近侧" to setOf("右前侧", "正前方")
        )
        return adjacencyMap[pos1]?.contains(pos2) == true
    }

    // 构建检测消息
    private fun buildDetectionMessage(
        result: Detection,
        label: String,
        direction: String
    ): Triple<String, String, MessageQueueManager.MsgPriority>? {
        if (label.isEmpty()) return null

        val context = contextMemory.compute(label) { _, v ->
            v?.apply { speedFactor = max(0.5f, speedFactor * 0.9f) } ?: ObjectContext()
        }!!

        return when {
            isCriticalDetection(result) -> Triple(
                "$direction$label",
                direction,
                MessageQueueManager.MsgPriority.CRITICAL
            )

            shouldReport(context, direction) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Triple(
                    "$direction$label",
                    direction,
                    getDirectionPriority(direction)
                )
            }
            else -> null
        }
    }

    private fun isCriticalDetection(result: Detection): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && result.boundingBox.width() > 0.25f
    }

    private fun shouldReport(
        context: ObjectContext,
        regionKey: String
    ): Boolean {
        val baseInterval = when (regionKey) {
            "正前方" -> MIN_REPORT_INTERVAL_MS / 2
            "左侧", "右侧" -> (MIN_REPORT_INTERVAL_MS * 3) / 2
            else -> MIN_REPORT_INTERVAL_MS
        }
        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }

    // 大区域消息生成
    private fun buildSuperRegionMessage(superRegion: String, labels: Set<String>): String {
        return when (labels.size) {
            1 -> "$superRegion${labels.first()}"
            2 -> "$superRegion${labels.joinToString("和")}"
            else -> "$superRegion${labels.take(2).joinToString("、")}等"
        }
    }

    // 处理大区域消息（增强过滤逻辑）
    private fun processSuperRegion(
        superRegion: String,
        labels: Set<String>
    ) {
        // 过滤已单独报告的物体
        val filteredLabels = labels.filterNot { label ->
            // 检查是否在最近1秒内报告过该物体
            positionChangeTimes[label]?.let {
                System.currentTimeMillis() - it < 1000
            } ?: false
        }

        if (filteredLabels.isEmpty()) return

        val message = buildSuperRegionMessage(superRegion, filteredLabels.toSet())
        if (message.isEmpty()) return

        val context = contextMemory.compute(superRegion) { _, v -> v ?: ObjectContext() }!!
        if (shouldReport(context, superRegion)) {
            context.lastReportTime = System.currentTimeMillis()
            messageQueueManager.enqueueMessage(
                message = message,
                direction = superRegion,
                priority = getSuperRegionPriority(superRegion),
                label = "区域合并",
                vibrationPattern = longArrayOf(0, 200)
            )

            // 更新区域内所有物体的位置记忆
            filteredLabels.forEach { label ->
                positionMemory[label] = superRegion
                positionChangeTimes[label] = System.currentTimeMillis()
            }
        }
    }

    // 方向计算逻辑
    fun calculateDirection(box: RectF): String {
        if (imageWidth <= 0 || imageHeight <= 0) return "未知"

        // 使用图像实际尺寸归一化
        val centerX = box.centerX() / imageWidth
        val centerY = box.centerY() / imageHeight

        // 更精确的区域划分算法
        return when {
            centerX < 0.3 -> {
                when {
                    centerY < 0.4 -> "左远侧"
                    centerY < 0.7 -> "左近侧"
                    else -> "左近侧" // 底部区域
                }
            }
            centerX < 0.7 -> {
                when {
                    centerY < 0.4 -> "正远方"
                    centerY < 0.7 -> "正前方"
                    else -> "正前方" // 底部区域
                }
            }
            else -> {
                when {
                    centerY < 0.4 -> "右前侧"
                    centerY < 0.7 -> "右近侧"
                    else -> "右近侧" // 底部区域
                }
            }
        }
    }

    private fun getDirectionPriority(direction: String): MessageQueueManager.MsgPriority {
        return when {
            direction.contains("正前") -> MessageQueueManager.MsgPriority.CRITICAL
            direction.contains("近") -> MessageQueueManager.MsgPriority.HIGH
            else -> MessageQueueManager.MsgPriority.NORMAL
        }
    }

    // 标签映射
    fun getChineseLabel(original: String): String {
        return when (original.lowercase()) {
            "person" -> "行人"
            "car" -> "汽车"
            "bus" -> "公交"
            "truck" -> "卡车"
            "motorcycle" -> "摩托"
            "bicycle" -> "自行车"
            "traffic light" -> "红绿灯"
            else -> "非关键标注障碍物"
        }
    }

    private fun getSuperRegionPriority(superRegion: String): MessageQueueManager.MsgPriority {
        return when (superRegion) {
            "正前方" -> MessageQueueManager.MsgPriority.CRITICAL
            "左侧", "右侧" -> MessageQueueManager.MsgPriority.HIGH
            else -> MessageQueueManager.MsgPriority.NORMAL
        }
    }

}