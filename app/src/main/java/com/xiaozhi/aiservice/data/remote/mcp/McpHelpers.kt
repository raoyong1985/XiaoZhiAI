package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
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

// ========== JSON Schema Helpers ==========

fun jsonSchema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject {
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

fun jsonStringProp(description: String): JsonObject = JsonObject().apply {
    addProperty("type", "string")
    addProperty("description", description)
}

fun jsonNumberProp(description: String): JsonObject = JsonObject().apply {
    addProperty("type", "number")
    addProperty("description", description)
}

fun jsonBoolProp(description: String): JsonObject = JsonObject().apply {
    addProperty("type", "boolean")
    addProperty("description", description)
}

// ========== HTTP Client ==========

val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .build()

fun httpGet(urlString: String, timeoutMs: Long = 5000): String {
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

// ========== Evaluate Expression ==========

fun evaluateSimpleExpression(expr: String): Double {
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
            ch == '-' && (i == 0 || trimmed[i - 1] in "+-*/( ") -> {
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

// ========== Notification Channel ==========

val createdChannels = ConcurrentHashMap.newKeySet<String>()

fun ensureNotificationChannel(channelId: String) {
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

// ========== Weather Helpers ==========

fun httpGetWeather(lat: Double, lon: Double): JsonObject {
    val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
        "precipitation,weather_code,wind_speed_10m&timezone=auto"
    val text = httpGet(url)
    if (text.isEmpty()) throw Exception("天气 API 请求失败")
    return JsonParser.parseString(text).asJsonObject
}

fun geoCodeCity(city: String): Pair<Double, Double> {
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

fun reverseGeoCode(lat: Double, lon: Double): String {
    val text = httpGet(
        "https://geocoding-api.open-meteo.com/v1/search?latitude=$lat&longitude=$lon&count=1"
    )
    if (text.isEmpty()) return "当前位置"
    return try {
        val rg = JsonParser.parseString(text).asJsonObject
        rg.getAsJsonArray("results")?.get(0)?.asJsonObject?.get("name")?.asString ?: "当前位置"
    } catch (_: Exception) { "当前位置" }
}

suspend fun getCurrentLocation(): Pair<Double, Double>? {
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
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) {} }
        addOnFailureListener { continuation.cancel() }
    }
}

// ========== Weather Cache ==========

data class CacheEntry(val data: JsonObject, val timestamp: Long)
val weatherCache = ConcurrentHashMap<String, CacheEntry>()

fun evictExpiredWeatherCache() {
    val now = System.currentTimeMillis()
    val maxAge = 300_000L  // 5分钟
    weatherCache.entries.removeIf { (now - it.value.timestamp) > maxAge }
}

// ========== WMO Weather Codes ==========

val WEATHER_CODES = mapOf(
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

// ========== Web Search ==========

data class SearchResult(val title: String, val url: String, val snippet: String)

fun webSearch(query: String, maxResults: Int): List<SearchResult> {
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

// ========== Calendar Helper ==========

data class CalendarEvent(
    val id: Long, val title: String, val description: String,
    val startTime: String, val endTime: String,
    val allDay: Boolean, val location: String
)

object CalendarHelper {
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
        ) { openAppPermissionSettings("日历读取权限不足") }

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

// ========== 通讯录查询 ==========

/**
 * 跳转到应用权限设置页面，引导用户开启指定权限
 */
fun openAppPermissionSettings(message: String): Nothing {
    val ctx = AppContainer.context
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", ctx.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    ctx.startActivity(intent)
    throw Exception("$message 已打开应用权限设置，请手动开启后重试")
}

/**
 * 检查并引导 WRITE_SETTINGS 特殊权限
 */
fun ensureWriteSettingsOrGuide(): Boolean {
    val ctx = AppContainer.context
    if (Settings.System.canWrite(ctx)) return true
    // 引导用户去系统设置中开启
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", ctx.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    ctx.startActivity(intent)
    return false
}

// ========== 通讯录查询 ==========

/**
 * 通过联系人名称查找电话号码。先精确匹配，再模糊匹配。
 */
fun lookupContactNumber(context: Context, contactName: String): String {
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
