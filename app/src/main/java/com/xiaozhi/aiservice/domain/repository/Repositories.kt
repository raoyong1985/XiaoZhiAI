package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备状态（参考 ESP32 device_state_machine.cc）
 * 状态转换: DISCONNECTED → CONNECTING → CONNECTED → (IDLE/LISTENING/SPEAKING/PROCESSING)
 */
enum class DeviceState(val displayName: String) {
    UNKNOWN("未知"),
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    ACTIVATING("激活中"),      // 设备激活流程（ESP32 kDeviceStateActivating）
    UPGRADING("升级中"),       // 固件升级中（ESP32 kDeviceStateUpgrading）
    IDLE("空闲"),
    LISTENING("倾听中"),       // ESP32 kDeviceStateListening
    SPEAKING("说话中"),        // ESP32 kDeviceStateSpeaking
    PROCESSING("处理中");      // ESP32 kDeviceStateProcessing
}

/**
 * 消息仓库接口
 */
interface MessageRepository {
    fun getMessages(contactId: String): StateFlow<List<ChatMessage>>
    fun getLastMessage(contactId: String): StateFlow<ChatMessage?>
    suspend fun sendTextMessage(contactId: String, text: String): Result<Unit>
    suspend fun sendVoiceMessage(contactId: String, filePath: String, duration: Int): Result<Unit>
    suspend fun addReceivedMessage(contactId: String, message: ChatMessage)
    suspend fun addSentMessage(contactId: String, message: ChatMessage)
    suspend fun updateMessageStatus(contactId: String, messageId: String, status: com.xiaozhi.aiservice.domain.model.MessageStatus)
    suspend fun finalizeStreamingMessage(contactId: String, messageId: String)  // sentence_end时停止流式动画
    suspend fun clearMessages(contactId: String)
}

/**
 * 设备仓库接口
 */
interface DeviceRepository {
    fun getDevices(): StateFlow<List<Device>>
    fun getConnectedDevice(): StateFlow<Device?>
    suspend fun scanDevices()
    suspend fun connectDevice(device: Device)
    suspend fun disconnectDevice()
}

/**
 * 连接仓库接口 - 对接小智后台
 */
interface ConnectionRepository {
    val connectionState: StateFlow<BotConnectionState>
    val aiState: StateFlow<AiState>
    val deviceState: StateFlow<DeviceState>  // 设备状态机（参考 ESP32 device_state_machine.cc）
    val emotion: StateFlow<String>  // AI情感状态
    val alert: SharedFlow<String>    // 服务器Alert弹窗
    val streamingEnd: SharedFlow<String>  // 流式消息结束信号（消息ID）
    val currentContactId: String  // 当前联系人 ID（用于 MCP 工具路由消息）

    /** 是否抑制自动开始聆听（群聊中由接龙引擎控制） */
    var suppressAutoListening: Boolean

    suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit>
    suspend fun disconnect()
    suspend fun sendTextMessage(contactId: String, text: String): Result<Unit>
    suspend fun sendAudio(audioData: ByteArray): Result<Unit>

    // 语音控制
    suspend fun startListening(mode: String = "realtime")  // auto / manual / realtime
    suspend fun stopListening(userExplicitStop: Boolean = true)

    // 唤醒词设置
    fun setWakeWord(wakeWord: String)
    fun startWakeWordMode(): Boolean
    /** 发送唤醒检测通知到服务器（ESP32 协议 detect），服务端通过 ASR 匹配 wakeup_words */
    fun sendListenDetect(text: String = "")
    /** 重新从持久化存储加载唤醒模板到内存匹配器 */
    fun reloadWakeTemplates()
    /** 检查当前是否有任何录音运行（唤醒模式或实时模式） */
    fun isRecording(): Boolean
}

/**
 * 联系人仓库接口
 */
interface ContactsRepository {
    fun getContacts(): Flow<List<BotContact>>
    suspend fun saveContact(contact: BotContact)
    suspend fun deleteContact(contactId: String)

    /** 从 JSON 恢复全部联系人 */
    suspend fun restoreContactsFromJson(json: String)

    /** 获取所有联系人唤醒模板：contactId → base64 编码的 40维 MFCC 平均特征 */
    fun getWakeTemplates(): Flow<Map<String, String>>

    /** 保存联系人唤醒模板 */
    suspend fun saveWakeTemplate(contactId: String, templateBase64: String)

    /** 从 PCM 音频中提取 MFCC 唤醒模板并保存（隐藏 Data 层实现细节） */
    suspend fun saveWakeTemplateFromPcm(contactId: String, pcmData: ByteArray)

    /** 删除联系人唤醒模板 */
    suspend fun deleteWakeTemplate(contactId: String)
}
