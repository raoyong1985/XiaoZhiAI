package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun registerBasicTools(registry: MCPToolRegistry) {
    registry.registerTool(
        name = "get_device_info",
        description = "获取Android设备的基本信息，包括厂商、型号、系统版本、屏幕分辨率等",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
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
            addProperty("screen_density_dp", "${(metrics.widthPixels / metrics.density).toInt()}x${(metrics.heightPixels / metrics.density).toInt()}")
            addProperty("app_version", com.xiaozhi.aiservice.BuildConfig.VERSION_NAME)
        }
    }

    registry.registerTool(
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

    registry.registerTool(
        name = "get_battery_level",
        description = "获取当前设备的电池信息：电量百分比、充电状态、温度、健康状态",
        inputSchema = jsonSchema()
    ) { _ ->
        val bm = registry.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        // 电池 Intent（含充电状态、温度、健康）
        val tempIntent = registry.context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        // 充电状态
        val plugged = tempIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeStatus = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "充电中(AC)"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "充电中(USB)"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "充电中(无线)"
            else -> "未充电"
        }
        // 电池温度（单位 0.1°C）
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

    registry.registerTool(
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

    registry.registerTool(
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

    registry.registerTool(
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

    // 统一系统信息工具（对应 ESP32 self.get_system_info）
    registry.registerTool(
        name = "get_system_info",
        description = "获取设备的完整系统信息，一次性返回厂商、型号、电量、内存、存储、时间等",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val stats = android.os.StatFs("/data")
        val blockSize = stats.blockSizeLong
        val totalBlocks = stats.blockCountLong
        val freeBlocks = stats.freeBlocksLong
        val runtime = Runtime.getRuntime()
        JsonObject().apply {
            addProperty("manufacturer", android.os.Build.MANUFACTURER)
            addProperty("model", android.os.Build.MODEL)
            addProperty("android_version", android.os.Build.VERSION.RELEASE)
            addProperty("sdk_int", android.os.Build.VERSION.SDK_INT)
            addProperty("brand", android.os.Build.BRAND)
            addProperty("battery_level", batteryLevel)
            addProperty("battery_status", if (batteryLevel >= 0) "ok" else "unavailable")
            addProperty("ram_total_mb", runtime.totalMemory() / 1024 / 1024)
            addProperty("ram_free_mb", runtime.freeMemory() / 1024 / 1024)
            addProperty("ram_max_mb", runtime.maxMemory() / 1024 / 1024)
            addProperty("storage_total_gb", String.format(Locale.US, "%.1f", totalBlocks * blockSize / 1e9))
            addProperty("storage_free_gb", String.format(Locale.US, "%.1f", freeBlocks * blockSize / 1e9))
            addProperty("app_version", com.xiaozhi.aiservice.BuildConfig.VERSION_NAME)
            addProperty("app_version_code", com.xiaozhi.aiservice.BuildConfig.VERSION_CODE)
            addProperty("current_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }
    }
}
