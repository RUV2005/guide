package com.danmo.guide.feature.weather

import android.content.Context
import android.util.Log
import com.danmo.guide.BuildConfig
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

class WeatherManager(private val context: Context) {
    companion object {
        // 城市名称转换方法（不再使用映射表）
        fun getChineseCityName(englishName: String?): String {
            return englishName ?: "未知地区"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // 获取天气数据
    suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        var response: Response? = null
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.OPENWEATHER_API_KEY
                val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"

                Log.d("Weather", "请求天气数据的 URL: $url")

                val request = Request.Builder().url(url).build()
                response = client.newCall(request).execute()

                if (response!!.isSuccessful) {
                    response!!.body?.string()?.let { json ->
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
            }
        }
    }

    fun generateSpeechText(weather: WeatherData, cityName: String, time: String? = null): String {
        return buildString {
            try {
                // 如果传入的时间为 null，则获取系统当前时间
                val currentTime = time ?: System.currentTimeMillis().let {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(it)
                }
                val temp = weather.main?.temp?.toInt() ?: 999
                val feelsLike = weather.main?.feelsLike?.toInt() ?: 999
                val weatherDesc = weather.weather?.firstOrNull()?.description ?: ""
                val windSpeed = weather.wind?.speed?.toInt() ?: 0

                Log.d("Weather", "生成播报文本: 城市=$cityName, 温度=$temp, 天气描述=$weatherDesc")

                append("亲爱的先行体验官，")
                // 智能时间问候
                val hourPart = currentTime.split(":").getOrNull(0)?.toIntOrNull()
                if (hourPart != null) {
                    append(
                        when {
                            hourPart in 5..9 -> "早上好呀！"
                            hourPart in 10..12 -> "上午好呀！"
                            hourPart in 13..17 -> "下午好呀！"
                            hourPart in 18..21 -> "晚上好呀！"
                            else -> "您好"
                        }
                    )
                } else {
                    append("您好")
                }

                // 城市播报
                append("您目前位于$cityName,现在")

                // 温度播报
                when {
                    temp == 999 -> append("暂时获取不到温度数据哦")
                    temp <= 0 -> append("零下${abs(temp)}度，外面很冷，注意保暖呀")
                    temp in 1..10 -> append("${temp}度，有点冷，出门穿厚点哦")
                    temp in 11..20 -> append("${temp}度，温度舒适呢")
                    temp in 21..28 -> append("${temp}度，穿短袖就行啦")
                    temp > 28 -> append("${temp}度，有点热，注意防暑哦")
                }

                // 天气现象播报
                when {
                    "雨" in weatherDesc -> append("，外面在下雨哦，记得带伞")
                    "雪" in weatherDesc -> append("，外面在下雪哦，出去玩要注意保暖呀")
                    "雷" in weatherDesc -> append("，外面有雷阵雨，要注意安全哦")
                    "晴" in weatherDesc && temp > 25 -> append("，阳光明媚，注意防晒哦")
                    "云" in weatherDesc -> append("，有点云，天气不错呀")
                    "雾" in weatherDesc -> append("，外面有雾，出行要注意安全哦")
                }

                // 智能生活建议
                when {
                    "雨" in weatherDesc -> append("，出门一定要记得带伞哦")
                    temp < 5 -> append("，天气冷，秋裤一定要穿好哦")
                    temp > 30 && "晴" in weatherDesc -> append("，天气热，记得涂防晒霜哦")
                    windSpeed > 5 -> append("，风大，出门戴好口罩哦")
                }

            } catch (e: Exception) {
                Log.e("Weather", "生成语音文本失败", e)
                "天气小助手正在努力更新数据，稍后再来问我吧～"
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