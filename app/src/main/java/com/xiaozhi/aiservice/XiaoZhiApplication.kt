package com.xiaozhi.aiservice

import android.app.Application
import com.xiaozhi.aiservice.di.AppContainer

class XiaoZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化设备ID
        AppContainer.initDeviceIds(this)
        AppContainer.context = this
    }
}
