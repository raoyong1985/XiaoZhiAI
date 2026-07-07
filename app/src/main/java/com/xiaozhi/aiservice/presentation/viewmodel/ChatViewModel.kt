package com.xiaozhi.aiservice.presentation.viewmodel

import com.xiaozhi.aiservice.utils.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.memory.RAGMemoryManager
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.VoiceMode
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "ChatViewModel"

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val messageRepository: MessageRepository
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
                    // 存储 AI 回复到本地 RAG 记忆
                    storeAssistantToRAG(messageId)
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

    /** 设置当前联系人的唤醒词 = 联系人名（直接喊名字唤醒） */
    fun setCurrentWakeWord(contactName: String) {
        val wakeWord = contactName
        Logger.i(TAG, "设置唤醒词: «${wakeWord}»")
        connectionRepository.setWakeWord(wakeWord)
        // 启动唤醒检测（单 AudioRecord，自动过渡）
        val started = connectionRepository.startWakeWordMode()
        Logger.i(TAG, "唤醒模式启动${if (started) "成功" else "失败"}，录音中=${connectionRepository.isRecording()}")
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
            // 存储用户消息到本地 RAG 记忆
            storeUserToRAG(text)
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

    // ── 本地 RAG 记忆 ────────────────────────────────────

    /** 存储用户消息到 RAG */
    private fun storeUserToRAG(text: String) {
        try {
            val rag = RAGMemoryManager.getExisting() ?: return
            if (currentContactId.isBlank()) return
            rag.addMemory(currentContactId, "user", text)
        } catch (e: Exception) {
            Logger.w(TAG, "存储用户记忆失败: ${e.message}")
        }
    }

    /** AI 回复结束时，存储到 RAG */
    private fun storeAssistantToRAG(messageId: String) {
        try {
            val rag = RAGMemoryManager.getExisting() ?: return
            if (currentContactId.isBlank()) return
            // 从消息列表中查找 AI 回复文本
            val messages = _uiState.value.messages
            val msg = messages.find { it.id == messageId && it.direction == com.xiaozhi.aiservice.domain.model.MessageDirection.RECEIVED }
            if (msg != null && msg.content.isNotBlank()) {
                rag.addMemory(currentContactId, "assistant", msg.content)
                Logger.i(TAG, "RAG: 已存储 AI 回复 (${msg.content.take(50)}...)")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "存储 AI 记忆失败: ${e.message}")
        }
    }

    /** 获取 RAG 上下文（用于在发送消息前附加到 prompt） */
    fun getRAGContext(query: String): String {
        try {
            val rag = RAGMemoryManager.getExisting() ?: return ""
            if (currentContactId.isBlank() || query.isBlank()) return ""
            // 搜索 + 近期记忆
            val searchResults = rag.search(currentContactId, query, 3)
            val recentContext = rag.getRecentContext(currentContactId, 5)
            val sb = StringBuilder()
            if (searchResults.isNotEmpty()) {
                sb.appendLine("相关记忆:")
                for (r in searchResults) {
                    val label = if (r.record.role == "user") "你" else "我"
                    sb.appendLine("- [$label] ${r.record.text}")
                }
            }
            if (recentContext.isNotBlank()) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.append(recentContext)
            }
            return sb.toString()
        } catch (e: Exception) {
            Logger.w(TAG, "获取 RAG 上下文失败: ${e.message}")
            return ""
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val aiState: AiState = AiState.IDLE,
    val connectionState: BotConnectionState = BotConnectionState.Disconnected,
    val emotion: String = "",   // AI情感：happy/sad/angry/worried/thoughtful/gentle/doubtful
    val voiceMode: VoiceMode = VoiceMode.REALTIME,  // 当前语音模式：auto / manual / realtime
    val alert: String? = null,  // 服务器Alert弹窗内容（非null时显示）
    val error: String? = null,
    val isAiResponding: Boolean = false  // 乒乓模式：AI正在回复中，等待回复完成
)
