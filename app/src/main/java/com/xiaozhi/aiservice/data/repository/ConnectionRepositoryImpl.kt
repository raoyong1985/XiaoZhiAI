package com.xiaozhi.aiservice.data.repository

import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.audio.OpusEncoder
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ConnectionRepo"

/**
 * 条件日志：仅在 debug 版本输出
 */
private fun debugLogRepo(tag: String, msg: String) {
    if (com.xiaozhi.aiservice.BuildConfig.ENABLE_DEBUG_LOG) android.util.Log.i(tag, msg)
}

private fun debugLogRepoE(tag: String, msg: String) {
    if (com.xiaozhi.aiservice.BuildConfig.ENABLE_DEBUG_LOG) android.util.Log.e(tag, msg)
}

private fun debugLogRepoW(tag: String, msg: String) {
    if (com.xiaozhi.aiservice.BuildConfig.ENABLE_DEBUG_LOG) android.util.Log.w(tag, msg)
}

class ConnectionRepositoryImpl(
    private val backendService: XiaoZhiBackendService,
    private val audioService: AudioService,
    private val messageRepository: MessageRepository
) : ConnectionRepository {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _aiState = MutableStateFlow(AiState.IDLE)
    override val aiState: StateFlow<AiState> = _aiState.asStateFlow()

    override val emotion: StateFlow<String> = backendService.emotion

    override val alert: SharedFlow<String> = backendService.alert

    override val streamingEnd: SharedFlow<String> = backendService.streamingEnd
    
    // 记录最后发送的消息内容，用于STT回显去重
    private var lastSentText: String? = null
    private val lastSentTextLock = Any()

    // 当前联系人ID，用于消息路由
    private var currentContactId: String = ""

    // 跟踪我们自己启动的录音（用于 AI 说话时自动停止，避免回声）
    private var weStartedRecording = false
    // 跟踪是否处于 AUTO 模式（AI 说完话后自动切回倾听）
    private var isAutoMode = false

    // Opus 解码器（native libopus + STREAM_MUSIC AudioTrack）
    private var opusDecoder: NativeOpusDecoder? = null
    // Opus 编码器（录音用）
    private var opusEncoder: OpusEncoder? = null

    init {
        observeBackendService()
    }
    
    private fun observeBackendService() {
        scope.launch {
            backendService.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is XiaoZhiBackendService.ConnectionState.Connected -> ConnectionState.Connected
                    is XiaoZhiBackendService.ConnectionState.Connecting -> ConnectionState.Connecting
                    is XiaoZhiBackendService.ConnectionState.Error -> ConnectionState.Error("连接错误")
                    is XiaoZhiBackendService.ConnectionState.Disconnected -> ConnectionState.Disconnected
                }
            }
        }

        scope.launch {
            backendService.aiState.collect { state ->
                val newAiState = when (state.lowercase()) {
                    "listening" -> AiState.LISTENING
                    "speaking" -> AiState.SPEAKING
                    "processing" -> AiState.PROCESSING
                    else -> AiState.IDLE
                }
                val oldAiState = _aiState.value

                // AUTO 模式：TTS 开始时如果还在 LISTENING 状态（VAD auto-stop 后等待 TTS）
                if (newAiState == AiState.SPEAKING && oldAiState == AiState.LISTENING && isAutoMode) {
                    debugLogRepo(TAG, "AUTO 模式：TTS 开始（从 LISTENING → SPEAKING），停止 encoder 防止回声")
                    audioService.stopRecording()
                    opusEncoder?.stop()
                    opusEncoder = null
                    weStartedRecording = false
                    // 不发送 abort，服务器知道我们在 listening 状态
                    _aiState.value = newAiState
                    return@collect
                }

                // AI 开始说话时，自动停止我们启动的录音（防止麦克风采集到 AI 自己的声音发往 STT）
                if (newAiState == AiState.SPEAKING && weStartedRecording && !(oldAiState == AiState.LISTENING && isAutoMode)) {
                    debugLogRepo(TAG, "AI 开始说话，自动停止录音以避免回声")
                    backendService.sendAbort("playback_start")
                    audioService.stopRecording()
                    audioService.abandonAudioFocus()
                    opusEncoder?.stop()
                    opusEncoder = null
                    backendService.sendListenStop()
                    weStartedRecording = false
                }

                // 更新状态
                _aiState.value = newAiState

                // AI 说完话回到 IDLE，且是 AUTO 模式 → 立刻重启监听
                if (newAiState == AiState.IDLE && oldAiState == AiState.SPEAKING && isAutoMode) {
                    debugLogRepo(TAG, "AI 说完话，AUTO 模式立即重启监听")
                    // 复用之前的 encoder（避免重新初始化延迟），或立即创建新的
                    var encoder = opusEncoder
                    if (encoder == null) {
                        val sampleRate = audioService.getSampleRate()
                        opusEncoder = OpusEncoder(sampleRate = sampleRate)
                        encoder = opusEncoder
                    }
                    if (encoder?.init { opusPacket -> scope.launch { sendAudio(opusPacket) } } == true) {
                        audioService.setOnSilenceDetected {
                            scope.launch { stopListening(userExplicitStop = false) }
                        }
                        audioService.startRecording({ pcmData ->
                            encoder.encode(pcmData)
                        }, enableVAD = true)
                        backendService.sendListenStart("auto")
                        _aiState.value = AiState.LISTENING
                        weStartedRecording = true
                        debugLogRepo(TAG, "AUTO 模式监听已重启")
                    } else {
                        debugLogRepoE(TAG, "AUTO 模式重启监听失败：Opus 编码器初始化失败")
                        opusEncoder = null
                        isAutoMode = false
                        _aiState.value = AiState.IDLE
                    }
                }
            }
        }

        // 当连接状态变化时管理解码器生命周期
        // 注意：不在 Connected 时初始化解码器，因为服务器 hello 还在路上
        // 解码器会在收到第一个音频包时延迟初始化（此时 hello 肯定已处理）
        scope.launch {
            backendService.connectionState.collect { state ->
                if (state is XiaoZhiBackendService.ConnectionState.Connected) {
                    val serverSampleRate = backendService.getServerSampleRate()
                    audioService.setSampleRate(serverSampleRate)
                    // 不在这初始化解码器，等 hello 收到后再初始化
                    debugLogRepo(TAG, "已连接，等待服务器hello...")
                } else if (state is XiaoZhiBackendService.ConnectionState.Disconnected ||
                           state is XiaoZhiBackendService.ConnectionState.Error) {
                    opusDecoder?.release()
                    opusDecoder = null
                    debugLogRepo(TAG, "连接断开，解码器已释放")
                }
            }
        }

        // 观察聊天消息流（stt/tts消息）
        scope.launch {
            backendService.chatMessages.collect { message ->
                debugLogRepo(TAG, "收到聊天消息: ${message.direction.name} - ${message.content.take(100)}")
                // STT回显的消息如果是自己刚发的，跳过（避免重复显示用户发送的消息）
                // 注意：STT的direction可能是SENT（用户说话）或RECEIVED（AI说话），两种都要检查
                if (message.type == MessageType.TEXT) {
                    synchronized(lastSentTextLock) {
                        if (message.content == lastSentText) {
                            debugLogRepo(TAG, "跳过STT回显的消息（与最后发送的消息相同）")
                            lastSentText = null
                            return@collect
                        }
                    }
                }
                messageRepository.addReceivedMessage(currentContactId, message)
            }
        }

        scope.launch {
            backendService.textMessage.collect { text ->
                debugLogRepo(TAG, "收到文本消息: ${text.take(100)}")
            }
        }

        scope.launch {
            backendService.audioData.collect { audioData ->
                if (audioData.isNotEmpty()) {
                    val t0 = System.nanoTime()
                    // 延迟初始化解码器：hello 肯定已在此时处理完
                    var decoder = opusDecoder
                    if (decoder == null) {
                        val serverRate = backendService.getServerSampleRate()
                        val serverCh = backendService.getServerChannels()
                        decoder = NativeOpusDecoder(serverRate, 16000, serverCh)
                        decoder.init()
                        opusDecoder = decoder
                        debugLogRepo(TAG, "Native Opus解码器初始化成功(${serverRate}Hz → 16kHz, ${serverCh}ch)")
                    }
                    decoder.decodePacket(audioData)
                    val dt = (System.nanoTime() - t0) / 1000
                    if (dt > 5000) {
                        debugLogRepoW(TAG, "audioData→decodePacket 耗时: ${dt}us, 包大小=${audioData.size}B")
                    }
                }
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit> {
        debugLogRepo(TAG, "ConnectionRepositoryImpl.connect() called: serverUrl=${config.serverUrl}, contactId=$contactId, deviceId=${config.deviceId}")
        
        // 互斥：连接新联系人前先断开旧连接
        if (_connectionState.value is ConnectionState.Connected || 
            _connectionState.value is ConnectionState.Connecting) {
            debugLogRepo(TAG, "切换联系人，断开旧连接: currentContactId=$currentContactId")
            backendService.disconnect()
            opusEncoder?.stop()
            opusEncoder = null
            opusDecoder?.release()
            opusDecoder = null
            _connectionState.value = ConnectionState.Disconnected
            _aiState.value = AiState.IDLE
        }
        
        currentContactId = contactId
        return try {
            _connectionState.value = ConnectionState.Connecting
            debugLogRepo(TAG, "ConnectionRepositoryImpl: calling backendService.connect()...")
            backendService.connect(config.serverUrl, config.otaUrl, config.deviceId, config.clientId, config.accessToken)
            debugLogRepo(TAG, "ConnectionRepositoryImpl: backendService.connect() returned successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            debugLogRepoE(TAG, "ConnectionRepositoryImpl.connect() failed: ${e.message}")
            _connectionState.value = ConnectionState.Error(e.message ?: "连接失败")
            Result.failure(e)
        }
    }
    
    override suspend fun disconnect() {
        backendService.disconnect()
        opusEncoder?.stop()
        opusEncoder = null
        opusDecoder?.release()
        opusDecoder = null
        _connectionState.value = ConnectionState.Disconnected
        _aiState.value = AiState.IDLE
    }
    
    override suspend fun sendTextMessage(contactId: String, text: String): Result<Unit> {
        currentContactId = contactId
        // 先添加到本地消息列表（用户发送）
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT, // 用户发送
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.addSentMessage(contactId, message)

        // 记录最后发送的消息，用于STT回显去重
        synchronized(lastSentTextLock) {
            lastSentText = text
        }

        // 发送文本消息到服务器
        backendService.sendTextContent(text)
        return Result.success(Unit)
    }
    
    override suspend fun sendAudio(audioData: ByteArray): Result<Unit> {
        return try {
            backendService.sendAudio(audioData)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun startListening(mode: String) {
        backendService.sendListenStart(mode)
        _aiState.value = AiState.LISTENING
        weStartedRecording = true
        isAutoMode = (mode == "auto")

        audioService.setOnSilenceDetected {
            scope.launch { stopListening(userExplicitStop = false) }
        }

        val sampleRate = audioService.getSampleRate()
        opusEncoder = OpusEncoder(sampleRate = sampleRate)
        val encoder = opusEncoder
        if (encoder?.init { opusPacket -> scope.launch { sendAudio(opusPacket) } } == true) {
            audioService.startRecording({ pcmData ->
                encoder.encode(pcmData)
            }, enableVAD = true)
        } else {
            debugLogRepoE(TAG, "Opus 编码器初始化失败")
            opusEncoder = null
            weStartedRecording = false
            isAutoMode = false
            _aiState.value = AiState.IDLE
        }
    }

    override suspend fun stopListening(userExplicitStop: Boolean) {
        if (weStartedRecording && userExplicitStop) {
            backendService.sendAbort("user_stop")
        }
        backendService.sendListenStop()

        // AUTO 模式 VAD 自然结束：只停录音和 encoder，保持状态等 TTS
        val isAutoStop = !userExplicitStop && isAutoMode
        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null
        weStartedRecording = false

        if (!isAutoStop) {
            audioService.abandonAudioFocus()
        }

        if (userExplicitStop) {
            isAutoMode = false
            _aiState.value = AiState.IDLE
        } else if (!isAutoMode) {
            _aiState.value = AiState.IDLE
        } else {
            debugLogRepo(TAG, "VAD auto-stop (AUTO 模式)，保持状态等待 TTS")
        }
        debugLogRepo(TAG, "stopListening: userExplicitStop=$userExplicitStop, isAutoMode=$isAutoMode, isAutoStop=$isAutoStop")
    }
}
