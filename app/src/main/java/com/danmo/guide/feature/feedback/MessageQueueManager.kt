package com.danmo.guide.feature.feedback
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
/**
 * 消息队列管理系统，负责语音反馈的优先级排序和防抖处理
 * Message queue management system handling speech feedback prioritization and debouncing
 */
class MessageQueueManager {
    // 优先级阻塞队列（按动态优先级排序） / Priority blocking queue (sorted by dynamic priority)
    private val speechQueue = PriorityBlockingQueue<SpeechItem>()
    // 固定线程池（CPU核心数） / Fixed thread pool (number of CPU cores)
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    // 主线程Handler / Main thread handler
    private val mainHandler = Handler(Looper.getMainLooper())
    // 当前播放项ID / Current speaking item ID
    private var currentSpeechId: String? = null
    // 队列操作锁 / Queue operation lock
    private val queueLock = ReentrantLock()
    // 应用上下文 / Application context
    private lateinit var context: Context
    // 最近消息记录（防重复） / Recent message tracker (anti-spam)
    private val recentMessages = object : ConcurrentHashMap<String, Long>() {
        // 过期条目清理器 / Expired entry cleaner
        private val cleaner = Executors.newSingleThreadScheduledExecutor()
        init {
            // 每分钟清理一次过期条目 / Clean expired entries every minute
            cleaner.scheduleWithFixedDelay(::removeExpiredEntries, 1, 1, TimeUnit.MINUTES)
        }
        // 清理5分钟前的条目 / Remove entries older than 5 minutes
        private fun removeExpiredEntries() {
            val now = System.currentTimeMillis()
            keys().asSequence()
                .filter { get(it)?.let { ts -> now - ts > 300_000 } ?: false }
                .forEach { remove(it) }
        }
    }
    /**
     * 语音消息项数据结构
     * Speech message item data structure
     * @property text 播报文本 / Text to speak
     * @property direction 方位信息 / Direction information
     * @property basePriority 基础优先级 / Base priority
     * @property label 物体标签 / Object label
     * @property timestamp 时间戳 / Timestamp
     * @property vibrationPattern 振动模式 / Vibration pattern
     * @property id 唯一标识 / Unique ID
     */
    private data class SpeechItem(
        val text: String,
        val direction: String,
        val basePriority: MsgPriority,
        val label: String,
        val timestamp: Long = System.currentTimeMillis(),
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) : Comparable<SpeechItem> {
        // 时效衰减因子（5秒内线性衰减） / Age factor (linear decay within 5 seconds)
        private val ageFactor: Float get() = synchronized(this) {
            1 - (System.currentTimeMillis() - timestamp).coerceAtMost(5000L) / 5000f
        }
        // 动态优先级计算（基础优先级 * 时效因子） / Dynamic priority calculation
        val dynamicPriority: Int get() = when (basePriority) {
            MsgPriority.CRITICAL -> (1000 * ageFactor).toInt()  // 紧急消息 / Critical messages
            MsgPriority.HIGH -> (800 * ageFactor).toInt()       // 高优先级 / High priority
            MsgPriority.NORMAL -> (500 * ageFactor).toInt()     // 普通优先级 / Normal priority
        }
        // 优先级比较（降序排列） / Priority comparison (descending order)
        override fun compareTo(other: SpeechItem): Int = other.dynamicPriority.compareTo(this.dynamicPriority)
        // 相等性判断（基于标签+方向+文本） / Equality check (label + direction + text)
        override fun equals(other: Any?): Boolean = (other as? SpeechItem)?.let {
            label == it.label && direction == it.direction && text == it.text
        } ?: false
        // 哈希值计算 / Hash code calculation
        override fun hashCode(): Int = label.hashCode() + 31 * direction.hashCode()
    }
    // 消息优先级枚举 / Message priority enumeration
    enum class MsgPriority { CRITICAL, HIGH, NORMAL }
    companion object {
        // 单例实例（双重校验锁） / Singleton instance (double-checked locking)
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MessageQueueManager? = null
        // 获取单例实例 / Get singleton instance
        fun getInstance(context: Context): MessageQueueManager {
            return instance ?: synchronized(this) {
                instance ?: MessageQueueManager().also {
                    instance = it
                    it.context = context.applicationContext  // 使用应用上下文 / Use application context
                }
            }
        }
    }
    /**
     * 将消息加入队列
     * Enqueue a message
     * @param message 播报文本 / Text to speak
     * @param direction 方位信息 / Direction info
     * @param priority 消息优先级 / Message priority
     * @param label 物体标签 / Object label
     * @param vibrationPattern 振动模式（可选） / Vibration pattern (optional)
     */
    fun enqueueMessage(
        message: String,
        direction: String,
        priority: MsgPriority,
        label: String,
        vibrationPattern: LongArray? = null
    ) {
        // 生成消息唯一键 / Generate unique message key
        val messageKey = "${label}_${direction}_${message.hashCode()}"
        // 设置消息抑制时长（根据优先级） / Set message suppression duration
        val suppressDuration = when (priority) {
            MsgPriority.CRITICAL -> 2000  // 紧急消息2秒内不重复 / Critical: 2s suppression
            MsgPriority.HIGH -> 1500       // 高优先级1.5秒 / High: 1.5s
            MsgPriority.NORMAL -> 1000    // 普通优先级1秒 / Normal: 1s
        }
        // 检查重复消息 / Check duplicate messages
        if (recentMessages[messageKey]?.let {
                System.currentTimeMillis() - it < suppressDuration
            } == true) return
        queueLock.withLock {
            // 防止重复添加相同消息 / Prevent adding duplicate messages
            if (!speechQueue.any { it.label == label && it.direction == direction && it.text == message }) {
                recentMessages[messageKey] = System.currentTimeMillis()
                // 创建消息项并加入队列 / Create and enqueue speech item
                val item = SpeechItem(
                    text = message,
                    direction = direction,
                    basePriority = priority,
                    label = label,
                    vibrationPattern = vibrationPattern
                )
                speechQueue.offer(item)
                // 触发队列处理 / Trigger queue processing
                processNextInQueue(TTSManager.getInstance(context), VibrationManager.getInstance(context))
            }
        }
    }
    /**
     * 处理队列中的下一条消息
     * Process next message in queue
     * @param ttsManager 语音管理器 / TTS manager
     * @param vibrationManager 振动管理器 / Vibration manager
     */
    private fun processNextInQueue(ttsManager: TTSManager, vibrationManager: VibrationManager) {
        queueLock.withLock {
            if (currentSpeechId != null || speechQueue.isEmpty()) return@withLock
            speechQueue.poll()?.let { item ->
                currentSpeechId = item.id
                executor.submit {
                    try {
                        // 小米设备兼容性延迟 / Xiaomi device compatibility delay
                        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                            mainHandler.postDelayed({
                                processItem(item, ttsManager, vibrationManager)
                            }, 200)
                        } else {
                            processItem(item, ttsManager, vibrationManager)
                        }
                    } catch (e: Exception) {
                        Log.e("MessageQueue", "处理消息失败: ${e.message}")
                        queueLock.withLock {
                            currentSpeechId = null
                        }
                        processNextInQueue(ttsManager, vibrationManager)
                    }
                }
            }
        }
    }
    // 实际处理消息项 / Process individual item
    private fun processItem(
        item: SpeechItem,
        ttsManager: TTSManager,
        vibrationManager: VibrationManager
    ) {
        try {
            // 执行振动反馈 / Execute vibration feedback
            item.vibrationPattern?.let { pattern ->
                vibrationManager.vibrate(pattern)
            }
            // 执行语音播报 / Execute TTS
            ttsManager.speak(item.text, item.id)
        } finally {
            queueLock.withLock {
                currentSpeechId = null
                // 继续处理下一条 / Continue processing next
                processNextInQueue(ttsManager, vibrationManager)
            }
        }
    }
    // 清空消息队列 / Clear message queue
    fun clearQueue() {
        queueLock.withLock {
            speechQueue.clear()
            currentSpeechId = null
        }
    }
    // 关闭资源 / Shutdown resources
    fun shutdown() {
        executor.shutdown()  // 关闭线程池 / Shutdown thread pool
        clearQueue()
    }
}