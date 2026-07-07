package com.xiaozhi.aiservice

import android.app.Application
import android.content.Intent
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.remote.ConnectivityReceiver
import com.xiaozhi.aiservice.data.remote.LocalCommandRouter
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.data.remote.XiaoZhiForegroundService
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.di.GlobalServices
import com.xiaozhi.aiservice.di.ServicesEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors

import com.xiaozhi.aiservice.utils.WebViewSpeaker

@HiltAndroidApp
class XiaoZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化 AppContext（context + 设备 ID）
        AppContext.application = this
        AppContext.initDeviceIds(this)
        // 初始化 Opus 解码器和本地命令路由
        NativeOpusDecoder.initAudioManager(this)
        // 通过 Hilt EntryPoint 获取 @Singleton 实例
        val entryPoint = EntryPointAccessors.fromApplication(this, ServicesEntryPoint::class.java)
        // MCPToolRegistry 通过 Hilt 构造注入自动创建（含所有 MCP 工具注册）
        val mcpToolRegistry = entryPoint.mcpToolRegistry()
        LocalCommandRouter.init(this, mcpToolRegistry)
        GlobalServices.xiaozhiBackend = entryPoint.xiaozhiBackend()
        // AudioService 由 Hilt @Singleton 管理
        entryPoint.audioService().init(this)
        // 初始化 WebView 语音播报（备用 TTS，不依赖系统引擎）
        WebViewSpeaker.init(this)

        // 启动前台保活 Service
        startForegroundService()

        // 注册网络变化监听
        ConnectivityReceiver.register(this)

        // 临时：从 sdcard 恢复联系人 DataStore
        restoreContactsFromBackup()
    }

    private fun startForegroundService() {
        val intent = Intent(this, XiaoZhiForegroundService::class.java)
        startForegroundService(intent)
    }

    /** 临时：从 app 外部 files 目录恢复联系人 DataStore */
    private fun restoreContactsFromBackup() {
        try {
            val bakFile = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "contacts.datastore")
            if (!bakFile.exists()) {
                android.util.Log.i("XiaoZhiApp", "备份文件不存在: ${bakFile.absolutePath}")
                return
            }
            val dsDir = java.io.File(filesDir, "datastore")
            dsDir.mkdirs()
            val dstFile = java.io.File(dsDir, "contacts.preferences_pb")
            if (dstFile.exists()) return  // 已有数据不覆盖
            bakFile.copyTo(dstFile, overwrite = false)
            android.util.Log.i("XiaoZhiApp", "联系人 DataStore 已从备份恢复: ${dstFile.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w("XiaoZhiApp", "恢复联系人备份失败: ${e.message}")
        }
    }
}
