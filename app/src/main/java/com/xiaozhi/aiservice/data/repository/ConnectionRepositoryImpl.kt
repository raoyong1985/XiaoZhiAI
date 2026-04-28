package com.xiaozhi.aiservice.data.repository

import android.util.Log
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.audio.OpusEncoder
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.domain.repository.IncomingMessage
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ConnectionRepo"

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
    
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(replay = 0)
    override val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

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
                // 停止 encoder 防止回声，但保持 LISTENING 状态
                // ESP32 行为：VAD auto-stop 后保持 LISTENING → TTS 播放 → tts.state=stop → auto-restart
                if (newAiState == AiState.SPEAKING && oldAiState == AiState.LISTENING && isAutoMode) {
                    Log.i(TAG, "TTS 开始（AUTO 模式，LISTENING 状态），停止 encoder 防止回声")
                    audioService.stopRecording()
                    opusEncoder?.stop()
                    opusEncoder = null
                    weStartedRecording = false
                    // 保持 LISTENING 状态（不切 SPEAKING），等待 TTS 完成
                    // _aiState 不更新，状态维持 LISTENING
                    return@collect
                }

                // AI 开始说话时，自动停止我们启动的录音（防止麦克风采集到 AI 自己的声音发往 STT）
                // 注意：仅当 AI 从非 LISTENING 状态变为 SPEAKING 时处理（如 IDLE → SPEAKING）
                if (newAiState == AiState.SPEAKING && weStartedRecording) {
                    Log.i(TAG, "AI 开始说话，自动停止录音以避免回声")
                    backendService.sendAbort("playback_start")  // 告知服务器中断ASR
                    audioService.stopRecording()
                    audioService.abandonAudioFocus()
                    opusEncoder?.stop()
                    opusEncoder = null
                    backendService.sendListenStop()
                    weStartedRecording = false
                    // isAutoMode 保持不变，等待 AI 说完后自动重启
                }

                // AI 说完话回到 IDLE，且是 AUTO 模式 → 自动切换到倾听模式
                // ESP32 逻辑：tts.state=stop → SPEAKING → LISTENING → SendStartListening → 重启 encoder
                // Android 修复：tts.state=stop → SPEAKING → IDLE → auto-restart
                if (oldAiState == AiState.SPEAKING && newAiState == AiState.IDLE && isAutoMode) {
                    Log.i(TAG, "AI 说完话，AUTO 模式自动切换到倾听（延迟 1s 等待 AudioTrack 缓冲排空）")
                    scope.launch {
                        // 延迟 1s：等待 AudioTrack 缓冲排空，避免 TTS 音频与新录音重叠
                        kotlinx.coroutines.delay(1000)
                        val sampleRate = 16000
                        opusEncoder = OpusEncoder(sampleRate = sampleRate)
                        val encoder = opusEncoder
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
                            Log.i(TAG, "AUTO 模式监听已重启")
                        } else {
                            Log.e(TAG, "AUTO 模式重启监听失败：Opus 编码器初始化失败")
                            opusEncoder = null
                            isAutoMode = false
                        }
                    }
                    // 不更新 _aiState（维持 IDLE 直到 auto-restart 完成）
                } else {
                    _aiState.value = newAiState
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
                    Log.i(TAG, "已连接，等待服务器hello...")
                } else if (state is XiaoZhiBackendService.ConnectionState.Disconnected ||
                           state is XiaoZhiBackendService.ConnectionState.Error) {
                    opusDecoder?.release()
                    opusDecoder = null
                    Log.i(TAG, "连接断开，解码器已释放")
                }
            }
        }

        // 观察聊天消息流（stt/tts消息）
        scope.launch {
            backendService.chatMessages.collect { message ->
                Log.i(TAG, "收到聊天消息: ${message.direction.name} - ${message.content}")
                // STT回显的消息如果是自己刚发的，跳过（避免重复显示用户发送的消息）
                // 注意：STT的direction可能是SENT（用户说话）或RECEIVED（AI说话），两种都要检查
                if (message.type == MessageType.TEXT) {
                    synchronized(lastSentTextLock) {
                        if (message.content == lastSentText) {
                            Log.i(TAG, "跳过STT回显的消息（与最后发送的消息相同）: ${message.content}")
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
                Log.i(TAG, "收到文本消息: $text")
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
                        Log.i(TAG, "Native Opus解码器初始化成功(${serverRate}Hz → 16kHz, ${serverCh}ch)")
                    }
                    decoder.decodePacket(audioData)
                    val dt = (System.nanoTime() - t0) / 1000
                    if (dt > 5000) {
                        Log.w(TAG, "audioData→decodePacket 耗时: ${dt}us, 包大小=${audioData.size}B")
                    }
                }
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit> {
        Log.i(TAG, "ConnectionRepositoryImpl.connect() called: serverUrl=${config.serverUrl}, contactId=$contactId, deviceId=${config.deviceId}")
        
        // 互斥：连接新联系人前先断开旧连接
        if (_connectionState.value is ConnectionState.Connected || 
            _connectionState.value is ConnectionState.Connecting) {
            Log.i(TAG, "切换联系人，断开旧连接: currentContactId=$currentContactId")
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
            Log.i(TAG, "ConnectionRepositoryImpl: calling backendService.connect()...")
            backendService.connect(config.serverUrl, config.otaUrl, config.deviceId, config.clientId, config.accessToken)
            Log.i(TAG, "ConnectionRepositoryImpl: backendService.connect() returned successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "ConnectionRepositoryImpl.connect() failed: ${e.message}")
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
        weStartedRecording = true  // 标记为我们启动的录音，AI 说话时自动停止
        isAutoMode = (mode == "auto")  // AUTO 模式下 AI 说完话自动重启监听
        
        // 设置静音自动停止回调（VAD 自然结束，不发 abort）
        audioService.setOnSilenceDetected {
            scope.launch {
                stopListening(userExplicitStop = false)
            }
        }
        
        // 启动 Opus 编码器（16kHz，与 AudioService 录音采样率一致）
        val sampleRate = 16000
        opusEncoder = OpusEncoder(sampleRate = sampleRate)
        val encoder = opusEncoder
        if (encoder?.init { opusPacket -> scope.launch { sendAudio(opusPacket) } } == true) {
            // 启动录音，PCM 数据送入 Opus 编码器（启用 VAD 静音检测）
            audioService.startRecording({ pcmData ->
                encoder.encode(pcmData)
            }, enableVAD = true)
        } else {
            Log.e(TAG, "Opus 编码器初始化失败")
            opusEncoder = null
        }
    }
    
    override suspend fun stopListening(userExplicitStop: Boolean) {
        if (weStartedRecording && userExplicitStop) {
            backendService.sendAbort("user_stop")  // 仅用户主动按按钮才发中断
        }
        backendService.sendListenStop()

        // AUTO 模式：VAD 自然结束时不放弃音频焦点、不清空 encoder
        // 保持 LISTENING 状态等待 TTS 音频到达，ESP32 行为参考：
        // VAD auto-stop → sendListenStop → 保持 LISTENING → TTS 播放 → tts.state=stop → auto-restart
        // 这样 AI 的 TTS 音频能正常接收，避免音频通道提前关闭导致 TTS 丢失
        val isAutoStop = !userExplicitStop && isAutoMode
        if (!isAutoStop) {
            audioService.stopRecording()
            audioService.abandonAudioFocus()
            opusEncoder?.stop()
            opusEncoder = null
            weStartedRecording = false
        } else {
            // AUTO 模式：停止 encoder 和录音，但保持 LISTENING 状态
            // encoder 在 TTS 开始时再停（见 aiState observer 中 LISTENING → SPEAKING 处理）
            audioService.stopRecording()
            // 不 abandonAudioFocus，不清空 encoder（TTS 音频可能还在播放）
            opusEncoder?.stop()
            opusEncoder = null
            weStartedRecording = false
            Log.i(TAG, "VAD auto-stop，暂不切 IDLE（等待 TTS 完成）")
        }

        // AUTO 模式：VAD 自然结束不重置 isAutoMode，AI 说完话继续自动倾听
        // 只有用户主动按停止按钮才退出 AUTO 自动循环
        if (userExplicitStop) {
            isAutoMode = false
        }

        // AUTO 模式保持 LISTENING 状态（等 TTS 完成）
        // 非 AUTO 模式才切 IDLE
        if (!isAutoStop) {
            _aiState.value = AiState.IDLE
        }
        Log.i(TAG, "stopListening: userExplicitStop=$userExplicitStop, isAutoMode=$isAutoMode, isAutoStop=$isAutoStop")
    }
}
