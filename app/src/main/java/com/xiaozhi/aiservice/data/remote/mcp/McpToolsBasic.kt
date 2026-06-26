package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first

fun registerBasicTools(registry: MCPToolRegistry) {
    // 已合并到 get_system_info（get_device_info 删除）

    registry.registerTool(
        name = "get_phone_time",
        description = "获取手机当前系统时间，支持指定时间格式",
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

    registry.registerTool(
        name = "get_battery_level",
        description = "获取当前设备的电池信息：电量百分比、充电状态、温度、健康状态",
        inputSchema = jsonSchema()
    ) { _ ->
        val bm = registry.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val tempIntent = registry.context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val plugged = tempIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeStatus = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "充电中(AC)"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "充电中(USB)"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "充电中(无线)"
            else -> "未充电"
        }
        val temperature = (tempIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = when (tempIntent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0) ?: 0) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            else -> "未知"
        }
        JsonObject().apply {
            addProperty("level", level)
            addProperty("charge_status", chargeStatus)
            addProperty("temperature_c", temperature)
            addProperty("health", health)
            addProperty("status", if (level >= 0) "ok" else "unavailable")
        }
    }

    // [已删除: calculate/random_number/encode_decode — LLM 自身能力，无需 MCP 工具占用流量]

    registry.registerTool(
        name = "get_orientation",
        description = "获取设备当前的屏幕方向和旋转角度",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
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

    registry.registerTool(
        name = "get_memory_info",
        description = "获取设备的内存（RAM）和存储空间使用情况",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val stats = android.os.StatFs("/data")
        val blockSize = stats.blockSizeLong
        val totalBlocks = stats.blockCountLong
        val freeBlocks = stats.freeBlocksLong
        val availBlocks = stats.availableBlocksLong
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        JsonObject().apply {
            addProperty("ram_total_mb", memoryInfo.totalMem / 1024 / 1024)
            addProperty("ram_avail_mb", memoryInfo.availMem / 1024 / 1024)
            addProperty("ram_threshold_mb", memoryInfo.threshold / 1024 / 1024)
            addProperty("ram_low", memoryInfo.lowMemory)
            addProperty("storage_total_gb", String.format(Locale.US, "%.1f", totalBlocks * blockSize / 1e9))
            addProperty("storage_used_gb", String.format(Locale.US, "%.1f", (totalBlocks - freeBlocks) * blockSize / 1e9))
            addProperty("storage_free_gb", String.format(Locale.US, "%.1f", freeBlocks * blockSize / 1e9))
            addProperty("storage_avail_gb", String.format(Locale.US, "%.1f", availBlocks * blockSize / 1e9))
        }
    }

    // 统一系统信息工具（含 get_device_info 所有字段）
    registry.registerTool(
        name = "get_system_info",
        description = "获取设备的完整系统信息，一次性返回厂商、型号、屏幕、电量、内存、存储、时间等",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val stats = android.os.StatFs("/data")
        val blockSize = stats.blockSizeLong
        val totalBlocks = stats.blockCountLong
        val freeBlocks = stats.freeBlocksLong
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        JsonObject().apply {
            addProperty("manufacturer", android.os.Build.MANUFACTURER)
            addProperty("model", android.os.Build.MODEL)
            addProperty("android_version", android.os.Build.VERSION.RELEASE)
            addProperty("sdk_int", android.os.Build.VERSION.SDK_INT)
            addProperty("brand", android.os.Build.BRAND)
            addProperty("device", android.os.Build.DEVICE)
            addProperty("screen_width_px", metrics.widthPixels)
            addProperty("screen_height_px", metrics.heightPixels)
            addProperty("screen_density", metrics.densityDpi)
            addProperty("battery_level", batteryLevel)
            addProperty("battery_status", if (batteryLevel >= 0) "ok" else "unavailable")
            addProperty("ram_total_mb", memoryInfo.totalMem / 1024 / 1024)
            addProperty("ram_avail_mb", memoryInfo.availMem / 1024 / 1024)
            addProperty("ram_low", memoryInfo.lowMemory)
            addProperty("storage_total_gb", String.format(Locale.US, "%.1f", totalBlocks * blockSize / 1e9))
            addProperty("storage_free_gb", String.format(Locale.US, "%.1f", freeBlocks * blockSize / 1e9))
            addProperty("app_version", com.xiaozhi.aiservice.BuildConfig.VERSION_NAME)
            addProperty("app_version_code", com.xiaozhi.aiservice.BuildConfig.VERSION_CODE)
            addProperty("current_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            addProperty("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
        }
    }

    // ── 联系人备份 ──

    registry.registerTool(
        name = "export_contacts",
        description = "导出所有联系人数据到备份文件，用于下次重装后恢复",
        inputSchema = jsonSchema()
    ) { _ ->
        val backupDir = java.io.File(registry.context.filesDir, "backup")
        backupDir.mkdirs()
        val file = java.io.File(backupDir, "contacts_backup.json")

        val dataStore = createContactsDataStore(registry.context)
        val json = dataStore.data.first()[CONTACTS_KEY] ?: "[]"
        file.writeText(json)

        val count = try { com.google.gson.JsonParser.parseString(json).asJsonArray.size() } catch (_: Exception) { 0 }
        JsonObject().apply { addProperty("message", "已导出 $count 个联系人: ${file.absolutePath}") }
    }

    registry.registerTool(
        name = "import_contacts",
        description = "从备份文件恢复联系人数据（覆盖当前联系人）",
        inputSchema = jsonSchema()
    ) { _ ->
        val file = java.io.File(registry.context.filesDir, "backup/contacts_backup.json")
        if (!file.exists()) {
            return@registerTool JsonObject().apply { addProperty("error", "备份文件不存在，请先执行 export_contacts") }
        }
        val json = file.readText()
        val dataStore = createContactsDataStore(registry.context)
        dataStore.edit { it[CONTACTS_KEY] = json }

        val count = try { com.google.gson.JsonParser.parseString(json).asJsonArray.size() } catch (_: Exception) { 0 }
        JsonObject().apply { addProperty("message", "已恢复 $count 个联系人") }
    }
}

private val CONTACTS_KEY = stringPreferencesKey("bot_contacts")

private fun createContactsDataStore(context: Context): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    return PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("contacts")
    }
}
