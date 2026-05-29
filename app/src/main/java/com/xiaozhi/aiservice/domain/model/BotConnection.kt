package com.xiaozhi.aiservice.domain.model

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BotConnection {
    val connectionState: StateFlow<BotConnectionState>
    val aiState: StateFlow<String>
    val audioData: SharedFlow<ByteArray>
    val chatMessages: SharedFlow<ChatMessage>
    val ttsText: StateFlow<String>
    val textMessage: StateFlow<String>
    val emotion: StateFlow<String>
    val alert: SharedFlow<String>
    val streamingEnd: SharedFlow<String>
    val speakRequest: SharedFlow<String>
    /** 仅在服务器发送 tts stop 时 emit，用于群聊 relay 精确判断 TTS 结束 */
    val ttsStopped: SharedFlow<Unit>

    fun connect(url: String, otaUrl: String, deviceId: String, clientId: String, accessToken: String = "")
    fun disconnect()
    fun destroy()
    fun sendListenStart(mode: String)
    fun sendListenStop()
    fun sendListenStopOnly()
    fun sendAbort(reason: String = "")
    fun sendAudio(audioData: ByteArray)
    fun sendTextContent(text: String)
    fun getServerSampleRate(): Int
    fun getServerChannels(): Int
    fun sendIotDescriptors()
}

sealed class BotConnectionState {
    data object Disconnected : BotConnectionState()
    data object Connecting : BotConnectionState()
    data object Connected : BotConnectionState()
    data class Error(val message: String = "") : BotConnectionState()
}
