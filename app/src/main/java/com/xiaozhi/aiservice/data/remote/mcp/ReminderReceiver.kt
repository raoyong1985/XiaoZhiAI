package com.xiaozhi.aiservice.data.remote.mcp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接收 AlarmManager 触发的定时提醒广播，显示通知。
 *
 * 和 AlarmReceiver 类似但使用独立的通知频道（reminder_channel）。
 * 触发后自动从持久化中删除已过期的提醒。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "提醒"
        val reminderId = intent.getStringExtra("reminder_id")
        // 支持笔记提醒（从 save_note 发起的）
        val noteTitle = intent.getStringExtra("note_title")
        val noteContent = intent.getStringExtra("note_content")
        val noteId = intent.getStringExtra("note_id")

        val displayTitle = if (noteTitle != null) "📝 $noteTitle" else "🔔 $message"
        val displayContent = noteContent ?: "小智 AI 提醒您"

        // 触发后删除持久化记录
        if (!reminderId.isNullOrEmpty()) {
            ReminderPersistence.remove(context, reminderId)
        }

        try {
            // 打开 App 的 Intent
            val notifIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = if (notifIntent != null) {
                PendingIntent.getActivity(
                    context, reminderId?.hashCode() ?: 0, notifIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notification = NotificationCompat.Builder(context, "reminder_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(displayTitle)
                .setContentText(displayContent)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .apply {
                    if (pendingIntent != null) {
                        setFullScreenIntent(pendingIntent, true)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(
                reminderId?.hashCode() ?: 2001, notification
            )
        } catch (_: Exception) {}
    }
}

/**
 * 提醒持久化存储，与 AlarmPersistence 类似但独立存储。
 * 用于设备重启后由 BootReceiver 恢复提醒。
 */
internal object ReminderPersistence {
    private const val PREFS = "xiaozhi_reminders"
    private const val KEY_IDS = "reminder_ids"

    data class ReminderRecord(
        val id: String,
        val message: String,
        val triggerAt: Long
    )

    fun save(ctx: Context, id: String, message: String, triggerAt: Long) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingIds = (prefs.getString(KEY_IDS, "") ?: "").split(",")
            .filter { it.isNotBlank() }
            .toMutableList()
        if (id !in existingIds) existingIds.add(id)
        prefs.edit().apply {
            putString("${id}:message", message)
            putLong("${id}:trigger", triggerAt)
            putString(KEY_IDS, existingIds.joinToString(","))
            apply()
        }
    }

    fun remove(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingIds = (prefs.getString(KEY_IDS, "") ?: "").split(",")
            .filter { it.isNotBlank() && it != id }
        prefs.edit().apply {
            remove("${id}:message")
            remove("${id}:trigger")
            putString(KEY_IDS, existingIds.joinToString(","))
            apply()
        }
    }

    fun getAll(ctx: Context): List<ReminderRecord> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = (prefs.getString(KEY_IDS, "") ?: "").split(",")
            .filter { it.isNotBlank() }
        val now = System.currentTimeMillis()
        val result = mutableListOf<ReminderRecord>()
        val expired = mutableListOf<String>()
        ids.forEach { id ->
            val triggerAt = prefs.getLong("${id}:trigger", 0)
            if (triggerAt < now) {
                expired.add(id)
            } else {
                val message = prefs.getString("${id}:message", "") ?: ""
                result.add(ReminderRecord(id, message, triggerAt))
            }
        }
        // 清理过期
        if (expired.isNotEmpty()) {
            expired.forEach { remove(ctx, it) }
        }
        return result
    }

    fun restoreAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = (prefs.getString(KEY_IDS, "") ?: "").split(",")
            .filter { it.isNotBlank() }
        val now = System.currentTimeMillis()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            ?: return

        ids.forEach { id ->
            val triggerAt = prefs.getLong("${id}:trigger", 0)
            if (triggerAt > now) {
                val message = prefs.getString("${id}:message", "") ?: "提醒"
                val requestCode = id.removePrefix("reminder_").toIntOrNull() ?: return@forEach

                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode,
                    Intent(context, ReminderReceiver::class.java).apply {
                        action = "com.xiaozhi.aiservice.REMINDER_$requestCode"
                        putExtra("message", message)
                        putExtra("reminder_id", id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (android.os.Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAlarmClock(
                        android.app.AlarmManager.AlarmClockInfo(triggerAt, pendingIntent),
                        pendingIntent
                    )
                } else {
                    alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                remove(context, id)
            }
        }
    }
}
