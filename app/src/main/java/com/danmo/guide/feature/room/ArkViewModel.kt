package com.danmo.guide.feature.room

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danmo.guide.BuildConfig
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

    // -------------------- 常量 --------------------
    private val tag = "ArkViewModel"
    private val modelName = "doubao-1-5-thinking-vision-pro-250428"

    // -------------------- 数据状态 --------------------
    private val _apiResponse = MutableLiveData<String>().apply { value = "" }
    val apiResponse: LiveData<String> = _apiResponse

    private val _errorMessage = MutableLiveData<String>().apply { value = "" }
    val errorMessage: LiveData<String> = _errorMessage

    // -------------------- ArkService（延迟初始化） --------------------
    private val arkService: ArkService by lazy { createArkService() }

    private fun createArkService(): ArkService {
        // 实际项目中应从安全源获取
        val apiKey = BuildConfig.ARK_API_KEY

        return ArkService.builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 5
                maxRequestsPerHost = 3
            })
            .connectionPool(ConnectionPool(5, 1, TimeUnit.SECONDS))
            .apiKey(apiKey)
            .build()
    }

    // -------------------- API 调用入口 --------------------
    fun sendVisionRequestWithImage(base64Image: String, prompt: String) {
        viewModelScope.launch {
            try {
                // 去除可能的 data:image/...;base64, 前缀
                val cleanedBase64 = base64Image.run {
                    if (contains(',')) split(',')[1] else this
                }

                val request = createChatRequest(cleanedBase64, prompt)
                Log.d(tag, "请求大小: ${cleanedBase64.length / 1024} KB")

                val response = withContext(Dispatchers.IO) {
                    arkService.createChatCompletion(request)
                }

                _apiResponse.value = response.choices.joinToString("\n") {
                    it.message.content?.toString() ?: "无内容返回"
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // -------------------- 错误处理 --------------------
    private fun handleError(e: Exception) {
        val errorMsg = when {
            e is SocketTimeoutException -> "请求超时（30秒）"
            e is IOException && e.message?.contains("400") == true ->
                "服务器拒绝：图片格式或大小无效"
            e.message?.contains("413") == true ->
                "图片过大，服务器拒绝"
            else -> "错误: ${e.message ?: "未知错误"}"
        }
        _errorMessage.value = errorMsg
        Log.e(tag, "API请求失败", e)
    }

    // -------------------- 构造 ChatCompletionRequest --------------------
    private fun createChatRequest(
        imageContent: String,
        prompt: String
    ): ChatCompletionRequest {
        val contentParts = mutableListOf<ChatCompletionContentPart>().apply {
            // 图片
            add(ChatCompletionContentPart().apply {
                type = "image_url"
                imageUrl = ChatCompletionContentPart.ChatCompletionContentPartImageURL().apply {
                    url = "data:image/jpeg;base64,$imageContent"
                }
            })
            // 文本
            add(ChatCompletionContentPart().apply {
                type = "text"
                text = prompt
            })
        }

        return ChatCompletionRequest.builder()
            .model(modelName)
            .messages(
                listOf(
                    ChatMessage.builder()
                        .role(ChatMessageRole.USER)
                        .multiContent(contentParts)
                        .build()
                )
            )
            .temperature(0.7)
            .maxTokens(1024)
            .build()
    }

    // -------------------- 清理资源 --------------------
    override fun onCleared() {
        super.onCleared()
        arkService.shutdownExecutor()
    }
}