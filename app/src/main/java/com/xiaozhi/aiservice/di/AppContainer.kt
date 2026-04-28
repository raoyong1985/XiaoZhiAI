package com.xiaozhi.aiservice.di

import android.content.Context
import com.google.gson.Gson
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl
import com.xiaozhi.aiservice.data.repository.ContactsRepositoryImpl
import com.xiaozhi.aiservice.data.repository.DeviceRepositoryImpl
import com.xiaozhi.aiservice.data.repository.MessageRepositoryImpl
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.DeviceRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import com.xiaozhi.aiservice.utils.DeviceIdHelper

object AppContainer {
    lateinit var context: Context
    val gson: Gson by lazy { Gson() }
    val audioService: AudioService by lazy { AudioService() }
    val xiaoZhiBackendService: XiaoZhiBackendService by lazy { XiaoZhiBackendService(gson) }

    val messageRepository: MessageRepository by lazy { MessageRepositoryImpl(context, gson) }
    val deviceRepository: DeviceRepository by lazy { DeviceRepositoryImpl() }
    val connectionRepository: ConnectionRepository by lazy {
        ConnectionRepositoryImpl(xiaoZhiBackendService, audioService, messageRepository)
    }
    val contactsRepository: ContactsRepository by lazy {
        ContactsRepositoryImpl(context, gson)
    }

    // 设备标识 - 延迟初始化
    private var _deviceId: String? = null
    private var _deviceMac: String? = null
    private var _clientId: String? = null

    fun initDeviceIds(context: Context) {
        if (_deviceId == null) {
            _deviceId = DeviceIdHelper.getDeviceId(context)
            _deviceMac = DeviceIdHelper.getDeviceMac(context)
            _clientId = DeviceIdHelper.getDeviceUuid(context)
        }
    }

    val deviceId: String
        get() = _deviceId ?: "unknown"

    val deviceMac: String
        get() = _deviceMac ?: "unknown"

    val clientId: String
        get() = _clientId ?: "unknown"
}
