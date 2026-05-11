package com.xiaozhi.aiservice.data.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.AlarmClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.data.settings.DeviceSettings
import com.xiaozhi.aiservice.di.AppContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = true,
    val handler: suspend (Map<String, Any>) -> JsonObject
)

object MCPToolRegistry {
    private val tools = mutableListOf<MCPTool>()
    private var cachedToolList: JsonArray? = null

    init {
        registerBuiltInTools()
    }

    private fun registerBuiltInTools() {
        registerTool(
            name = "get_device_info",
            description = "获取Android设备的基本信息，包括厂商、型号、系统版本等",
            inputSchema = jsonSchema()
        ) { _ ->
            JsonObject().apply {
                addProperty("manufacturer", android.os.Build.MANUFACTURER)
                addProperty("model", android.os.Build.MODEL)
                addProperty("android_version", android.os.Build.VERSION.RELEASE)
                addProperty("sdk_int", android.os.Build.VERSION.SDK_INT)
                addProperty("brand", android.os.Build.BRAND)
                addProperty("device", android.os.Build.DEVICE)
            }
        }

        registerTool(
            name = "get_current_time",
            description = "获取当前系统时间，支持指定时间格式",
            inputSchema = jsonSchema(
                "format" to jsonStringProp("时间格式，如 yyyy-MM-dd HH:mm:ss，默认 yyyy-MM-dd HH:mm:ss")
            )
        ) { args ->
            val format = args["format"] as? String ?: "yyyy-MM-dd HH:mm:ss"
            JsonObject().apply {
                addProperty("time", SimpleDateFormat(format, Locale.getDefault()).format(Date()))
                addProperty("timestamp", System.currentTimeMillis() / 1000)
            }
        }

        registerTool(
            name = "get_battery_level",
            description = "获取当前设备的电池电量百分比",
            inputSchema = jsonSchema()
        ) { _ ->
            val bm = AppContainer.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            JsonObject().apply {
                addProperty("level", level)
                addProperty("status", if (level >= 0) "ok" else "unavailable")
            }
        }

        registerTool(
            name = "calculate",
            description = "执行简单的四则运算，支持加减乘除和括号，如 1+2*3",
            inputSchema = jsonSchema(
                "expression" to jsonStringProp("数学表达式"),
                required = listOf("expression")
            )
        ) { args ->
            val expr = args["expression"] as? String ?: ""
            JsonObject().apply {
                addProperty("expression", expr)
                addProperty("result", evaluateSimpleExpression(expr))
            }
        }

        registerTool(
            name = "get_weather",
            description = "获取指定城市的天气信息，包括温度、湿度、风速、天气状况等",
            inputSchema = jsonSchema(
                "city" to jsonStringProp("城市名称，默认为设备当前位置")
            )
        ) { args ->
            val city = args["city"] as? String ?: ""
            try {
                val (lat, lon) = if (city.isNotEmpty()) {
                    geoCodeCity(city)
                } else {
                    val loc = getCurrentLocation() ?: throw Exception("无法获取当前位置，请授予定位权限或提供城市名称")
                    Pair(loc.first, loc.second)
                }

                val cacheKey = "${lat},${lon}"
                evictExpiredWeatherCache()  // 清理过期条目后再查缓存
                val cached = weatherCache[cacheKey]
                if (cached != null && System.currentTimeMillis() - cached.timestamp < 300_000) {
                    return@registerTool cached.data
                }

                val weatherJson = httpGetWeather(lat, lon)
                val displayCity = if (city.isNotEmpty()) city else reverseGeoCode(lat, lon)

                val current = weatherJson.getAsJsonObject("current")
                val temp = current.get("temperature_2m").asDouble
                val weatherCode = current.get("weather_code").asInt

                val result = JsonObject().apply {
                    addProperty("city", displayCity)
                    addProperty("latitude", lat)
                    addProperty("longitude", lon)
                    addProperty("temperature", temp)
                    addProperty("feels_like", current.get("apparent_temperature").asDouble)
                    addProperty("humidity", current.get("relative_humidity_2m").asInt)
                    addProperty("precipitation", current.get("precipitation").asDouble)
                    addProperty("wind_speed", current.get("wind_speed_10m").asDouble)
                    addProperty("weather_code", weatherCode)
                    addProperty("weather", WEATHER_CODES[weatherCode] ?: "未知($weatherCode)")
                    addProperty("timezone", weatherJson.get("timezone").asString)
                }
                weatherCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
                result
            } catch (e: Exception) {
                throw Exception("获取天气失败: ${e.message}")
            }
        }

        registerTool(
            name = "get_network_info",
            description = "获取当前设备的网络状态，包括 WiFi/移动网络、IP 地址、信号强度等",
            inputSchema = jsonSchema()
        ) { _ ->
            val context = AppContainer.context
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val network = cm?.activeNetwork
            val capabilities = network?.let { cm.getNetworkCapabilities(it) }

            val networkType = when {
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                network != null -> "Unknown"
                else -> "None"
            }

            val wifiInfo = wifiManager?.connectionInfo
            val ip = wifiInfo?.ipAddress ?: 0
            val rssi = wifiInfo?.rssi ?: -127

            JsonObject().apply {
                addProperty("connected", network != null)
                addProperty("type", networkType)
                addProperty("wifi_ssid", wifiInfo?.ssid?.replace("\"", "") ?: "Unknown")
                addProperty("wifi_bssid", wifiInfo?.bssid ?: "Unknown")
                addProperty("wifi_rssi", rssi)
                addProperty("wifi_link_speed", wifiInfo?.linkSpeed ?: -1)
                addProperty("ip_address", "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}")
                addProperty("network_score", when {
                    networkType == "WiFi" && rssi > -50 -> "Excellent"
                    networkType == "WiFi" && rssi > -70 -> "Good"
                    networkType == "WiFi" && rssi > -80 -> "Fair"
                    network != null -> "Poor"
                    else -> "No connection"
                })
            }
        }

        registerTool(
            name = "set_alarm",
            description = "在手机上设置闹钟提醒",
            inputSchema = jsonSchema(
                "hour" to jsonNumberProp("小时 (0-23)"),
                "minute" to jsonNumberProp("分钟 (0-59)"),
                "message" to jsonStringProp("闹钟标签/备注"),
                required = listOf("hour", "minute")
            )
        ) { args ->
            val hour = (args["hour"] as? Number)?.toInt() ?: throw Exception("hour 必须为 0-23 的数字")
            val minute = (args["minute"] as? Number)?.toInt() ?: throw Exception("minute 必须为 0-59 的数字")
            require(hour in 0..23) { "hour 必须在 0-23 之间" }
            require(minute in 0..59) { "minute 必须在 0-59 之间" }
            val message = args["message"] as? String ?: ""

            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (message.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }.let { intent ->
                try {
                    AppContainer.context.startActivity(intent)
                    JsonObject().apply {
                        addProperty("success", true)
                        addProperty("hour", hour)
                        addProperty("minute", minute)
                        addProperty("message", message)
                        addProperty("time", String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                    }
                } catch (e: Exception) {
                    throw Exception("无法设置闹钟，请检查是否授予闹钟权限: ${e.message}")
                }
            }
        }

        registerTool(
            name = "get_location",
            description = "获取设备当前地理位置（经纬度），需要定位权限",
            inputSchema = jsonSchema()
        ) { _ ->
            val loc = getCurrentLocation()
            if (loc != null) {
                JsonObject().apply {
                    addProperty("latitude", loc.first)
                    addProperty("longitude", loc.second)
                    addProperty("provider", "fused")
                    addProperty("status", "ok")
                }
            } else {
                JsonObject().apply {
                    addProperty("status", "unavailable")
                    addProperty("message", "无法获取位置，请检查定位权限或确保定位服务已开启")
                }
            }
        }

        registerTool(
            name = "send_notification",
            description = "在手机通知栏发送一条通知提醒",
            inputSchema = jsonSchema(
                "title" to jsonStringProp("通知标题"),
                "content" to jsonStringProp("通知内容"),
                "channel_id" to jsonStringProp("通知渠道ID，用于分类通知（默认 xiaozhi）"),
                required = listOf("title", "content")
            )
        ) { args ->
            val title = (args["title"] as? String).takeIf { !it.isNullOrEmpty() }
                ?: throw Exception("title 不能为空")
            val content = (args["content"] as? String).takeIf { !it.isNullOrEmpty() }
                ?: throw Exception("content 不能为空")
            val channelId = args["channel_id"] as? String ?: "xiaozhi"

            ensureNotificationChannel(channelId)

            val notification = NotificationCompat.Builder(AppContainer.context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            if (ActivityCompat.checkSelfPermission(AppContainer.context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw Exception("未授予通知权限（POST_NOTIFICATIONS），请在设置中开启")
            }
            NotificationManagerCompat.from(AppContainer.context).notify(
                System.currentTimeMillis().toInt(), notification
            )
            JsonObject().apply {
                addProperty("success", true)
                addProperty("title", title)
                addProperty("content", content)
            }
        }

        registerTool(
            name = "get_calendar",
            description = "获取日历中的事件列表，默认查询今天的事件，支持指定日期范围",
            inputSchema = jsonSchema(
                "start_date" to jsonStringProp("开始日期，格式 yyyy-MM-dd，默认今天"),
                "end_date" to jsonStringProp("结束日期，格式 yyyy-MM-dd，默认 start_date + 1 天"),
                "max_results" to jsonNumberProp("最多返回事件数量，默认 20")
            )
        ) { args ->
            val startDate = args["start_date"] as? String
            val endDate = args["end_date"] as? String
            val maxResults = (args["max_results"] as? Number)?.toInt() ?: 20
            val events = CalendarHelper.getEvents(startDate, endDate, maxResults)
            val eventsArray = JsonArray()
            events.forEach { event ->
                eventsArray.add(JsonObject().apply {
                    addProperty("id", event.id)
                    addProperty("title", event.title)
                    addProperty("description", event.description)
                    addProperty("start_time", event.startTime)
                    addProperty("end_time", event.endTime)
                    addProperty("all_day", event.allDay)
                    addProperty("location", event.location)
                })
            }
            JsonObject().apply {
                add("events", eventsArray)
                addProperty("count", events.size)
            }
        }

        registerTool(
            name = "web_search",
            description = "搜索网页获取实时信息，返回搜索结果列表",
            inputSchema = jsonSchema(
                "query" to jsonStringProp("搜索关键词"),
                "max_results" to jsonNumberProp("最多返回结果数量，默认 5"),
                required = listOf("query")
            )
        ) { args ->
            val query = (args["query"] as? String).takeIf { !it.isNullOrEmpty() }
                ?: throw Exception("query 不能为空")
            val maxResults = (args["max_results"] as? Number)?.toInt() ?: 5
            val results = webSearch(query, maxResults)
            val resultsArray = JsonArray()
            results.forEach { r ->
                resultsArray.add(JsonObject().apply {
                    addProperty("title", r.title)
                    addProperty("url", r.url)
                    addProperty("snippet", r.snippet)
                })
            }
            JsonObject().apply {
                add("results", resultsArray)
                addProperty("query", query)
                addProperty("count", results.size)
            }
        }

        registerTool(
            name = "open_app",
            description = "在手机上打开指定的应用，通过包名启动",
            inputSchema = jsonSchema(
                "package_name" to jsonStringProp("应用包名，如 com.tencent.mm"),
                "fallback_search" to jsonBoolProp("如果未找到，是否在应用商店搜索（默认 true）"),
                required = listOf("package_name")
            )
        ) { args ->
            val pkg = (args["package_name"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw Exception("package_name 不能为空")
            val fallback = (args["fallback_search"] as? Boolean) ?: true
            val ctx = AppContainer.context
            try {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(it)
                    JsonObject().apply { addProperty("success", true); addProperty("package", pkg) }
                } ?: throw Exception("未找到应用 $pkg")
            } catch (e: Exception) {
                if (fallback) {
                    try {
                        val market = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                        market.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(market)
                        JsonObject().apply { addProperty("success", true); addProperty("package", pkg); addProperty("note", "已跳转应用商店") }
                    } catch (_: Exception) { throw Exception("未找到应用 $pkg") }
                } else throw Exception("未找到应用 $pkg: ${e.message}")
            }
        }

        registerTool(
            name = "open_url",
            description = "在系统浏览器中打开指定链接",
            inputSchema = jsonSchema(
                "url" to jsonStringProp("完整的网页链接，需包含 https:// 或 http://"),
                required = listOf("url")
            )
        ) { args ->
            val url = (args["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw Exception("url 不能为空")
            val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                AppContainer.context.startActivity(this)
            }
            JsonObject().apply { addProperty("success", true); addProperty("url", finalUrl) }
        }

        registerTool(
            name = "set_volume",
            description = "调节手机音量，支持媒体、闹钟、通知、通话音量",
            inputSchema = jsonSchema(
                "type" to jsonStringProp("音量类型：media/alarm/ring/notification，默认 media"),
                "level" to jsonNumberProp("音量 0-100，百分比"),
                required = listOf("level")
            )
        ) { args ->
            val level = (args["level"] as? Number)?.toInt()?.coerceIn(0, 100) ?: throw Exception("level 必须为 0-100")
            val type = (args["type"] as? String)?.lowercase() ?: "media"
            val audioManager = AppContainer.context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: throw Exception("无法获取 AudioManager")
            val streamType = when (type) {
                "media" -> android.media.AudioManager.STREAM_MUSIC
                "alarm" -> android.media.AudioManager.STREAM_ALARM
                "ring" -> android.media.AudioManager.STREAM_RING
                "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                else -> throw Exception("不支持的音量类型: $type，支持 media/alarm/ring/notification")
            }
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val realLevel = (level * maxVol / 100).coerceAtLeast(0)
            audioManager.setStreamVolume(streamType, realLevel, 0)
            JsonObject().apply {
                addProperty("success", true); addProperty("type", type)
                addProperty("level", level); addProperty("real_level", realLevel); addProperty("max", maxVol)
            }
        }

        registerTool(
            name = "set_timer",
            description = "设置一个倒计时器，到时间会响铃提醒",
            inputSchema = jsonSchema(
                "seconds" to jsonNumberProp("倒计时秒数"),
                "label" to jsonStringProp("倒计时标签/备注"),
                required = listOf("seconds")
            )
        ) { args ->
            val seconds = (args["seconds"] as? Number)?.toInt() ?: throw Exception("seconds 必须为整数秒")
            require(seconds in 1..86400) { "seconds 必须在 1-86400 之间" }
            val label = args["label"] as? String ?: ""
            val display = if (seconds >= 60) "${seconds / 60}分${seconds % 60}秒" else "${seconds}秒"
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                try {
                    Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        AppContainer.context.startActivity(this)
                    }
                    JsonObject().apply { addProperty("success", true); addProperty("seconds", seconds); addProperty("display", display) }
                } catch (e: Exception) { throw Exception("无法设置倒计时: ${e.message}") }
            } else {
                JsonObject().apply { addProperty("success", true); addProperty("note", "此设备不支持倒计时，请手动设置"); addProperty("seconds", seconds); addProperty("display", display) }
            }
        }

        registerTool(
            name = "get_memory_info",
            description = "获取设备的内存（RAM）和存储空间使用情况",
            inputSchema = jsonSchema()
        ) { _ ->
            val stats = android.os.StatFs("/data")
            val blockSize = stats.blockSizeLong
            val totalBlocks = stats.blockCountLong
            val freeBlocks = stats.freeBlocksLong
            val availBlocks = stats.availableBlocksLong
            val runtime = Runtime.getRuntime()
            JsonObject().apply {
                addProperty("ram_total_mb", runtime.totalMemory() / 1024 / 1024)
                addProperty("ram_free_mb", runtime.freeMemory() / 1024 / 1024)
                addProperty("ram_max_mb", runtime.maxMemory() / 1024 / 1024)
                addProperty("storage_total_gb", String.format(Locale.US, "%.1f", totalBlocks * blockSize / 1e9))
                addProperty("storage_used_gb", String.format(Locale.US, "%.1f", (totalBlocks - freeBlocks) * blockSize / 1e9))
                addProperty("storage_free_gb", String.format(Locale.US, "%.1f", freeBlocks * blockSize / 1e9))
                addProperty("storage_avail_gb", String.format(Locale.US, "%.1f", availBlocks * blockSize / 1e9))
            }
        }

        registerTool(
            name = "vibrate",
            description = "让手机震动，可指定时长和模式",
            inputSchema = jsonSchema(
                "duration_ms" to jsonNumberProp("震动时长（毫秒），默认 500"),
                "pattern" to jsonStringProp("震动模式：once/short/long/rapid，默认 once")
            )
        ) { args ->
            val duration = (args["duration_ms"] as? Number)?.toLong() ?: 500L
            val pattern = (args["pattern"] as? String)?.lowercase() ?: "once"
            val vibrator = AppContainer.context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                ?: throw Exception("此设备不支持震动")
            val patternArr = when (pattern) {
                "short" -> longArrayOf(0, 100)
                "long" -> longArrayOf(0, 1000)
                "rapid" -> longArrayOf(0, 200, 100, 200, 100, 200)
                else -> longArrayOf(0, duration)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(patternArr, -1))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(patternArr, -1)
            }
            JsonObject().apply { addProperty("success", true); addProperty("pattern", pattern); addProperty("duration_ms", patternArr.sum()) }
        }

        registerTool(
            name = "screen_brightness",
            description = "获取当前屏幕亮度，或设置亮度百分比",
            inputSchema = jsonSchema(
                "level" to jsonNumberProp("设置亮度 0-100（百分比），不传则只读取当前亮度"),
            )
        ) { args ->
            val ctx = AppContainer.context
            val resolver = ctx.contentResolver
            try {
                val current = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                val level = args["level"] as? Number
                if (level != null) {
                    val v = level.toInt().coerceIn(0, 100)
                    val sysVal = (v * 255 / 100).coerceIn(0, 255)
                    android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, sysVal)
                    JsonObject().apply { addProperty("success", true); addProperty("previous", current * 100 / 255); addProperty("current", v) }
                } else {
                    JsonObject().apply { addProperty("success", true); addProperty("current", current * 100 / 255); addProperty("note", "不传 level 参数只读取亮度，传 level=N 可设置亮度") }
                }
            } catch (e: Exception) {
                throw Exception("获取/设置亮度失败: ${e.message}")
            }
        }

        registerTool(
            name = "get_public_ip",
            description = "获取设备的公网 IP 地址和所在地",
            inputSchema = jsonSchema()
        ) { _ ->
            try {
                val text = httpGet("https://httpbin.org/ip", timeoutMs = 5000)
                if (text.isEmpty()) throw Exception("请求失败")
                val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                val ip = json?.get("origin")?.asString ?: text.trim()
                val locText = httpGet("https://ipapi.co/$ip/json/", timeoutMs = 5000)
                var city = ""; var country = ""; var isp = ""
                try {
                    val loc = JsonParser.parseString(locText).asJsonObject
                    city = loc.get("city")?.asString ?: ""
                    country = loc.get("country_name")?.asString ?: ""
                    isp = loc.get("org")?.asString ?: ""
                } catch (_: Exception) {}
                JsonObject().apply {
                    addProperty("ip", ip)
                    if (city.isNotEmpty()) addProperty("city", city)
                    if (country.isNotEmpty()) addProperty("country", country)
                    if (isp.isNotEmpty()) addProperty("isp", isp)
                }
            } catch (e: Exception) { throw Exception("获取公网 IP 失败: ${e.message}") }
        }

        registerTool(
            name = "get_news",
            description = "获取最新新闻头条，支持指定类别",
            inputSchema = jsonSchema(
                "category" to jsonStringProp("新闻类别：top/general/world/business/technology/science/sports/entertainment，默认 top"),
                "country" to jsonStringProp("国家代码：cn/us/jp/etc，默认 cn"),
                "max_results" to jsonNumberProp("最多返回条数，默认 5"),
            )
        ) { args ->
            val category = (args["category"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: "top"
            val country = (args["country"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: "cn"
            val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
            val catParam = if (category == "top") "" else "&category=$category"
            val url = "https://newsapi.org/v2/top-headlines?country=$country$catParam&pageSize=$maxResults&apiKey=a1c2e3f4g5h6i7j8k9l0m1n2o3p4q5r"
            try {
                var text = httpGet(url, timeoutMs = 6000)
                var json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                var articles = json?.getAsJsonArray("articles")
                if (articles == null || articles.size() == 0) {
                    text = httpGet("https://tenapi.cn/v2/topnews?type=hot", timeoutMs = 5000)
                    json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                    articles = json?.getAsJsonArray("data")
                    if (articles != null) {
                        val arr = JsonArray()
                        for (i in 0 until minOf(articles.size(), maxResults)) {
                            val a = articles[i].asJsonObject
                            arr.add(JsonObject().apply {
                                addProperty("title", a.get("title")?.asString ?: "")
                                addProperty("desc", a.get("desc")?.asString ?: a.get("content")?.asString ?: "")
                            })
                        }
                        return@registerTool JsonObject().apply { add("articles", arr); addProperty("count", arr.size()) }
                    }
                }
                if (articles != null) {
                    val arr = JsonArray()
                    for (i in 0 until minOf(articles.size(), maxResults)) {
                        val a = articles[i].asJsonObject
                        arr.add(JsonObject().apply {
                            addProperty("title", a.get("title")?.asString ?: "")
                            addProperty("description", a.get("description")?.asString ?: "")
                            addProperty("source", a.getAsJsonObject("source")?.get("name")?.asString ?: "")
                            addProperty("url", a.get("url")?.asString ?: "")
                        })
                    }
                    JsonObject().apply { add("articles", arr); addProperty("count", arr.size()) }
                } else throw Exception("未获取到新闻")
            } catch (e: Exception) { throw Exception("获取新闻失败: ${e.message}") }
        }

        registerTool(
            name = "random_number",
            description = "生成指定范围内的随机整数，或随机浮点数",
            inputSchema = jsonSchema(
                "min" to jsonNumberProp("最小值（含），默认 0"),
                "max" to jsonNumberProp("最大值（含），默认 100"),
                "type" to jsonStringProp("类型：int/float，默认 int"),
            )
        ) { args ->
            val min = (args["min"] as? Number)?.toInt() ?: 0
            val max = (args["max"] as? Number)?.toInt() ?: 100
            val type = (args["type"] as? String)?.lowercase() ?: "int"
            require(min <= max) { "min 必须 <= max" }
            if (type == "float") {
                val v = min + Math.random() * (max - min)
                JsonObject().apply { addProperty("result", v); addProperty("min", min); addProperty("max", max); addProperty("type", "float") }
            } else {
                val v = (min + Math.random() * (max - min + 1)).toInt()
                JsonObject().apply { addProperty("result", v); addProperty("min", min); addProperty("max", max); addProperty("type", "int") }
            }
        }

        registerTool(
            name = "encode_decode",
            description = "对文本进行 Base64 或 URL 编码/解码",
            inputSchema = jsonSchema(
                "text" to jsonStringProp("要编解码的文本"),
                "mode" to jsonStringProp("模式：base64_encode/base64_decode/url_encode/url_decode"),
                required = listOf("text", "mode")
            )
        ) { args ->
            val text = (args["text"] as? String).takeIf { it != null } ?: throw Exception("text 不能为空")
            val mode = (args["mode"] as? String)?.lowercase() ?: throw Exception("mode 不能为空")
            when (mode) {
                "base64_encode" -> JsonObject().apply { addProperty("result", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)) }
                "base64_decode" -> JsonObject().apply { addProperty("result", String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))) }
                "url_encode" -> JsonObject().apply { addProperty("result", java.net.URLEncoder.encode(text, "UTF-8")) }
                "url_decode" -> JsonObject().apply { addProperty("result", java.net.URLDecoder.decode(text, "UTF-8")) }
                else -> throw Exception("不支持的 mode：$mode，支持 base64_encode/base64_decode/url_encode/url_decode")
            }.also { it.addProperty("mode", mode) }
        }

        registerTool(
            name = "get_orientation",
            description = "获取设备当前的屏幕方向和旋转角度",
            inputSchema = jsonSchema()
        ) { _ ->
            val ctx = AppContainer.context
            val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
            val rotation = display?.rotation ?: -1
            val orientation = ctx.resources.configuration.orientation
            val rotationStr = when (rotation) {
                0 -> "0° (竖屏-正常)"
                1 -> "90° (横屏)"
                2 -> "180° (竖屏-倒置)"
                3 -> "270° (横屏-倒置)"
                else -> "未知"
            }
            val orientationStr = when (orientation) {
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> "竖屏"
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "横屏"
                else -> "未知"
            }
            JsonObject().apply {
                addProperty("orientation", orientationStr)
                addProperty("rotation", rotationStr)
                addProperty("rotation_degrees", rotation * 90)
            }
        }

        registerTool(
            name = "get_installed_apps",
            description = "列出手机上安装的应用包名和名称（非系统应用）",
            inputSchema = jsonSchema(
                "max_results" to jsonNumberProp("最多返回数量，默认 30"),
                "keyword" to jsonStringProp("搜索关键词，只返回名称或包名包含此关键词的应用"),
            )
        ) { args ->
            val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 30
            val keyword = (args["keyword"] as? String)?.lowercase() ?: ""
            val pm = AppContainer.context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .distinctBy { it.packageName }
                .filter { keyword.isEmpty() || it.packageName.lowercase().contains(keyword) || it.loadLabel(pm).toString().lowercase().contains(keyword) }
                .take(maxResults)
            val arr = JsonArray()
            apps.forEach { a ->
                arr.add(JsonObject().apply {
                    addProperty("name", a.loadLabel(pm).toString())
                    addProperty("package", a.packageName)
                })
            }
            JsonObject().apply { add("apps", arr); addProperty("count", arr.size()) }
        }

        registerTool(
            name = "dict_lookup",
            description = "中英文词典查询，返回单词释义、音标、例句等",
            inputSchema = jsonSchema(
                "word" to jsonStringProp("要查询的单词或词语（中文或英文）"),
                required = listOf("word")
            )
        ) { args ->
            val word = (args["word"] as? String)?.takeIf { it.isNotBlank() } ?: throw Exception("word 不能为空")
            try {
                val text = httpGet("https://api.dictionaryapi.dev/api/v2/entries/en/${java.net.URLEncoder.encode(word, "UTF-8")}", timeoutMs = 6000)
                if (text.isEmpty() || text.startsWith("<")) {
                    val cnText = httpGet("https://dict.youdao.com/suggest?q=${java.net.URLEncoder.encode(word, "UTF-8")}&le=zh", timeoutMs = 5000)
                    val cnJson = try { JsonParser.parseString(cnText).asJsonObject } catch (_: Exception) { null }
                    val entries = cnJson?.getAsJsonArray("data")?.get(0)?.asJsonObject?.getAsJsonArray("entries")
                    if (entries != null && entries.size() > 0) {
                        val e = entries[0].asJsonObject
                        val explains = e.getAsJsonArray("explains")
                        val arr = JsonArray()
                        for (i in 0 until explains.size()) arr.add(explains[i].asString)
                        return@registerTool JsonObject().apply { add("explanations", arr); addProperty("word", e.get("key")?.asString ?: word) }
                    }
                    throw Exception("未找到释义")
                }
                val json = JsonParser.parseString(text).asJsonArray
                val first = json[0].asJsonObject
                val meanings = first.getAsJsonArray("meanings")
                val arr = JsonArray()
                meanings.forEach { m ->
                    val obj = m.asJsonObject
                    val defs = obj.getAsJsonArray("definitions")
                    for (i in 0 until minOf(defs.size(), 3)) {
                        arr.add("[${obj.get("partOfSpeech")?.asString}] ${defs[i].asJsonObject.get("definition")?.asString}")
                    }
                }
                val phonetics = first.getAsJsonArray("phonetics")
                var phonetic = ""
                for (i in 0 until (phonetics?.size() ?: 0)) {
                    val p = phonetics!![i].asJsonObject
                    val textStr = p.get("text")?.asString
                    if (textStr != null) { phonetic = textStr; break }
                }
                JsonObject().apply {
                    addProperty("word", first.get("word")?.asString ?: word)
                    if (phonetic.isNotEmpty()) addProperty("phonetic", phonetic)
                    add("definitions", arr)
                }
            } catch (e: Exception) {
                if (e.message?.contains("释义") == true) throw e
                throw Exception("查词失败: ${e.message}")
            }
        }

        registerTool(
            name = "joke",
            description = "讲一个随机笑话，支持中文或英文",
            inputSchema = jsonSchema(
                "lang" to jsonStringProp("语言：zh/en，默认 zh"),
            )
        ) { args ->
            val lang = (args["lang"] as? String)?.lowercase()?.takeIf { it == "en" } ?: "zh"
            try {
                if (lang == "en") {
                    val text = httpGet("https://v2.jokeapi.dev/joke/Any?type=single&safe-mode", timeoutMs = 5000)
                    val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                    val joke = json?.get("joke")?.asString
                    if (joke != null) return@registerTool JsonObject().apply { addProperty("joke", joke); addProperty("lang", "en") }
                }
                val text = httpGet("https://tenapi.cn/v2/joke", timeoutMs = 5000)
                val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                val joke = json?.get("data")?.asJsonObject?.get("content")?.asString
                    ?: json?.get("joke")?.asString
                    ?: json?.get("data")?.asString
                    ?: "程序员问老婆：我写的代码哪里有问题？老婆：你的问题就是写代码的时候想着我。"
                JsonObject().apply { addProperty("joke", joke); addProperty("lang", "zh") }
            } catch (e: Exception) {
                JsonObject().apply { addProperty("joke", "为什么 AI 不会讲笑话？因为它的幽默感被404了。"); addProperty("note", "在线获取失败，使用默认笑话") }
            }
        }

        // ========== 手机功能工具 ==========

        registerTool(
            name = "navigate",
            description = "打开地图导航到指定目的地，支持高德/百度地图或系统地图",
            inputSchema = jsonSchema(
                "destination" to jsonStringProp("目的地名称或地址"),
                "latitude" to jsonNumberProp("目的地纬度（可选，传经纬度可精确定位）"),
                "longitude" to jsonNumberProp("目的地经度（可选，传经纬度可精确定位）"),
                "mode" to jsonStringProp("导航模式：driving/walking/transit/cycling，默认 driving"),
                "app" to jsonStringProp("地图应用：auto/amap/baidu/google，默认 auto"),
                required = listOf("destination")
            )
        ) { args ->
            val destination = (args["destination"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw Exception("destination 不能为空")
            val lat = (args["latitude"] as? Number)?.toDouble()
            val lon = (args["longitude"] as? Number)?.toDouble()
            val mode = (args["mode"] as? String)?.lowercase() ?: "driving"
            val app = (args["app"] as? String)?.lowercase() ?: "auto"
            val ctx = AppContainer.context

            val dlat = args["dlat"] as? Double
            val dlon = args["dlon"] as? Double

            val intent = when (app) {
                "amap" -> {
                    val uri = if (lat != null && lon != null)
                        "androidamap://navi?sourceApplication=xiaozhi&lat=$lat&lon=$lon&poiname=${URLEncoder.encode(destination, "UTF-8")}&style=${when(mode){"driving"->2;"walking"->4;"transit"->1;else->2}}"
                    else
                        "androidamap://keywordNavi?sourceApplication=xiaozhi&keyword=${URLEncoder.encode(destination, "UTF-8")}&style=${when(mode){"driving"->2;"walking"->4;"transit"->1;else->2}}"
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                }
                "baidu" -> {
                    val coord = if (lat != null && lon != null) "|ll=${lat},${lon}" else ""
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "baidumap://map/direction?destination=${URLEncoder.encode(destination, "UTF-8")}$coord&coord_type=gcj02&mode=${when(mode){"driving"->"driving";"walking"->"walking";"transit"->"transit";"cycling"->"riding";else->"driving"}}"
                    ))
                }
                "google" -> {
                    val coord = if (lat != null && lon != null) "${lat},${lon}" else URLEncoder.encode(destination, "UTF-8")
                    val travelMode = when (mode) { "driving" -> "driving"; "walking" -> "walking"; "transit" -> "transit"; "cycling" -> "bicycling"; else -> "driving" }
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://www.google.com/maps/dir/?api=1&destination=$coord&travelmode=$travelMode"
                    ))
                }
                else -> { // auto - try system geo intent
                    val coord = if (lat != null && lon != null) "geo:${lat},${lon}?q=${lat},${lon}(${URLEncoder.encode(destination, "UTF-8")})"
                    else "geo:0,0?q=${URLEncoder.encode(destination, "UTF-8")}"
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(coord)).apply {
                        // Also try amap/baidu if installed
                        val pm = ctx.packageManager
                        if (resolveActivity(pm) == null) {
                            // Fallback to Google Maps web
                            setData(android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(destination, "UTF-8")}"))
                        }
                    }
                }
            }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }

            try {
                ctx.startActivity(intent)
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("destination", destination)
                    addProperty("app", app)
                    addProperty("mode", mode)
                    addProperty("message", "已打开${destination}的导航")
                }
            } catch (e: Exception) {
                // Fallback: try Google Maps web
                try {
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(destination, "UTF-8")}"
                    )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }.let { ctx.startActivity(it) }
                    JsonObject().apply {
                        addProperty("success", true)
                        addProperty("destination", destination)
                        addProperty("note", "未找到地图应用，已使用浏览器打开")
                    }
                } catch (_: Exception) {
                    throw Exception("无法打开导航，请安装地图应用")
                }
            }
        }

        registerTool(
            name = "play_music",
            description = "播放音乐或音频，支持指定音乐应用或系统默认播放器",
            inputSchema = jsonSchema(
                "name" to jsonStringProp("歌曲名或音频名称（可选）"),
                "artist" to jsonStringProp("歌手名（可选）"),
                "app" to jsonStringProp("音乐应用：auto/qqmusic/netease/spotify/system，默认 auto"),
                "action" to jsonStringProp("操作：play/pause/next/previous/resume，默认 play"),
            )
        ) { args ->
            val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val artist = (args["artist"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val app = (args["app"] as? String)?.lowercase() ?: "auto"
            val action = (args["action"] as? String)?.lowercase() ?: "play"
            val ctx = AppContainer.context

            when (action) {
                "pause" -> return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("action", "pause")
                    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(Intent.EXTRA_KEY_EVENT,
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                    }
                    ctx.sendBroadcast(intent)
                }
                "resume" -> return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("action", "resume")
                    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(Intent.EXTRA_KEY_EVENT,
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                    ctx.sendBroadcast(intent)
                }
                "next" -> return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("action", "next")
                    Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(Intent.EXTRA_KEY_EVENT,
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                    }.let { ctx.sendBroadcast(it) }
                }
                "previous" -> return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("action", "previous")
                    Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(Intent.EXTRA_KEY_EVENT,
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    }.let { ctx.sendBroadcast(it) }
                }
                else -> {
                    val searchQuery = if (name.isNotEmpty() && artist.isNotEmpty()) "$name $artist"
                        else if (name.isNotEmpty()) name
                        else ""

                    val pkgOverride = when (app) {
                        "qqmusic" -> "com.tencent.qqmusic"
                        "netease" -> "com.netease.cloudmusic"
                        "spotify" -> "com.spotify.music"
                        else -> null
                    }

                    // Try specified app first, then fallback
                    val candidates = listOfNotNull(pkgOverride) + listOf(
                        "com.tencent.qqmusic", "com.netease.cloudmusic", "com.spotify.music",
                        "com.google.android.apps.youtube.music", "com.android.music", "com.miui.player"
                    )

                    val found = candidates.firstOrNull { pkg ->
                        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
                        catch (_: Exception) { false }
                    }

                    if (found != null) {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(found)!!
                        if (searchQuery.isNotEmpty()) {
                            try {
                                Intent(Intent.ACTION_SEARCH).apply {
                                    setPackage(found); putExtra("query", searchQuery)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }.let { ctx.startActivity(it) }
                                return@registerTool JsonObject().apply {
                                    addProperty("success", true); addProperty("action", "play")
                                    addProperty("app", found); addProperty("search", searchQuery)
                                }
                            } catch (_: Exception) { }
                        }
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(intent)
                        return@registerTool JsonObject().apply {
                            addProperty("success", true); addProperty("action", "play")
                            addProperty("app", found)
                            if (searchQuery.isNotEmpty()) addProperty("search", searchQuery)
                        }
                    }
                    return@registerTool JsonObject().apply {
                        addProperty("success", false)
                        addProperty("message", "未找到音乐应用，请先安装 QQ音乐、网易云音乐或 Spotify")
                    }
                }
            }
        }

        registerTool(
            name = "make_call",
            description = "拨打电话。支持联系人名称查找、直接呼叫（需权限）或打开拨号界面",
            inputSchema = jsonSchema(
                "phone_number" to jsonStringProp("电话号码（可选，提供 name 时会自动查找联系人号码）"),
                "name" to jsonStringProp("联系人姓名（可选，提供后自动从通讯录查找号码）"),
                "direct" to jsonBoolProp("是否直接呼叫（需 CALL_PHONE 权限），false 则打开拨号界面，默认 false"),
            )
        ) { args ->
            val ctx = AppContainer.context
            var number = (args["phone_number"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val direct = (args["direct"] as? Boolean) ?: false

            // 只有 name 没有 number 时，从通讯录查找
            if (number.isEmpty() && name.isNotEmpty()) {
                number = lookupContactNumber(ctx, name)
                if (number.isEmpty()) throw Exception("未在通讯录中找到联系人: $name")
            }
            if (number.isEmpty()) throw Exception("phone_number 或 name 至少提供一个")
            // 清理号码格式
            number = number.replace(Regex("[\\s\\-()（）]"), "")
            val displayName = if (name.isNotEmpty()) "$name($number)" else number

            if (direct) {
                // 直接呼叫（需要 CALL_PHONE 权限）
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    // 无权限，降级到 ACTION_DIAL
                    val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                    return@registerTool JsonObject().apply {
                        addProperty("success", true)
                        addProperty("phone_number", number)
                        addProperty("name", name)
                        addProperty("direct", false)
                        addProperty("message", "未授予通话权限，已打开拨号界面: $displayName")
                    }
                }
                val intent = Intent(Intent.ACTION_CALL, android.net.Uri.fromParts("tel", number, null)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("phone_number", number)
                    if (name.isNotEmpty()) addProperty("name", name)
                    addProperty("direct", true)
                    addProperty("message", "正在呼叫: $displayName")
                }
            } else {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("phone_number", number)
                    if (name.isNotEmpty()) addProperty("name", name)
                    addProperty("direct", false)
                    addProperty("message", "已打开拨号界面: $displayName")
                }
            }
        }

        registerTool(
            name = "send_sms",
            description = "发送短信。支持联系人名称查找、直接发送（需权限）或打开短信编辑界面",
            inputSchema = jsonSchema(
                "phone_number" to jsonStringProp("接收短信的电话号码（可选，提供 name 时会自动查找）"),
                "message" to jsonStringProp("短信内容"),
                "name" to jsonStringProp("联系人姓名（可选，自动从通讯录查找号码）"),
                "direct" to jsonBoolProp("是否直接发送（需 SEND_SMS 权限），false 则打开短信界面，默认 false"),
                required = listOf("message")
            )
        ) { args ->
            val ctx = AppContainer.context
            var number = (args["phone_number"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""
            val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw Exception("message 不能为空")
            val direct = (args["direct"] as? Boolean) ?: false

            // 只有 name 没有 number 时，从通讯录查找
            if (number.isEmpty() && name.isNotEmpty()) {
                number = lookupContactNumber(ctx, name)
                if (number.isEmpty()) throw Exception("未在通讯录中找到联系人: $name")
            }
            if (number.isEmpty()) throw Exception("phone_number 或 name 至少提供一个")
            number = number.replace(Regex("[\\s\\-()（）]"), "")
            val displayName = if (name.isNotEmpty()) name else number

            if (direct) {
                // 直接发送（需要 SEND_SMS 权限）
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    // 无权限，降级到打开短信界面
                    val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)).apply {
                        putExtra("sms_body", message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                    return@registerTool JsonObject().apply {
                        addProperty("success", true)
                        addProperty("phone_number", number)
                        if (name.isNotEmpty()) addProperty("name", name)
                        addProperty("direct", false)
                        addProperty("message_length", message.length)
                        addProperty("message", "未授予发送权限，已打开短信编辑界面，收件人: $displayName")
                    }
                }
                // 使用 SmsManager 直接发送
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    @Suppress("DEPRECATION")
                    (ctx.getSystemService("telephony_sms") as? android.telephony.SmsManager)
                        ?: android.telephony.SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                @Suppress("DEPRECATION")
                smsManager.sendTextMessage(number, null, message, null, null)
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("phone_number", number)
                    if (name.isNotEmpty()) addProperty("name", name)
                    addProperty("direct", true)
                    addProperty("message_length", message.length)
                    addProperty("message", "短信已发送到: $displayName")
                }
            } else {
                val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)).apply {
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("phone_number", number)
                    if (name.isNotEmpty()) addProperty("name", name)
                    addProperty("direct", false)
                    addProperty("message_length", message.length)
                    addProperty("message", "已打开短信编辑界面，收件人: $displayName")
                }
            }
        }

        registerTool(
            name = "toggle_flashlight",
            description = "打开或关闭手机手电筒/闪光灯",
            inputSchema = jsonSchema(
                "enable" to jsonBoolProp("true 打开，false 关闭"),
                required = listOf("enable")
            )
        ) { args ->
            val enable = (args["enable"] as? Boolean) ?: throw Exception("enable 必须为布尔值")
            val ctx = AppContainer.context
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: throw Exception("此设备不支持相机/闪光灯")

            try {
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw Exception("未找到闪光灯")

                if (enable) {
                    cameraManager.setTorchMode(cameraId, true)
                } else {
                    cameraManager.setTorchMode(cameraId, false)
                }
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("enabled", enable)
                    addProperty("message", if (enable) "手电筒已打开" else "手电筒已关闭")
                }
            } catch (e: Exception) {
                throw Exception("控制手电筒失败: ${e.message}")
            }
        }

        registerTool(
            name = "toggle_wifi",
            description = "打开或关闭手机 WiFi",
            inputSchema = jsonSchema(
                "enable" to jsonBoolProp("true 打开，false 关闭"),
                required = listOf("enable")
            )
        ) { args ->
            val enable = (args["enable"] as? Boolean) ?: throw Exception("enable 必须为布尔值")
            val ctx = AppContainer.context
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: throw Exception("此设备不支持 WiFi")

            try {
                wifiManager.isWifiEnabled = enable
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("enabled", enable)
                    addProperty("message", if (enable) "WiFi 已打开" else "WiFi 已关闭")
                }
            } catch (e: Exception) {
                throw Exception("控制 WiFi 失败: ${e.message}")
            }
        }

        registerTool(
            name = "take_screenshot",
            description = "对手机当前屏幕进行截图（需要 Android 11+ 或辅助功能权限）",
            inputSchema = jsonSchema()
        ) { _ ->
            val ctx = AppContainer.context
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    // Use MediaProjection for screenshot on Android 11+
                    val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
                    if (mgr != null) {
                        JsonObject().apply {
                            addProperty("success", false)
                            addProperty("message", "截图需要系统授权，请使用系统截图快捷键（音量减+电源键）")
                            addProperty("note", "可通过 MCP 调用开放系统截图界面")
                        }
                    } else {
                        JsonObject().apply {
                            addProperty("success", false)
                            addProperty("message", "此设备不支持截图 API")
                        }
                    }
                } catch (e: Exception) {
                    JsonObject().apply {
                        addProperty("success", false)
                        addProperty("message", "截图失败: ${e.message}")
                    }
                }
            } else {
                // Try using /system/bin/screenshot on older Android or root
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                        "screencap -p /sdcard/DCIM/Screenshots/xiaozhi_${System.currentTimeMillis()}.png"
                    ))
                    process.waitFor()
                    if (process.exitValue() == 0) {
                        JsonObject().apply {
                            addProperty("success", true)
                            addProperty("message", "截图已保存到 DCIM/Screenshots 目录")
                        }
                    } else {
                        JsonObject().apply {
                            addProperty("success", false)
                            addProperty("message", "截图失败，请使用系统截图快捷键（音量减+电源键）")
                        }
                    }
                } catch (_: Exception) {
                    JsonObject().apply {
                        addProperty("success", false)
                        addProperty("message", "截图需要系统授权，请使用系统截图快捷键（音量减+电源键）")
                    }
                }
            }
        }

        registerTool(
            name = "toggle_xiaozhi_mute",
            description = "切换小智 AI 麦克风的静音状态。静音后 AI 不会听到你的声音，但对话仍然可以继续",
            inputSchema = jsonSchema(
                "muted" to jsonBoolProp("true 静音，false 取消静音"),
                required = listOf("muted")
            )
        ) { args ->
            val muted = (args["muted"] as? Boolean) ?: throw Exception("muted 必须为布尔值")
            val backend = AppContainer.xiaozhiBackend
            backend.sendMute(muted)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("muted", muted)
                addProperty("message", if (muted) "麦克风已静音" else "麦克风已取消静音")
            }
        }

        // ========== 小智 AI 设备控制工具 ==========
        // 参考 ESP32 设备控制接口和 Android 本地实现

        registerTool(
            name = "set_xiaozhi_volume",
            description = "设置小智 AI 的语音输出音量（0-100），会持久化保存，下次启动自动恢复",
            inputSchema = jsonSchema(
                "level" to jsonNumberProp("音量 0-100，默认 80"),
                required = listOf("level")
            )
        ) { args ->
            val level = (args["level"] as? Number)?.toInt()?.coerceIn(0, 100)
                ?: throw Exception("level 必须为 0-100 的整数")
            AudioSettings.setOutputVolume(AppContainer.context, level)
            // 立即应用到当前活跃的解码器
            AppContainer.audioService.let { _ ->
                // 获取当前连接并更新 decoder 音量
                // 注意：需要在连接活跃时才能获取到 decoder，这里只保存设置
            }
            JsonObject().apply {
                addProperty("success", true)
                addProperty("level", level)
                addProperty("message", "音量已设置为 $level%，下次启动自动恢复")
            }
        }

        registerTool(
            name = "get_xiaozhi_volume",
            description = "获取当前小智 AI 的语音输出音量（0-100）",
            inputSchema = jsonSchema()
        ) { _ ->
            val level = AudioSettings.getOutputVolume(AppContainer.context)
            JsonObject().apply {
                addProperty("level", level)
            }
        }

        registerTool(
            name = "set_listening_mode",
            description = "设置小智 AI 的监听模式：auto（自动停止后重启）/ manual（手动重启）/ realtime（实时持续）。参考 ESP32 ListeningMode",
            inputSchema = jsonSchema(
                "mode" to jsonStringProp("监听模式: auto/manual/realtime，默认 realtime"),
                required = listOf("mode")
            )
        ) { args ->
            val mode = (args["mode"] as? String)?.lowercase()
                ?: throw Exception("mode 必须为 auto/manual/realtime")
            val validModes = setOf("auto", "manual", "realtime")
            if (mode !in validModes) throw Exception("mode 必须为 auto/manual/realtime，当前值: $mode")
            AudioSettings.setListeningMode(AppContainer.context,
                com.xiaozhi.aiservice.domain.model.VoiceMode.fromServerValue(mode))
            JsonObject().apply {
                addProperty("success", true)
                addProperty("mode", mode)
                addProperty("message", "监听模式已设置为 $mode")
            }
        }

        registerTool(
            name = "get_listening_mode",
            description = "获取当前小智 AI 的监听模式",
            inputSchema = jsonSchema()
        ) { _ ->
            val mode = AudioSettings.getListeningMode(AppContainer.context)
            JsonObject().apply {
                addProperty("mode", mode.serverValue)
                addProperty("display_name", mode.displayName)
            }
        }

        registerTool(
            name = "set_aec_mode",
            description = "设置 AEC（声学回声消除）模式：off（关闭）/ device（设备端 AEC）。AEC 可减少 AI 说话时麦克风采集回声的问题",
            inputSchema = jsonSchema(
                "mode" to jsonStringProp("AEC模式: off/device，默认 off")
            )
        ) { args ->
            val mode = (args["mode"] as? String)?.lowercase() ?: "off"
            val validModes = setOf("off", "device")
            if (mode !in validModes) throw Exception("mode 必须为 off/device")
            val aecMode = DeviceSettings.AecMode.fromValue(mode)
            DeviceSettings.setAecMode(AppContainer.context, aecMode)
            // 立即应用 AEC 状态
            AppContainer.audioService.setAecEnabled(aecMode != DeviceSettings.AecMode.OFF)
            val isSupported = AppContainer.audioService.isAecSupported()
            JsonObject().apply {
                addProperty("success", true)
                addProperty("mode", aecMode.value)
                addProperty("device_support", isSupported)
                addProperty("message", when {
                    !isSupported -> "AEC 已设置但设备不支持 AcousticEchoCanceler"
                    aecMode == DeviceSettings.AecMode.OFF -> "AEC 已关闭"
                    else -> "AEC 已启用（device 模式）"
                })
            }
        }

        registerTool(
            name = "get_device_state",
            description = "获取小智 AI 设备的当前状态（设备状态机）",
            inputSchema = jsonSchema()
        ) { _ ->
            val connRepo = AppContainer.connectionRepository
            val aiState = connRepo.aiState.value
            val deviceState = connRepo.deviceState.value
            JsonObject().apply {
                addProperty("device_state", deviceState.name)
                addProperty("device_state_display", deviceState.displayName)
                addProperty("ai_state", aiState.name)
            }
        }
    }

    private fun evaluateSimpleExpression(expr: String): Double {
        val trimmed = expr.replace(" ", "")
        if (trimmed.isEmpty()) throw IllegalArgumentException("空表达式")

        val numStack = ArrayDeque<Double>()
        val opStack = ArrayDeque<Char>()
        var i = 0

        fun applyOp() {
            val b = numStack.removeLastOrNull()
                ?: throw IllegalArgumentException("缺少操作数")
            val a = numStack.removeLastOrNull()
                ?: throw IllegalArgumentException("缺少操作数")
            val op = opStack.removeLastOrNull()
                ?: throw IllegalArgumentException("缺少运算符")
            numStack.addLast(when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b == 0.0) throw ArithmeticException("除数不能为零") else a / b
                else -> throw IllegalArgumentException("未知运算符: $op")
            })
        }

        fun precedence(op: Char): Int = when (op) {
            '+', '-' -> 1
            '*', '/' -> 2
            else -> 0
        }

        while (i < trimmed.length) {
            val ch = trimmed[i]
            when {
                ch == '-' && (i == 0 || trimmed[i - 1] in "+-*/(") -> {
                    i++
                    val start = i
                    while (i < trimmed.length && (trimmed[i].isDigit() || trimmed[i] == '.')) i++
                    if (i == start) throw IllegalArgumentException("无效的负数表达式")
                    numStack.addLast(("-" + trimmed.substring(start, i)).toDouble())
                }
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    while (i < trimmed.length && (trimmed[i].isDigit() || trimmed[i] == '.')) i++
                    numStack.addLast(trimmed.substring(start, i).toDouble())
                }
                ch == '(' -> { opStack.addLast(ch); i++ }
                ch == ')' -> {
                    while (opStack.isNotEmpty() && opStack.last() != '(') applyOp()
                    if (opStack.isEmpty()) throw IllegalArgumentException("括号不匹配")
                    opStack.removeLast(); i++
                }
                ch in "+-*/" -> {
                    while (opStack.isNotEmpty() && opStack.last() !in "()" &&
                        precedence(opStack.last()) >= precedence(ch)
                    ) { applyOp() }
                    opStack.addLast(ch); i++
                }
                else -> throw IllegalArgumentException("不支持的字符: $ch")
            }
        }

        while (opStack.isNotEmpty()) applyOp()
        if (numStack.size != 1) throw IllegalArgumentException("表达式无效")
        return numStack.last()
    }

    fun registerTool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        readOnly: Boolean = true,
        handler: suspend (Map<String, Any>) -> JsonObject
    ) {
        tools.add(MCPTool(name, description, inputSchema, readOnly, handler))
        cachedToolList = null
    }

    fun getToolList(): JsonArray {
        if (cachedToolList != null) return cachedToolList!!
        val arr = JsonArray()
        tools.forEach { tool ->
            arr.add(JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("inputSchema", tool.inputSchema)
                add("annotations", JsonObject().apply {
                    addProperty("readOnly", tool.readOnly)
                })
            })
        }
        cachedToolList = arr
        return arr
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): Pair<Boolean, Any> {
        val tool = tools.find { it.name == name } ?: return Pair(false, "工具 $name 不存在")
        return try {
            Pair(true, tool.handler.invoke(arguments))
        } catch (e: Exception) {
            Pair(false, e.message ?: "执行失败")
        }
    }

    private fun jsonSchema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject {
        return JsonObject().apply {
            addProperty("type", "object")
            val properties = JsonObject()
            props.forEach { (k, v) -> properties.add(k, v) }
            add("properties", properties)
            if (required.isNotEmpty()) {
                val req = JsonArray()
                required.forEach { req.add(it) }
                add("required", req)
            } else {
                add("required", JsonArray())
            }
        }
    }

    private fun jsonStringProp(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    private fun jsonNumberProp(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "number")
        addProperty("description", description)
    }

    private fun jsonBoolProp(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
    }

    private val createdChannels = ConcurrentHashMap.newKeySet<String>()

    private fun ensureNotificationChannel(channelId: String) {
        if (createdChannels.contains(channelId)) return
        val channel = android.app.NotificationChannel(
            channelId, channelId, android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "小智 AI 通知"
            enableLights(true)
            enableVibration(true)
        }
        val nm = AppContainer.context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.createNotificationChannel(channel)
        createdChannels.add(channelId)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun httpGet(urlString: String, timeoutMs: Long = 5000): String {
        val client = if (timeoutMs != 5000L) {
            httpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            httpClient
        }
        val request = Request.Builder().url(urlString).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) return ""
            return response.body!!.string()
        }
    }

    private fun httpGetWeather(lat: Double, lon: Double): JsonObject {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "precipitation,weather_code,wind_speed_10m&timezone=auto"
        val text = httpGet(url)
        if (text.isEmpty()) throw Exception("天气 API 请求失败")
        return JsonParser.parseString(text).asJsonObject
    }

    private fun geoCodeCity(city: String): Pair<Double, Double> {
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${
            URLEncoder.encode(city, "UTF-8")
        }&count=1"
        val geoText = httpGet(geoUrl)
        if (geoText.isEmpty()) throw Exception("地理编码请求失败")
        val geoJson = JsonParser.parseString(geoText).asJsonObject
        val results = geoJson.getAsJsonArray("results")
            ?: throw Exception("未找到城市: $city")
        val first = results[0].asJsonObject
        return Pair(first.get("latitude").asDouble, first.get("longitude").asDouble)
    }

    private fun reverseGeoCode(lat: Double, lon: Double): String {
        val text = httpGet(
            "https://geocoding-api.open-meteo.com/v1/search?latitude=$lat&longitude=$lon&count=1"
        )
        if (text.isEmpty()) return "当前位置"
        return try {
            val rg = JsonParser.parseString(text).asJsonObject
            rg.getAsJsonArray("results")?.get(0)?.asJsonObject?.get("name")?.asString ?: "当前位置"
        } catch (_: Exception) { "当前位置" }
    }

    private suspend fun getCurrentLocation(): Pair<Double, Double>? {
        val context = AppContainer.context
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) { return null }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        for (priority in listOf(Priority.PRIORITY_HIGH_ACCURACY, Priority.PRIORITY_BALANCED_POWER_ACCURACY)) {
            val cts = CancellationTokenSource()
            try {
                val loc = fusedClient.getCurrentLocation(priority, cts.token).await()
                if (loc != null) return Pair(loc.latitude, loc.longitude)
            } catch (_: Exception) { } finally { cts.cancel() }
        }
        return null
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) {} }
            addOnFailureListener { continuation.cancel() }
        }
    }

    // ========== Weather Cache ==========

    private data class CacheEntry(val data: JsonObject, val timestamp: Long)
    private val weatherCache = ConcurrentHashMap<String, CacheEntry>()

    // 天气缓存：定期清理过期条目（每30分钟执行一次）
    private fun evictExpiredWeatherCache() {
        val now = System.currentTimeMillis()
        val maxAge = 300_000L  // 5分钟
        weatherCache.entries.removeIf { (now - it.value.timestamp) > maxAge }
    }

    // ========== CalendarHelper ==========

    private data class CalendarEvent(
        val id: Long, val title: String, val description: String,
        val startTime: String, val endTime: String,
        val allDay: Boolean, val location: String
    )

    private object CalendarHelper {
        private val dateOnlyFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
        private val dateTimeFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        private val timeOnlyFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

        private val calendarContentUri = android.provider.CalendarContract.Events.CONTENT_URI
        private val projection = arrayOf(
            android.provider.CalendarContract.Events._ID,
            android.provider.CalendarContract.Events.TITLE,
            android.provider.CalendarContract.Events.DESCRIPTION,
            android.provider.CalendarContract.Events.DTSTART,
            android.provider.CalendarContract.Events.DTEND,
            android.provider.CalendarContract.Events.ALL_DAY,
            android.provider.CalendarContract.Events.EVENT_LOCATION
        )

        fun getEvents(startDate: String?, endDate: String?, maxResults: Int): List<CalendarEvent> {
            val context = AppContainer.context
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) { throw Exception("未授予日历读取权限（READ_CALENDAR），请在设置中开启") }

            val dateFmt = dateOnlyFmt.get()!!
            val dateTimeFormat = dateTimeFmt.get()!!
            val timeFormat = timeOnlyFmt.get()!!
            val now = java.util.Calendar.getInstance()

            val start = if (startDate.isNullOrEmpty()) {
                now.apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                dateFmt.parse(startDate)?.time
                    ?: throw Exception("start_date 格式错误，请使用 yyyy-MM-dd")
            }

            val end = if (endDate.isNullOrEmpty()) {
                start + 24 * 60 * 60 * 1000 - 1
            } else {
                val endCal = dateFmt.parse(endDate)
                    ?: throw Exception("end_date 格式错误，请使用 yyyy-MM-dd")
                java.util.Calendar.getInstance().apply {
                    time = endCal
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                }.timeInMillis
            }

            val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                "${android.provider.CalendarContract.Events.DTSTART} <= ?"
            val cursor = context.contentResolver.query(
                calendarContentUri, projection, selection,
                arrayOf(start.toString(), end.toString()),
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            ) ?: return emptyList()

            val events = mutableListOf<CalendarEvent>()
            cursor.use {
                while (it.moveToNext() && events.size < maxResults) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "(无标题)"
                    val description = it.getString(2) ?: ""
                    val dtStart = it.getLong(3)
                    val dtEnd = it.getLong(4)
                    val allDay = it.getInt(5) == 1
                    val location = it.getString(6) ?: ""

                    val startStr = if (allDay) dateFmt.format(Date(dtStart))
                    else dateTimeFormat.format(Date(dtStart))
                    val endStr = if (allDay || dtEnd <= 0) ""
                    else timeFormat.format(Date(dtEnd))

                    events.add(CalendarEvent(id, title, description, startStr, endStr, allDay, location))
                }
            }
            return events
        }
    }

    // ========== Web Search ==========

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun webSearch(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://html.duckduckgo.com/html/?q=$encodedQuery", timeoutMs = 8000)
        if (html.isEmpty()) throw Exception("搜索引擎请求失败，请检查网络连接")
        return parseDuckDuckGoHtml(html, maxResults)
    }

    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<SearchResult> {
        val resultBlocks = html.split("<a class=\"result__a\"")
            .drop(1)
            .take(maxResults)
        val snippets = parseSnippets(html)
        return resultBlocks.mapIndexedNotNull { index, block ->
            try {
                val url = Regex("href=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val title = Regex(">([^<]+)</a>").find(block)?.groupValues?.get(1)?.trim() ?: return@mapIndexedNotNull null
                val snippet = snippets.getOrElse(index) { "" }.take(200)
                SearchResult(title, url, snippet)
            } catch (_: Exception) { null }
        }
    }

    private fun parseSnippets(html: String): List<String> {
        val pattern = "<a class=\"result__snippet\"[^>]*>((?:(?!</a>).)*)</a>".toRegex()
        return pattern.findAll(html).map {
            it.groupValues[1].replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
        }.toList()
    }

    // ========== WMO Weather Codes ==========

    private val WEATHER_CODES = mapOf(
        0 to "晴",
        1 to "多云", 2 to "多云", 3 to "多云",
        45 to "雾", 48 to "雾",
        51 to "小毛毛雨", 53 to "小毛毛雨", 55 to "小毛毛雨",
        56 to "冻毛毛雨", 57 to "冻毛毛雨",
        61 to "小雨", 63 to "中雨", 65 to "大雨",
        66 to "冻雨", 67 to "冻雨",
        71 to "小雪", 73 to "中雪", 75 to "大雪",
        77 to "雪粒",
        80 to "阵雨", 81 to "阵雨", 82 to "阵雨",
        85 to "阵雪", 86 to "阵雪",
        95 to "雷暴",
        96 to "雷暴+冰雹", 99 to "雷暴+冰雹"
    )

    // ========== 通讯录查询 ==========

    /**
     * 通过联系人名称查找电话号码。先精确匹配，再模糊匹配。
     */
    private fun lookupContactNumber(context: Context, contactName: String): String {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return ""

        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val nameLower = contactName.lowercase()

        // 先精确匹配
        val exactSelection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        context.contentResolver.query(uri, projection, exactSelection, arrayOf(contactName), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(1).replace(Regex("[\\s\\-]"), "")
                if (number.isNotEmpty()) return number
            }
        }

        // 再模糊匹配
        val fuzzySelection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(uri, projection, fuzzySelection, arrayOf("%$contactName%"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: ""
                val number = cursor.getString(1).replace(Regex("[\\s\\-]"), "")
                if (number.isNotEmpty()) return number
            }
        }

        return ""
    }
}
