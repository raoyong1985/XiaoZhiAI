package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接状态
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 收到的消息类型
 */
sealed class IncomingMessage {
    data class SttResult(val text: String) : IncomingMessage()
    data class TtsState(val state: String) : IncomingMessage()
    data class LlmEmotion(val emotion: String) : IncomingMessage()
    data class AudioData(val data: ByteArray) : IncomingMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioData
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }
    data class TextMessage(val content: String) : IncomingMessage()
}

/**
 * 消息仓库接口
 */
interface MessageRepository {
    fun getMessages(contactId: String): StateFlow<List<ChatMessage>>
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
    val connectionState: StateFlow<ConnectionState>
    val aiState: StateFlow<AiState>
    val emotion: StateFlow<String>  // AI情感状态
    val alert: SharedFlow<String>    // 服务器Alert弹窗
    val streamingEnd: SharedFlow<String>  // 流式消息结束信号（消息ID）
    val incomingMessages: SharedFlow<IncomingMessage>
    suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit>
    suspend fun disconnect()
    suspend fun sendTextMessage(contactId: String, text: String): Result<Unit>
    suspend fun sendAudio(audioData: ByteArray): Result<Unit>

    // 语音控制
    suspend fun startListening(mode: String = "realtime")  // auto / manual / realtime
    suspend fun stopListening(userExplicitStop: Boolean = true)
}

/**
 * 联系人仓库接口
 */
interface ContactsRepository {
    fun getContacts(): Flow<List<BotContact>>
    suspend fun saveContact(contact: BotContact)
    suspend fun deleteContact(contactId: String)
}
