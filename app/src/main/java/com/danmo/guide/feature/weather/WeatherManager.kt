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
        // 城市名称映射表（可扩展）
        private val chineseCityMap = mapOf(
            "Wuhan" to "武汉",
            "Beijing" to "北京",
            "Shanghai" to "上海",
            "Guangzhou" to "广州",
            "Shenzhen" to "深圳",
            "Chengdu" to "成都",
            "Chongqing" to "重庆",
            "Hangzhou" to "杭州",
            "Nanjing" to "南京",
            "Tianjin" to "天津"
        )

        // 城市名称转换方法
        fun getChineseCityName(englishName: String?): String {
            return englishName?.let {
                chineseCityMap[it] ?: it
            } ?: "未知地区"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // 获取IP地址定位
    suspend fun getLocationByIP(): GeoLocation? {
        var response: Response? = null
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.IPDATA_API_KEY
                val request = Request.Builder()
                    .url("https://api.ipdata.co?api-key=$apiKey")
                    .build()

                response = client.newCall(request).execute()
                if (response!!.isSuccessful) {
                    response!!.body?.string()?.let { json ->
                        val geoLocation = gson.fromJson(json, GeoLocation::class.java)
                        // 添加日志记录城市信息
                        Log.d("Weather", "IP定位成功，城市：${geoLocation.city}")
                        return@withContext geoLocation
                    }
                } else {
                    // 添加日志记录请求失败
                    Log.e("Weather", "IP定位请求失败，状态码：${response?.code}")
                }
                null
            } catch (e: Exception) {
                Log.e("Weather", "IP定位失败", e)
                null
            } finally {
                response?.close()
            }
        }
    }

    // 获取天气数据
    suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        var response: Response? = null
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.OPENWEATHER_API_KEY
                val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"

                val request = Request.Builder().url(url).build()
                response = client.newCall(request).execute()

                if (response!!.isSuccessful) {
                    response!!.body?.string()?.let { json ->
                        val weatherData = gson.fromJson(json, WeatherData::class.java)
                        // 数据完整性检查
                        if (weatherData.name.isNullOrEmpty() || weatherData.weather.isNullOrEmpty()) {
                            throw IllegalStateException("返回数据不完整")
                        }
                        // 添加日志记录城市信息
                        Log.d("Weather", "获取天气数据成功，城市：${weatherData.name}")
                        return@withContext weatherData
                    }
                } else {
                    // 添加日志记录请求失败
                    Log.e("Weather", "天气数据请求失败，状态码：${response?.code}")
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

    fun generateSpeechText(weather: WeatherData, time: String? = null): String {
        return buildString {
            try {
                // 如果传入的时间为 null，则获取系统当前时间
                val currentTime = time ?: System.currentTimeMillis().let {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(it)
                }
                val cityName = getChineseCityName(weather.name)
                val temp = weather.main?.temp?.toInt() ?: 999
                val feelsLike = weather.main?.feelsLike?.toInt() ?: 999
                val weatherDesc = weather.weather?.firstOrNull()?.description ?: ""
                val windSpeed = weather.wind?.speed?.toInt() ?: 0

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
                append("$cityName,现在")

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

                // 风力提醒
                when {
                    windSpeed > 7 -> append("，外面风好大哦，出去要注意安全呀")
                    windSpeed in 5..7 -> append("，风有点大，要注意一下哦")
                    windSpeed in 3..4 -> append("，有微微的风，很舒服呢")
                }

                // 智能生活建议
                when {
                    "雨" in weatherDesc -> append("，出门一定要记得带伞哦")
                    temp < 5 -> append("，天气冷，秋裤一定要穿好哦")
                    temp > 30 && "晴" in weatherDesc -> append("，天气热，记得涂防晒霜哦")
                    windSpeed > 5 -> append("，风大，出门戴好口罩哦")
                    else -> append("，今天天气不错，出去走走吧")
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
    data class GeoLocation(
        val latitude: Double,
        val longitude: Double,
        val city: String,
        val country_name: String
    )

    data class WeatherData(
        val name: String?,      // 城市英文名
        val main: Main?,
        val weather: List<Weather>?,
        val wind: Wind?
    ) {
        data class Main(
            val temp: Float?,
            @SerializedName("feels_like")  // 添加注解映射JSON字段
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