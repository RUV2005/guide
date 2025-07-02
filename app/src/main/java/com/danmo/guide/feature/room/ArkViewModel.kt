package com.danmo.guide.feature.room

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.volcengine.ark.runtime.model.completion.chat.*
import com.volcengine.ark.runtime.service.ArkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ArkViewModel : ViewModel() {
    companion object {
        private const val TAG = "ArkViewModel"
        private const val MODEL_NAME = "doubao-1-5-thinking-vision-pro-250428"
    }

    // 状态管理
    private val _apiResponse = MutableLiveData("")
    val apiResponse: LiveData<String> = _apiResponse

    private val _errorMessage = MutableLiveData("")
    val errorMessage: LiveData<String> = _errorMessage

    // 服务实例
    private val arkService by lazy { createArkService() }

    private fun createArkService(): ArkService {
        // 实际项目中应从安全源获取
        val apiKey = "7406a191-85c0-47de-a74a-3247cfd4c885"

        return ArkService.builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 5
                maxRequestsPerHost = 3
            })
            .connectionPool(ConnectionPool(5, 1, TimeUnit.SECONDS))
            .apiKey(apiKey)
            .build()
    }

    fun sendVisionRequestWithImage(base64Image: String, prompt: String) {
        viewModelScope.launch {
            try {
                // 确保 Base64 数据格式正确
                val cleanedBase64 = base64Image.split(',')[1] // 移除前缀如 `data:image/jpeg;base64,`

                val request = createChatRequest(base64Image, prompt, isBase64 = true)

                Log.d(TAG, "请求大小: ${cleanedBase64.length / 1024}KB")

                val response = withContext(Dispatchers.IO) {
                    arkService.createChatCompletion(request)
                }

                _apiResponse.value = response.choices.joinToString("\n") {
                    it.message.content?.toString() ?: "无内容返回"
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e is SocketTimeoutException -> "请求超时（30秒）"
                    e is IOException && e.message?.contains("400") == true -> "服务器拒绝：图片格式或大小无效"
                    e.message?.contains("413") == true -> "图片过大，服务器拒绝"
                    else -> "错误: ${e.message ?: "未知错误"}"
                }
                _errorMessage.value = errorMsg
                Log.e(TAG, "API请求失败", e)
            }
        }
    }

    private fun createChatRequest(
        imageContent: String,
        prompt: String,
        isBase64: Boolean = true
    ): ChatCompletionRequest {
        val messages = mutableListOf<ChatMessage>()
        val contentParts = mutableListOf<ChatCompletionContentPart>()

        // 图片部分
        val imagePart = if (isBase64) {
            // Base64 图片
            ChatCompletionContentPart().apply {
                type = "image_url"
                imageUrl = ChatCompletionContentPart.ChatCompletionContentPartImageURL().apply {
                    url = imageContent
                }
            }
        } else {
            // URL 图片
            ChatCompletionContentPart().apply {
                type = "image_url"
                imageUrl = ChatCompletionContentPart.ChatCompletionContentPartImageURL().apply {
                    url = imageContent
                }
            }
        }
        contentParts.add(imagePart)

        // 文本部分
        contentParts.add(ChatCompletionContentPart().apply {
            type = "text"
            text = prompt
        })

        messages.add(
            ChatMessage.builder()
                .role(ChatMessageRole.USER)
                .multiContent(contentParts)
                .build()
        )

        return ChatCompletionRequest.builder()
            .model(MODEL_NAME)
            .messages(messages)
            .temperature(0.7)
            .maxTokens(1024)
            .build()
    }

    override fun onCleared() {
        super.onCleared()
        arkService.shutdownExecutor()
    }

    init {
        _apiResponse.value = ""
        _errorMessage.value = ""
    }
}