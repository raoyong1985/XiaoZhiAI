package com.xiaozhi.aiservice.data.repository

import android.content.Context
import android.os.PowerManager
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.AudioRecorder
import com.xiaozhi.aiservice.data.audio.OpusEncoder
import com.xiaozhi.aiservice.data.audio.WakeTemplateMatcher
import com.xiaozhi.aiservice.data.logging.AndroidLogger
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager

import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.ConnectionConfig
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.VoiceMode  // 复用 Models.kt，与 ESP32 ListeningMode 对应
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.DeviceState
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConnectionRepo"

@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val backendService: XiaoZhiBackendService,
    private val audioService: AudioService,
    private val messageRepository: MessageRepository,
    private val playbackManager: AudioPlaybackManager,
    private val contactsRepository: ContactsRepository,
    @ApplicationContext private val context: Context
) : ConnectionRepository {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionState = MutableStateFlow<BotConnectionState>(BotConnectionState.Disconnected)
    override val connectionState: StateFlow<BotConnectionState> = _connectionState.asStateFlow()
    
    private val _aiState = MutableStateFlow(AiState.IDLE)
    override val aiState: StateFlow<AiState> = _aiState.asStateFlow()

    // AI 说话时持唤醒锁，阻止黑屏休眠
    private val wakeLock: PowerManager.WakeLock? = run {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "XiaoZhi:BotSpeaking")
    }

    init {
        // AI 说话/停止时自动管理唤醒锁
        // 用 collectLatest：state 变化时取消当前块，新 state 立即接管
        // 内部 while + delay(25s) 循环续期，避免 30s 超时后 state 没变导致锁丢失
        scope.launch {
            _aiState.collectLatest { state ->
                if (state == AiState.SPEAKING) {
                    // 循环续期：每 20s 重新 acquire，state 变化时 collectLatest 取消本块
                    // ⚠️ 必须用 try/finally：collectLatest 取消时 delay 抛 CancellationException，
                    // 没有 finally 的锁释放会永不被执行，导致唤醒锁一直持有
                    try {
                        while (true) {
                            if (wakeLock?.isHeld != true) {
                                wakeLock?.acquire(30_000L)
                            }
                            delay(20_000L)
                        }
                    } finally {
                        if (wakeLock?.isHeld == true) wakeLock.release()
                    }
                } else {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        }
    }

    override val emotion: StateFlow<String> = backendService.emotion

    override val alert: SharedFlow<String> = backendService.alert
    // 流式消息结束信号（用于关闭流式动画）
    override val streamingEnd: SharedFlow<String> = backendService.streamingEnd


    // 记录最后一条用户消息，用于 sentence_end 时配对存储
    @Volatile
    private var lastUserMessageForMemory: String = ""
    
    // 记录最后发送的消息内容，用于STT回显去重
    private var lastSentText: String? = null
    private val lastSentTextLock = Any()

    // AI 最后说的话（RECEIVED 消息），用于回声检测
    @Volatile
    private var lastTtsText: String = ""
    // AI 最后一次说话结束时间（毫秒），用于唤醒启动冷却
    @Volatile
    private var lastSpeakingEndMs = 0L

    /**
     * 群聊接龙活跃中时，ConnectionRepositoryImpl 不应自动重启录音或唤醒检测。
     * 由 GroupChatViewModel 在 connect/disconnect 时设置。
     */
    @Volatile
    override var suppressAutoListening = false

    // 当前联系人 ID，用于消息路由
    @Volatile
    private var _currentContactId: String = ""
    override val currentContactId: String get() = _currentContactId

    // 本地唤醒模板匹配器
    val wakeTemplateMatcher = WakeTemplateMatcher()

    // 跟踪我们自己启动的录音（用于 AI 说话时自动停止，避免回声）
    private var weStartedRecording = false
    // 跟踪是否处于 AUTO 模式（AI 说完话后自动切回倾听）
    private var isAutoMode = false
    // 当前监听模式：auto / manual / realtime（参考 ESP32 ListeningMode）
    private var listeningMode: VoiceMode = VoiceMode.REALTIME
    // 文本输入激活中：发送文本后不自动重启语音监听
    private var textInputActive = false
    // 等待文本消息回复（参考对方 Completer 模式）
    @Volatile
    private var pendingTextResponse: CompletableDeferred<Unit>? = null

    // 设备状态（参考 ESP32 device_state_machine.cc）
    // 使用 internal 可见性让 MCP 工具能访问，同时对接口暴露为 StateFlow
    private val _deviceState = MutableStateFlow(DeviceState.UNKNOWN)
    override val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    // 唤醒词模式启动 Job，防止多次快速重连时重复启动
    private var wakeWordStartupJob: Job? = null

    // Opus 解码器（native libopus + STREAM_MUSIC AudioTrack）
    private val audioRecorder = AudioRecorder(audioService)

    // 状态机互斥锁：防止 VAD 回调与 WebSocket 消息并发修改状态
    private val stateMutex = Mutex()
    // 录音模式互斥锁：唤醒模式 vs 直播模式，阻止竞态
    private val recordingLock = Any()

    init {
        observeBackendService()
        loadWakeTemplatesFromStore()
    }

    private fun loadWakeTemplatesFromStore() {
        scope.launch {
            contactsRepository.getWakeTemplates().collect { templates ->
                wakeTemplateMatcher.clear()
                templates.forEach { (contactId, base64) ->
                    wakeTemplateMatcher.addTemplate(contactId, base64, contactId)
                }
                Logger.i(TAG, "已加载 ${wakeTemplateMatcher.templateCount()} 个唤醒模板到本地匹配器")
            }
        }
    }

    override fun reloadWakeTemplates() {
        // collect 会自动同步 DataStore 变更，无需手动加载
    }

    private fun observeBackendService() {
        scope.launch {
            backendService.connectionState.collect { state ->
                _connectionState.value = state
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
                        audioRecorder.stop("main")
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
                        audioRecorder.stop("main")
                        audioService.abandonAudioFocus()
                        weStartedRecording = false
                    }

                    // 参考 ESP32 TransitionTo: 先设状态再执行操作
                    // 统一在这里设 _deviceState，替代分散在各条件分支中设置
                    // 确保文本模式（weStartedRecording=false）也能正确更新设备状态
                    if (newAiState == AiState.SPEAKING && oldAiState != AiState.SPEAKING) {
                        // ⚠️ 无条件停止录音（唤醒模式 + 对话模式），防止 TTS 回声被捕获触发唤醒
                        audioRecorder.stop("main")
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
                        if (suppressAutoListening) {
                            Logger.i(TAG, "AUTO 模式：群聊活跃中，不重启监听")
                            return@withLock
                        }
                        lastSpeakingEndMs = System.currentTimeMillis()
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
                        if (suppressAutoListening) { Logger.i(TAG, "MANUAL 模式：群聊活跃中，跳过"); return@withLock }
                        lastSpeakingEndMs = System.currentTimeMillis()
                        Logger.i(TAG, "Manual Stop 模式：TTS 结束，保持 IDLE，启动唤醒词检测")
                        _deviceState.value = DeviceState.IDLE
                        audioService.abandonAudioFocus()
                        isAutoMode = false
                        weStartedRecording = false
                        startWakeWordMode()
                    }

                    // Realtime 模式：TTS 结束后自动重启监听（使用 realtime 参数）
                    if (newAiState == AiState.IDLE && oldAiState == AiState.SPEAKING && listeningMode == VoiceMode.REALTIME) {
                        if (suppressAutoListening) { Logger.i(TAG, "REALTIME 模式：群聊活跃中，跳过"); return@withLock }
                        lastSpeakingEndMs = System.currentTimeMillis()
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
                if (state is BotConnectionState.Connected) {
                    val serverSampleRate = backendService.getServerSampleRate()
                    audioService.setSampleRate(serverSampleRate)
                    // 不在这初始化解码器，等 hello 收到后再初始化
                    Logger.i(TAG, "已连接，等待服务器hello...")

                    // 连接建立后，在 IDLE 状态启动唤醒词检测
                    // 延迟启动避免与初始化流程竞争
                    scheduleWakeWordStartup(3000)
                } else if (state is BotConnectionState.Disconnected ||
                           state is BotConnectionState.Error) {
                    playbackManager.releaseAll()
                    Logger.i(TAG, "连接断开，解码器已释放")
                    // 断开后延迟再启动唤醒，让残留 TTS 播完
                    scheduleWakeWordStartup(3000)
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
                try {
                if (audioData.isEmpty()) return@collect
                val t0 = System.nanoTime()
                val serverRate = backendService.getServerSampleRate()
                val serverCh = backendService.getServerChannels()
                val volume = AudioSettings.getOutputVolume(context)
                playbackManager.decodeAndPlay("main", serverRate, serverCh, audioData, volume)
                val dt = (System.nanoTime() - t0) / 1000
                if (dt > 5000) {
                    Logger.w(TAG, "audioData→decodePacket 耗时: ${dt}us, 包大小=${audioData.size}B")
                }
                } catch (e: Exception) {
                    Logger.e(TAG, "audioData 处理异常: ${e.message}")
                }
            }
        }
    }

    override suspend fun connect(config: ConnectionConfig, contactId: String): Result<Unit> {
        Logger.i(TAG, "ConnectionRepositoryImpl.connect() called: serverUrl=${config.serverUrl}, contactId=$contactId, deviceId=${config.deviceId}")

        // 互斥：连接新联系人前先断开旧连接
        if (_connectionState.value is BotConnectionState.Connected ||
            _connectionState.value is BotConnectionState.Connecting) {
            Logger.i(TAG, "切换联系人，断开旧连接: currentContactId=$currentContactId")
            backendService.disconnect()
            audioRecorder.stop("main")
            playbackManager.releaseAll()
            _connectionState.value = BotConnectionState.Disconnected
            _aiState.value = AiState.IDLE
            // 重置全部状态变量（防止旧会话泄漏到新连接）
            weStartedRecording = false
            isAutoMode = false
            textInputActive = false
            synchronized(lastSentTextLock) { lastSentText = null }
            lastTtsText = ""
            pendingTextResponse?.complete(Unit)
            pendingTextResponse = null
        }

        _currentContactId = contactId
        // 设置设备状态为 CONNECTING（参考 ESP32 kDeviceStateConnecting）
        _deviceState.value = DeviceState.CONNECTING

        // 初始化设备标识（从持久化存储读取，参考 ESP32 settings.cc）
        AppContext.initDeviceIds(context)

        return try {
            _connectionState.value = BotConnectionState.Connecting
            Logger.i(TAG, "ConnectionRepositoryImpl: calling backendService.connect()...")
            backendService.connect(config.serverUrl, config.otaUrl, config.deviceId, config.clientId, config.accessToken)
            Logger.i(TAG, "ConnectionRepositoryImpl: backendService.connect() returned successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "ConnectionRepositoryImpl.connect() failed: ${e.message}")
            _connectionState.value = BotConnectionState.Error(e.message ?: "连接失败")
            Result.failure(e)
        }
    }
    
    override suspend fun disconnect() {
        backendService.disconnect()
        audioRecorder.stop("main")
        playbackManager.releaseAll()
        _connectionState.value = BotConnectionState.Disconnected
        _aiState.value = AiState.IDLE
        // 断开后重启唤醒词检测
        startWakeWordMode()
    }
    
    override suspend fun sendTextMessage(contactId: String, text: String): Result<Unit> {
        _currentContactId = contactId
        // 先检查连接状态
        if (_connectionState.value !is BotConnectionState.Connected) {
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
    /**
     * 初始化并启动唤醒模式（单 AudioRecord + SileroVAD + 环形缓冲）。
     * 检测到连续语音后自动过渡到编码发送。
     */
    override fun startWakeWordMode(): Boolean {
        if (_aiState.value != AiState.IDLE) return false

        // TTS 刚结束冷却期（3秒），防止残余回声被捕获触发假唤醒
        if (System.currentTimeMillis() - lastSpeakingEndMs < 3000) {
            Logger.i(TAG, "唤醒模式延迟启动：TTS 刚结束（冷却中），跳过")
            return false
        }

        synchronized(recordingLock) {
            // 二次检查：可能在获取锁期间录音已被对话启动
            if (audioRecorder.isRecording) return false
            // 设置唤醒模板匹配器
            audioRecorder.wakeTemplateMatcher = wakeTemplateMatcher
            val ok = audioRecorder.startWake(
                owner = "main",
                scope = scope,
                onWakeDetected = { _ ->
                    Logger.i(TAG, "🎤 唤醒触发，发送 listen_start")

                    // 发送唤醒事件到服务器（ESP32 协议风格）
                    backendService.sendListenStart("realtime")

                    scope.launch {
                        _aiState.value = AiState.LISTENING
                        weStartedRecording = true
                        _deviceState.value = DeviceState.LISTENING
                    }
                },
                onOpusPacket = { packet -> scope.launch { sendAudio(packet) } }
            )
            return ok
        }
    }

    /**
     * 安全调度唤醒词模式启动，取消前一次未执行的调度防止重复
     */
    private fun scheduleWakeWordStartup(delayMs: Long) {
        wakeWordStartupJob?.cancel()
        wakeWordStartupJob = scope.launch {
            delay(delayMs)
            startWakeWordMode()
        }
    }

    override fun setWakeWord(wakeWord: String) {
        // 云端文本匹配已移除，唤醒词验证完全由本地模板匹配器完成
        Logger.d(TAG, "setWakeWord 已弃用 (云端文本匹配已移除)")
    }

    override fun isRecording(): Boolean = audioRecorder.isRecording

    override suspend fun startListening(mode: String) {
        stateMutex.withLock {
            textInputActive = false
            listeningMode = VoiceMode.fromServerValue(mode)
            isAutoMode = (mode == "auto")

            val enableVAD = listeningMode != VoiceMode.REALTIME

            // 先停任何旧录音，再启实时录音
            audioRecorder.stop("main")

            // 计算静默期：用户主动按键（距上次 TTS > 1s）则跳过回声防护
            val muteMs = if (System.currentTimeMillis() - lastSpeakingEndMs > 1000) 0L else 600L
            Logger.i(TAG, "startListening: muteMs=$muteMs (lastSpeakingEndMs=$lastSpeakingEndMs)")

            if (!audioRecorder.startLive(
                    owner = "main",
                    scope = scope,
                    enableVAD = enableVAD,
                    startMuteMs = muteMs,
                    onOpusPacket = { packet -> scope.launch { sendAudio(packet) } },
                    onSilenceDetected = { scope.launch { stopListening(userExplicitStop = false) } }
                )) {
                val audioErr = audioService.lastRecordError
                Logger.e(TAG, "录音启动失败: $audioErr")
                weStartedRecording = false
                isAutoMode = false
                _aiState.value = AiState.IDLE
                return
            }

            backendService.sendListenStart(mode)
            _aiState.value = AiState.LISTENING
            weStartedRecording = true
            _deviceState.value = DeviceState.LISTENING
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
            audioRecorder.stop("main")
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
        // 确保旧录音已停（唤醒模式或实时模式）
        audioRecorder.stop("main")
        drainAndFlushPlayback()
        if (audioService.isAecSupported()) {
            audioService.setAecEnabled(true)
        }
        if (audioService.isAgcSupported()) {
            audioService.setAgcEnabled(true)
        }
        if (audioService.isNsSupported()) {
            audioService.setNsEnabled(true)
        }
        if (!audioRecorder.startLive(
                owner = "main",
                scope = scope,
                enableVAD = enableVAD,
                startMuteMs = 600L,  // AI说话刚结束，防回声
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
        // 服务端"stop"已经等待了音频发送完成+预缓冲播放时间，队列应已接近空
        val hadQueueData = playbackManager.waitForQueueEmpty("main", 500)
        // AudioTrack 硬件缓冲自然排空（和ESP32一样不做显式flush，少量等待即可）
        val hadHardware = playbackManager.waitForHardwareDrain("main", 200)
        Logger.i(TAG, "drainAndFlushPlayback 完成（软件队列${if (hadQueueData) "空" else "超时"}，硬件${if (hadHardware) "已排空" else "超时"}）")
        // 简短安全延迟，ESP32不需要等待硬件，Android 加少量缓冲
        delay(200)
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
