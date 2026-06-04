package com.xiaozhi.aiservice.data.remote

import com.xiaozhi.aiservice.utils.Logger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.AiStateStr
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
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
 * 小智AI WebSocket客户端
 * 协议文档: https://my.feishu.cn/wiki/M0XiwldO9iJwHikpXD5cEx71nKh
 *
 * 连接流程:
 * 1. 建立WebSocket连接 (携带headers)
 * 2. 交换hello消息
 * 3. 开始语音交互
 */
class XiaoZhiBackendService(
    private val gson: Gson,
    private val mcpToolRegistry: MCPToolRegistry,
    private val audioService: AudioService
) : BotConnection {

    private var binaryAudioInSession = false  // 本轮TTS是否已有二进制音频（用于base64去重）

    companion object {
        private const val PROTOCOL_VERSION = "1"
        private const val TAG = "XiaoZhiWS"
        private const val MAX_RECONNECT_ATTEMPTS = 10

        /** 共享 OkHttpClient 实例，减少连接池和线程创建开销 */
        val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    /** 服务器下发的视觉AI API URL（从MCP initialize消息中解析） */
    var visionUrl: String? = null
    /** 服务器下发的视觉AI token */
    var visionToken: String? = null

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private val client = sharedClient

    private val _connectionState = MutableStateFlow<BotConnectionState>(BotConnectionState.Disconnected)
    override val connectionState: StateFlow<BotConnectionState> = _connectionState.asStateFlow()

    // AI状态: idle, listening, speaking
    private val _aiState = MutableStateFlow<String>(AiStateStr.IDLE)
    override val aiState: StateFlow<String> = _aiState.asStateFlow()

    // 收到服务器转写的文本
    private val _textMessage = MutableStateFlow("")
    override val textMessage: StateFlow<String> = _textMessage.asStateFlow()

    // 完整的聊天消息流（区分用户/AI消息）
    private val _chatMessages = MutableSharedFlow<ChatMessage>(replay = 0)
    override val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    // 收到TTS音频数据 (Opus) - 使用SharedFlow支持多包
    private val _audioData = MutableSharedFlow<ByteArray>(replay = 0)
    override val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    // 服务器返回的TTS文本
    private val _ttsText = MutableStateFlow("")
    override val ttsText: StateFlow<String> = _ttsText.asStateFlow()

    // 情感状态
    private val _emotion = MutableStateFlow("")
    override val emotion: StateFlow<String> = _emotion.asStateFlow()

    // 服务器Alert弹窗消息
    private val _alert = MutableSharedFlow<String>(replay = 0)
    override val alert: SharedFlow<String> = _alert.asSharedFlow()

    // 流式消息结束信号（sentence_end时发出消息ID）
    private val _streamingEnd = MutableSharedFlow<String>(replay = 0)
    override val streamingEnd: SharedFlow<String> = _streamingEnd.asSharedFlow()

    @Volatile private var isConnected = false
    @Volatile private var serverUrl = ""
    private var otaUrl = ""
    private var deviceId = ""
    private var clientId = ""
    @Volatile private var sessionId = ""
    private var serverSampleRate = 16000
    private var serverChannels = 1
    private var accessToken = ""
    private var wsToken = ""
    @Volatile private var reconnectAttempt = 0
    private val baseReconnectDelay = 1000L
    @Volatile private var isDisconnecting = false  // 用户主动断开，不再自动重连
    @Volatile
    private var isReconnecting = false   // 防止 onClosing+onClosed 双重重连
    private var streamingMessageId = ""     // 当前流式消息ID，sentence_end时用于去重
    private var reconnectJob: kotlinx.coroutines.Job? = null  // 重连协程，disconnect时取消
    private var idleDebounceJob: kotlinx.coroutines.Job? = null  // 防抖：sentence_end后无后续消息则切idle
    private var localCommandSettleJob: kotlinx.coroutines.Job? = null  // 本地命令冷却：等服务器pending响应排空

    /**
     * 连接服务器
     * @param url WebSocket URL (如 wss://xiaozhi.me/ws)
     * @param deviceId 设备ID (用于Device-Id header，格式XX:XX:XX:XX:XX:XX)
     * @param clientId 客户端ID (用于Client-Id header，UUID格式)
     * @param accessToken 访问令牌 (用于Authorization header)
     */
    override fun connect(url: String, otaUrl: String, deviceId: String, clientId: String, accessToken: String) {
        serverUrl = url
        this.otaUrl = otaUrl
        this.deviceId = deviceId
        this.clientId = clientId
        this.accessToken = accessToken
        this.reconnectAttempt = 0
        this.isDisconnecting = false
        _connectionState.value = BotConnectionState.Connecting
        Logger.i(TAG, "连接中: $url")

        // 先进行OTA注册（设备激活），注册完成后建立WebSocket连接
        scope.launch {
            try {
                registerDevice(otaUrl, deviceId, clientId)
                Logger.i(TAG, "OTA注册完成，开始建立WebSocket连接")
            } catch (e: Exception) {
                Logger.e(TAG, "OTA注册失败: ${e.message}，但仍尝试连接")
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
        Logger.i(TAG, "OTA注册请求: $body")

        val requestBody = body.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(otaUrl)
            .post(requestBody)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .addHeader("Content-Type", "application/json")
            .build()

        val client = sharedClient
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "empty"
            Logger.i(TAG, "OTA注册响应: ${response.code} - ${responseBody.take(200)}")

            if (response.isSuccessful) {
                try {
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    // 保存WebSocket token
                    val wsObj = json.getAsJsonObject("websocket")
                    if (wsObj != null) {
                        wsToken = wsObj.get("token")?.asString ?: ""
                        Logger.i(TAG, "WebSocket token 已获取")
                    }
                    // 保存激活challenge（如果有）
                    if (json.has("activation")) {
                        val activation = json.getAsJsonObject("activation")
                        val code = activation?.get("code")?.asString ?: ""
                        Logger.i(TAG, "设备需要激活，验证码: $code")
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
                    Logger.e(TAG, "解析OTA响应失败: ${e.message}")
                }
            }
        }
    }

    private fun doConnect(url: String, deviceId: String, clientId: String, accessToken: String) {
        // 创建新连接前关闭旧连接，防止泄漏
        webSocket?.close(1001, "Reconnecting")
        Logger.i(TAG, "Device-Id: $deviceId, Client-Id: $clientId")

        val headers = Headers.Builder().apply {
            add("Protocol-Version", PROTOCOL_VERSION)
            add("Device-Id", deviceId)
            add("Client-Id", clientId)
            // 优先使用OTA注册返回的websocket token
            if (wsToken.isNotEmpty()) {
                add("Authorization", "Bearer $wsToken")
                Logger.i(TAG, "使用WebSocket token认证")
            } else if (accessToken.isNotEmpty()) {
                add("Authorization", "Bearer $accessToken")
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()
        Logger.i(TAG, "发送WebSocket请求...")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isReconnecting = false
                reconnectAttempt = 0
                _connectionState.value = BotConnectionState.Connected
                Logger.i(TAG, "WebSocket已连接! 发送hello...")
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Logger.i(TAG, "收到文本: ${text.take(200)}")
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i(TAG, "WebSocket关闭: $code $reason")
                isConnected = false
                _connectionState.value = BotConnectionState.Disconnected
                // onClosing 只做状态更新，重连由 onClosed/onFailure 统一处理
                // 避免双重触发 scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i(TAG, "WebSocket已关闭: $code $reason")
                isConnected = false
                _connectionState.value = BotConnectionState.Disconnected
                if (code != 1000 && code != 1001) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e(TAG, "WebSocket失败: ${t.message}")
                isConnected = false
                _connectionState.value = BotConnectionState.Error()
                scheduleReconnect()
            }
        })
    }

    override fun disconnect() {
        isDisconnecting = true
        isReconnecting = false
        reconnectJob?.cancel()
        reconnectJob = null
        idleDebounceJob?.cancel()
        idleDebounceJob = null
        localCommandSettleJob?.cancel()
        localCommandSettleJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        reconnectAttempt = 0  // 重置重连计数
        wsToken = ""
        _connectionState.value = BotConnectionState.Disconnected
        _aiState.value = AiStateStr.IDLE
        // 注意：不 cancel supervisorJob，scope 保持活跃以支持重连
        // 各协程通过 isDisconnecting 标志 + cancel() 协作取消
    }

    /**
     * 完全销毁（Service 生命周期结束），取消全部协程
     */
    override fun destroy() {
        disconnect()
        supervisorJob.cancel()
    }

    /**
     * 计划重连 - 无限重试，直到连接成功
     */
    private fun scheduleReconnect() {
        if (isDisconnecting) return
        if (isReconnecting) return  // 防止 onClosing+onClosed 双重调度
        isReconnecting = true
        if (serverUrl.isEmpty()) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Logger.e(TAG, "重连已达到最大次数($MAX_RECONNECT_ATTEMPTS)，停止重连")
            _connectionState.value = BotConnectionState.Error()
            return
        }

        reconnectAttempt++

        // 指数退避: 1s, 2s, 4s, 8s, 16s, 32s, 最多2分钟
        val delay = (baseReconnectDelay * (1 shl (reconnectAttempt - 1).coerceAtMost(7))).coerceAtMost(120000L)
        Logger.i(TAG, "计划第 $reconnectAttempt 次重连 (延迟: ${delay}ms)")

        // 取消上一次重连协程（如果有），避免 disconnect 后残留
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            Logger.i(TAG, "执行重连... (第 $reconnectAttempt 次)")
            _connectionState.value = BotConnectionState.Connecting
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
        Logger.i(TAG, "发送hello")
        sendTextMessage(hello.toString())
    }

    /**
     * 开始监听
     * @param mode "auto", "manual", 或 "realtime"
     */
    override fun sendListenStart(mode: String) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "start")
            addProperty("mode", mode)
        }
        sendTextMessage(message.toString())
        _aiState.value = AiStateStr.LISTENING
    }

    /**
     * 停止监听
     */
    override fun sendListenStop() {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        sendTextMessage(message.toString())
        _aiState.value = AiStateStr.IDLE
    }

    /**
     * 只发送 listen stop 消息，不修改 aiState
     * 用于群聊接龙场景：避免提前设置 idle 覆盖后续 TTS stop 事件
     */
    override fun sendListenStopOnly() {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "stop")
        }
        sendTextMessage(message.toString())
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
        Logger.i(TAG, "发送激活响应")
        sendTextMessage(message.toString())
    }

    /**
     * 终止当前会话
     */
    override fun sendAbort(reason: String) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "abort")
            if (reason.isNotEmpty()) {
                addProperty("reason", reason)
            }
        }
        sendTextMessage(message.toString())
        _aiState.value = AiStateStr.IDLE
    }

    /**
     * 获取服务器采样率
     */
    override fun getServerSampleRate(): Int = serverSampleRate
    override fun getServerChannels(): Int = serverChannels

    /**
     * 发送Opus编码的音频数据
     */
    override fun sendAudio(audioData: ByteArray) {
        if (!isConnected || audioData.isEmpty()) return
        webSocket?.send(audioData.toByteString())
    }

    /**
     * 发送文本内容（用于聊天消息/群聊接龙）
     * 当前服务器仅支持 state="detect"，且文本需限制在 8 字符以内
     * state="text" 服务器不会响应
     */
    override fun sendTextContent(text: String) {
        Logger.i(TAG, "sendTextContent: isConnected=$isConnected text=${text.take(30)}")
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "listen")
            addProperty("state", "detect")
            addProperty("text", text)
        }
        sendTextMessage(message.toString())
    }

    /**
     * 发送静音状态到服务器
     */
    /**
     * 判断当前连接是否为本地服务器
     */
    override fun isLocalServer(): Boolean {
        return serverUrl.contains("192.168.") || serverUrl.contains("localhost")
            || serverUrl.contains("127.0.0.1") || serverUrl.contains("10.")
    }

    fun sendMute(muted: Boolean) {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", if (muted) "voice_mute" else "voice_unmute")
        }
        sendTextMessage(message.toString())
        Logger.i(TAG, "发送静音状态: muted=$muted")
    }

    private fun sendTextMessage(text: String) {
        if (!isConnected) {
            Logger.w(TAG, "WebSocket未连接，消息未发送: ${text.take(50)}")
            return
        }
        Logger.i(TAG, "发送: ${text.take(200)}")
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
            Logger.i(TAG, "处理消息类型: $type")
            when (type) {
                "hello" -> handleHello(json)
                "activation" -> handleActivation(json)
                "stt" -> handleStt(json)
                "tts" -> handleTts(json)
                "llm" -> handleLlm(json)
                "mcp" -> handleMcp(json)
                "abort" -> handleAbort(json)
                "alert" -> handleAlert(json)
                "iot" -> handleIot(json)
                "server" -> handleServer(json)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理文本消息失败: ${e.message}")
        }
    }

    private fun handleHello(json: JsonObject) {
        Logger.i(TAG, "收到hello响应! 设备已连接")
        _aiState.value = AiStateStr.IDLE
        sessionId = json.get("session_id")?.asString ?: ""
        val audioParams = json.getAsJsonObject("audio_params")
        if (audioParams != null) {
            serverSampleRate = audioParams.get("sample_rate")?.asInt ?: 16000
            serverChannels = audioParams.get("channels")?.asInt ?: 1
            Logger.i(TAG, "服务器音频参数: ${serverSampleRate}Hz, ${serverChannels}ch")
        }
        Logger.i(TAG, "Session ID: $sessionId")
        val activation = json.getAsJsonObject("activation")
        if (activation != null) {
            val challenge = activation.get("challenge")?.asString ?: ""
            val challengeCode = activation.get("code")?.asString ?: ""
            Logger.i(TAG, "服务器要求激活: challenge=$challenge, code=$challengeCode")
            if (challenge.isNotEmpty()) sendActivation(challenge, challengeCode)
        }
        // 连接建立后上报 IoT 设备能力
        sendIotDescriptors()
    }

    private fun handleActivation(json: JsonObject) {
        val code = json.get("code")?.asString
        Logger.i(TAG, "收到激活码: $code")
        _textMessage.value = "激活码: $code"
    }

    private fun handleStt(json: JsonObject) {
        val textContent = json.get("text")?.asString ?: ""
        _textMessage.value = textContent
        if (textContent.isNotEmpty()) {
            val message = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                content = textContent,
                type = MessageType.TEXT,
                direction = MessageDirection.SENT,
                status = MessageStatus.SENT,
                timestamp = System.currentTimeMillis()
            )
            scope.launch { _chatMessages.emit(message) }

            // 本地命令拦截：匹配则拦截，不发给服务器LLM
            scope.launch {
                // 先快速判断是否可能匹配（不执行工具，只检查关键词）
                val clean = textContent.trim().lowercase()
                var matchedRule: LocalCommandRouter.CmdRule? = null
                for (rule in LocalCommandRouter.commands) {
                    for (kw in rule.keywords) {
                        if (clean.contains(kw.lowercase())) {
                            matchedRule = rule
                            break
                        }
                    }
                    if (matchedRule != null) break
                }

                if (matchedRule != null) {
                    Logger.i(TAG, "本地命令拦截: ${matchedRule.toolName}")
                    // 发送abort到服务器（不调sendAbort，避免立即切aiState=IDLE触发监听重启）
                    val abortMsg = JsonObject().apply {
                        addProperty("session_id", sessionId)
                        addProperty("type", "abort")
                        addProperty("reason", "本地执行: ${matchedRule.toolName}")
                    }
                    sendTextMessage(abortMsg.toString())

                    // 启动冷却计时器：等服务器pending响应排空后再切IDLE，防止回声循环
                    localCommandSettleJob?.cancel()
                    localCommandSettleJob = scope.launch {
                        delay(3500)
                        _aiState.value = AiStateStr.IDLE
                        localCommandSettleJob = null
                    }

                    // 执行工具
                    delay(100)
                    val result = LocalCommandRouter.matchAndExecute(textContent)
                    if (result != null) {
                        // 让服务器TTS播报反馈——把反馈文字发给服务器
                        val feedbackText = if (result.success) result.responseText
                            else "执行${result.toolName}失败了: ${result.rawResult}"
                        val detectMsg = JsonObject().apply {
                            addProperty("session_id", sessionId)
                            addProperty("type", "listen")
                            addProperty("state", "detect")
                            addProperty("text", feedbackText)
                            addProperty("from_local", true)
                        }
                        Logger.i(TAG, "发送反馈文字到服务器TTS: $feedbackText")
                        sendTextMessage(detectMsg.toString())
                        // 显示到聊天
                        if (result.success && result.rawResult is JsonObject) {
                            val toolResult = result.rawResult as JsonObject
                            if (toolResult.size() > 1) {
                                val chatMsg = ChatMessage(
                                    id = java.util.UUID.randomUUID().toString(),
                                    content = toolResult.toString(),
                                    type = MessageType.TEXT,
                                    direction = MessageDirection.RECEIVED,
                                    status = MessageStatus.SENT,
                                    timestamp = System.currentTimeMillis()
                                )
                                scope.launch { _chatMessages.emit(chatMsg) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleTts(json: JsonObject) {
        val state = json.get("state")?.asString ?: ""
        val ttsTextContent = json.get("text")?.asString ?: ""
        val audioBase64 = json.get("audio")?.asString
            ?: json.get("data")?.asString
            ?: json.get("audio_data")?.asString

        if (audioBase64 != null && audioBase64.isNotEmpty()) {
            Logger.i(TAG, "收到 TTS 音频数据 (base64): ${audioBase64.length} 字符")
            if (binaryAudioInSession) {
                Logger.i(TAG, "跳过 base64 音频（二进制音频已在播放）")
            } else {
                try {
                    val audioData = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
                    if (audioData.isNotEmpty()) scope.launch { _audioData.emit(audioData) }
                } catch (e: Exception) {
                    Logger.e(TAG, "Base64 解码失败: ${e.message}")
                }
            }
        }

        when (state) {
            "start" -> {
                idleDebounceJob?.cancel()
                binaryAudioInSession = false
                Logger.i(TAG, "tts start received, setting aiState=speaking")
                _aiState.value = AiStateStr.SPEAKING
                _ttsText.value = ""
            }
            "stop" -> {
                idleDebounceJob?.cancel()
                // 本地命令冷却期间，不设置IDLE（防止监听立即重启导致回声循环）
                if (localCommandSettleJob?.isActive == true) {
                    Logger.i(TAG, "tts stop ignored（本地命令冷却中，等3500ms后自动切idle）")
                } else {
                    Logger.i(TAG, "tts stop received, setting aiState=idle")
                    _aiState.value = AiStateStr.IDLE
                }
            }
            "sentence_start" -> {
                idleDebounceJob?.cancel()
                if (ttsTextContent.isNotEmpty()) {
                    val id = java.util.UUID.randomUUID().toString()
                    streamingMessageId = id
                    val message = ChatMessage(
                        id = id, content = ttsTextContent,
                        type = MessageType.TEXT, direction = MessageDirection.RECEIVED,
                        status = MessageStatus.SENT, timestamp = System.currentTimeMillis(),
                        isStreaming = true
                    )
                    scope.launch { _chatMessages.emit(message) }
                }
                _ttsText.value = ttsTextContent
            }
            "sentence_end" -> {
                if (streamingMessageId.isNotEmpty()) {
                    scope.launch { _streamingEnd.emit(streamingMessageId) }
                    streamingMessageId = ""
                }
                _ttsText.value = ""
                idleDebounceJob?.cancel()
                idleDebounceJob = scope.launch {
                    delay(3000)
                    if (_aiState.value == AiStateStr.SPEAKING) {
                        Logger.i(TAG, "tts sentence_end 超时无后续，自动切 idle")
                        _aiState.value = AiStateStr.IDLE
                    }
                }
            }
        }
    }

    private fun handleLlm(json: JsonObject) {
        val emotionStr = json.get("emotion")?.asString ?: ""
        _emotion.value = emotionStr
    }

    private fun handleMcp(json: JsonObject) {
        val payload = json.get("payload")?.asJsonObject
        val method = payload?.get("method")?.asString
        val msgId = payload?.get("id") ?: com.google.gson.JsonNull.INSTANCE

        when (method) {
            "initialize" -> {
                val params = payload?.get("params")?.asJsonObject
                val capabilities = params?.getAsJsonObject("capabilities")
                val vision = capabilities?.getAsJsonObject("vision")
                val visionUrlStr = vision?.get("url")?.asString
                if (visionUrlStr != null) {
                    visionUrl = visionUrlStr
                    Logger.i(TAG, "服务器视觉AI URL: $visionUrlStr")
                }
                val visionTokenStr = vision?.get("token")?.asString
                if (visionTokenStr != null) {
                    visionToken = visionTokenStr
                    Logger.i(TAG, "服务器视觉AI Token: $visionTokenStr")
                }
                val response = JsonObject().apply {
                    addProperty("type", "mcp")
                    addProperty("session_id", sessionId)
                    val responsePayload = JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        add("id", msgId)
                        val result = JsonObject().apply {
                            addProperty("protocolVersion", "2024-11-05")
                            add("capabilities", JsonObject().apply { addProperty("tools", true) })
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
                Logger.i(TAG, "回复MCP initialized响应")
            }
            "tools/list" -> {
                val toolList = mcpToolRegistry.getToolList()
                val response = JsonObject().apply {
                    addProperty("type", "mcp")
                    addProperty("session_id", sessionId)
                    val responsePayload = JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        add("id", msgId)
                        add("result", JsonObject().apply { add("tools", toolList) })
                    }
                    add("payload", responsePayload)
                }
                sendTextMessage(response.toString())
                Logger.i(TAG, "回复MCP tools/list响应，工具数: ${toolList.size()}")
            }
            "resources/list" -> {
                val response = JsonObject().apply {
                addProperty("type", "mcp")
                addProperty("session_id", sessionId)
                val responsePayload = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    add("id", msgId)
                    add("result", JsonObject().apply {
                        add("resources", JsonArray())
                    })
                }
                add("payload", responsePayload)
                }
                sendTextMessage(response.toString())
                Logger.i(TAG, "回复MCP resources/list响应(空)")
                }
                "resources/read" -> {
                val response = JsonObject().apply {
                addProperty("type", "mcp")
                addProperty("session_id", sessionId)
                val responsePayload = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    add("id", msgId)
                    add("result", JsonObject().apply {
                        add("contents", JsonArray())
                    })
                }
                add("payload", responsePayload)
                }
                sendTextMessage(response.toString())
                Logger.i(TAG, "回复MCP resources/read响应(空)")
                }
                "prompts/list" -> {
                val response = JsonObject().apply {
                addProperty("type", "mcp")
                addProperty("session_id", sessionId)
                val responsePayload = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    add("id", msgId)
                    add("result", JsonObject().apply {
                        add("prompts", JsonArray())
                    })
                }
                add("payload", responsePayload)
                }
                sendTextMessage(response.toString())
                Logger.i(TAG, "回复MCP prompts/list响应(空)")
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
                scope.launch(Dispatchers.IO) {
                    val (isSuccess, result) = mcpToolRegistry.callTool(toolName, argsMap)
                    val response = JsonObject().apply {
                        addProperty("type", "mcp")
                        addProperty("session_id", sessionId)
                        val responsePayload = JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            add("id", msgId)
                            if (isSuccess) {
                                add("result", JsonObject().apply {
                                    add("content", JsonArray().apply {
                                        add(JsonObject().apply {
                                            addProperty("type", "text")
                                            addProperty("text", (result as JsonObject).toString())
                                        })
                                    })
                                })
                            } else {
                                add("error", JsonObject().apply {
                                    addProperty("code", -32603)
                                    addProperty("message", result as String)
                                })
                            }
                        }
                        add("payload", responsePayload)
                    }
                    sendTextMessage(response.toString())
                    Logger.i(TAG, "回复MCP tools/call响应: $toolName, success=$isSuccess")
                }
            }
            "notifications/initialized" -> { /* JSON-RPC 通知，无需回复 */ }
            else -> Logger.w(TAG, "未知MCP方法: $method")
        }
    }

    private fun handleAbort(json: JsonObject) {
        _aiState.value = AiStateStr.IDLE
        val reason = json.get("reason")?.asString ?: ""
        _textMessage.value = "[中断] $reason"
    }

    private fun handleAlert(json: JsonObject) {
        val alertText = json.get("text")?.asString
            ?: json.get("message")?.asString
            ?: json.get("content")?.asString
            ?: "收到通知"
        Logger.i(TAG, "收到Alert: $alertText")
        scope.launch { _alert.emit(alertText) }
    }

    /**
     * 处理 IoT 消息（服务端推送的设备/工具描述符）
     * 参考 xiaozhi-esp32-server core/handle/textHandler/iotMessageHandler.py
     *
     * 服务端下发 IoT 命令格式:
     * {"type":"iot","commands":[{"name":"...","description":"..."}]}
     */
    private fun handleIot(json: JsonObject) {
        val commands = json.getAsJsonArray("commands")
        if (commands != null) {
            Logger.i(TAG, "收到 ${commands.size()} 个 IoT 命令")
            for (cmd in commands) {
                val cmdObj = cmd.asJsonObject
                val name = cmdObj.get("name")?.asString ?: ""
                val desc = cmdObj.get("description")?.asString ?: ""
                Logger.i(TAG, "IoT 命令: $name — $desc")
                // TODO: 注册 MCP 工具供 LLM 调用 (当前通过 MCP protocol 已实现兼容)
            }
        }
    }

    /**
     * 处理 Server 消息（服务端推送的配置更新/OTA 通知）
     * 参考 xiaozhi-esp32-server core/handle/textHandler/serverMessageHandler.py
     *
     * 格式: {"type":"server","action":"ota_update","url":"..."}
     */
    private fun handleServer(json: JsonObject) {
        val action = json.get("action")?.asString ?: ""
        Logger.i(TAG, "收到 Server 消息: action=$action")
        when (action) {
            "ota_update" -> {
                val url = json.get("url")?.asString ?: ""
                Logger.i(TAG, "OTA 更新地址: $url")
                scope.launch { _alert.emit("检测到新版本，请更新: $url") }
            }
            "config_update" -> {
                Logger.i(TAG, "服务端配置已更新，可能需要重连")
            }
            else -> Logger.i(TAG, "Server 消息 (未处理): $action")
        }
    }

    /**
     * 上报 IoT 设备能力描述符到服务端
     * 格式: {"type":"iot","descriptors":[...]}
     * 参考 xiaozhi-esp32-server core/providers/tools/device_iot/iot_descriptor.py
     */
    override fun sendIotDescriptors() {
        val message = JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("type", "iot")
            val descriptors = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "volume")
                    addProperty("type", "number")
                    addProperty("description", "输出音量 0-100")
                    addProperty("value", AudioSettings.getOutputVolume(AppContext.context))
                })
                add(JsonObject().apply {
                    addProperty("name", "aec")
                    addProperty("type", "binary")
                    addProperty("description", "声学回声消除")
                    addProperty("value", audioService.isAecEnabled())
                })
                add(JsonObject().apply {
                    addProperty("name", "device_name")
                    addProperty("type", "string")
                    addProperty("description", "设备名称")
                    addProperty("value", android.os.Build.MODEL)
                })
            }
            add("descriptors", descriptors)
        }
        sendTextMessage(message.toString())
        Logger.i(TAG, "已上报 IoT 设备能力")
    }

    /**
     * 处理服务器二进制消息 (Opus音频)
     * 协议格式自动检测并剥离：
     * - BinaryProtocol3 (ESP32): type(1) + reserved(1) + payload_size(2大端) + payload[]
     * - 小智AI 1字节协议: type(1) + payload[]
     * 剥离后只发射纯Opus payload，保证音频缓存和转发不走样
     */
    private fun handleBinaryMessage(data: ByteArray) {
        if (data.isEmpty()) {
            Logger.w(TAG, "收到空二进制消息")
            return
        }
        Logger.d(TAG, "收到二进制音频: ${data.size}B session=$sessionId")
        // 有音频数据到达，取消空闲防抖
        idleDebounceJob?.cancel()
        // 标记本轮TTS已有二进制音频（用于base64去重）
        binaryAudioInSession = true
        // 剥离协议头，只发射纯Opus payload
        val opusPayload = stripProtocolHeader(data)
        Logger.d(TAG, "发射音频到Flow: ${opusPayload.size}B (原${data.size}B)")
        scope.launch {
            _audioData.emit(opusPayload)
        }
    }

    /**
     * 自动检测并剥离二进制协议头
     * 返回纯Opus数据
     */
    private fun stripProtocolHeader(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        val type = data[0].toInt() and 0xFF
        if (data.size >= 4) {
            val payloadSize = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if (payloadSize > 0 && payloadSize + 4 <= data.size) {
                // BinaryProtocol3: 4字节头有效
                return data.copyOfRange(4, 4 + payloadSize)
            }
        }
        if (type == 0x01 || type == 0x02) {
            // 小智AI 1字节协议: [type(1)][payload...]
            return data.copyOfRange(1, data.size)
        }
        return data
    }
}
