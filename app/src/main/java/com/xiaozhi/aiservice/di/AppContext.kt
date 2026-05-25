package com.xiaozhi.aiservice.di

import android.app.Application
import android.content.Context
import com.xiaozhi.aiservice.utils.DeviceIdHelper

/**
 * App 上下文的全局持有者。
 * 在 XiaoZhiApplication.onCreate() 中初始化。
 * 仅给 MCP tools（静态注册函数）等无法用构造函数注入的组件使用。
 */
object AppContext {
    lateinit var application: Application
        internal set

    val context: Context
        get() = application

    // 设备标识 - volatile 保证可见性
    @Volatile private var _deviceId: String? = null
    @Volatile private var _deviceMac: String? = null
    @Volatile private var _clientId: String? = null

    fun initDeviceIds(ctx: Context) {
        if (_deviceId == null) {
            _deviceId = DeviceIdHelper.getDeviceId(ctx)
            _deviceMac = DeviceIdHelper.getDeviceMac(ctx)
            _clientId = DeviceIdHelper.getDeviceUuid(ctx)
        }
    }

    val deviceId: String
        get() = _deviceId ?: "unknown"

    val deviceMac: String
        get() = _deviceMac ?: "unknown"

    val clientId: String
        get() = _clientId ?: "unknown"
}
