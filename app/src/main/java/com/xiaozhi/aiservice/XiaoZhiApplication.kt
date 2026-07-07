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
import com.xiaozhi.aiservice.utils.WebViewSpeaker
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors

@HiltAndroidApp
class XiaoZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.application = this
        AppContext.initDeviceIds(this)
        NativeOpusDecoder.initAudioManager(this)
        val entryPoint = EntryPointAccessors.fromApplication(this, ServicesEntryPoint::class.java)
        val mcpToolRegistry = entryPoint.mcpToolRegistry()
        LocalCommandRouter.init(this, mcpToolRegistry)
        GlobalServices.xiaozhiBackend = entryPoint.xiaozhiBackend()
        entryPoint.audioService().init(this)
        WebViewSpeaker.init(this)
        startForegroundService()
        ConnectivityReceiver.register(this)
    }

    private fun startForegroundService() {
        val intent = Intent(this, XiaoZhiForegroundService::class.java)
        startForegroundService(intent)
    }
}
