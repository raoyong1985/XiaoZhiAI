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
    
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(replay = 0)
    override val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    // 记录最后发送的消息内容，用于STT回显去重
    private var lastSentText: String? = null
    private val lastSentTextLock = Any()

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
                _aiState.value = when (state.lowercase()) {
                    "listening" -> AiState.LISTENING
                    "speaking" -> AiState.SPEAKING
                    "processing" -> AiState.PROCESSING
                    else -> AiState.IDLE
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
                messageRepository.addReceivedMessage(message)
            }
        }

        scope.launch {
            backendService.textMessage.collect { text ->
                Log.i(TAG, "收到文本消息: $text")
                if (text.isNotEmpty()) {
                    // 旧的textMessage流程保留，但已经不被使用了
                }
            }
        }

        scope.launch {
            backendService.audioData.collect { audioData ->
                if (audioData.isNotEmpty()) {
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
                }
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig): Result<Unit> {
        return try {
            _connectionState.value = ConnectionState.Connecting
            backendService.connect(config.serverUrl, config.deviceId, config.clientId, config.accessToken ?: "")
            Result.success(Unit)
        } catch (e: Exception) {
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
    
    override suspend fun sendTextMessage(text: String): Result<Unit> {
        // 先添加到本地消息列表（用户发送）
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT, // 用户发送
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.addSentMessage(message)

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
    
    override suspend fun startListening() {
        backendService.sendListenStart("realtime")
        _aiState.value = AiState.LISTENING
        
        // 设置静音自动停止回调
        audioService.setOnSilenceDetected {
            scope.launch {
                stopListening()
            }
        }
        
        // 启动 Opus 编码器
        val sampleRate = 16000
        audioService.requestAudioFocus()  // 录音前请求焦点
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
    
    override suspend fun stopListening() {
        backendService.sendListenStop()
        audioService.stopRecording()
        audioService.abandonAudioFocus()  // 放弃焦点
        opusEncoder?.stop()
        opusEncoder = null
        _aiState.value = AiState.IDLE
        Log.i(TAG, "录音已停止")
    }
}
