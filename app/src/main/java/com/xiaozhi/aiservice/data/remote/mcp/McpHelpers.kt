package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.di.AppContext
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

/**
 * 共享 HTTP 客户端（单例 + 连接池）。
 * 所有 MCP 工具的 HTTP 调用都走这里，connection pool 共享避免每次冷启动 DNS/TCP。
 */
val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
}

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
    val nm = AppContext.context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
    nm?.createNotificationChannel(channel)
    createdChannels.add(channelId)
}

// ========== Web Search ==========

suspend fun getCurrentLocation(): Pair<Double, Double>? {
    val context = AppContext.context
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
        val context = AppContext.context
        val calPerm = checkPermissionWithGuide(
            Manifest.permission.READ_CALENDAR, permissionName = "日历"
        )
        if (calPerm != null) return emptyList()  // 权限由调用方检查，此处静默返回空列表

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
 * 发送通知引导用户开启权限，并返回标准的权限不足 JSON
 * 在后台服务中无法可靠地 startActivity，用通知替代
 */
fun sendPermissionGuideNotification(permissionName: String): JsonObject {
    val ctx = AppContext.context
    val channelId = "permission_guide"
    ensureNotificationChannel(channelId)

    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", ctx.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = android.app.PendingIntent.getActivity(
        ctx, System.currentTimeMillis().toInt(), intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(ctx, channelId)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("小智 AI - 需要 $permissionName 权限")
        .setContentText("点击前往设置开启「$permissionName」权限")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    try {
        NotificationManagerCompat.from(ctx).notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification
        )
    } catch (_: Exception) {}

    return JsonObject().apply {
        addProperty("success", false)
        addProperty("error", "permission_denied")
        addProperty("permission", permissionName)
        addProperty("message", "需要「$permissionName」权限，已发送通知提醒，请点击通知前往系统设置开启")
    }
}

/**
 * 通用权限检查 + 通知引导。权限不足时发送通知 → 返回 JsonObject 让调用方 return
 * 用法: if (permMissing) return@registerTool checkPermissionWithGuide("相机")
 */
fun checkPermissionWithGuide(
    permission: String,
    vararg permissions: String,
    permissionName: String
): JsonObject? {
    val ctx = AppContext.context
    val allGranted = (listOf(permission) + permissions).all { p ->
        ActivityCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }
    if (allGranted) return null
    return sendPermissionGuideNotification(permissionName)
}

/**
 * 旧版兼容：跳转到应用权限设置页面（只在应用可见时有效）
 */
fun openAppPermissionSettings(message: String): Nothing {
    val ctx = AppContext.context
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", ctx.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {}
    throw Exception("$message 已打开应用权限设置，请手动开启后重试")
}

/**
 * 检查并引导 WRITE_SETTINGS 特殊权限
 */
fun ensureWriteSettingsOrGuide(): Boolean {
    val ctx = AppContext.context
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
    val nameTrimmed = contactName.trim()
    val nameClean = nameTrimmed.replace(Regex("[\\s\\-_.（）()]+"), "")

    // 策略1: 精确匹配
    val exactSelection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
    context.contentResolver.query(uri, projection, exactSelection, arrayOf(nameTrimmed), null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val number = cursor.getString(1).replace(Regex("[\\s\\-]"), "")
            if (number.isNotEmpty()) return number
        }
    }

    // 策略2: LIKE 模糊匹配（包含）
    val likeSelection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    context.contentResolver.query(uri, projection, likeSelection, arrayOf("%$nameTrimmed%"), null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val number = cursor.getString(1).replace(Regex("[\\s\\-]"), "")
            if (number.isNotEmpty()) return number
        }
    }

    // 策略3: 去特殊字符后精确匹配（"张三" vs "张三(家)"）
    val allRows = mutableListOf<Pair<String, String>>()
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val name = cursor.getString(0) ?: ""
            val number = cursor.getString(1).replace(Regex("[\\s\\-]"), "")
            if (number.isNotEmpty()) allRows.add(name to number)
        }
    }

    // 去特殊字符匹配
    for ((name, number) in allRows) {
        if (name.replace(Regex("[\\s\\-_.（）()\\[\\]【】]+"), "").contains(nameClean, ignoreCase = true) ||
            nameClean.contains(name.replace(Regex("[\\s\\-_.（）()\\[\\]【】]+"), ""), ignoreCase = true)
        ) {
            android.util.Log.i("ContactLookup", "模糊匹配成功: '$name' -> $number (查询: $contactName)")
            return number
        }
    }

    // 策略4: 前缀匹配（"爸" → "爸爸", "张" → "张三"）
    for ((name, number) in allRows) {
        if (name.startsWith(nameTrimmed, ignoreCase = true) ||
            nameTrimmed.startsWith(name, ignoreCase = true)
        ) {
            android.util.Log.i("ContactLookup", "前缀匹配成功: '$name' -> $number (查询: $contactName)")
            return number
        }
    }

    android.util.Log.w("ContactLookup", "未找到联系人: $contactName (通讯录共${allRows.size}条)")
    return ""
}
