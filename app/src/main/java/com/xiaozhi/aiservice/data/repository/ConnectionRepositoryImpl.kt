package com.xiaozhi.aiservice.data.repository

import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.AudioRecorder
import com.xiaozhi.aiservice.data.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.data.audio.WakeWordDetector
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.VoiceMode  // 复用 Models.kt，与 ESP32 ListeningMode 对应
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.domain.repository.DeviceState  // 设备状态机（定义在 Repositories.kt）
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    
    // 记录最后发送的消息内容，用于STT回显去重
    private var lastSentText: String? = null
    private val lastSentTextLock = Any()

    // AI 最后说的话（RECEIVED 消息），用于回声检测
    private var lastTtsText: String = ""

    // 当前联系人ID，用于消息路由
    var currentContactId: String = ""

    // 跟踪我们自己启动的录音（用于 AI 说话时自动停止，避免回声）
    private var weStartedRecording = false
    // 跟踪是否处于 AUTO 模式（AI 说完话后自动切回倾听）
    private var isAutoMode = false
    // 当前监听模式：auto / manual / realtime（参考 ESP32 ListeningMode）
    private var listeningMode: VoiceMode = VoiceMode.REALTIME
    // 文本输入激活中：发送文本后不自动重启语音监听
    private var textInputActive = false
    // 等待文本消息回复（参考对方 Completer 模式）
    private var pendingTextResponse: CompletableDeferred<Unit>? = null
    // 唤醒词已启用
    private var wakeWordEnabled = false

    // 设备状态（参考 ESP32 device_state_machine.cc）
    // 使用 internal 可见性让 MCP 工具能访问，同时对接口暴露为 StateFlow
    private val _deviceState = MutableStateFlow(DeviceState.UNKNOWN)
    override val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    // Opus 解码器（native libopus + STREAM_MUSIC AudioTrack）
    private val playbackManager = AudioPlaybackManager()
    private val audioRecorder = AudioRecorder(audioService)

    // 状态机互斥锁：防止 VAD 回调与 WebSocket 消息并发修改状态
    private val stateMutex = Mutex()

    init {
        observeBackendService()
    }

    // 将后端连接状态映射到领域层连接状态
    private fun XiaoZhiBackendService.ConnectionState.toDomainState(): ConnectionState = when (this) {
        is XiaoZhiBackendService.ConnectionState.Connected -> ConnectionState.Connected
        is XiaoZhiBackendService.ConnectionState.Connecting -> ConnectionState.Connecting
        is XiaoZhiBackendService.ConnectionState.Error -> ConnectionState.Error("连接错误")
        is XiaoZhiBackendService.ConnectionState.Disconnected -> ConnectionState.Disconnected
    }

    private fun observeBackendService() {
        scope.launch {
            backendService.connectionState.collect { state ->
                _connectionState.value = state.toDomainState()
            }
        }

        // 参考 ESP32 HandleStateChangedEvent(Idle): 统一在 IDLE 状态重启唤醒词
        // 替代分散在各处的 startWakeWordMode() 调用，确保所有回到 IDLE 的路径都触发
        scope.launch {
            _deviceState.collect { state ->
                if (state == DeviceState.IDLE) {
                    startWakeWordMode()
                }
            }
        }

        scope.launch {
            backendService.aiState.collect { state ->
                stateMutex.withLock {
                    val newAiState = when (state.lowercase()) {
                        "listening" -> AiState.LISTENING
                        "speaking" -> AiState.SPEAKING
                        "processing" -> AiState.PROCESSING
                        else -> AiState.IDLE
                    }
                    val oldAiState = _aiState.value

                    // AUTO 模式：TTS 开始时如果还在 LISTENING 状态（VAD auto-stop 后等待 TTS）
                    if (newAiState == AiState.SPEAKING && oldAiState == AiState.LISTENING && isAutoMode) {
                        Logger.i(TAG, "AUTO 模式：TTS 开始（从 LISTENING → SPEAKING），停止 encoder 防止回声")
                        _deviceState.value = DeviceState.SPEAKING
                        audioRecorder.stop()
                        weStartedRecording = false
                        // 不 flush 解码器——AUTO 模式进入 SPEAKING 时前一轮音频已播完，
                        // 解码队列已空，flush AudioTrack 会清空硬件缓冲导致首包延迟输出
                        // 不发送 abort，服务器知道我们在 listening 状态
                        _aiState.value = newAiState
                        return@withLock
                    }

                    // AI 开始说话时，自动停止我们启动的录音（防止麦克风采集到 AI 自己的声音发往 STT）
                    // 注意：不发送 abort/listenStop，服务器已知自己在说话，abort 会打断 AI 回复
                    if (newAiState == AiState.SPEAKING && weStartedRecording && !(oldAiState == AiState.LISTENING && isAutoMode)) {
                        Logger.i(TAG, "AI 开始说话，自动停止录音以避免回声（保留 AI 回复）")
                        audioRecorder.stop()
                        audioService.abandonAudioFocus()
                        weStartedRecording = false
                    }

                    // 参考 ESP32 TransitionTo: 先设状态再执行操作
                    // 统一在这里设 _deviceState，替代分散在各条件分支中设置
                    // 确保文本模式（weStartedRecording=false）也能正确更新设备状态
                    if (newAiState == AiState.SPEAKING && oldAiState != AiState.SPEAKING) {
                        _deviceState.value = DeviceState.SPEAKING
                    }

                    // 更新状态
                    _aiState.value = newAiState

                    // ESP32 HandleStateChangedEvent(Speaking): ResetDecoder() 清空残留音频队列
                    if (newAiState == AiState.SPEAKING && oldAiState != AiState.SPEAKING) {
                        playbackManager.flushSource("main")
                        // 参考对方 Completer 模式：AI 开始说话 → 文本消息回复确认
                        pendingTextResponse?.complete(Unit)
                        pendingTextResponse = null
                    }

                    // AUTO 模式：AI 说完话回到 IDLE → 等播放排空后重启监听
                    // 文本模式下(textInputActive=true)不自动重启（参考 ESP32: 非 REALTIME 走 IDLE）
                    if (newAiState == AiState.IDLE && oldAiState == AiState.SPEAKING && isAutoMode) {
                        if (textInputActive) {
                            Logger.i(TAG, "AUTO 模式：文本输入中，不重启监听，启动唤醒词检测")
                            _deviceState.value = DeviceState.IDLE
                            audioService.abandonAudioFocus()
                            weStartedRecording = false
                            startWakeWordMode()
                            return@withLock
                        }
                        Logger.i(TAG, "AUTO 模式：AI 说完话，等待播放排空后重启监听")
                        restartListeningAfterSpeaking("auto", enableVAD = true)
                    }

                    // Manual Stop 模式：TTS 结束后回到 IDLE，不自动重启监听，等待用户手动触发
                    // 参考 ESP32 kListeningModeManualStop 行为
                    if (newAiState == AiState.IDLE && oldAiState == AiState.SPEAKING && listeningMode == VoiceMode.MANUAL) {
                        Logger.i(TAG, "Manual Stop 模式：TTS 结束，保持 IDLE，启动唤醒词检测")
                        _deviceState.value = DeviceState.IDLE
                        audioService.abandonAudioFocus()
                        isAutoMode = false
                        weStartedRecording = false
                        startWakeWordMode()
                    }

                    // Realtime 模式：TTS 结束后自动重启监听（使用 realtime 参数）
                    if (newAiState == AiState.IDLE && oldAiState == AiState.SPEAKING && listeningMode == VoiceMode.REALTIME) {
                        if (textInputActive) {
                            Logger.i(TAG, "Realtime 模式：文本输入中，不重启监听")
                            _deviceState.value = DeviceState.IDLE
                            audioService.abandonAudioFocus()
                            weStartedRecording = false
                            return@withLock
                        }
                        Logger.i(TAG, "Realtime 模式：AI 说完话，等待播放排空后重启监听（启用VAD防回声）")
                        restartListeningAfterSpeaking("realtime", enableVAD = true)
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
                    Logger.i(TAG, "已连接，等待服务器hello...")

                    // 连接建立后，在 IDLE 状态启动唤醒词检测
                    // 延迟启动避免与初始化流程竞争
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        startWakeWordMode()
                    }
                } else if (state is XiaoZhiBackendService.ConnectionState.Disconnected ||
                           state is XiaoZhiBackendService.ConnectionState.Error) {
                    playbackManager.releaseAll()
                    Logger.i(TAG, "连接断开，解码器已释放")
                    // 断开后唤醒词已经在 disconnect() 中启动，但如果在 observeBackendService 外断开（如后端主动断开），此处兜底
                    startWakeWordMode()
                }
            }
        }

        // 观察聊天消息流（stt/tts消息）
        scope.launch {
            backendService.chatMessages.collect { message ->
                Logger.i(TAG, "收到聊天消息: ${message.direction.name} - ${message.content.take(100)}")
                // STT回显的消息如果是自己刚发的，跳过（避免重复显示用户发送的消息）
                // 注意：STT的direction可能是SENT（用户说话）或RECEIVED（AI说话），两种都要检查
                if (message.type == MessageType.TEXT) {
                    // 1. 用户文本发送回显去重
                    synchronized(lastSentTextLock) {
                        if (message.content == lastSentText) {
                            Logger.i(TAG, "跳过STT回显的消息（与最后发送的消息相同）")
                            lastSentText = null
                            return@collect
                        }
                    }
                    // 2. 回声检测：SENT 消息如果和 AI 刚说的话重叠，判定为扬声器回声
                    if (message.direction == MessageDirection.SENT && lastTtsText.isNotEmpty()) {
                        if (isEcho(message.content, lastTtsText)) {
                            Logger.i(TAG, "检测到回声（\"${message.content.take(30)}\" ≈ \"${lastTtsText.take(30)}\"），跳过")
                            return@collect
                        }
                    }
                    // 3. 记录 AI 说的话（RECEIVED），用于下一轮回声检测
                    if (message.direction == MessageDirection.RECEIVED) {
                        lastTtsText = message.content
                    }
                }
                messageRepository.addReceivedMessage(currentContactId, message)
            }
        }

        scope.launch {
            backendService.audioData.collect { audioData ->
                // 音频数据仅由服务器在 SPEAKING 状态发送，且 WebSocket 单线程保证
                // tts {state:start} 先于二进制音频帧到达，不要加 _aiState 检查——协程间 StateFlow
                // 收集有调度延迟，会导致首几包被丢弃，引起语音延迟
                if (audioData.isEmpty()) return@collect
                val t0 = System.nanoTime()
                val serverRate = backendService.getServerSampleRate()
                val serverCh = backendService.getServerChannels()
                val volume = AudioSettings.getOutputVolume(AppContainer.context)
                playbackManager.decodeAndPlay("main", serverRate, serverCh, audioData, volume)
                val dt = (System.nanoTime() - t0) / 1000
                if (dt > 5000) {
                    Logger.w(TAG, "audioData→decodePacket 耗时: ${dt}us, 包大小=${audioData.size}B")
                }
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit> {
        Logger.i(TAG, "ConnectionRepositoryImpl.connect() called: serverUrl=${config.serverUrl}, contactId=$contactId, deviceId=${config.deviceId}")

        // 互斥：连接新联系人前先断开旧连接
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            Logger.i(TAG, "切换联系人，断开旧连接: currentContactId=$currentContactId")
            backendService.disconnect()
            audioRecorder.stop()
            playbackManager.releaseAll()
            _connectionState.value = ConnectionState.Disconnected
            _aiState.value = AiState.IDLE
        }

        currentContactId = contactId
        // 设置设备状态为 CONNECTING（参考 ESP32 kDeviceStateConnecting）
        _deviceState.value = DeviceState.CONNECTING

        // 初始化设备标识（从持久化存储读取，参考 ESP32 settings.cc）
        AppContainer.initDeviceIds(AppContainer.context)

        return try {
            _connectionState.value = ConnectionState.Connecting
            Logger.i(TAG, "ConnectionRepositoryImpl: calling backendService.connect()...")
            backendService.connect(config.serverUrl, config.otaUrl, config.deviceId, config.clientId, config.accessToken)
            Logger.i(TAG, "ConnectionRepositoryImpl: backendService.connect() returned successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "ConnectionRepositoryImpl.connect() failed: ${e.message}")
            _connectionState.value = ConnectionState.Error(e.message ?: "连接失败")
            Result.failure(e)
        }
    }
    
    override suspend fun disconnect() {
        backendService.disconnect()
        audioRecorder.stop()
        playbackManager.releaseAll()
        _connectionState.value = ConnectionState.Disconnected
        _aiState.value = AiState.IDLE
        // 断开后重启唤醒词检测
        startWakeWordMode()
    }
    
    override suspend fun sendTextMessage(contactId: String, text: String): Result<Unit> {
        currentContactId = contactId
        // 先检查连接状态
        if (_connectionState.value !is ConnectionState.Connected) {
            Logger.w(TAG, "sendTextMessage: 未连接，消息未发送: $text")
            return Result.failure(Exception("未连接到服务器"))
        }
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
        // 文本模式下禁止自动重启语音监听（参考 ESP32: listening_mode != REALTIME 时 speaking 后走 IDLE）
        textInputActive = true
        // 参考对方 Completer 模式：等待 AI 开始回复，超时 15 秒不阻塞用户体验
        val deferred = CompletableDeferred<Unit>()
        pendingTextResponse = deferred
        scope.launch {
            withTimeoutOrNull(15000L) { deferred.await() }
            if (pendingTextResponse != null) {
                Logger.w(TAG, "sendTextMessage: 服务器未在15秒内回复（可能静默处理成功）")
                pendingTextResponse = null
            }
        }
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
    
    /**
     * 初始化并启动唤醒词模式
     * 对应 ESP32 EnableWakeWordDetection(true)
     * 在 IDLE 状态时运行，监听唤醒词后自动切换到语音模式
     */
    fun startWakeWordMode() {
        if (wakeWordEnabled) return
        if (_aiState.value != AiState.IDLE) return

        // 设置唤醒检测回调（唤醒→启动语音模式）
        audioService.setOnWakeWordDetected { wakeWord ->
            Logger.i(TAG, "🎤 检测到唤醒词「$wakeWord」，启动语音模式")
            wakeWordEnabled = false
            scope.launch {
                startListening("realtime")
            }
        }

        if (audioService.startWakeWordDetection()) {
            wakeWordEnabled = true
            Logger.i(TAG, "唤醒词模式已启动，说「${WakeWordDetector.DEFAULT_KEYWORD}」唤醒")
        }
    }

    /**
     * 停止唤醒词模式
     * 对应 ESP32 EnableWakeWordDetection(false)
     */
    fun stopWakeWordMode() {
        if (!wakeWordEnabled) return
        wakeWordEnabled = false
        audioService.stopWakeWordDetection()
        Logger.i(TAG, "唤醒词模式已停止")
    }

    override suspend fun startListening(mode: String) {
        // 停止唤醒词检测（录音冲突）
        stopWakeWordMode()

        stateMutex.withLock {
            // 用户开始语音输入，关闭文本模式标记
            textInputActive = false
            // 解析并保存监听模式（参考 ESP32 ListeningMode）
            listeningMode = VoiceMode.fromServerValue(mode)
            isAutoMode = (mode == "auto")

            backendService.sendListenStart(mode)
            _aiState.value = AiState.LISTENING
            weStartedRecording = true

            // 更新设备状态（参考 ESP32 kDeviceStateListening）
            _deviceState.value = DeviceState.LISTENING

            // Realtime 模式：禁用 VAD 自动停止（持续录音流）
            // 注意：Realtime 模式需要 AEC 支持才能在说话时继续录音
            val enableVAD = listeningMode != VoiceMode.REALTIME

            if (!audioRecorder.start(
                    scope = scope,
                    enableVAD = enableVAD,
                    onOpusPacket = { packet -> scope.launch { sendAudio(packet) } },
                    onSilenceDetected = { scope.launch { stopListening(userExplicitStop = false) } }
                )) {
                Logger.e(TAG, "录音启动失败")
                weStartedRecording = false
                isAutoMode = false
                _aiState.value = AiState.IDLE
            }
        }
    }

    override suspend fun stopListening(userExplicitStop: Boolean) {
        stateMutex.withLock {
            if (weStartedRecording && userExplicitStop) {
                backendService.sendAbort("user_stop")
            }
            backendService.sendListenStop()

            // AUTO 模式 VAD 自然结束：只停录音和 encoder，保持状态等 TTS
            val isAutoStop = !userExplicitStop && isAutoMode
            audioRecorder.stop()
            weStartedRecording = false

            if (!isAutoStop) {
                audioService.abandonAudioFocus()
            }

            if (userExplicitStop) {
                isAutoMode = false
                _aiState.value = AiState.IDLE
                // 用户手动停止后重启唤醒词检测
                startWakeWordMode()
            } else if (!isAutoMode) {
                _aiState.value = AiState.IDLE
                // 非 AUTO 模式回到 IDLE 后重启唤醒词
                startWakeWordMode()
            } else {
                Logger.i(TAG, "VAD auto-stop (AUTO 模式)，保持状态等待 TTS")
            }
            Logger.i(TAG, "stopListening: userExplicitStop=$userExplicitStop, isAutoMode=$isAutoMode, isAutoStop=$isAutoStop")
        }
    }

    /**
     * 自然排空播放缓冲 + 硬件延迟补偿
     * 参考 ESP32 HandleStateChangedEvent:
     *   WaitForPlaybackQueueEmpty() + EnableVoiceProcessing(true) → ResetDecoder()
     *
     * ⚠️ 不强制 flush AudioTrack！只等待解码队列自然排空 + 硬件缓冲补偿。
     * 以前用 flushAudio() 直接 pause+flush 会截断最后一段音频（话说到一半中断）。
     * NativeOpusDecoder 播放线程在队列空后持续写入 silence 帧，所以不能等
     * getRemainingBytes()==0，只需等 audioQueue 清空即可。AEC 会处理残余播放。
     *
     * 用户主动打断时才用 forceFlushPlayback() 强行停止。
     */

    /**
     * 共享方法：AI 说完后重启监听（消除 AUTO/REALTIME 重复代码）
     */
    private suspend fun restartListeningAfterSpeaking(mode: String, enableVAD: Boolean) {
        _deviceState.value = DeviceState.IDLE
        drainAndFlushPlayback()
        if (audioService.isAecSupported()) {
            audioService.setAecEnabled(true)
        }
        if (!audioRecorder.start(
                scope = scope,
                enableVAD = enableVAD,
                onOpusPacket = { packet -> scope.launch { sendAudio(packet) } },
                onSilenceDetected = { scope.launch { stopListening(userExplicitStop = false) } }
            )) {
            Logger.e(TAG, "${mode} 模式重启监听失败")
            _aiState.value = AiState.IDLE
            return
        }
        backendService.sendListenStart(mode)
        _aiState.value = AiState.LISTENING
        _deviceState.value = DeviceState.LISTENING
        weStartedRecording = true
        Logger.i(TAG, "${mode} 模式监听已重启")
    }

    private suspend fun drainAndFlushPlayback() {
        // ESP32 WaitForPlaybackQueueEmpty: 等软件队列自然排空
        val hadQueueData = playbackManager.waitForQueueEmpty("main", 1000)
        // 等 AudioTrack 硬件缓冲排空（适度等待，文本回声滤波器兜底）
        val hadHardware = playbackManager.waitForHardwareDrain("main", 1500)
        Logger.i(TAG, "drainAndFlushPlayback 完成（软件队列${if (hadQueueData) "空" else "超时"}，硬件${if (hadHardware) "已排空" else "超时"}）")
    }

    /**
     * 回声检测：判断 SENT 消息是否是扬声器播出的 AI 话语被麦克风采集回头。
     *
     * 策略：
     * 1. 短文本（<4字）跳过——用户简短回复"好的""嗯"是正常交互，不是回声
     * 2. 长文本用用户文字在 TTS 中的覆盖率判定（用户说的所有字都在 AI 话里 = 回声）
     */
    private fun isEcho(userText: String, ttsText: String): Boolean {
        if (userText.isEmpty() || ttsText.isEmpty()) return false
        // 短文本（1-3字）跳过回声过滤——用户简短应答是正常交互
        val textChars = userText.filter { it.isLetter() }
        if (textChars.length < 4) return false
        val userChars = textChars.toSet()
        val ttsChars = ttsText.filter { it.isLetter() }.toSet()
        if (userChars.isEmpty()) return false
        val overlap = userChars.intersect(ttsChars).size.toFloat()
        // 用覆盖度：用户说的字有多少在 AI 的话里
        val coverage = overlap / userChars.size
        return coverage >= 0.75f  // ≥75% 的字重叠 = 回声
    }

    /** 更新私聊解码器音量（由 set_xiaozhi_volume MCP 工具调用） */
    fun setVolume(level: Int) {
        playbackManager.setVolume(level)
    }
}
