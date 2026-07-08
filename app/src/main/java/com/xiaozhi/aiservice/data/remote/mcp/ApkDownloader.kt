package com.xiaozhi.aiservice.data.remote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * APK 下载与安装管理器
 *
 * 处理 OTA 固件更新：通过 DownloadManager 下载 APK，
 * 完成后自动调用系统安装界面。
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    /**
     * 检查并安装 OTA 固件更新
     *
     * @param fwUrl 固件下载 URL
     * @param fwVersion 固件版本号
     * @param scope 协程作用域（用于发射消息到 UI）
     * @param chatMessageFlow 聊天消息 Flow（用于显示下载进度）
     */
    suspend fun checkAndInstallUpdate(
        fwUrl: String,
        fwVersion: String,
        scope: CoroutineScope,
        chatMessageFlow: MutableSharedFlow<ChatMessage>
    ) {
        // 比较版本号：服务端返回的版本与当前 App 版本不同则更新
        val currentVersion = try {
            val pkg = AppContext.context.packageManager.getPackageInfo(AppContext.context.packageName, 0)
            pkg.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
        if (fwVersion.isNotEmpty() && fwVersion == currentVersion) {
            Logger.i(TAG, "OTA: 版本相同 ($currentVersion)，跳过更新")
            return
        }

        Logger.i(TAG, "OTA: 开始下载更新 $currentVersion → $fwVersion ($fwUrl)")

        // 显示下载开始消息
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = "📥 检测到新版本 $fwVersion，正在下载...",
            type = MessageType.TEXT,
            direction = MessageDirection.RECEIVED,
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        scope.launch { chatMessageFlow.emit(msg) }

        val context = AppContext.context
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return

        // 清理旧下载文件
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "xiaozhi-ota.apk")
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(fwUrl)).apply {
            setTitle("小智 AI 更新 ($fwVersion)")
            setDescription("正在下载新版本 APK...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apkFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val downloadId = downloadManager.enqueue(request)

        // 注册下载完成广播
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                // 检查下载结果
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Logger.i(TAG, "OTA: 下载完成，开始安装")
                        scope.launch { installApk(apkFile) }
                    } else {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Logger.e(TAG, "OTA: 下载失败, status=$status, reason=$reason")
                    }
                    cursor.close()
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }

    /** 安装已下载的 APK */
    private suspend fun installApk(apkFile: File) {
        val context = AppContext.context
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.i(TAG, "OTA: 已启动安装界面")
        } catch (e: Exception) {
            Logger.e(TAG, "OTA: 安装失败: ${e.message}")
        }
    }
}
