package com.xiaozhi.aiservice.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import okhttp3.*

import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 小智AI WebSocket客户端
 * 协议文档: https://my.feishu.cn/wiki/M0XiwldO9iJwHikpXD5cEx71nKh
 *
 * 连接流程:
 * 1. 建立WebSocket连接 (携带headers)
 * 2. 交换hello消息
 * 3. 开始语音交互
 */
class XiaoZhiBackendService(private val gson: Gson) {

    companion object {
        private const val PROTOCOL_VERSION = "1"
        private const val TAG = "XiaoZhiWS"
        private const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // 连接状态
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data object Error : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // AI状态: idle, listening, speaking
    private val _aiState = MutableStateFlow("idle")
    val aiState: StateFlow<String> = _aiState.asStateFlow()

    // 收到服务器转写的文本
    private val _textMessage = MutableStateFlow("")
    val textMessage: StateFlow<String> = _textMessage.asStateFlow()

    // 完整的聊天消息流（区分用户/AI消息）
    private val _chatMessages = MutableSharedFlow<ChatMessage>(replay = 0)
    val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    // 收到TTS音频数据 (Opus) - 使用SharedFlow支持多包
    private val _audioData = MutableSharedFlow<ByteArray>(replay = 0)
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    // 服务器返回的TTS文本
    private val _ttsText = MutableStateFlow("")
    val ttsText: StateFlow<String> = _ttsText.asStateFlow()

    // 情感状态
    private val _emotion = MutableStateFlow("")
    val emotion: StateFlow<String> = _emotion.asStateFlow()

    private var isConnected = false
    private var serverUrl = ""
    private var deviceId = ""
    private var clientId = ""
    private var sessionId = ""
    private var serverSampleRate = 16000  // 服务器返回的采样率
    private var serverChannels = 1            // 服务器返回的声道数
    private var accessToken = ""           // 保存accessToken用于重连
    private var reconnectAttempt = 0       // 重连次数
    private val maxReconnectAttempts = 10   // 最大重连次数
    private val baseReconnectDelay = 2000L  // 基础重连延迟(ms)
    private var isReconnecting = false     // 是否正在重连

    /**
     * 连接服务器
     * @param url WebSocket URL (如 wss://xiaozhi.me/ws)
     * @param deviceId 设备ID (用于Device-Id header，格式XX:XX:XX:XX:XX:XX)
     * @param clientId 客户端ID (用于Client-Id header，UUID格式)
     * @param accessToken 访问令牌 (用于Authorization header)
     */
    fun connect(url: String, deviceId: String, clientId: String, accessToken: String = "") {
        serverUrl = url
        this.deviceId = deviceId
        this.clientId = clientId
        this.accessToken = accessToken
        this.reconnectAttempt = 0
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "连接中: $url")

        // 先进行OTA注册（设备激活）
        scope.launch {
            try {
                registerDevice(deviceId, clientId)
            } catch (e: Exception) {
                Log.e(TAG, "OTA注册失败: ${e.message}")
            }
            // 注册完成后建立WebSocket连接
            doConnect(url, deviceId, clientId, accessToken)
        }
    }

    /**
     * OTA注册设备
     * 必须在连接WebSocket之前调用，用于向服务器注册设备
     * 参考: https://xujiwei.com/blog/2025/04/xiaozhi-ai-protocol-ota/
     */
    private suspend fun registerDevice(deviceId: String, clientId: String) {
        val systemInfo = JsonObject().apply {
            addProperty("version", 2)
            addProperty("language", "zh")
            addProperty("mac_address", deviceId)
            addProperty("uuid", clientId)
            addProperty("chip_model_name", "android")
            add("application", JsonObject().apply {
                addProperty("name", "xiaozhi-android")
                addProperty("version", "1.0.0")
            })
        }

        val body = systemInfo.toString()
        Log.i(TAG, "OTA注册请求: $body")

        val requestBody = body.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(OTA_URL)
            .post(requestBody)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .build()

        val client = OkHttpClient()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "empty"
        Log.i(TAG, "OTA注册响应: ${response.code} - $responseBody")

        if (response.isSuccessful) {
            // 解析响应，检查是否有activation字段（设备未激活）
            try {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                if (json.has("activation")) {
                    val activation = json.getAsJsonObject("activation")
                    val code = activation?.get("code")?.asString ?: ""
                    Log.i(TAG, "设备需要激活，验证码: $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析OTA响应失败: ${e.message}")
            }
        }
    }

    private fun doConnect(url: String, deviceId: String, clientId: String, accessToken: String) {
        Log.i(TAG, "发送WebSocket请求...")
        Log.i(TAG, "Device-Id: $deviceId, Client-Id: $clientId")

        val headers = Headers.Builder().apply {
            add("Protocol-Version", PROTOCOL_VERSION)
            add("Device-Id", deviceId)
            add("Client-Id", clientId)
            if (accessToken.isNotEmpty()) {
                add("Authorization", "Bearer $accessToken")
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()

        Log.i(TAG, "发送WebSocket请求...")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "WebSocket已连接! 发送hello...")
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "收到文本: ${text.take(200)}")
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.i(TAG, "收到二进制: ${bytes.size} bytes")
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket关闭: $code $reason")
                isConnected = false
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket失败: ${t.message}")
                isConnected = false
                _connectionState.value = ConnectionState.Error
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        reconnectAttempt = 0  // 重置重连计数
        _connectionState.value = ConnectionState.Disconnected
        _aiState.value = "idle"
    }

    /**
     * 计划重连
     */
    private fun scheduleReconnect() {
        if (isReconnecting) return
        if (serverUrl.isEmpty()) return

        isReconnecting = true
        reconnectAttempt++

        if (reconnectAttempt > maxReconnectAttempts) {
            Log.w(TAG, "已达到最大重连次数 ($maxReconnectAttempts)，停止重连")
            isReconnecting = false
            _connectionState.value = ConnectionState.Error
            return
        }

        // 指数退避: 2s, 4s, 8s, 16s, 32s (最多)
        val delay = (baseReconnectDelay * (1 shl (reconnectAttempt - 1).coerceAtMost(4))).coerceAtMost(30000L)
        Log.i(TAG, "计划 ${reconnectAttempt}s 后重连 (延迟: ${delay}ms)")

        scope.launch {
            delay(delay)
            isReconnecting = false
            Log.i(TAG, "执行重连... (第 $reconnectAttempt 次)")
            _connectionState.value = ConnectionState.Connecting
            doConnect(serverUrl, deviceId, clientId, accessToken)
        }
    }

    /**
     * 发送hello消息 - 握手
     */
    private fun sendHello() {
        val hello = JsonObject().apply {
            addProperty("type", "hello")
            addProperty("version", 1)
            addProperty("transport", "websocket")
            add("features", JsonObject().apply {
                addProperty("mcp", true)
            })
            add("audio_params", JsonObject().apply {
                addProperty("format", "opus")
                addProperty("sample_rate", 16000)
                addProperty("channels", 1)
                addProperty("frame_duration", 60)
            })
        }
        sendTextMessage(hello.toString())
    }

    /**
     * 开始监听
     * @param mode "auto", "manual", 或 "realtime"
     */
    fun sendListenStart(mode: String = "auto") {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", mode)
        }
        sendTextMessage(message.toString())
        _aiState.value = "listening"
    }

    /**
     * 停止监听
     */
    fun sendListenStop() {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        sendTextMessage(message.toString())
        _aiState.value = "idle"
    }

    /**
     * 唤醒词检测
     * @param wakeWord 唤醒词文本
     */
    fun sendWakeWordDetected(wakeWord: String) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", wakeWord)
        }
        sendTextMessage(message.toString())
    }

    /**
     * 终止当前会话
     */
    fun sendAbort(reason: String = "") {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "abort")
            if (reason.isNotEmpty()) {
                addProperty("reason", reason)
            }
        }
        sendTextMessage(message.toString())
        _aiState.value = "idle"
    }

    /**
     * 发送音频数据
     */
    fun sendAudioData(data: ByteArray) {
        if (!isConnected) return
        webSocket?.send(ByteString.of(*data))
    }

    /**
     * 获取服务器采样率
     */
    fun getServerSampleRate(): Int = serverSampleRate
    fun getServerChannels(): Int = serverChannels

    /**
     * 发送Opus编码的音频数据
     */
    fun sendAudio(audioData: ByteArray) {
        if (!isConnected || audioData.isEmpty()) return
        webSocket?.send(audioData.toByteString())
    }

    /**
     * 发送文本消息 (用于MCP等)
     */
    fun sendMcpMessage(payload: JsonObject) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "mcp")
            add("payload", payload)
        }
        sendTextMessage(message.toString())
    }

    /**
     * 发送文本内容（用于聊天消息）
     * 格式参考官方Android app: D0.java 的 d() 方法
     */
    fun sendTextContent(text: String) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
        }
        sendTextMessage(message.toString())
    }

    private fun sendTextMessage(text: String) {
        if (!isConnected) return
        Log.i(TAG, "发送: ${text.take(200)}")
        webSocket?.send(text)
    }

    /**
     * 处理服务器文本消息
     *
     * 消息类型:
     * - hello: 连接成功响应
     * - activation: 设备激活码
     * - stt: 语音识别结果
     * - tts: TTS状态 (start/stop/sentence_start)
     * - llm: 情感状态
     * - mcp: MCP协议消息
     * - abort: 中止消息
     */
    private fun handleTextMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java) ?: return
            val type = json.get("type")?.asString ?: return
            Log.i(TAG, "处理消息类型: $type")

            when (type) {
                "hello" -> {
                    Log.i(TAG, "收到hello响应! 设备已连接")
                    // 解析session_id
                    sessionId = json.get("session_id")?.asString ?: ""
                    // 解析服务器返回的采样率
                    val audioParams = json.getAsJsonObject("audio_params")
                    if (audioParams != null) {
                        serverSampleRate = audioParams.get("sample_rate")?.asInt ?: 16000
                        serverChannels = audioParams.get("channels")?.asInt ?: 1
                        Log.i(TAG, "服务器音频参数: ${serverSampleRate}Hz, ${serverChannels}ch")
                    }
                    Log.i(TAG, "Session ID: $sessionId")
                }
                "activation" -> {
                    val code = json.get("code")?.asString
                    Log.i(TAG, "收到激活码: $code")
                    // 通知UI显示激活码
                    _textMessage.value = "激活码: $code"
                }
            "stt" -> {
                // 用户语音被STT转文字（服务器返回的用户说的话）
                val textContent = json.get("text")?.asString ?: ""
                _textMessage.value = textContent
                if (textContent.isNotEmpty()) {
                    val message = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = textContent,
                        type = MessageType.TEXT,
                        direction = MessageDirection.SENT, // 用户说话的内容，显示在用户侧
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                    scope.launch { _chatMessages.emit(message) }
                }
            }
                "tts" -> {
                    val state = json.get("state")?.asString ?: ""
                    val ttsTextContent = json.get("text")?.asString ?: ""
                    
                    // 检查是否有 base64 编码的音频数据
                    val audioBase64 = json.get("audio")?.asString 
                        ?: json.get("data")?.asString
                        ?: json.get("audio_data")?.asString
                    
                    if (audioBase64 != null && audioBase64.isNotEmpty()) {
                        Log.i(TAG, "收到 TTS 音频数据 (base64): ${audioBase64.length} 字符")
                        try {
                            val audioData = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
                            if (audioData.isNotEmpty()) {
                                scope.launch { _audioData.emit(audioData) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Base64 解码失败: ${e.message}")
                        }
                    }
                    
                    when (state) {
                        "start" -> {
                            _aiState.value = "speaking"
                            _ttsText.value = ""
                        }
                        "stop" -> {
                            _aiState.value = "idle"
                        }
                        "sentence_start" -> {
                            _ttsText.value = ttsTextContent
                        }
                        "sentence_end" -> {
                            // AI最终回复，发送给UI显示
                            _textMessage.value = ttsTextContent
                            if (ttsTextContent.isNotEmpty()) {
                                val message = ChatMessage(
                                    id = java.util.UUID.randomUUID().toString(),
                                    content = ttsTextContent,
                                    type = MessageType.TEXT,
                                    direction = MessageDirection.RECEIVED, // AI回复
                                    status = MessageStatus.SENT,
                                    timestamp = System.currentTimeMillis()
                                )
                                scope.launch { _chatMessages.emit(message) }
                            }
                        }
                    }
                }
                "llm" -> {
                    val emotionStr = json.get("emotion")?.asString ?: ""
                    _emotion.value = emotionStr
                }
                "mcp" -> {
                    // MCP协议消息，传递给应用处理
                    val payload = json.get("payload")
                    val method = payload?.asJsonObject?.get("method")?.asString
                    val msgId = payload?.asJsonObject?.get("id")

                    // 如果是initialize请求，需要回复initialized响应
                    if (method == "initialize") {
                        val id = msgId?.asString ?: "1"
                        val response = JsonObject().apply {
                            addProperty("type", "mcp")
                            addProperty("session_id", sessionId)
                            val responsePayload = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                val result = JsonObject().apply {
                                    addProperty("protocolVersion", "2024-11-05")
                                    addProperty("capabilities", "{}")
                                    val serverInfo = JsonObject().apply {
                                        addProperty("name", "xiaozhi-android")
                                        addProperty("version", "1.0.0")
                                    }
                                    add("serverInfo", serverInfo)
                                }
                                add("result", result)
                            }
                            add("payload", responsePayload)
                        }
                        sendTextMessage(response.toString())
                        Log.i(TAG, "回复MCP initialized响应")
                    }

                    // 如果是tools/list请求，需要回复工具列表
                    if (method == "tools/list") {
                        val id = msgId?.asString ?: "2"
                        val response = JsonObject().apply {
                            addProperty("type", "mcp")
                            addProperty("session_id", sessionId)
                            val responsePayload = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                val result = JsonObject().apply {
                                    add("tools", JsonArray()) // 空工具列表
                                }
                                add("result", result)
                            }
                            add("payload", responsePayload)
                        }
                        sendTextMessage(response.toString())
                        Log.i(TAG, "回复MCP tools/list响应")
                    }
                    
                    // 应用可通过观察emotion来获取MCP消息
                    _emotion.value = "mcp:${payload?.toString() ?: ""}"
                }
                "abort" -> {
                    // 服务器中止当前操作
                    _aiState.value = "idle"
                    val reason = json.get("reason")?.asString ?: ""
                    _textMessage.value = "[中断] $reason"
                }
            }
        } catch (e: Exception) {
            // ignore parse errors
        }
    }

    /**
     * 处理服务器二进制消息 (Opus音频)
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (data.isEmpty()) {
            Log.w(TAG, "收到空二进制消息")
            return
        }
        Log.i(TAG, "收到二进制音频: ${data.size} bytes")
        // 使用emit发送Opus音频数据包
        scope.launch {
            _audioData.emit(data)
        }
    }
}
