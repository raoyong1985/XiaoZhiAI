package com.xiaozhi.aiservice.data.remote.mcp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun registerAlarmTools(registry: MCPToolRegistry) {
    // ──────────────── set_reminder ────────────────
    registry.registerTool(
        name = "set_reminder",
        description = "在手机上设置一个定时提醒，到时间会通过通知提醒你。可以设置延迟时间或具体时间。当用户说「提醒我…」「定时提醒」「X分钟后提醒我」「X点提醒我」时调用此工具",
        inputSchema = jsonSchema(
            "message" to jsonStringProp("提醒内容，如「喝水」「吃药」「开会」"),
            "minutes" to jsonNumberProp("多少分钟后提醒（与 hour/minute 二选一，优先用此参数）"),
            "hour" to jsonNumberProp("提醒的小时 (0-23)，用于指定具体时间"),
            "minute" to jsonNumberProp("提醒的分钟 (0-59)，用于指定具体时间"),
            required = listOf("message")
        )
    ) { args ->
        val ctx = registry.context
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("message 不能为空")

        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance()

        // 优先用 minutes 参数（相对时间）
        val delayMinutes = args["minutes"] as? Number
        if (delayMinutes != null) {
            val mins = delayMinutes.toInt().coerceIn(1, 10080) // 最多7天
            calendar.timeInMillis = now.timeInMillis + mins * 60_000L
        } else {
            // 用绝对时间
            val hour = (args["hour"] as? Number)?.toInt() ?: throw Exception("hour 或 minutes 必须提供一个")
            val minute = (args["minute"] as? Number)?.toInt() ?: 0
            require(hour in 0..23) { "hour 必须在 0-23 之间" }
            require(minute in 0..59) { "minute 必须在 0-59 之间" }
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            if (calendar.before(now)) calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 创建提醒通知频道
        try {
            val channel = android.app.NotificationChannel(
                "reminder_channel", "定时提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI设置的定时提醒"
                enableVibration(true)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {}

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw Exception("系统闹钟服务不可用")

        val requestCode = (System.nanoTime().toInt() and 0x7FFFFFFF)
        val reminderId = "reminder_${requestCode}"

        val pendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = "com.xiaozhi.aiservice.REMINDER_$requestCode"
                putExtra("message", message)
                putExtra("reminder_id", reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 检查精确闹钟权限
        val hasExactPerm = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (hasExactPerm) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                pendingIntent
            )
        } else {
            // setExactAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM，在 Doze 模式下也能触发
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            sendPermissionGuideNotification("精确闹钟")
        }

        // 持久化
        ReminderPersistence.save(ctx, reminderId, message, calendar.timeInMillis)

        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", message)
            addProperty("trigger_time", df.format(calendar.time))
            addProperty("is_today", calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR))
            addProperty("reminder_id", reminderId)
            if (!hasExactPerm) {
                addProperty("permission_note", "需要手动开启「精确闹钟」权限→设置→应用管理→小智→其他权限，否则提醒可能延迟或不响")
            }
        }
    }

    // ──────────────── list_reminders ────────────────
    registry.registerTool(
        name = "list_reminders",
        description = "列出所有尚未触发的定时提醒。当用户说「有哪些提醒」「查看提醒」「我的提醒」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val records = ReminderPersistence.getAll(ctx)
        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val arr = JsonArray()
        records.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("message", r.message)
                addProperty("trigger_time", df.format(Date(r.triggerAt)))
            })
        }
        JsonObject().apply {
            add("reminders", arr)
            addProperty("count", arr.size())
        }
    }

    // ──────────────── cancel_reminder ────────────────
    registry.registerTool(
        name = "cancel_reminder",
        description = "取消一个尚未触发的定时提醒。通过提醒ID或内容查找取消。当用户说「取消提醒」「删掉提醒」「取消XX提醒」时调用此工具",
        inputSchema = jsonSchema(
            "reminder_id" to jsonStringProp("要取消的提醒ID（从 list_reminders 获取）"),
            "message" to jsonStringProp("要取消的提醒内容关键词，匹配后取消第一个匹配项"),
            required = listOf()
        )
    ) { args ->
        val ctx = registry.context
        val reminderId = (args["reminder_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val msg = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (reminderId != null) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ReminderPersistence.remove(ctx, reminderId)
            // 取消已注册的 PendingIntent
            val requestCode = reminderId.removePrefix("reminder_").toIntOrNull()
            if (requestCode != null && alarmManager != null) {
                val pi = PendingIntent.getBroadcast(
                    ctx, requestCode,
                    Intent(ctx, ReminderReceiver::class.java).apply { action = "com.xiaozhi.aiservice.REMINDER_$requestCode" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                pi?.cancel()
            }
            return@registerTool JsonObject().apply {
                addProperty("success", true)
                addProperty("message", "已取消提醒")
            }
        }

        if (msg != null) {
            val records = ReminderPersistence.getAll(ctx)
            val match = records.find { it.message.contains(msg, ignoreCase = true) }
            if (match != null) {
                ReminderPersistence.remove(ctx, match.id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("cancelled_message", match.message)
                    addProperty("message", "已取消提醒「${match.message}」")
                }
            }
            throw Exception("未找到包含「$msg」的提醒")
        }

        throw Exception("请提供 reminder_id 或 message")
    }

    // ──────────────── list_alarms ────────────────
    registry.registerTool(
        name = "list_alarms",
        description = "列出所有已设置的闹钟。当用户说「有哪些闹钟」「查看闹钟」「我的闹钟」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val records = AlarmPersistence.getAll(ctx)
        val arr = JsonArray()
        records.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("time", String.format(Locale.getDefault(), "%02d:%02d", r.hour, r.minute))
                addProperty("message", r.message)
            })
        }
        JsonObject().apply {
            add("alarms", arr)
            addProperty("count", arr.size())
        }
    }

    // ──────────────── cancel_alarm ────────────────
    registry.registerTool(
        name = "cancel_alarm",
        description = "取消一个已设置的闹钟。通过闹钟ID或时间取消。当用户说「取消闹钟」「删掉闹钟」「取消XX点的闹钟」时调用此工具",
        inputSchema = jsonSchema(
            "alarm_id" to jsonStringProp("要取消的闹钟ID（从 list_alarms 获取）"),
            "hour" to jsonNumberProp("要取消的闹钟小时 (0-23)，与 minute 配合使用"),
            "minute" to jsonNumberProp("要取消的闹钟分钟 (0-59)，与 hour 配合使用"),
            required = listOf()
        )
    ) { args ->
        val ctx = registry.context
        val alarmId = (args["alarm_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val hour = (args["hour"] as? Number)?.toInt()
        val minute = (args["minute"] as? Number)?.toInt()

        if (alarmId != null) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            AlarmPersistence.remove(ctx, alarmId)
            // 取消 PendingIntent
            val requestCode = alarmId.substringAfterLast('_').toIntOrNull()
            if (requestCode != null && alarmManager != null) {
                val pi = PendingIntent.getBroadcast(
                    ctx, requestCode,
                    Intent(ctx, AlarmReceiver::class.java).apply { action = "com.xiaozhi.aiservice.ALARM_$requestCode" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                pi?.cancel()
            }
            return@registerTool JsonObject().apply {
                addProperty("success", true)
                addProperty("message", "已取消闹钟")
            }
        }

        if (hour != null && minute != null) {
            val records = AlarmPersistence.getAll(ctx)
            val match = records.find { it.hour == hour && it.minute == minute }
                ?: records.filter { it.hour == hour }.firstOrNull()
            if (match != null) {
                AlarmPersistence.remove(ctx, match.id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("message", "已取消 ${match.hour}:${String.format(Locale.getDefault(), "%02d", match.minute)} 的闹钟")
                }
            }
            throw Exception("未找到 ${String.format(Locale.getDefault(), "%02d:%02d", hour, minute)} 的闹钟")
        }

        throw Exception("请提供 alarm_id 或 hour+minute")
    }
}
