package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.di.GlobalServices
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

fun registerIntentTools(registry: MCPToolRegistry) {
    registry.registerTool(
        name = "open_app",
        description = "在手机上打开指定应用（按应用名或包名）。如果应用未安装，会自动跳转到应用商店下载页。当用户说「打开微信」「启动QQ」「帮我打开抖音」「打开地图」「启动相机」「打开设置」「打开 app」等需要打开某个 app 时调用此工具",
        inputSchema = jsonSchema(
            "app_name" to jsonStringProp("应用名称（中文或英文），如「微信」「淘宝」「抖音」「高德地图」"),
            "package_name" to jsonStringProp("可选，应用包名（精确时优先用），如 com.tencent.mm"),
            "fallback_search" to jsonBoolProp("如果未找到，是否打开应用商店下载页（默认 true）"),
            required = listOf("app_name")
        )
    ) { args ->
        val appName = (args["app_name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("app_name 不能为空")
        val inputPkg = (args["package_name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val fallback = (args["fallback_search"] as? Boolean) ?: true
        val ctx = registry.context

        // 解析包名：内置映射 → 设备已装应用模糊匹配 → 用户传包名 → null
        val resolvedPkg = resolvePackageName(ctx, appName, inputPkg)

        if (resolvedPkg != null) {
            val launchIntent = try {
                ctx.packageManager.getLaunchIntentForPackage(resolvedPkg)
            } catch (_: Exception) { null }

            if (launchIntent != null) {
                try {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(launchIntent)
                    return@registerTool JsonObject().apply {
                        addProperty("success", true)
                        addProperty("app_name", appName)
                        addProperty("package", resolvedPkg)
                        addProperty("via", "installed")
                    }
                } catch (_: Exception) {}
            }
        }

        // 未安装 / 启动失败 → 跳应用商店
        if (!fallback) {
            throw Exception("未找到应用 $appName")
        }

        // 优先用设备默认商店 + 包名
        val pkgForStore = resolvedPkg ?: ""  // 未知包名则跳搜索页
        val marketUri = if (pkgForStore.isNotBlank()) "market://details?id=$pkgForStore"
                        else "market://search?q=${java.net.URLEncoder.encode(appName, "UTF-8")}"
        val marketIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(marketUri))
        marketIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        if (ctx.packageManager.resolveActivity(marketIntent, 0) != null) {
            try {
                ctx.startActivity(marketIntent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("app_name", appName)
                    addProperty("package", pkgForStore.ifBlank { "(未知，按名称搜索)" })
                    addProperty("via", "market")
                    addProperty("uri", marketUri)
                    addProperty("note", "应用未安装，已跳转到应用商店")
                }
            } catch (_: Exception) {}
        }

        // 兜底：常见应用商店
        val knownStores = listOf(
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.oppo.market",
            "com.bbk.appstore",
            "com.tencent.android.qqdownloader",
            "com.android.vending"
        )
        val pm = ctx.packageManager
        for (storePkg in knownStores) {
            if (pm.getLaunchIntentForPackage(storePkg) == null) continue
            val storeIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(marketUri))
            storeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            storeIntent.setPackage(storePkg)
            try {
                ctx.startActivity(storeIntent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("app_name", appName)
                    addProperty("via", "market_fallback")
                    addProperty("store", storePkg)
                    addProperty("note", "已跳转到 $storePkg 搜索 $appName")
                }
            } catch (_: Exception) { continue }
        }

        // 终极兜底：浏览器搜
        try {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.google.com/search?q=${java.net.URLEncoder.encode("$appName app download", "UTF-8")}")
            )
            webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(webIntent)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("app_name", appName)
                addProperty("via", "web")
                addProperty("note", "设备无应用商店，已打开浏览器搜索 $appName")
            }
        } catch (e: Exception) {
            throw Exception("未找到应用 $appName，且设备无可用应用商店: ${e.message}")
        }
    }

    registry.registerTool(
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
            registry.context.startActivity(this)
        }
        JsonObject().apply { addProperty("success", true); addProperty("url", finalUrl) }
    }

    registry.registerTool(
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
        val audioManager = registry.context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
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

    registry.registerTool(
        name = "set_alarm",
        description = "在手机上设置闹钟提醒",
        inputSchema = jsonSchema(
            "hour" to jsonNumberProp("小时 (0-23)"),
            "minute" to jsonNumberProp("分钟 (0-59)"),
            "message" to jsonStringProp("闹钟标签/备注"),
            required = listOf("hour", "minute")
        )
    ) { args ->
        val ctx = registry.context
        val hour = (args["hour"] as? Number)?.toInt() ?: throw Exception("hour 必须为 0-23 的数字")
        val minute = (args["minute"] as? Number)?.toInt() ?: throw Exception("minute 必须为 0-59 的数字")
        require(hour in 0..23) { "hour 必须在 0-23 之间" }
        require(minute in 0..59) { "minute 必须在 0-59 之间" }
        val message = args["message"] as? String ?: "闹钟"
        val timeStr = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

        // 创建通知频道（首次调用会自动创建）
        try {
            val channel = android.app.NotificationChannel(
                "alarm_channel", "闹钟提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI设置的闹钟提醒"
                enableVibration(true)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {}

        // 计算触发时间
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_MONTH, 1) // 已过则设到明天
            }
        }

        // 文案："今天 HH:MM" 还是 "明天 HH:MM"——按实际 Y/M/D 判断，不依赖 timeInMillis（timeInMillis 此时永远 >= now）
        val isToday = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val alarmTimeText = if (isToday) "今天 $timeStr" else "明天 $timeStr"

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw Exception("系统闹钟服务不可用")

        // 唯一 requestCode：时间戳 + 分钟偏移
        val requestCode = ((hour * 60 + minute) and 0x7FFFFFFF) xor (System.nanoTime().toInt() and 0xFFFF)
        // 闹钟唯一 ID（用于 SharedPreferences 持久化，重启后用同一 ID 重新注册）
        val alarmId = "${hour}_${minute}_$requestCode"

        // 共用 PendingIntent 实例（避免之前代码创建两次 → 第二次实际覆盖第一次）
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, AlarmReceiver::class.java).apply {
                action = "com.xiaozhi.aiservice.ALARM_$requestCode"
                putExtra("hour", hour); putExtra("minute", minute); putExtra("message", message)
                putExtra("alarm_id", alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 检查 SCHEDULE_EXACT_ALARM 权限
        val hasExactPerm = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (hasExactPerm) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                pendingIntent
            )
        } else {
            // setExactAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM，不精确但可接受
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            // 引导用户开启精确闹钟权限
            sendPermissionGuideNotification("精确闹钟")
        }

        // 持久化闹钟到 SharedPreferences（用于设备重启后由 BootReceiver 重新注册）
        AlarmPersistence.save(ctx, alarmId, hour, minute, message, calendar.timeInMillis)

        JsonObject().apply {
            addProperty("success", true)
            addProperty("hour", hour)
            addProperty("minute", minute)
            addProperty("message", message)
            addProperty("time", timeStr)
            addProperty("alarm_time", alarmTimeText)
            addProperty("alarm_id", alarmId)
            if (!hasExactPerm) {
                addProperty("permission_note", "需要手动开启「精确闹钟」权限→设置→应用管理→小智→其他权限，否则闹钟可能延迟或不响")
            }
        }
    }

    registry.registerTool(
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
                    registry.context.startActivity(this)
                }
                JsonObject().apply { addProperty("success", true); addProperty("seconds", seconds); addProperty("display", display) }
            } catch (e: Exception) { throw Exception("无法设置倒计时: ${e.message}") }
        } else {
            JsonObject().apply { addProperty("success", true); addProperty("note", "此设备不支持倒计时，请手动设置"); addProperty("seconds", seconds); addProperty("display", display) }
        }
    }

    registry.registerTool(
        name = "vibrate",
        description = "让手机震动，可指定时长和模式",
        inputSchema = jsonSchema(
            "duration_ms" to jsonNumberProp("震动时长（毫秒），默认 500"),
            "pattern" to jsonStringProp("震动模式：once/short/long/rapid，默认 once")
        )
    ) { args ->
        val duration = (args["duration_ms"] as? Number)?.toLong() ?: 500L
        val pattern = (args["pattern"] as? String)?.lowercase() ?: "once"
        val ctx = registry.context

        // 检查震动权限
        val vibratePerm = checkPermissionWithGuide(
            Manifest.permission.VIBRATE, permissionName = "震动"
        )
        if (vibratePerm != null) return@registerTool vibratePerm

        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
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

    registry.registerTool(
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

        val notification = NotificationCompat.Builder(registry.context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notifPerm = checkPermissionWithGuide(
            Manifest.permission.POST_NOTIFICATIONS, permissionName = "通知"
        )
        if (notifPerm != null) return@registerTool notifPerm
        NotificationManagerCompat.from(registry.context).notify(
            System.currentTimeMillis().toInt(), notification
        )
        JsonObject().apply {
            addProperty("success", true)
            addProperty("title", title)
            addProperty("content", content)
        }
    }

    registry.registerTool(
        name = "screen_brightness",
        description = "获取当前屏幕亮度，或设置亮度百分比",
        inputSchema = jsonSchema(
            "level" to jsonNumberProp("设置亮度 0-100（百分比），不传则只读取当前亮度"),
        )
    ) { args ->
        val ctx = registry.context
        val resolver = ctx.contentResolver
        try {
            val current = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
            val level = args["level"] as? Number
            if (level != null) {
                val v = level.toInt().coerceIn(0, 100)
                val sysVal = (v * 255 / 100).coerceIn(0, 255)
                if (android.provider.Settings.System.canWrite(ctx)) {
                    android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, sysVal)
                } else if (ensureWriteSettingsOrGuide()) {
                    android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, sysVal)
                } else {
                    return@registerTool JsonObject().apply {
                        addProperty("success", false)
                        addProperty("error", "permission_denied")
                        addProperty("permission", "修改系统设置")
                        addProperty("message", "需要「修改系统设置」权限才能调节亮度，已打开设置页面，请手动开启后重试")
                    }
                }
                JsonObject().apply { addProperty("success", true); addProperty("previous", current * 100 / 255); addProperty("current", v) }
            } else {
                JsonObject().apply { addProperty("success", true); addProperty("current", current * 100 / 255); addProperty("note", "不传 level 参数只读取亮度，传 level=N 可设置亮度") }
            }
        } catch (e: Exception) {
            throw Exception("获取/设置亮度失败: ${e.message}")
        }
    }

    registry.registerTool(
        name = "navigate",
        description = "打开地图导航到指定目的地，支持高德/百度/腾讯地图或系统地图",
        inputSchema = jsonSchema(
            "destination" to jsonStringProp("目的地名称或地址"),
            "latitude" to jsonNumberProp("目的地纬度（可选，传经纬度可精确定位）"),
            "longitude" to jsonNumberProp("目的地经度（可选，传经纬度可精确定位）"),
            "mode" to jsonStringProp("导航模式：driving/walking/transit/cycling，默认 driving"),
            "app" to jsonStringProp("地图应用：auto/amap/baidu/tencent/google，默认 auto（依次尝试高德→百度→腾讯→系统地图）"),
            required = listOf("destination")
        )
    ) { args ->
        val destination = (args["destination"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("destination 不能为空")
        val lat = (args["latitude"] as? Number)?.toDouble()
        val lon = (args["longitude"] as? Number)?.toDouble()
        val mode = (args["mode"] as? String)?.lowercase() ?: "driving"
        val app = (args["app"] as? String)?.lowercase() ?: "auto"
        val ctx = registry.context

        val dlat = args["dlat"] as? Double
        val dlon = args["dlon"] as? Double

        // Auto mode: try amap → baidu → tencent → system → google web in sequence
        if (app == "auto") {
            val encodedDest = URLEncoder.encode(destination, "UTF-8")
            val naviStyle = when(mode){"driving"->2;"walking"->4;"transit"->1;else->2}
            val baiduMode = when(mode){"driving"->"driving";"walking"->"walking";"transit"->"transit";"cycling"->"riding";else->"driving"}
            val tMode = when(mode){"driving"->"drive";"walking"->"walk";"transit"->"bus";"cycling"->"bike";else->"drive"}

            // 1. Try Amap (高德)
            val amapUri = if (lat != null && lon != null)
                "androidamap://navi?sourceApplication=xiaozhi&lat=$lat&lon=$lon&poiname=$encodedDest&style=$naviStyle"
            else
                "androidamap://keywordNavi?sourceApplication=xiaozhi&keyword=$encodedDest&style=$naviStyle"
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(amapUri)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                ctx.startActivity(intent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("destination", destination)
                    addProperty("app", "amap"); addProperty("mode", mode)
                    addProperty("message", "已打开高德地图导航到$destination")
                }
            } catch (_: Exception) { /* amap not installed, try next */ }

            // 2. Try Baidu (百度)
            val bCoord = if (lat != null && lon != null) "|ll=${lat},${lon}" else ""
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "baidumap://map/direction?destination=$encodedDest$bCoord&coord_type=gcj02&mode=$baiduMode"
                )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                ctx.startActivity(intent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("destination", destination)
                    addProperty("app", "baidu"); addProperty("mode", mode)
                    addProperty("message", "已打开百度地图导航到$destination")
                }
            } catch (_: Exception) { /* baidu not installed, try next */ }

            // 3. Try Tencent (腾讯)
            val tCoord = if (lat != null && lon != null) "latlng=${lat},${lon}&name=$encodedDest" else "keyword=$encodedDest"
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://apis.map.qq.com/uri/v1/navigation?$tCoord&type=$tMode"
                )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                ctx.startActivity(intent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("destination", destination)
                    addProperty("app", "tencent"); addProperty("mode", mode)
                    addProperty("message", "已打开腾讯地图导航到$destination")
                }
            } catch (_: Exception) { /* tencent not installed, try next */ }

            // 4. System geo intent
            try {
                val geoUri = if (lat != null && lon != null) "geo:${lat},${lon}?q=${lat},${lon}($encodedDest)"
                else "geo:0,0?q=$encodedDest"
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(geoUri)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                ctx.startActivity(intent)
                return@registerTool JsonObject().apply {
                    addProperty("success", true); addProperty("destination", destination)
                    addProperty("app", "system"); addProperty("mode", mode)
                    addProperty("message", "已打开系统地图导航到$destination")
                }
            } catch (_: Exception) { /* no system map, try google web */ }

            // 5. Google Maps web fallback
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                "https://www.google.com/maps/search/?api=1&query=$encodedDest"
            )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            try {
                ctx.startActivity(intent)
                JsonObject().apply {
                    addProperty("success", true); addProperty("destination", destination)
                    addProperty("app", "google"); addProperty("mode", mode)
                    addProperty("message", "已打开Google Maps导航到$destination")
                }
            } catch (_: Exception) {
                throw Exception("无法打开导航，请安装高德/百度/腾讯地图")
            }
        }

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
            "tencent" -> {
                val coord = if (lat != null && lon != null) "latlng=${lat},${lon}&name=${URLEncoder.encode(destination, "UTF-8")}" else "keyword=${URLEncoder.encode(destination, "UTF-8")}"
                val tMode = when(mode){"driving"->"drive";"walking"->"walk";"transit"->"bus";"cycling"->"bike";else->"drive"}
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://apis.map.qq.com/uri/v1/navigation?$coord&type=$tMode"
                ))
            }
            "google" -> {
                val coord = if (lat != null && lon != null) "${lat},${lon}" else URLEncoder.encode(destination, "UTF-8")
                val travelMode = when (mode) { "driving" -> "driving"; "walking" -> "walking"; "transit" -> "transit"; "cycling" -> "bicycling"; else -> "driving" }
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=$coord&travelmode=$travelMode"
                ))
            }
            else -> throw Exception("不支持的地图应用: $app")
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


    registry.registerTool(
        name = "make_call",
        description = "拨打电话。有权限直接呼叫，无权限打开拨号界面",
        inputSchema = jsonSchema(
            "phone_number" to jsonStringProp("电话号码（可选，提供 name 时会自动查找联系人号码）"),
            "name" to jsonStringProp("联系人姓名（可选，自动从通讯录查找号码）"),
        )
    ) { args ->
        val ctx = registry.context
        var number = (args["phone_number"] as? String)?.takeIf { it.isNotBlank() } ?: ""
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""

        // 只有 name 没有 number 时，从通讯录查找
        if (number.isEmpty() && name.isNotEmpty()) {
            try {
                number = lookupContactNumber(ctx, name)
            } catch (_: Exception) {}
            if (number.isEmpty()) throw Exception("未在通讯录中找到联系人: $name")
        }
        if (number.isEmpty()) throw Exception("phone_number 或 name 至少提供一个")
        number = number.replace(Regex("[\\s\\-()（）]"), "")
        val displayName = if (name.isNotEmpty()) "$name($number)" else number

        val hasCallPerm = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        if (hasCallPerm) {
            // 有权限 → 直接呼叫
            val intent = Intent(Intent.ACTION_CALL, android.net.Uri.fromParts("tel", number, null)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("phone_number", number)
                if (name.isNotEmpty()) addProperty("name", name)
                addProperty("message", "正在呼叫: $displayName")
            }
        } else {
            // 无权限 → 通知引导 + 打开拨号界面
            sendPermissionGuideNotification("通话")
            val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("phone_number", number)
                if (name.isNotEmpty()) addProperty("name", name)
                addProperty("message", "已打开拨号界面: $displayName（尚未授予通话权限，已发送通知引导设置）")
            }
        }
    }

    registry.registerTool(
        name = "send_sms",
        description = "发送短信。有权限直接发送，无权限打开短信编辑界面",
        inputSchema = jsonSchema(
            "phone_number" to jsonStringProp("接收短信的电话号码（可选，提供 name 时会自动查找）"),
            "message" to jsonStringProp("短信内容"),
            "name" to jsonStringProp("联系人姓名（可选，自动从通讯录查找号码）"),
            required = listOf("message")
        )
    ) { args ->
        val ctx = registry.context
        var number = (args["phone_number"] as? String)?.takeIf { it.isNotBlank() } ?: ""
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("message 不能为空")

        // 只有 name 没有 number 时，从通讯录查找
        if (number.isEmpty() && name.isNotEmpty()) {
            try {
                number = lookupContactNumber(ctx, name)
            } catch (_: Exception) {}
            if (number.isEmpty()) throw Exception("未在通讯录中找到联系人: $name")
        }
        if (number.isEmpty()) throw Exception("phone_number 或 name 至少提供一个")
        number = number.replace(Regex("[\\s\\-()（）]"), "")
        val displayName = if (name.isNotEmpty()) name else number

        val hasSmsPerm = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPerm) {
            // 有权限 → 直接发送
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
                addProperty("message_length", message.length)
                addProperty("message", "短信已发送到: $displayName")
            }
        } else {
            // 无权限 → 通知引导 + 打开短信界面
            sendPermissionGuideNotification("短信")
            val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("phone_number", number)
                if (name.isNotEmpty()) addProperty("name", name)
                addProperty("message_length", message.length)
                addProperty("message", "已打开短信编辑界面（尚未授予短信权限，已发送通知引导设置）")
            }
        }
    }

    registry.registerTool(
        name = "toggle_flashlight",
        description = "打开或关闭手机手电筒/闪光灯",
        inputSchema = jsonSchema(
            "enable" to jsonBoolProp("true 打开，false 关闭"),
            required = listOf("enable")
        )
    ) { args ->
        val enable = (args["enable"] as? Boolean) ?: throw Exception("enable 必须为布尔值")
        val ctx = registry.context

        // 检查相机权限（手电筒使用相机闪光灯需要 CAMERA 权限）
        val flashlightPerm = checkPermissionWithGuide(
            Manifest.permission.CAMERA, permissionName = "相机"
        )
        if (flashlightPerm != null) return@registerTool flashlightPerm

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

    registry.registerTool(
        name = "toggle_wifi",
        description = "打开或关闭手机 WiFi",
        inputSchema = jsonSchema(
            "enable" to jsonBoolProp("true 打开，false 关闭"),
            required = listOf("enable")
        )
    ) { args ->
        val enable = (args["enable"] as? Boolean) ?: throw Exception("enable 必须为布尔值")
        val ctx = registry.context
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: throw Exception("此设备不支持 WiFi")

        try {
            // Android 10+ (API 29+) 禁止三方应用直接开关 WiFi
            // Settings.Global.WIFI_ON 在 10+ 三方 app 写入会被系统忽略 → 跳系统 WiFi 面板
            if (Build.VERSION.SDK_INT >= 29) {
                val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(panel)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("via_panel", true)
                    addProperty("enabled", enable)
                    addProperty("message", "Android 10+ 已打开系统 WiFi 面板，请手动切换 WiFi 开关")
                }
            } else {
                wifiManager.isWifiEnabled = enable
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("enabled", enable)
                    addProperty("message", if (enable) "WiFi 已打开" else "WiFi 已关闭")
                }
            }
        } catch (e: Exception) {
            throw Exception("控制 WiFi 失败: ${e.message}")
        }
    }

    registry.registerTool(
        name = "take_screenshot",
        description = "对手机当前屏幕进行截图。当用户说「截屏」「截图」「截个图」「屏幕截图」时，调用此工具。截图后会在聊天框中显示图片",
        inputSchema = jsonSchema()
    ) { _ ->
        val context = registry.context

        if (android.os.Build.VERSION.SDK_INT < 31) {
            return@registerTool JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "此设备不支持自动截图，请使用系统截图快捷键（音量减+电源键）")
            }
        }

        // 并发保护：如果上一次截图还没回来，拒绝本次请求
        val existing = com.xiaozhi.aiservice.presentation.ui.ScreenshotCaptureActivity.pendingResult
        if (existing != null && !existing.isCompleted) {
            throw Exception("正在截图中，请稍候再试")
        }

        // 启动截图 Activity 并等待结果
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        com.xiaozhi.aiservice.presentation.ui.ScreenshotCaptureActivity.pendingResult = deferred
        com.xiaozhi.aiservice.presentation.ui.ScreenshotCaptureActivity.launch(context)

        val filePath = kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
            ?: throw Exception("截图超时（15秒），请重试")

        // 检查文件是否存在
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw Exception("截图文件保存失败")
        }

        // 将截图插入聊天消息，显示为图片气泡
        val contactId = registry.connectionRepository.get().currentContactId
        if (contactId.isNotEmpty()) {
            registry.messageRepository.addSentMessage(
                contactId,
                com.xiaozhi.aiservice.domain.model.ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    content = file.absolutePath,
                    type = com.xiaozhi.aiservice.domain.model.MessageType.IMAGE,
                    direction = com.xiaozhi.aiservice.domain.model.MessageDirection.RECEIVED,
                    status = com.xiaozhi.aiservice.domain.model.MessageStatus.SENT,
                    timestamp = System.currentTimeMillis()
                )
            )
            Logger.i("MCPToolRegistry", "截图已添加到聊天: ${file.absolutePath}")
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", "截屏已保存到相册")
            addProperty("file_path", file.absolutePath)
            addProperty("file_size", file.length())
        }
    }

    registry.registerTool(
        name = "toggle_xiaozhi_mute",
        description = "切换小智 AI 麦克风的静音状态。静音后 AI 不会听到你的声音，但对话仍然可以继续",
        inputSchema = jsonSchema(
            "muted" to jsonBoolProp("true 静音，false 取消静音"),
            required = listOf("muted")
        )
    ) { args ->
        val muted = (args["muted"] as? Boolean) ?: throw Exception("muted 必须为布尔值")
        val backend = GlobalServices.xiaozhiBackend
        backend.sendMute(muted)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("muted", muted)
            addProperty("message", if (muted) "麦克风已静音" else "麦克风已取消静音")
        }
    }

    registry.registerTool(
        name = "get_installed_apps",
        description = "列出手机上安装的应用包名、名称和版本号（非系统应用）",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 30"),
            "keyword" to jsonStringProp("搜索关键词，只返回名称或包名包含此关键词的应用"),
        )
    ) { args ->
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 30
        val keyword = (args["keyword"] as? String)?.lowercase() ?: ""
        val pm = registry.context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .distinctBy { it.packageName }
            .filter { keyword.isEmpty() || it.packageName.lowercase().contains(keyword) || it.loadLabel(pm).toString().lowercase().contains(keyword) }
            .take(maxResults)
        val arr = JsonArray()
        apps.forEach { a ->
            val pkgInfo = try { pm.getPackageInfo(a.packageName, 0) } catch (_: Exception) { null }
            arr.add(JsonObject().apply {
                addProperty("name", a.loadLabel(pm).toString())
                addProperty("package", a.packageName)
                pkgInfo?.versionName?.let { addProperty("version", it) }
            })
        }
        JsonObject().apply { add("apps", arr); addProperty("count", arr.size()) }
    }

    registry.registerTool(
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

    registry.registerTool(
        name = "get_calendar",
        description = "获取日历中的事件列表，默认查询今天的事件，支持指定日期范围",
        inputSchema = jsonSchema(
            "start_date" to jsonStringProp("开始日期，格式 yyyy-MM-dd，默认今天"),
            "end_date" to jsonStringProp("结束日期，格式 yyyy-MM-dd，默认 start_date + 1 天"),
            "max_results" to jsonNumberProp("最多返回事件数量，默认 20")
        )
    ) { args ->
        // 先检查日历权限
        val calPerm = checkPermissionWithGuide(
            Manifest.permission.READ_CALENDAR, permissionName = "日历"
        )
        if (calPerm != null) return@registerTool calPerm

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
}

/**
 * 闹钟持久化（SharedPreferences），用于设备重启后由 BootReceiver 重新注册
 *
 * 数据格式：
 * - "alarm_ids"  StringSet  保存所有闹钟 ID
 * - "$id:triggerAt"  Long    触发时间戳
 * - "$id:hour"       Int     小时
 * - "$id:minute"     Int     分钟
 * - "$id:message"    String  标签
 */
internal object AlarmPersistence {
    private const val PREFS = "xiaozhi_alarms"
    private const val KEY_IDS = "alarm_ids"

    data class AlarmRecord(
        val id: String,
        val hour: Int,
        val minute: Int,
        val message: String,
        val triggerAt: Long
    )

    fun save(context: Context, id: String, hour: Int, minute: Int, message: String, triggerAt: Long) {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            putLong("$id:triggerAt", triggerAt)
            putInt("$id:hour", hour)
            putInt("$id:minute", minute)
            putString("$id:message", message)
            val ids = sp.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            ids.add(id)
            putStringSet(KEY_IDS, ids)
        }.apply()
    }

    fun remove(context: Context, id: String) {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            remove("$id:triggerAt")
            remove("$id:hour")
            remove("$id:minute")
            remove("$id:message")
            val ids = sp.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            ids.remove(id)
            putStringSet(KEY_IDS, ids)
        }.apply()
    }

    fun getAll(context: Context): List<AlarmRecord> {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = sp.getStringSet(KEY_IDS, emptySet()) ?: return emptyList()
        val now = System.currentTimeMillis()
        return ids.mapNotNull { id ->
            val triggerAt = sp.getLong("$id:triggerAt", -1L)
            if (triggerAt < 0) return@mapNotNull null
            if (triggerAt < now) {
                // 已过期，清理
                remove(context, id)
                return@mapNotNull null
            }
            AlarmRecord(
                id = id,
                hour = sp.getInt("$id:hour", 0),
                minute = sp.getInt("$id:minute", 0),
                message = sp.getString("$id:message", "") ?: "",
                triggerAt = triggerAt
            )
        }
    }

    /**
     * 用与 set_alarm 一致的方式重新调度所有未过期的闹钟。
     * 供 BootReceiver 在 BOOT_COMPLETED 时调用，恢复重启前设置的闹钟。
     */
    fun restoreAll(context: Context) {
        val ctx = context.applicationContext
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val records = getAll(ctx)
        records.forEach { r ->
            // 从 id "H_M_requestCode" 还原 requestCode（最后一段），保证 PendingIntent 标识一致
            val requestCode = r.id.substringAfterLast('_').toIntOrNull() ?: return@forEach
            val pi = PendingIntent.getBroadcast(
                ctx, requestCode,
                Intent(ctx, AlarmReceiver::class.java).apply {
                    action = "com.xiaozhi.aiservice.ALARM_$requestCode"
                    putExtra("hour", r.hour)
                    putExtra("minute", r.minute)
                    putExtra("message", r.message)
                    putExtra("alarm_id", r.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
                } else {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(r.triggerAt, pi),
                        pi
                    )
                }
            } catch (_: SecurityException) {
                // SCHEDULE_EXACT_ALARM 未授权时降级为 inexact
                try { alarmManager.set(AlarmManager.RTC_WAKEUP, r.triggerAt, pi) } catch (_: Exception) {}
            }
        }
    }
}
