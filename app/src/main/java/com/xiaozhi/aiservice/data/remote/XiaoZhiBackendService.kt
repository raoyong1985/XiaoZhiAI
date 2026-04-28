package com.xiaozhi.aiservice.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.BuildConfig
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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 条件日志：仅在 debug 版本输出
 */
private fun debugLog(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
}

private fun debugLogE(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.e(tag, msg)
}

private fun debugLogW(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.w(tag, msg)
}

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

    // 服务器Alert弹窗消息
    private val _alert = MutableSharedFlow<String>(replay = 0)
    val alert: SharedFlow<String> = _alert.asSharedFlow()

    // 流式消息结束信号（sentence_end时发出消息ID）
    private val _streamingEnd = MutableSharedFlow<String>(replay = 0)
    val streamingEnd: SharedFlow<String> = _streamingEnd.asSharedFlow()

    private var isConnected = false
    private var serverUrl = ""
    private var otaUrl = ""
    private var deviceId = ""
    private var clientId = ""
    private var sessionId = ""
    private var serverSampleRate = 16000  // 服务器返回的采样率
    private var serverChannels = 1            // 服务器返回的声道数
    private var accessToken = ""           // 保存accessToken用于重连
    private var wsToken = ""              // WebSocket token (OTA注册返回)
    private var pendingActivationChallenge = "" // 待发送的激活challenge
    private var reconnectAttempt = 0       // 重连次数
    private val baseReconnectDelay = 1000L  // 基础重连延迟(ms)，断线后1s开始重连
    private var isReconnecting = false     // 是否正在重连
    private var streamingMessageId = ""     // 当前流式消息ID，sentence_end时用于去重

    /**
     * 连接服务器
     * @param url WebSocket URL (如 wss://xiaozhi.me/ws)
     * @param deviceId 设备ID (用于Device-Id header，格式XX:XX:XX:XX:XX:XX)
     * @param clientId 客户端ID (用于Client-Id header，UUID格式)
     * @param accessToken 访问令牌 (用于Authorization header)
     */
    fun connect(url: String, otaUrl: String, deviceId: String, clientId: String, accessToken: String = "") {
        serverUrl = url
        this.otaUrl = otaUrl
        this.deviceId = deviceId
        this.clientId = clientId
        this.accessToken = accessToken
        this.reconnectAttempt = 0
        _connectionState.value = ConnectionState.Connecting
        debugLog(TAG, "连接中: $url")

        // 先进行OTA注册（设备激活），注册完成后建立WebSocket连接
        scope.launch {
            try {
                registerDevice(otaUrl, deviceId, clientId)
                debugLog(TAG, "OTA注册完成，开始建立WebSocket连接")
            } catch (e: Exception) {
                debugLogE(TAG, "OTA注册失败: ${e.message}，但仍尝试连接")
            }
            doConnect(url, deviceId, clientId, accessToken)
        }
    }

    /**
     * OTA注册设备
     * 必须在连接WebSocket之前调用，用于向服务器注册设备
     * 参考: https://xujiwei.com/blog/2025/04/xiaozhi-ai-protocol-ota/
     */
    private suspend fun registerDevice(otaUrl: String, deviceId: String, clientId: String) {
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
        debugLog(TAG, "OTA注册请求: $body")

        val requestBody = body.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(otaUrl)
            .post(requestBody)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .build()

        val client = OkHttpClient()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "empty"
        debugLog(TAG, "OTA注册响应: ${response.code} - ${responseBody.take(200)}")

        if (response.isSuccessful) {
            try {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                // 保存WebSocket token
                val wsObj = json.getAsJsonObject("websocket")
                if (wsObj != null) {
                    wsToken = wsObj.get("token")?.asString ?: ""
                    debugLog(TAG, "WebSocket token 已获取")
                }
                // 保存激活challenge（如果有）
                if (json.has("activation")) {
                    val activation = json.getAsJsonObject("activation")
                    pendingActivationChallenge = activation?.get("challenge")?.asString ?: ""
                    val code = activation?.get("code")?.asString ?: ""
                    debugLog(TAG, "设备需要激活，验证码: $code")
                    // 显示验证码到聊天界面
                    val message = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = "🔐 设备需要激活，请在 xiaozhi.me 输入验证码：$code",
                        type = MessageType.TEXT,
                        direction = MessageDirection.RECEIVED,
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                    scope.launch { _chatMessages.emit(message) }
                }
            } catch (e: Exception) {
                debugLogE(TAG, "解析OTA响应失败: ${e.message}")
            }
        }
    }

    private fun doConnect(url: String, deviceId: String, clientId: String, accessToken: String) {
        debugLog(TAG, "Device-Id: $deviceId, Client-Id: $clientId")

        val headers = Headers.Builder().apply {
            add("Protocol-Version", PROTOCOL_VERSION)
            add("Device-Id", deviceId)
            add("Client-Id", clientId)
            // 优先使用OTA注册返回的websocket token
            if (wsToken.isNotEmpty()) {
                add("Authorization", "Bearer $wsToken")
                debugLog(TAG, "使用WebSocket token认证")
            } else if (accessToken.isNotEmpty()) {
                add("Authorization", "Bearer $accessToken")
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()
        debugLog(TAG, "发送WebSocket请求...")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                _connectionState.value = ConnectionState.Connected
                debugLog(TAG, "WebSocket已连接! 发送hello...")
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                debugLog(TAG, "收到文本: ${text.take(200)}")
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val ba = bytes.toByteArray()
                debugLog(TAG, "收到二进制: ${ba.size} bytes")
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                debugLog(TAG, "WebSocket关闭: $code $reason")
                isConnected = false
                _connectionState.value = ConnectionState.Disconnected
                // 1005 = 服务器强制关闭（设备未激活），不重试
                if (code == 1005) {
                    debugLogW(TAG, "服务器要求激活设备，停止重连。请在 xiaozhi.me 完成设备激活。")
                    isReconnecting = false
                    reconnectAttempt = 0
                    _connectionState.value = ConnectionState.Error
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                debugLogE(TAG, "WebSocket失败: ${t.message}")
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
        wsToken = ""
        pendingActivationChallenge = ""
        _connectionState.value = ConnectionState.Disconnected
        _aiState.value = "idle"
    }

    /**
     * 计划重连 - 无限重试，直到连接成功
     */
    private fun scheduleReconnect() {
        if (isReconnecting) return
        if (serverUrl.isEmpty()) return

        isReconnecting = true
        reconnectAttempt++

        // 指数退避: 1s, 2s, 4s, 8s, 16s, 32s, 最多2分钟
        val delay = (baseReconnectDelay * (1 shl (reconnectAttempt - 1).coerceAtMost(7))).coerceAtMost(120000L)
        debugLog(TAG, "计划 ${reconnectAttempt}s 后重连 (延迟: ${delay}ms)")

        scope.launch {
            delay(delay)
            isReconnecting = false
            debugLog(TAG, "执行重连... (第 $reconnectAttempt 次)")
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
     * 发送设备激活响应
     * @param challenge 服务器下发的challenge
     * @param code 激活码（用户网站上确认后获取）
     */
    private fun sendActivation(challenge: String, code: String) {
        val message = JsonObject().apply {
            addProperty("type", "activation")
            addProperty("challenge", challenge)
            addProperty("code", code)
        }
        debugLog(TAG, "发送激活响应")
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
        debugLog(TAG, "发送: ${text.take(200)}")
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
            debugLog(TAG, "处理消息类型: $type")

            when (type) {
                "hello" -> {
                    debugLog(TAG, "收到hello响应! 设备已连接")
                    // 解析session_id
                    sessionId = json.get("session_id")?.asString ?: ""
                    // 解析服务器返回的采样率
                    val audioParams = json.getAsJsonObject("audio_params")
                    if (audioParams != null) {
                        serverSampleRate = audioParams.get("sample_rate")?.asInt ?: 16000
                        serverChannels = audioParams.get("channels")?.asInt ?: 1
                        debugLog(TAG, "服务器音频参数: ${serverSampleRate}Hz, ${serverChannels}ch")
                    }
                    debugLog(TAG, "Session ID: $sessionId")

                    // 检查hello中是否包含激活challenge（服务器要求激活）
                    val activation = json.getAsJsonObject("activation")
                    if (activation != null) {
                        val challenge = activation.get("challenge")?.asString ?: ""
                        val challengeCode = activation.get("code")?.asString ?: ""
                        debugLog(TAG, "服务器要求激活: challenge=$challenge, code=$challengeCode")
                        // 立即发送激活响应
                        if (challenge.isNotEmpty()) {
                            sendActivation(challenge, challengeCode)
                        }
                    }
                }
                "activation" -> {
                    val code = json.get("code")?.asString
                    debugLog(TAG, "收到激活码: $code")
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
                        debugLog(TAG, "收到 TTS 音频数据 (base64): ${audioBase64.length} 字符")
                        try {
                            val audioData = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
                            if (audioData.isNotEmpty()) {
                                scope.launch { _audioData.emit(audioData) }
                            }
                        } catch (e: Exception) {
                            debugLogE(TAG, "Base64 解码失败: ${e.message}")
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
                            // 流式TTS：立即发出消息，让用户看到AI正在"思考"
                            if (ttsTextContent.isNotEmpty()) {
                                val id = java.util.UUID.randomUUID().toString()
                                streamingMessageId = id
                                val message = ChatMessage(
                                    id = id,
                                    content = ttsTextContent,
                                    type = MessageType.TEXT,
                                    direction = MessageDirection.RECEIVED,
                                    status = MessageStatus.SENT,
                                    timestamp = System.currentTimeMillis(),
                                    isStreaming = true
                                )
                                scope.launch { _chatMessages.emit(message) }
                            }
                            _ttsText.value = ttsTextContent
                        }
                        "sentence_end" -> {
                            // 流式TTS final：通知UI停止动画，不重复发消息
                            if (streamingMessageId.isNotEmpty()) {
                                scope.launch { _streamingEnd.emit(streamingMessageId) }
                                streamingMessageId = ""
                            }
                            _ttsText.value = ""
                        }
                    }
                }
                "llm" -> {
                    val emotionStr = json.get("emotion")?.asString ?: ""
                    _emotion.value = emotionStr
                }
                "mcp" -> {
                    // MCP协议消息处理
                    val payload = json.get("payload")?.asJsonObject
                    val method = payload?.get("method")?.asString
                    val msgId = payload?.get("id") ?: com.google.gson.JsonNull.INSTANCE

                    when (method) {
                        "initialize" -> {
                            val response = JsonObject().apply {
                                addProperty("type", "mcp")
                                addProperty("session_id", sessionId)
                                val responsePayload = JsonObject().apply {
                                    addProperty("jsonrpc", "2.0")
                                    add("id", msgId)
                                    val result = JsonObject().apply {
                                        addProperty("protocolVersion", "2024-11-05")
                                        add("capabilities", JsonObject().apply {
                                            addProperty("tools", true)
                                        })
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
                            debugLog(TAG, "回复MCP initialized响应")
                        }
                        "tools/list" -> {
                            val response = JsonObject().apply {
                                addProperty("type", "mcp")
                                addProperty("session_id", sessionId)
                                val responsePayload = JsonObject().apply {
                                    addProperty("jsonrpc", "2.0")
                                    add("id", msgId)
                                    val result = JsonObject().apply {
                                        add("tools", MCPToolRegistry.getToolList())
                                    }
                                    add("result", result)
                                }
                                add("payload", responsePayload)
                            }
                            sendTextMessage(response.toString())
                            debugLog(TAG, "回复MCP tools/list响应，工具数: ${MCPToolRegistry.getToolList().size()}")
                        }
                        "tools/call" -> {
                            val params = payload?.get("params")?.asJsonObject
                            val toolName = params?.get("name")?.asString ?: ""
                            val arguments = params?.get("arguments")?.asJsonObject
                            val argsMap = mutableMapOf<String, Any>()
                            arguments?.entrySet()?.forEach { (k, v) ->
                                argsMap[k] = when {
                                    v.isJsonPrimitive -> {
                                        val prim = v.asJsonPrimitive
                                        if (prim.isString) prim.asString
                                        else if (prim.isNumber) prim.asNumber
                                        else if (prim.isBoolean) prim.asBoolean
                                        else prim.toString()
                                    }
                                    else -> v.toString()
                                }
                            }
                            val result = MCPToolRegistry.callTool(toolName, argsMap)
                            val response = JsonObject().apply {
                                addProperty("type", "mcp")
                                addProperty("session_id", sessionId)
                                val responsePayload = JsonObject().apply {
                                    addProperty("jsonrpc", "2.0")
                                    add("id", msgId)
                                    add("result", result)
                                }
                                add("payload", responsePayload)
                            }
                            sendTextMessage(response.toString())
                            debugLog(TAG, "回复MCP tools/call响应: $toolName")
                        }
                        else -> {
                            debugLogW(TAG, "未知MCP方法: $method")
                        }
                    }
                }
                "abort" -> {
                    // 服务器中止当前操作
                    _aiState.value = "idle"
                    val reason = json.get("reason")?.asString ?: ""
                    _textMessage.value = "[中断] $reason"
                }
                "alert" -> {
                    // 服务器下发Alert弹窗消息
                    val alertText = json.get("text")?.asString
                        ?: json.get("message")?.asString
                        ?: json.get("content")?.asString
                        ?: "收到通知"
                    debugLog(TAG, "收到Alert: $alertText")
                    scope.launch { _alert.emit(alertText) }
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
            debugLogW(TAG, "收到空二进制消息")
            return
        }
        debugLog(TAG, "收到二进制音频: ${data.size} bytes")
        // 使用emit发送Opus音频数据包
        scope.launch {
            _audioData.emit(data)
        }
    }
}
