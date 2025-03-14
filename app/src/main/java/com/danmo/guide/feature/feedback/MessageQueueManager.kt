package com.danmo.guide.feature.feedback

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MessageQueueManager {
    private val speechQueue = PriorityBlockingQueue<SpeechItem>()
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSpeechId: String? = null
    private val queueLock = ReentrantLock()
    private lateinit var context: Context
    private val recentMessages = object : ConcurrentHashMap<String, Long>() {
        private val cleaner = Executors.newSingleThreadScheduledExecutor()
        init {
            cleaner.scheduleWithFixedDelay(::removeExpiredEntries, 1, 1, TimeUnit.MINUTES)
        }

        private fun removeExpiredEntries() {
            val now = System.currentTimeMillis()
            keys().asSequence().filter { get(it)?.let { ts -> now - ts > 300_000 } ?: false }
                .forEach { remove(it) }
        }
    }

    private data class SpeechItem(
        val text: String,
        val direction: String,
        val basePriority: MsgPriority,
        val label: String,
        val timestamp: Long = System.currentTimeMillis(),
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) : Comparable<SpeechItem> {
        private val ageFactor: Float get() = synchronized(this) {
            1 - (System.currentTimeMillis() - timestamp).coerceAtMost(5000L) / 5000f
        }

        val dynamicPriority: Int get() = when (basePriority) {
            MsgPriority.CRITICAL -> (1000 * ageFactor).toInt()
            MsgPriority.HIGH -> (800 * ageFactor).toInt()
            MsgPriority.NORMAL -> (500 * ageFactor).toInt()
        }

        override fun compareTo(other: SpeechItem): Int = other.dynamicPriority.compareTo(this.dynamicPriority)

        override fun equals(other: Any?): Boolean = (other as? SpeechItem)?.let {
            label == it.label && direction == it.direction && text == it.text
        } ?: false

        override fun hashCode(): Int = label.hashCode() + 31 * direction.hashCode()
    }

    enum class MsgPriority { CRITICAL, HIGH, NORMAL }

    companion object {
        @Volatile
        private var instance: MessageQueueManager? = null

        fun getInstance(context: Context): MessageQueueManager {
            return instance ?: synchronized(this) {
                instance ?: MessageQueueManager().also { 
                    instance = it
                    it.context = context.applicationContext
                }
            }
        }
    }

    fun enqueueMessage(message: String, direction: String, priority: MsgPriority, label: String, vibrationPattern: LongArray? = null) {
        val messageKey = "${label}_${direction}_${message.hashCode()}"
        val suppressDuration = when (priority) {
            MsgPriority.CRITICAL -> 2000
            MsgPriority.HIGH -> 1500
            MsgPriority.NORMAL -> 1000
        }

        if (recentMessages[messageKey]?.let {
                System.currentTimeMillis() - it < suppressDuration
            } == true) return

        queueLock.withLock {
            if (!speechQueue.any { it.label == label && it.direction == direction && it.text == message }) {
                recentMessages[messageKey] = System.currentTimeMillis()

                val item = SpeechItem(
                    text = message,
                    direction = direction,
                    basePriority = priority,
                    label = label,
                    vibrationPattern = vibrationPattern
                )
                speechQueue.offer(item)
                processNextInQueue(TTSManager.getInstance(context), VibrationManager.getInstance(context))
            }
        }
    }

    fun processNextInQueue(ttsManager: TTSManager, vibrationManager: VibrationManager) {
        queueLock.withLock {
            if (currentSpeechId != null || speechQueue.isEmpty()) return@withLock
            speechQueue.poll()?.let { item ->
                currentSpeechId = item.id
                executor.submit { 
                    try {
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

    private fun processItem(item: SpeechItem, ttsManager: TTSManager, vibrationManager: VibrationManager) {
        try {
            item.vibrationPattern?.let { pattern ->
                vibrationManager.vibrate(pattern)
            }
            ttsManager.speak(item.text, item.id)
        } finally {
            queueLock.withLock {
                currentSpeechId = null
                processNextInQueue(ttsManager, vibrationManager)
            }
        }
    }

    fun clearQueue() {
        queueLock.withLock {
            speechQueue.clear()
            currentSpeechId = null
        }
    }

    fun shutdown() {
        executor.shutdown()
        clearQueue()
    }
} 