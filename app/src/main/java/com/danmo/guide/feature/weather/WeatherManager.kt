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
import java.util.concurrent.TimeUnit

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
                        return@withContext gson.fromJson(json, GeoLocation::class.java)
                    }
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
                val url = "https://api.openweathermap.org/data/2.5/weather?" +
                        "lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"

                val request = Request.Builder().url(url).build()
                response = client.newCall(request).execute()

                if (response!!.isSuccessful) {
                    response!!.body?.string()?.let { json ->
                        return@withContext gson.fromJson(json, WeatherData::class.java).apply {
                            // 数据完整性检查
                            if (name.isNullOrEmpty() || weather.isNullOrEmpty()) {
                                throw IllegalStateException("返回数据不完整")
                            }
                        }
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

    // 生成语音文本
    fun generateSpeechText(weather: WeatherData): String {
        return buildString {
            try {
                append("当前位置：${getChineseCityName(weather.name)}，")
                append("温度：${weather.main?.temp?.toInt() ?: "未知"}℃，")
                append("体感温度：${weather.main?.feelsLike?.toInt() ?: "未知"}℃，")
                append("天气状况：${weather.weather?.firstOrNull()?.description ?: "未知"}，")
                append("风速：${weather.wind?.speed?.toInt() ?: "未知"}米每秒，")
                append("湿度：${weather.main?.humidity ?: "未知"}%")
            } catch (e: Exception) {
                Log.e("Weather", "生成语音文本失败", e)
                append("天气信息生成异常，请稍后再试")
            }
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