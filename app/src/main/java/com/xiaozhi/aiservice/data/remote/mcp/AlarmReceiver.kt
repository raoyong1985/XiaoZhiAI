package com.xiaozhi.aiservice.data.remote.mcp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 接收 AlarmManager 闹钟触发的广播
 *
 * 修复：
 * 1. 加 setFullScreenIntent → 锁屏下能弹出全屏提醒
 * 2. 闹钟触发后从持久化中删除（避免 BootReceiver 误恢复已响过的闹钟）
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra("hour", 8)
        val minute = intent.getIntExtra("minute", 0)
        val message = intent.getStringExtra("message") ?: "闹钟"
        val alarmId = intent.getStringExtra("alarm_id")

        val timeStr = String.format("%02d:%02d", hour, minute)

        // 触发后从持久化中删除
        if (!alarmId.isNullOrEmpty()) {
            AlarmPersistence.remove(context, alarmId)
        }

        // 发全屏通知（锁屏下也能看到）
        try {
            val notifIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = if (notifIntent != null) {
                PendingIntent.getActivity(
                    context, alarmId?.hashCode() ?: 0, notifIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notification = NotificationCompat.Builder(context, "alarm_channel")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ $message")
                .setContentText("闹钟时间: $timeStr")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .apply {
                    // 锁屏下全屏 Intent（API 31+ 需同时声明 USE_EXACT_ALARM 才有效，否则降级到 heads-up）
                    if (pendingIntent != null) {
                        setFullScreenIntent(pendingIntent, true)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(
                alarmId?.hashCode() ?: 1001, notification
            )
        } catch (_: Exception) {}
    }
}
