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

    fun connect(url: String, otaUrl: String, deviceId: String, clientId: String, accessToken: String = "")
    fun disconnect()
    fun destroy()
    fun sendListenStart(mode: String)
    fun sendListenDetect(text: String = "")
    fun sendListenStop()
    fun sendListenStopOnly()
    fun sendAbort(reason: String = "")
    fun sendAudio(audioData: ByteArray)
    fun sendTextContent(text: String)
    fun isLocalServer(): Boolean
    fun isOfficialServer(): Boolean
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
