package com.xiaozhi.aiservice

import android.app.Application
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.di.AppContainer

class XiaoZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 先设置context，再初始化设备ID
        AppContainer.context = this
        AppContainer.initDeviceIds(this)
        // 初始化 AudioManager（OPPO/MTK 音频焦点需要）
        NativeOpusDecoder.initAudioManager(this)
    }
}
