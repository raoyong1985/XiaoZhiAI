package com.xiaozhi.aiservice.di

import android.content.Context
import com.google.gson.Gson
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl
import com.xiaozhi.aiservice.data.repository.ContactsRepositoryImpl
import com.xiaozhi.aiservice.data.repository.DeviceRepositoryImpl
import com.xiaozhi.aiservice.data.repository.MessageRepositoryImpl
import com.xiaozhi.aiservice.data.repository.GroupChatRepositoryImpl
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.DeviceRepository
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.xiaozhi.aiservice.utils.DeviceIdHelper
import java.util.concurrent.ConcurrentHashMap

object AppContainer {
    lateinit var context: Context
    val gson: Gson by lazy { Gson() }
    val audioService: AudioService by lazy { AudioService() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 支持多个WebSocket连接（群聊功能）
    private val backendServices = ConcurrentHashMap<String, XiaoZhiBackendService>()
    fun getBackendService(contactId: String): XiaoZhiBackendService {
        return backendServices.getOrPut(contactId) { XiaoZhiBackendService(gson) }
    }
    fun removeBackendService(contactId: String) {
        backendServices.remove(contactId)?.disconnect()
    }

    val messageRepository: MessageRepository by lazy { MessageRepositoryImpl(context, gson) }
    val deviceRepository: DeviceRepository by lazy { DeviceRepositoryImpl() }
    
    // 群聊连接Repository（每个群聊一个）
    private val groupConnections = ConcurrentHashMap<String, ConnectionRepository>()
    fun getGroupConnectionRepository(groupId: String): ConnectionRepository {
        return groupConnections.getOrCreate(groupId) {
            val service = XiaoZhiBackendService(gson)
            ConnectionRepositoryImpl(service, audioService, messageRepository, context)
        }
    }
    fun removeGroupConnection(groupId: String) {
        groupConnections.remove(groupId)?.let { repo ->
            scope.launch {
                repo.disconnect()
            }
        }
    }
    fun clearAllConnections() {
        backendServices.values.forEach { it.disconnect() }
        backendServices.clear()
        groupConnections.values.forEach { repo ->
            scope.launch { repo.disconnect() }
        }
        groupConnections.clear()
    }
    
    val connectionRepository: ConnectionRepository by lazy {
        ConnectionRepositoryImpl(getBackendService("default"), audioService, messageRepository, context)
    }

    /** 默认后端服务实例 */
    val xiaozhiBackend: XiaoZhiBackendService
        get() = getBackendService("default")
    
    val contactsRepository: ContactsRepository by lazy {
        ContactsRepositoryImpl(context, gson)
    }
    
    val groupChatRepository: GroupChatRepository by lazy {
        GroupChatRepositoryImpl(context, gson)
    }

    // 设备标识 - volatile 保证可见性
    @Volatile private var _deviceId: String? = null
    @Volatile private var _deviceMac: String? = null
    @Volatile private var _clientId: String? = null

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

// 扩展函数：获取或创建
fun <K, V> MutableMap<K, V>.getOrCreate(key: K, create: () -> V): V {
    return this.getOrPut(key) { create() }
}
