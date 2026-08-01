package com.xiaozhi.aiservice.data.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaozhi.aiservice.presentation.ui.MainActivity
import com.xiaozhi.aiservice.utils.Logger

/**
 * 小智 AI 前台保活 Service
 *
 * 功能：
 * 1. 显示常驻通知，防止 Android 12+ 杀掉后台 WebSocket 连接
 * 2. START_STICKY：被系统杀死后自动重启
 * 3. 提供 WebSocket 引用，网络恢复时触发重连
 */
class XiaoZhiForegroundService : Service() {

    companion object {
        private const val TAG = "XiaoZhiFGS"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "xiaozhi_foreground"

        /**
         * 全局 Service 实例引用，ConnectivityReceiver 通过它触发重连
         */
        @Volatile
        var instance: XiaoZhiForegroundService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Logger.i(TAG, "前台Service已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Logger.i(TAG, "前台Service已启动 (startId=$startId)")
        // START_STICKY：被系统杀死后自动重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        Logger.i(TAG, "前台Service已销毁")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "小智 AI",
            NotificationManager.IMPORTANCE_LOW  // LOW = 不发出声音
        ).apply {
            description = "小智 AI 语音助手运行中"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小智 AI")
            .setContentText("语音助手运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)                // 不可滑动清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 网络恢复时由 ConnectivityReceiver 调用
     * 尝试触发所有活跃连接的 WebSocket 重连
     */
    fun onNetworkRestored() {
        Logger.i(TAG, "网络已恢复，尝试触发重连")
        // 通过 GlobalServices 拿到 backend 实例触发重连
        val backend = com.xiaozhi.aiservice.di.GlobalServices.xiaozhiBackend
        backend.triggerReconnect()
    }
}
