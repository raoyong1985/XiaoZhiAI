package com.xiaozhi.aiservice

import android.app.Application
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.remote.LocalCommandRouter
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
        LocalCommandRouter.init(this)
        // 通过 Hilt EntryPoint 获取全局服务实例，设置给 GlobalServices
        val entryPoint = EntryPointAccessors.fromApplication(this, ServicesEntryPoint::class.java)
        GlobalServices.audioService = entryPoint.audioService()
        GlobalServices.connectionRepository = entryPoint.connectionRepository()
        GlobalServices.messageRepository = entryPoint.messageRepository()
        GlobalServices.xiaozhiBackend = entryPoint.xiaozhiBackend()
        // 初始化 AudioService（含 SileroVAD 后台线程）
        GlobalServices.audioService.init(this)
    }
}
