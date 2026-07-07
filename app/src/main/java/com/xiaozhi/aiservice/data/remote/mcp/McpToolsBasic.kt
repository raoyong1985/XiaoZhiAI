package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


fun registerBasicTools(registry: MCPToolRegistry) {
    // 已合并到 get_system_info（get_device_info 删除）

    // [已删除: calculate/random_number/encode_decode — LLM 自身能力，无需 MCP 工具占用流量]

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

}
