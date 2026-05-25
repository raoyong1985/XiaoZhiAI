package com.xiaozhi.aiservice

import android.app.Application
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.remote.LocalCommandRouter
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.di.GlobalServices
import com.xiaozhi.aiservice.di.ServicesEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors

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
    }
}
