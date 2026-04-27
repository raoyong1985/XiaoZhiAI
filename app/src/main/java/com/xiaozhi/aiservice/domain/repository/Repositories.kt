package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.Device
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
    fun getMessages(): StateFlow<List<ChatMessage>>
    suspend fun sendTextMessage(text: String): Result<Unit>
    suspend fun sendVoiceMessage(filePath: String, duration: Int): Result<Unit>
    suspend fun addReceivedMessage(message: ChatMessage)
    suspend fun addSentMessage(message: ChatMessage)
    suspend fun updateMessageStatus(messageId: String, status: com.xiaozhi.aiservice.domain.model.MessageStatus)
    suspend fun clearMessages()
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
    val incomingMessages: SharedFlow<IncomingMessage>
    suspend fun connect(config: ConnectionConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun sendTextMessage(text: String): Result<Unit>
    suspend fun sendAudio(audioData: ByteArray): Result<Unit>
    
    // 语音控制
    suspend fun startListening()
    suspend fun stopListening()
}
