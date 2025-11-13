package com.danmo.guide.feature.weather
import android.util.Log
import com.danmo.guide.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
class WeatherManager {
    companion object;
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    // 获取天气数据
/**
 * 暂停函数，用于获取指定地理位置的天气数据
 * 使用suspend关键字表明此函数需要在协程中调用，以支持异步执行
 *
 * @param lat 地理纬度，用于指定地点
 * @param lon 地理经度，用于指定地点
 *
 * @return WeatherData? 返回WeatherData对象，表示天气数据，如果请求失败或解析错误，则返回null
 */
suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
    val trace = Firebase.performance.newTrace("network_request_weather")
    trace.start()

    var response: Response? = null
    return withContext(Dispatchers.IO) {
        try {
            // 从 BuildConfig 中获取 OpenWeather API 密钥
            val apiKey = BuildConfig.OPENWEATHER_API_KEY
            // 添加日志输出，确认是否正确读取了密钥
            Log.d("Weather", "API 密钥: $apiKey")
            Log.d("Weather", "API 密钥长度: ${apiKey.length}")
            Log.d("Weather", "BuildConfig 类: ${BuildConfig::class.java.name}")
            
            // 如果 API 密钥为空，记录错误
            if (apiKey.isBlank()) {
                Log.e("Weather", "错误：API 密钥为空！请检查 local.properties 和 build.gradle 配置")
                return@withContext null
            }

            // 构建请求 URL，包含纬度、经度、API 密钥、单位和语言参数
            val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"
            Log.d("Weather", "请求天气数据的 URL: $url")

            // 创建 HTTP 请求
            val request = Request.Builder().url(url).build()
            // 执行 HTTP 请求并获取响应
            response = client.newCall(request).execute()

            if (response!!.isSuccessful) {
                response!!.body?.string().let { json ->
                    Log.d("Weather", "天气数据响应: $json")
                    return@withContext gson.fromJson(json, WeatherData::class.java)
                }
            }
            null
        } catch (e: Exception) {
            Log.e("Weather", "天气查询失败", e)
            null
        } finally {
            response?.close()
            trace.stop()
        }
    }
}

    fun generateSpeechText(weather: WeatherData, cityName: String, time: String? = null): String {
        return buildString {
            try {
                // 如果传入的时间为 null，则获取系统当前时间
                time ?: System.currentTimeMillis().let {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(it)
                }
                val temp = weather.main?.temp?.toInt() ?: 999
                weather.main?.feelsLike?.toInt() ?: 999
                val weatherDesc = weather.weather?.firstOrNull()?.description ?: ""
                weather.wind?.speed?.toInt() ?: 0
                Log.d("Weather", "生成播报文本: 城市=$cityName, 温度=$temp, 天气描述=$weatherDesc")
                // 温度播报
                when {
                    temp == 999 -> append("暂时无法获取温度哦")
                    temp <= 0 -> append("当前温度：零下${abs(temp)}度，注意保暖呀")
                    temp in 1..10 -> append("当前温度：${temp}度，出门穿厚点哦")
                    temp in 11..20 -> append("当前温度：${temp}度，温度舒适呢")
                    temp in 21..28 -> append("当前温度：${temp}度，穿短袖就行啦")
                    else -> append("当前温度：${temp}度，注意防暑哦")
                }
                // 天气现象播报
                when {
                    "雨" in weatherDesc -> append("，天气有雨，记得带伞")
                    "雪" in weatherDesc -> append("，外面有雪，注意保暖呀")
                    "雷" in weatherDesc -> append("，外面有雷阵雨，谨慎出行哦")
                    "晴" in weatherDesc && temp > 25 -> append("，阳光明媚")
                    "云" in weatherDesc -> append("，有点云，天气不错呀")
                    "雾" in weatherDesc -> append("，外面有雾，谨慎出行哦")
                }
            } catch (e: Exception) {
                Log.e("Weather", "生成语音文本失败", e)
            }
        }.run {
            // 智能标点处理
            replace("，)", ")")
                .replace("，。", "。")
                .replace("！。", "！")
        }
    }
    // 数据类
    data class WeatherData(
        var name: String?,      // 城市名称（改为 var 以便修改）
        val main: Main?,
        val weather: List<Weather>?,
        val wind: Wind?
    ) {
        data class Main(
            val temp: Float?,
            @SerializedName("feels_like")
            val feelsLike: Float?,
            val humidity: Int?
        )
        data class Weather(
            val description: String?
        )
        data class Wind(
            val speed: Float?
        )
    }
}