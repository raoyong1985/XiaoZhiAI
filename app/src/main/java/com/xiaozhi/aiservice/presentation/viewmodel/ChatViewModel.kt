package com.xiaozhi.aiservice.presentation.viewmodel

import com.xiaozhi.aiservice.utils.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.VoiceMode
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import android.content.Context
import android.content.Intent

private const val TAG = "ChatViewModel"

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val messageRepository: MessageRepository,
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<BotConnectionState> = connectionRepository.connectionState

    private var currentContactId: String = ""
    private var pendingConfig: ConnectionConfig? = null
    private var messageCollectJob: kotlinx.coroutines.Job? = null

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
                        isRecording = if (state != AiState.LISTENING) false else it.isRecording,
                        // 乒乓模式：AI 回到空闲状态时解锁输入
                        isAiResponding = if (state == AiState.IDLE) false else it.isAiResponding
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
        messageCollectJob?.cancel()
        messageCollectJob = viewModelScope.launch {
            messageRepository.getMessages(contactId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun connect(serverUrl: String, otaUrl: String, deviceId: String, clientId: String, contactId: String, voiceMode: VoiceMode) {
        Logger.i(TAG, "connect() called: serverUrl=$serverUrl, contactId=$contactId, deviceId=$deviceId")
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
            Logger.i(TAG, "connect: about to call connectionRepository.connect()")
            connectionRepository.connect(config, contactId)
        }
    }

    /** 设置当前唤醒词（不自动启动，由状态机在需要时调用 startWakeWordMode） */
    fun setCurrentWakeWord(contactName: String) {
        Logger.i(TAG, "设置唤醒词: <<${contactName}>>（ESP32 方案：服务端 ASR）")
        // 只记录唤醒词名称，不启动唤醒模式
        // 唤醒模式由状态机在 stopListening/disconnect 后自动启动
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
        Logger.i(TAG, "tryReconnect: attempting reconnection...")
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

    /**
     * 断线时先强制重连，连接成功后再开始录音
     */
    fun startListeningWithReconnect() {
        viewModelScope.launch {
            val state = connectionRepository.connectionState.value
            if (state !is BotConnectionState.Connected) {
                val config = pendingConfig ?: return@launch
                Logger.i(TAG, "startListeningWithReconnect: 断线中，先重连...")
                connectionRepository.connect(config, currentContactId)
                // 等待连接成功（超时 10 秒）
                withTimeoutOrNull(10000L) {
                    connectionRepository.connectionState
                        .first { it is BotConnectionState.Connected || it is BotConnectionState.Error }
                }
                if (connectionRepository.connectionState.value !is BotConnectionState.Connected) {
                    Logger.e(TAG, "startListeningWithReconnect: 重连失败")
                    _uiState.update { it.copy(error = "重连失败，请检查网络") }
                    return@launch
                }
                Logger.i(TAG, "startListeningWithReconnect: 重连成功，开始录音")
            }
            startListening()
        }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(alert = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun stopListening() {
        viewModelScope.launch {
            connectionRepository.stopListening()
            _uiState.update { it.copy(isRecording = false) }
        }
    }

    fun sendTextMessage(text: String) {
        viewModelScope.launch {
            Logger.i(TAG, "sendTextMessage: 发送文本: $text")
            _uiState.update { it.copy(isAiResponding = true) }  // 乒乓模式：锁定输入
            val result = connectionRepository.sendTextMessage(currentContactId, text)
            result.onFailure { e ->
                Logger.e(TAG, "发送失败: ${e.message}")
                _uiState.update { it.copy(error = "发送失败: ${e.message}") }
            }
        }
    }

    /**
     * 断线时先强制重连，连接成功后再发送文本
     */
    fun sendTextWithReconnect(text: String) {
        viewModelScope.launch {
            val state = connectionRepository.connectionState.value
            if (state !is BotConnectionState.Connected) {
                val config = pendingConfig ?: return@launch
                Logger.i(TAG, "sendTextWithReconnect: 断线中，先重连...")
                connectionRepository.connect(config, currentContactId)
                // 等待连接成功（超时 10 秒）
                withTimeoutOrNull(10000L) {
                    connectionRepository.connectionState
                        .first { it is BotConnectionState.Connected || it is BotConnectionState.Error }
                }
                if (connectionRepository.connectionState.value !is BotConnectionState.Connected) {
                    Logger.e(TAG, "sendTextWithReconnect: 重连失败")
                    _uiState.update { it.copy(error = "重连失败，消息未发送") }
                    return@launch
                }
                Logger.i(TAG, "sendTextWithReconnect: 重连成功，发送消息")
            }
            sendTextMessage(text)
        }
    }

    fun clearMessages() {
        viewModelScope.launch {
            messageRepository.clearMessages(currentContactId)
        }
    }

    fun exportMessages(context: Context) {
        viewModelScope.launch {
            val messages = messageRepository.getMessages(currentContactId).value
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                val sender = if (msg.direction == MessageDirection.SENT) "我" else "AI"
                "[${sender}]: ${msg.content}"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "聊天记录")
            }
            context.startActivity(Intent.createChooser(intent, "导出聊天记录"))
        }
    }

    /** 检查当前联系人是否需要录制唤醒词模板 */
    fun checkWakeTemplate(wakeWord: String) {
        if (currentContactId.isBlank() || wakeWord.isBlank()) return
        viewModelScope.launch {
            val templates = contactsRepository.getWakeTemplates().first()
            val hasTemplate = templates.containsKey(currentContactId)
            _uiState.update { it.copy(needsWakeRecording = !hasTemplate && wakeWord.isNotBlank()) }
        }
    }

    /** 关闭唤醒词提示 */
    fun dismissWakeRecording() {
        _uiState.update { it.copy(needsWakeRecording = false) }
    }

}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val aiState: AiState = AiState.IDLE,
    val connectionState: BotConnectionState = BotConnectionState.Disconnected,
    val emotion: String = "",
    val voiceMode: VoiceMode = VoiceMode.REALTIME,
    val alert: String? = null,
    val error: String? = null,
    val isAiResponding: Boolean = false,
    val needsWakeRecording: Boolean = false
)
