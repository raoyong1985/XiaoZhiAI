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

            val formatter = dateOnlyFmt.get()
            val now = java.util.Calendar.getInstance()

            val start = if (startDate.isNullOrEmpty()) {
                now.apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                formatter.parse(startDate)?.time
                    ?: throw Exception("start_date 格式错误，请使用 yyyy-MM-dd")
            }

            val end = if (endDate.isNullOrEmpty()) {
                start + 24 * 60 * 60 * 1000 - 1
            } else {
                val endCal = formatter.parse(endDate)
                    ?: throw Exception("end_date 格式错误，请使用 yyyy-MM-dd")
                java.util.Calendar.getInstance().apply {
                    time = endCal
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                    set(java.util.Calendar.MILLISECOND, 999)
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

                    val startStr = if (allDay) formatter.format(Date(dtStart))
                    else dateTimeFmt.get().format(Date(dtStart))
                    val endStr = if (allDay || dtEnd <= 0) ""
                    else timeOnlyFmt.get().format(Date(dtEnd))

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
}
