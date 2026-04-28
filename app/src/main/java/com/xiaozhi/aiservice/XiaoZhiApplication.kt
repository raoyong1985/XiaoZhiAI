package com.xiaozhi.aiservice

import android.app.Application
import com.xiaozhi.aiservice.di.AppContainer

class XiaoZhiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 先设置context，再初始化设备ID
        AppContainer.context = this
        AppContainer.initDeviceIds(this)
    }
}
