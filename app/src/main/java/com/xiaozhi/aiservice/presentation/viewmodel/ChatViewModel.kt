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
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
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
    private var currentServerUrl: String = ""
    private var currentAuthToken: String = ""

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
    }

    private fun observeMessages(contactId: String) {
        viewModelScope.launch {
            messageRepository.getMessages(contactId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun connect(serverUrl: String, otaUrl: String, deviceId: String, clientId: String, contactId: String) {
        Log.i(TAG, "connect() called: serverUrl=$serverUrl, contactId=$contactId, deviceId=$deviceId")
        currentContactId = contactId
        currentServerUrl = serverUrl
        observeMessages(contactId)
        viewModelScope.launch {
            val config = ConnectionConfig(
                serverUrl = serverUrl,
                otaUrl = otaUrl,
                deviceId = deviceId,
                clientId = clientId
            )
            Log.i(TAG, "connect: about to call connectionRepository.connect()")
            connectionRepository.connect(config, contactId)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
        }
    }

    fun startListening() {
        viewModelScope.launch {
            connectionRepository.startListening()
            _uiState.update { it.copy(isRecording = true) }
        }
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
            // 检查是否包含拍照关键词
            if (isCaptureTrigger(text)) {
                handleCaptureText(text)
            } else {
                connectionRepository.sendTextMessage(currentContactId, text)
            }
        }
    }

    /**
     * 检查文本是否是拍照触发词
     */
    private fun isCaptureTrigger(text: String): Boolean {
        val lowerText = text.lowercase()
        return lowerText.contains("拍照") ||
               lowerText.contains("看看这是什么") ||
               lowerText.contains("这是什么") ||
               lowerText.contains("拍一下") ||
               lowerText.contains("识别图片") ||
               lowerText.contains("图片识别")
    }

    /**
     * 处理拍照文本 - 拍照并上传识别
     */
    private suspend fun handleCaptureText(text: String) {
        Log.i(TAG, "检测到拍照指令: $text")
        
        // 显示用户消息
        val userMessage = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            content = text,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT,
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.addSentMessage(currentContactId, userMessage)

        // 设置AI状态为处理中
        _uiState.update { it.copy(aiState = AiState.PROCESSING, isCapturing = true) }

        try {
            val cameraService = AppContainer.getCameraService()

            // 初始化相机
            val initialized = cameraService.initialize()
            if (!initialized) {
                throw Exception("相机初始化失败，设备可能没有相机")
            }

            // 拍照并上传
            val captureResult = cameraService.captureAndExplain(
                question = text,
                serverUrl = currentServerUrl,
                deviceId = AppContainer.deviceId,
                clientId = AppContainer.clientId,
                authToken = currentAuthToken
            )

            captureResult.fold(
                onSuccess = { response: String ->
                    Log.i(TAG, "图片识别成功: ${response.take(200)}")
                    // 将服务器响应作为AI消息显示
                    val aiMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = response,
                        type = MessageType.TEXT,
                        direction = MessageDirection.RECEIVED,
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                    messageRepository.addReceivedMessage(currentContactId, aiMessage)
                },
                onFailure = { error: Throwable ->
                    Log.e(TAG, "图片识别失败: ${error.message}")
                    val errorMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = "❌ 拍照识别失败: ${error.message}",
                        type = MessageType.TEXT,
                        direction = MessageDirection.RECEIVED,
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                    messageRepository.addReceivedMessage(currentContactId, errorMessage)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "拍照异常: ${e.message}")
            val errorMessage = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                content = "❌ 拍照异常: ${e.message}",
                type = MessageType.TEXT,
                direction = MessageDirection.RECEIVED,
                status = MessageStatus.SENT,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.addReceivedMessage(currentContactId, errorMessage)
        } finally {
            _uiState.update { it.copy(aiState = AiState.IDLE, isCapturing = false) }
        }
    }

    /**
     * 手动触发拍照 (点击相机按钮)
     */
    fun triggerCapture() {
        viewModelScope.launch {
            handleCaptureText("拍照")
        }
    }

    /**
     * 设置认证Token (用于图片上传)
     */
    fun setAuthToken(token: String) {
        currentAuthToken = token
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
    val isCapturing: Boolean = false,
    val aiState: AiState = AiState.IDLE,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val error: String? = null
)
