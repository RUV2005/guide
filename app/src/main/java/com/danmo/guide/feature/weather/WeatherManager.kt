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
        // 缓存有效期配置
        private const val IP_CACHE_EXPIRY_MINUTES = 30L    // IP定位缓存30分钟
        private const val WEATHER_CACHE_EXPIRY_MINUTES = 10L // 天气数据缓存10分钟

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

    // 获取IP地址定位（优化缓存处理版本）
    suspend fun getLocationByIP(): GeoLocation? = withContext(Dispatchers.IO) {
        // 尝试读取缓存
        sharedPreferences.getString("last_ip_location", null)?.let { cachedJson ->
            try {
                val cacheEntry = gson.fromJson(cachedJson, IPCacheEntry::class.java)
                val isCacheValid = System.currentTimeMillis() - cacheEntry.timestamp < IP_CACHE_EXPIRY_MINUTES * 60 * 1000

                when {
                    isCacheValid -> {
                        Log.d("Weather", "使用缓存的IP定位数据")
                        return@withContext cacheEntry.geoLocation
                    }
                    else -> {
                        Log.d("Weather", "IP定位缓存已过期，自动清除")
                        sharedPreferences.edit().remove("last_ip_location").apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("Weather", "解析IP定位缓存失败，清除无效数据", e)
                sharedPreferences.edit().remove("last_ip_location").apply()
            }
        }

        // 无有效缓存时请求API
        var response: Response? = null
        try {
            val request = Request.Builder()
                .url("https://api.ipdata.co?api-key=${BuildConfig.IPDATA_API_KEY}")
                .build()
            response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body!!.string().let { json ->
                    gson.fromJson(json, GeoLocation::class.java).also { geoLocation ->
                        // 写入新缓存
                        sharedPreferences.edit().putString(
                            "last_ip_location",
                            gson.toJson(IPCacheEntry(geoLocation, System.currentTimeMillis()))
                        ).apply()
                    }
                }
            } else null
        } catch (e: Exception) {
            Log.e("Weather", "IP定位失败", e)
            null
        } finally {
            response?.close()
        }
    }

    // 获取天气数据（优化缓存处理版本）
    suspend fun getWeather(lat: Double, lon: Double): WeatherData? = withContext(Dispatchers.IO) {
        // 生成定位精度为小数点后4位的缓存键（约11米精度）
        val cacheKey = "weather_%.4f_%.4f".format(lat, lon)

        // 尝试读取缓存
        sharedPreferences.getString(cacheKey, null)?.let { cachedJson ->
            try {
                val cacheEntry = gson.fromJson(cachedJson, WeatherCacheEntry::class.java)
                val isCacheValid = System.currentTimeMillis() - cacheEntry.timestamp < WEATHER_CACHE_EXPIRY_MINUTES * 60 * 1000

                when {
                    isCacheValid -> {
                        Log.d("Weather", "使用缓存的天气数据")
                        return@withContext cacheEntry.weatherData
                    }
                    else -> {
                        Log.d("Weather", "天气缓存已过期，自动清除")
                        sharedPreferences.edit().remove(cacheKey).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("Weather", "解析天气缓存失败，清除无效数据", e)
                sharedPreferences.edit().remove(cacheKey).apply()
            }
        }

        // 无有效缓存时请求API
        var response: Response? = null
        try {
            val url = "https://api.openweathermap.org/data/2.5/weather" +
                    "?lat=$lat&lon=$lon&appid=${BuildConfig.OPENWEATHER_API_KEY}&units=metric&lang=zh_cn"
            response = client.newCall(Request.Builder().url(url).build()).execute()

            if (response.isSuccessful) {
                response.body!!.string().let { json ->
                    gson.fromJson(json, WeatherData::class.java).apply {
                        // 数据有效性检查
                        require(!name.isNullOrEmpty() && !weather.isNullOrEmpty()) { "返回数据不完整" }

                        // 写入新缓存
                        sharedPreferences.edit().putString(
                            cacheKey,
                            gson.toJson(WeatherCacheEntry(this, System.currentTimeMillis()))
                        ).apply()
                    }
                }
            } else null
        } catch (e: Exception) {
            Log.e("Weather", "天气查询失败", e)
            null
        } finally {
            response?.close()
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

    private val sharedPreferences by lazy {
        context.getSharedPreferences("WeatherCache", Context.MODE_PRIVATE)
    }

    // 缓存条目数据类
    private data class IPCacheEntry(
        val geoLocation: GeoLocation,
        val timestamp: Long
    )

    private data class WeatherCacheEntry(
        val weatherData: WeatherData,
        val timestamp: Long
    )

    // 数据类
    data class GeoLocation(
        val latitude: Double,
        val longitude: Double,
        val city: String,
        val country_name: String
    )

    data class WeatherData(
        val name: String?,
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