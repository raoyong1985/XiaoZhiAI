package com.xiaozhi.aiservice.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.VoiceMode
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

class ChatViewModel(
    private val connectionRepository: ConnectionRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState

    private var currentContactId: String = ""
    private var pendingConfig: ConnectionConfig? = null

    init {
        observeConnection()
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionRepository.aiState.collect { state ->
                _uiState.update {
                    it.copy(
                        aiState = state,
                        // 当 AI 不再监听时，自动切回麦克风图标
                        isRecording = if (state != AiState.LISTENING) false else it.isRecording
                    )
                }
            }
        }

        viewModelScope.launch {
            connectionRepository.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        viewModelScope.launch {
            connectionRepository.emotion.collect { emotion ->
                _uiState.update { it.copy(emotion = emotion) }
            }
        }

        // 观察服务器Alert弹窗
        viewModelScope.launch {
            connectionRepository.alert.collect { alertText ->
                _uiState.update { it.copy(alert = alertText) }
            }
        }

        // 观察流式消息结束信号，停止对应消息的动画
        viewModelScope.launch {
            connectionRepository.streamingEnd.collect { messageId ->
                if (currentContactId.isNotEmpty()) {
                    messageRepository.finalizeStreamingMessage(currentContactId, messageId)
                }
            }
        }
    }

    private fun observeMessages(contactId: String) {
        viewModelScope.launch {
            messageRepository.getMessages(contactId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun connect(serverUrl: String, otaUrl: String, deviceId: String, clientId: String, contactId: String, voiceMode: VoiceMode) {
        Log.i(TAG, "connect() called: serverUrl=$serverUrl, contactId=$contactId, deviceId=$deviceId")
        currentContactId = contactId
        _uiState.update { it.copy(voiceMode = voiceMode) }
        observeMessages(contactId)
        viewModelScope.launch {
            val config = ConnectionConfig(
                serverUrl = serverUrl,
                otaUrl = otaUrl,
                deviceId = deviceId,
                clientId = clientId
            )
            pendingConfig = config
            Log.i(TAG, "connect: about to call connectionRepository.connect()")
            connectionRepository.connect(config, contactId)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
        }
    }

    fun tryReconnect() {
        val config = pendingConfig ?: return
        val contactId = currentContactId
        if (contactId.isBlank()) return
        Log.i(TAG, "tryReconnect: attempting reconnection...")
        viewModelScope.launch {
            connectionRepository.connect(config, contactId)
        }
    }

    fun startListening() {
        viewModelScope.launch {
            connectionRepository.startListening(_uiState.value.voiceMode.serverValue)
            _uiState.update { it.copy(isRecording = true) }
        }
    }

    fun setVoiceMode(mode: VoiceMode) {
        _uiState.update { it.copy(voiceMode = mode) }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(alert = null) }
    }

    fun stopListening() {
        viewModelScope.launch {
            connectionRepository.stopListening()
            _uiState.update { it.copy(isRecording = false) }
        }
    }

    fun sendTextMessage(text: String) {
        viewModelScope.launch {
            Log.i(TAG, "sendTextMessage: 发送文本: $text")
            connectionRepository.sendTextMessage(currentContactId, text)
        }
    }

    fun clearMessages() {
        viewModelScope.launch {
            messageRepository.clearMessages(currentContactId)
        }
    }

    class Factory(private val gson: Gson) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                AppContainer.connectionRepository,
                AppContainer.messageRepository
            ) as T
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val aiState: AiState = AiState.IDLE,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val emotion: String = "",   // AI情感：happy/sad/angry/worried/thoughtful/gentle/doubtful
    val voiceMode: VoiceMode = VoiceMode.REALTIME,  // 当前语音模式：auto / manual / realtime
    val alert: String? = null,  // 服务器Alert弹窗内容（非null时显示）
    val error: String? = null
)
