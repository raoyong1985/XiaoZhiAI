package com.xiaozhi.aiservice.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.data.audio.AudioRecorder
import com.xiaozhi.aiservice.data.remote.BotConnectionPool
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.GroupMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class GroupChatViewModel : ViewModel() {
    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository

    private val botConnectionPool = BotConnectionPool(AppContainer.gson)
    private val playbackManager = AudioPlaybackManager()
    private val botObserverJobs = ConcurrentHashMap<String, Job>()

    // ── 接龙状态（不可变 RelayState，每次变更用 copy，通过 MutableStateFlow 保证原子性） ──
    private val _relayState = MutableStateFlow(RelayState())

    private val audioForwardBuffer = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val botLastAudioTime = ConcurrentHashMap<String, Long>()

    // ── 群聊 UI 状态 ──
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()

    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    val contacts: StateFlow<List<BotContact>> = _contacts.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // ── 录音状态 ──
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val audioService = AppContainer.audioService
    private val audioRecorder = AudioRecorder(audioService)
    private var currentGroupId = ""
    private var sttCollectorJob: Job? = null
    private var observeJob: Job? = null
    private var stuckRelayWatcher: Job? = null

    private val speakerMutex = Mutex()

    // 防死循环
    private val recentHints = mutableListOf<String>()
    private val recentCandidates = mutableListOf<String>()
    private var deadloopCount = 0
    private val MAX_DEADLOOP = 5

    // 去重
    private val recentReplyKeys = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val MAX_RECENT_REPLIES = 40

    companion object {
        private const val TAG = "GroupChatVM"
        private val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")
        private const val AUDIO_DRAIN_TIMEOUT_MS = 5000L
        private const val AUDIO_DRAIN_SILENCE_MS = 1000L
        /** 音频转发缓冲单 bot 上限（防止内存持续增长） */
        private const val MAX_FORWARD_BUFFER_SIZE = 200
    }

    init {
        loadGroups()
        loadContacts()
    }

    // ═══════════════════════════════════════════
    // lifecycle / 连接管理
    // ═══════════════════════════════════════════

    private fun loadGroups() {
        viewModelScope.launch { _groups.value = groupChatRepository.getGroups() }
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactsRepository.getContacts().collect { _contacts.value = it }
        }
    }

    fun connectGroup(groupId: String) {
        observeJob?.cancel()
        observeJob = null
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        _relayState.value = RelayState()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        recentReplyKeys.clear()
        currentGroupId = groupId
        _isConnecting.value = true

        observeJob = viewModelScope.launch {
            try {
                loadGroups()
                val group = groupChatRepository.getGroup(groupId)
                group?.let { g ->
                    g.botIds.map { botId ->
                        async {
                            try {
                                val contact = _contacts.value.find { it.id == botId }
                                contact?.let { c ->
                                    if (botConnectionPool.get(botId) == null) {
                                        val service = botConnectionPool.getOrConnect(botId, c)
                                        observeBotEvents(botId, groupId, c.name, service)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "连接bot[$botId]失败: ${e.message}")
                            }
                        }
                    }.forEach { it.await() }
                    _relayState.update { it.copy(botIds = g.botIds) }
                }
            } finally {
                _isConnecting.value = false
            }
        }

        // 接龙看门狗：定期检查接龙是否卡死（等待某个bot但20秒无响应），自动恢复
        stuckRelayWatcher?.cancel()
        stuckRelayWatcher = viewModelScope.launch {
            var lastCleanup = 0L
            while (true) {
                delay(15_000)
                // 定期清理死 bot 列表（每 2 分钟一次）
                val now = System.currentTimeMillis()
                if (now - lastCleanup > 120_000) {
                    lastCleanup = now
                    botConnectionPool.clearDead()
                    android.util.Log.i(TAG, "清理死 bot 列表")
                }

                val rs = _relayState.value
                if (rs.waitingSpeakerIndex < 0) continue  // 没有正在等待的bot，跳过
                if (_isRecording.value) continue           // 用户正在录音，跳过
                
                // 检查等待的bot是否已无响应超时
                val waitingBotId = rs.botIds.getOrNull(rs.waitingSpeakerIndex) ?: continue
                val lastAudio = botLastAudioTime[waitingBotId] ?: 0L
                val silenceDuration = System.currentTimeMillis() - lastAudio
                
                if (silenceDuration > 30_000) {
                    android.util.Log.w(TAG, "接龙卡死检测：等待[$waitingBotId] 已静默 ${silenceDuration/1000}s（超30s），清除等待状态并重启接龙")
                    _relayState.update { it.clearWaiting() }
                    triggerNextSpeaker("")
                }
            }
        }
    }

    fun setGroupInputMode(groupId: String, mode: Int) {
        viewModelScope.launch {
            groupChatRepository.updateGroupInputMode(groupId, mode)
            val currentGroups = _groups.value.toMutableList()
            val index = currentGroups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                currentGroups[index] = currentGroups[index].copy(inputMode = mode)
                _groups.value = currentGroups
            }
        }
    }

    // ═══════════════════════════════════════════
    // 录音
    // ═══════════════════════════════════════════

    fun startVoiceInput(groupId: String) {
        if (_isRecording.value) return
        if (_relayState.value.botIds.isEmpty()) return

        currentGroupId = groupId
        botConnectionPool.abortAll("user_interrupt")
        playbackManager.releaseAll()
        // 不取消 botObserverJobs！监听器跨录音会话存活，
        // 取消会导致 aiState/audioData 无人处理，机器人有回复但听不到
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deadloopCount = 0
        recentHints.clear()
        recentCandidates.clear()

        val firstBotId = _relayState.value.botIds[0]
        val service = botConnectionPool.get(firstBotId) ?: return

        _relayState.update { it.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0,
            waitingSpeakerIndex = 0, originalUserQuery = ""
        ) }
        recentReplyKeys.clear()

        service.sendListenStart("auto")

        if (!audioRecorder.start(
                scope = viewModelScope,
                enableVAD = true,
                onOpusPacket = { packet -> service.sendAudio(packet) },
                onSilenceDetected = {
                    android.util.Log.i(TAG, "VAD触发")
                    viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
                }
            )) {
            android.util.Log.w(TAG, "录音启动失败")
            service.sendListenStop()
            return
        }
        _isRecording.value = true

        sttCollectorJob?.cancel()
        sttCollectorJob = viewModelScope.launch {
            service.textMessage.drop(1).collect { text ->
                if (text.isNotBlank()) {
                    if (_relayState.value.originalUserQuery.isEmpty()) {
                        _relayState.update { it.copy(originalUserQuery = text) }
                    }
                    val userMessage = GroupMessage(
                        id = java.util.UUID.randomUUID().toString(), groupId = currentGroupId,
                        botId = "", botName = "我", content = text,
                        type = MessageType.TEXT, direction = MessageDirection.SENT
                    )
                    groupChatRepository.addMessage(currentGroupId, userMessage)
                    isDuplicateReply("", text)
                }
            }
        }
    }

    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        if (!_isRecording.value) return
        _isRecording.value = false
        android.util.Log.i(TAG, "停止录音 userExplicitStop=$userExplicitStop")

        if (userExplicitStop) {
            botConnectionPool.abortAll("user_stop")
        }

        audioRecorder.stop()
        audioService.abandonAudioFocus()

        try { botConnectionPool.get(_relayState.value.botIds.firstOrNull() ?: "")?.sendListenStopOnly() } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════
    // Bot 事件驱动（B1：合并 4 个流为一个事件通道）
    // ═══════════════════════════════════════════

    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        botObserverJobs[botId]?.cancel()
        val parentJob = Job(viewModelScope.coroutineContext[Job])
        botObserverJobs[botId] = parentJob

        // BUFFERED channel：缓冲 64 个事件，不阻塞发送方，不丢事件
        // CONFLATED 会丢弃中间事件（ttsText 在多句子时只剩最后一个）
        val eventChannel = Channel<BotEvent>(Channel.BUFFERED)

        viewModelScope.launch(parentJob) {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) eventChannel.send(BotEvent.TtsText(text))
            }
        }
        viewModelScope.launch(parentJob) {
            service.chatMessages.collect { msg ->
                eventChannel.send(BotEvent.Message(msg))
            }
        }
        viewModelScope.launch(parentJob) {
            service.aiState.collect { state ->
                eventChannel.send(BotEvent.AiState(state))
            }
        }
        // 音频数据不走事件通道：decodePacket 是 CPU 密集操作，
        // 走通道会阻塞 aiState/ttsText 处理，导致音频堆积、无声音
        // 直接用独立协程解码播放（参考旧版 4 协程模式）
        viewModelScope.launch(parentJob) {
            service.audioData.collect { audioData ->
                if (audioData.isEmpty()) return@collect
                botLastAudioTime[botId] = System.currentTimeMillis()
                val rs = _relayState.value
                val idx = rs.botIds.indexOf(botId)
                if (idx >= 0 && rs.waitingSpeakerIndex >= 0 && idx != rs.waitingSpeakerIndex) {
                    android.util.Log.w(TAG, "[$botName] 音频被ws过滤(idx=$idx, ws=${rs.waitingSpeakerIndex}), ${audioData.size}B")
                    return@collect
                }
                android.util.Log.i(TAG, "[$botName] 收到音频 ${audioData.size}B idx=$idx ws=${rs.waitingSpeakerIndex}")

                val serverRate = service.getServerSampleRate()
                val serverCh = service.getServerChannels()
                val volume = AudioSettings.getOutputVolume(AppContainer.context)
                // 群聊所有 bot 共享单个解码器（OPPO/MTK 多个 AudioTrack 同时存在会无声）
                // 切换 bot 时释放旧解码器，用新参数创建共享解码器
                val currentSourceId = "${groupId}_shared"
                if (!playbackManager.contains(currentSourceId)) {
                    speakerMutex.withLock {
                        if (!playbackManager.contains(currentSourceId)) {
                            playbackManager.releaseAll()
                            playbackManager.getOrCreate(currentSourceId, serverRate, serverCh, volume)
                        }
                    }
                } else {
                    // 采样率变化时重建解码器（不同 bot 可能用不同采样率）
                    val existing = playbackManager.get(currentSourceId)
                    if (existing != null && existing.getServerSampleRate() != serverRate) {
                        speakerMutex.withLock {
                            playbackManager.release(currentSourceId)
                            playbackManager.getOrCreate(currentSourceId, serverRate, serverCh, volume)
                        }
                    }
                }
                playbackManager.decodeAndPlay(currentSourceId, serverRate, serverCh, audioData, volume)
                val buf = audioForwardBuffer.getOrPut(botId) { mutableListOf() }
                buf.add(audioData)
                if (buf.size > MAX_FORWARD_BUFFER_SIZE) {
                    buf.removeAt(0) // 超出上限时丢弃最早的数据
                }
            }
        }

        // WebSocket重连恢复：当bot的ws断线重连后，如果接龙正等待该bot，清除等待状态
        // 不通过eventChannel，直接修改relayState避免事件队列阻塞
        viewModelScope.launch(parentJob) {
            var wasDisconnected = false
            service.connectionState.collect { state ->
                when (state) {
                    is XiaoZhiBackendService.ConnectionState.Error,
                    is XiaoZhiBackendService.ConnectionState.Disconnected -> {
                        wasDisconnected = true
                    }
                    is XiaoZhiBackendService.ConnectionState.Connected -> {
                        if (wasDisconnected) {
                            wasDisconnected = false
                            android.util.Log.i(TAG, "[$botName] WebSocket重连完成，检查接龙状态")
                            val rs = _relayState.value
                            val idx = rs.botIds.indexOf(botId)
                            if (idx >= 0 && rs.waitingSpeakerIndex >= 0 && idx == rs.waitingSpeakerIndex) {
                                android.util.Log.i(TAG, "[$botName] 接龙正等待本bot，清除等待状态")
                                _relayState.update { it.clearWaiting() }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // 单协程消费：保证事件顺序处理
        var replyText = ""
        var hasSpoken = false
        var speakerSession = 0L

        viewModelScope.launch(parentJob) {
            for (event in eventChannel) {
                when (event) {
                    is BotEvent.TtsText -> {
                        replyText = if (replyText.isBlank()) event.text else "$replyText ${event.text}"
                    }
                    is BotEvent.Message -> {
                        val msg = event.msg
                        if (msg.direction == MessageDirection.RECEIVED && msg.content.isNotBlank()) {
                            if (isDuplicateReply(botId, msg.content)) {
                                android.util.Log.w(TAG, "[$botName] 跳过重复消息: ${msg.content.take(30)}")
                                continue
                            }
                            groupChatRepository.addMessage(groupId, GroupMessage(
                                id = java.util.UUID.randomUUID().toString(), groupId = groupId,
                                botId = botId, botName = botName, content = msg.content,
                                type = MessageType.TEXT, direction = MessageDirection.RECEIVED
                            ))
                        }
                    }
                    is BotEvent.AiState -> {
                        val state = event.state
                        val rs = _relayState.value
                        android.util.Log.i(TAG, "[$botName] aiState=$state reply='${replyText.take(60)}' fwd=${rs.forwardRound} ws=${rs.waitingSpeakerIndex}")

                        if (state == "speaking") {
                            // 请求音频焦点，确保其他应用降音量
                            audioService.requestAudioFocus()
                            val botIndex = rs.botIds.indexOf(botId)
                            if (rs.waitingSpeakerIndex >= 0 && botIndex != rs.waitingSpeakerIndex && botIndex >= 0) {
                                android.util.Log.w(TAG, "[$botName] 非预期发言(idx=$botIndex, expected=${rs.waitingSpeakerIndex})，丢弃音频")
                                replyText = ""  // 清空累积文本，避免下次转发旧数据
                                continue
                            }
                            hasSpoken = true
                            speakerSession = rs.sessionId
                            if (botId == rs.botIds.firstOrNull()) {
                                if (_isRecording.value) {
                                    android.util.Log.i(TAG, "AI开始说话，自动停止录音")
                                    stopVoiceInput(userExplicitStop = false)
                                }
                                sttCollectorJob?.cancel()
                            }
                            if (!playbackManager.contains(botId)) {
                                val serverRate = service.getServerSampleRate()
                                val serverCh = service.getServerChannels()
                                val volume = AudioSettings.getOutputVolume(AppContainer.context)
                                playbackManager.getOrCreate(botId, serverRate, serverCh, volume)
                                android.util.Log.i(TAG, "[$botName] 预创建解码器 (${serverRate}Hz, 音量=$volume%)")
                            }
                        }

                        if (state == "idle" && hasSpoken) {
                            hasSpoken = false
                            val prevBotId = rs.botIds.getOrNull(rs.currentSpeakerIndex)
                            drainPreviousBotAudio(prevBotId)

                            var shouldTrigger = false
                            var replyToForward = ""
                            speakerMutex.withLock {
                                if (speakerSession != rs.sessionId) {
                                    android.util.Log.i(TAG, "[$botName] 跳过旧轮次 session=$speakerSession current=${rs.sessionId}")
                                    return@withLock
                                }
                                val botIndex = rs.botIds.indexOf(botId)
                                if (botIndex < 0) return@withLock
                                if (botIndex == rs.waitingSpeakerIndex && rs.botIds.size > 1) {
                                    replyToForward = replyText.also { replyText = "" }
                                    shouldTrigger = true
                                    android.util.Log.i(TAG, "[$botName] triggering next, fwd=${rs.forwardRound} reply='${replyToForward.take(60)}'")
                                } else if (rs.waitingSpeakerIndex < 0 && rs.botIds.size > 1 && rs.forwardRound >= 0) {
                                    // ws=-1 但还有活跃 bot → 自动继续下一轮（接龙之前失败或轮次结束）
                                    replyToForward = replyText.also { replyText = "" }
                                    shouldTrigger = true
                                    android.util.Log.i(TAG, "[$botName] 自动继续下一轮(ws=-1), fwd=${rs.forwardRound} reply='${replyToForward.take(60)}'")
                                } else {
                                    // 本 bot 已 idle 但不是被等待的 speaker（如已被跳过），清空文本
                                    replyText = ""
                                }
                            }
                            if (shouldTrigger) {
                                val reply = replyToForward
                                viewModelScope.launch { triggerNextSpeaker(reply) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 接龙引擎
    // ═══════════════════════════════════════════

    private suspend fun triggerNextSpeaker(previousReply: String = "") {
        val rs = _relayState.value
        if (rs.botIds.size <= 1) {
            _relayState.update { it.clearWaiting() }
            return
        }
        if (_isRecording.value) {
            android.util.Log.w(TAG, "用户正在录音，跳过转发")
            _relayState.update { it.clearWaiting() }
            return
        }
        if (previousReply.isNotBlank() && GOODBYE_KEYWORDS.any { previousReply.contains(it) }) {
            android.util.Log.i(TAG, "检测到结束语，停止接龙: '${previousReply.take(20)}'")
            _relayState.update { it.clearWaiting() }
            return
        }

        val prevIndex = rs.currentSpeakerIndex
        val prevBotId = rs.botIds.getOrNull(prevIndex)
        // 原子地读取状态、计算候选、写入更新（避免读-计算-写之间的竞态）
        var candidates: List<Pair<Int, String>> = emptyList()
        _relayState.update { current ->
            val (result, newState) = resolveSpeakerCandidates(prevIndex, previousReply, current.botIds, current)
            candidates = result
            newState
        }

        val connected = candidates.filter { (_, id) ->
            val svc = botConnectionPool.get(id)
            svc != null && (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected
                || run {
                    val contact = _contacts.value.find { it.id == id }
                    contact != null && botConnectionPool.ensureConnected(id, contact)
                })
        }

        for ((targetIndex, speakerId) in connected) {
            val service = botConnectionPool.get(speakerId) ?: continue
            _relayState.update { it.moveToSpeaker(targetIndex) }
            botLastAudioTime[speakerId] = System.currentTimeMillis()  // 重置看门狗计时

            val audioBuf = audioForwardBuffer[prevBotId]
            val isLongAudio = audioBuf != null && audioBuf.size > 200  // >200包≈10秒以上，跳过音频转发
            val audioOk = if (isLongAudio) {
                android.util.Log.i(TAG, "超长语音(${audioBuf!!.size}包)，跳过音频转发直接文字兜底")
                false
            } else {
                forwardAudioToBot(prevBotId, service)
            }
            if (audioOk) {
                val forwardAudioSize = audioForwardBuffer[prevBotId]?.sumOf { it.size } ?: 0
                val estimatedAudioMs = (forwardAudioSize / (32000.0 / 50.0 * 2)).toLong().coerceIn(0, 60000)
                val timeoutMs = (estimatedAudioMs + 15_000).coerceIn(10_000, 120_000)
                android.util.Log.i(TAG, "等待 $speakerId 开始说话 (${timeoutMs}ms超时, 音频≈${estimatedAudioMs}ms)")
                val startConfirmed = withTimeoutOrNull(timeoutMs) {
                    service.aiState.first { it == "speaking" }
                }
                if (startConfirmed != null) {
                    val hint = if (previousReply.isNotBlank()) _relayState.value.originalUserQuery.take(8) else ""
                    if (checkDeadloop(hint, speakerId)) {
                        android.util.Log.i(TAG, "死循环终止，跳过转发")
                        _relayState.update { it.clearWaiting() }
                        return
                    }
                    _relayState.update {
                        it.copy(
                            forwardRound = it.forwardRound + 1
                        ).markSpoken(targetIndex)
                    }
                    if (previousReply.isNotBlank()) {
                        _relayState.update { it.copy(originalUserQuery = previousReply.take(20)) }
                    }
                    audioForwardBuffer.remove(prevBotId)
                    android.util.Log.i(TAG, "音频转发成功 → $speakerId fwd=${_relayState.value.forwardRound}")
                    return
                }
                android.util.Log.w(TAG, "音频转发超时($speakerId 10s未响应)，尝试下一候选")
                _relayState.update { it.clearWaiting() }
            }

            val textToSend = buildForwardMessage(previousReply)
            if (checkDeadloop(textToSend)) {
                android.util.Log.i(TAG, "死循环终止，跳过本轮转发")
                _relayState.update { it.clearWaiting() }
                return
            }
            if (textToSend.isNotBlank()) {
                if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                    android.util.Log.w(TAG, "文字兜底失败: $speakerId WebSocket未连接")
                    _relayState.update { it.clearWaiting() }
                    continue
                }
                // 文字兜底前清理旧音频缓存，防止新旧数据混合
                audioForwardBuffer.remove(prevBotId)
                android.util.Log.i(TAG, "文字兜底 ${textToSend.length}字 → $speakerId: '$textToSend'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        _relayState.update { it.copy(originalUserQuery = previousReply.take(20)) }
                    }
                    val startConfirmed = withTimeoutOrNull(30_000) {
                        service.aiState.first { it == "speaking" }
                    }
                    if (startConfirmed != null) {
                        _relayState.update { it.markSpoken(targetIndex) }
                        android.util.Log.i(TAG, "文字兜底转发成功")
                        return
                    }
                    android.util.Log.w(TAG, "文字兜底超时($speakerId 10s未响应)")
                    _relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "文字兜底异常: ${e.message}")
                    _relayState.update { it.clearWaiting() }
                }
            }
        }

        audioForwardBuffer.remove(prevBotId)
        android.util.Log.w(TAG, "无可用bot转发，跳过本轮")
        _relayState.update { it.clearWaiting() }
        val firstAlive = _relayState.value.botIds.firstOrNull()
        if (firstAlive != null) {
            _relayState.update {
                it.copy(
                    currentSpeakerIndex = it.botIds.indexOf(firstAlive).coerceAtLeast(0)
                )
            }
        }
    }

    // ── 智能 speaker 选择 ──
    private fun resolveSpeakerCandidates(
        prevIndex: Int, previousReply: String, botIds: List<String>, rs: RelayState
    ): Pair<List<Pair<Int, String>>, RelayState> {
        if (botIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        // 过滤死 bot，压缩索引 —— 返回 compacted 索引，避免指向死 bot
        val aliveBotIds = botIds.filter { !botConnectionPool.isDead(it) }
        if (aliveBotIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        // 重映射 prevIndex 和 spokenInThisRound 到压缩后的索引
        val prevBotId = botIds.getOrNull(prevIndex)
        val compactedPrevIndex = if (prevBotId != null) aliveBotIds.indexOf(prevBotId) else -1
        val compactedSpoken = rs.spokenInThisRound.mapNotNull { i ->
            botIds.getOrNull(i)?.let { aliveBotIds.indexOf(it) }
        }.filter { it >= 0 }.toSet()

        data class Candidate(val index: Int, val id: String, var score: Float)
        val candidates = aliveBotIds.indices
            .filter { it != compactedPrevIndex }
            .map { Candidate(it, aliveBotIds[it], 0f) }
        if (candidates.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        if (previousReply.isNotBlank()) {
            val mentionMatch = Regex("@([\\w\\u4e00-\\u9fff]+)").find(previousReply)
            if (mentionMatch != null) {
                val mentionedName = mentionMatch.groupValues[1]
                candidates.forEach { c ->
                    val contact = _contacts.value.find { it.id == c.id }
                    if (contact != null && (contact.name == mentionedName || contact.id.startsWith(mentionedName))) {
                        c.score += 100f
                    }
                }
            }
            candidates.forEach { c ->
                val contact = _contacts.value.find { it.id == c.id }
                if (contact != null && contact.name.isNotBlank() && previousReply.contains(contact.name)) {
                    c.score += 30f
                }
            }
        }

        candidates.forEach { c ->
            if (c.index !in compactedSpoken) c.score += 20f
        }
        candidates.forEach { c -> c.score += (Math.random().toFloat() * 15f) }

        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.score }
            .thenBy { Math.random() })

        // 压缩索引后的 spokenInThisRound 重置
        val allSpoken = sorted.all { it.index in compactedSpoken }
        val forceReset = rs.spokenRoundCounter >= maxOf(aliveBotIds.size * 2, 4)
        val newRs = if (allSpoken || forceReset) {
            rs.copy(
                botIds = aliveBotIds,
                spokenInThisRound = emptySet(),
                spokenRoundCounter = 0
            )
        } else {
            rs.copy(
                botIds = aliveBotIds,
                spokenInThisRound = compactedSpoken
            )
        }

        return sorted.map { it.index to it.id } to newRs
    }

    // ── 连接保活 ──
    // 使用 botConnectionPool 管理

    private suspend fun drainPreviousBotAudio(prevBotId: String?) {
        if (prevBotId == null) return
        withTimeoutOrNull(AUDIO_DRAIN_TIMEOUT_MS) {
            while (true) {
                val lastTs = botLastAudioTime[prevBotId] ?: 0L
                if (lastTs <= 0) break
                if (System.currentTimeMillis() - lastTs > AUDIO_DRAIN_SILENCE_MS) break
                delay(100)
            }
        }
    delay(200) // in-flight 包
    if (!playbackManager.waitForQueueEmpty(prevBotId, 2000L)) {
        android.util.Log.w(TAG, "drainPreviousBotAudio 超时")
    }
    }

    // ── 音频转发 ──
    private suspend fun forwardAudioToBot(prevBotId: String?, service: XiaoZhiBackendService): Boolean {
        // 用户正在录音时禁止转发音频，避免录音与音频发送冲突
        if (_isRecording.value) {
            android.util.Log.w(TAG, "用户正在录音，跳过音频转发")
            return false
        }
        // 检查WebSocket连接状态，断线时直接返回false
        if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
            android.util.Log.w(TAG, "音频转发失败：WebSocket未连接")
            return false
        }
        if (prevBotId == null) return false
        val cachedList = audioForwardBuffer[prevBotId] ?: return false
        if (cachedList.isEmpty()) return false
        val cachedOpus = cachedList.toList()
        android.util.Log.i(TAG, "音频转发 ${cachedOpus.size}包/${cachedOpus.sumOf { it.size }}B prev=$prevBotId")
        try {
            service.sendListenStart("auto")
            // 攒批发送：每 5 包合并后一次发，减少 WebSocket 消息数
            val batchSize = 5
            cachedOpus.chunked(batchSize).forEachIndexed { batchIdx, batch ->
                batch.forEach { packet -> service.sendAudio(packet) }
                // 前 2 批给宽松延迟，后面每 4 批给 10ms
                when {
                    batchIdx < 2 -> delay(40)
                    batchIdx % 4 == 0 -> delay(10)
                }
            }
            delay(200)
            service.sendListenStopOnly()
            // 转发完成后再次检查连接，防止转发过程中断线
            return service.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected
        } catch (e: Exception) {
            android.util.Log.w(TAG, "音频转发失败: ${e.message}")
            return false
        }
    }

    // ── 文字兜底 ──
    private fun buildForwardMessage(previousReply: String): String {
        if (previousReply.isBlank()) return ""
        val hint = if (_relayState.value.originalUserQuery.isNotBlank()) _relayState.value.originalUserQuery.take(8)
        else truncateReply(previousReply, 8)
        return hint
    }

    // ── 死循环检测 ──
    private fun checkDeadloop(hint: String, speakerId: String? = null): Boolean {
        var isDead = false
        if (hint.isNotBlank()) {
            recentHints.add(hint)
            if (recentHints.size > MAX_DEADLOOP) recentHints.removeAt(0)
            if (recentHints.size >= MAX_DEADLOOP && recentHints.distinct().size <= 2) isDead = true
        }
        if (speakerId != null) {
            recentCandidates.add(speakerId)
            if (recentCandidates.size > MAX_DEADLOOP) recentCandidates.removeAt(0)
            if (recentCandidates.size >= MAX_DEADLOOP && recentCandidates.distinct().size == 1) isDead = true
        }
        if (isDead) {
            deadloopCount++
            if (deadloopCount >= MAX_DEADLOOP) {
                deadloopCount = 0; recentHints.clear(); recentCandidates.clear()
                _relayState.update { it.clearWaiting() }
                return true
            }
        } else deadloopCount = 0
        return false
    }

    // ── 消息截断 ──
    private fun truncateReply(text: String, maxLen: Int = 24): String {
        if (text.isBlank()) return ""
        val clean = text.trim()
        if (clean.length <= maxLen) return clean
        val sentenceEnd = clean.indexOfAny(charArrayOf('。', '！', '？', '\n'))
        if (sentenceEnd in 1..maxLen) return clean.substring(0, sentenceEnd)
        val boundary = clean.lastIndexOfAny(charArrayOf('，', '、', '；', '：', ' ', ','), maxLen)
        if (boundary > 3) return clean.substring(0, boundary)
        return clean.substring(0, maxLen)
    }

    // ── 去重 ──
    private fun isDuplicateReply(botId: String, content: String): Boolean {
        val key = "$botId|$content"
        synchronized(recentReplyKeys) {
            if (recentReplyKeys.contains(key)) return true
            recentReplyKeys.addFirst(key)
            if (recentReplyKeys.size > MAX_RECENT_REPLIES) recentReplyKeys.removeLast()
        }
        return false
    }

    // ═══════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════

    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        val group = groupChatRepository.createGroup(name, selectedBotIds)
        loadGroups()
        return group
    }

    suspend fun deleteGroup(groupId: String) {
        groupChatRepository.deleteGroup(groupId)
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        loadGroups()
    }

    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        return groupChatRepository.getGroupMessages(groupId)
    }

    suspend fun sendMessageToGroup(groupId: String, content: String) {
        val userMessage = GroupMessage(
            id = java.util.UUID.randomUUID().toString(), groupId = groupId,
            botId = "", botName = "我", content = content,
            type = MessageType.TEXT, direction = MessageDirection.SENT
        )
        groupChatRepository.addMessage(groupId, userMessage)

        _relayState.update { it.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0, waitingSpeakerIndex = 0,
            originalUserQuery = content
        ) }
        audioForwardBuffer.clear()
        recentReplyKeys.clear()
        recentHints.clear()
        recentCandidates.clear()

        if (_relayState.value.botIds.isNotEmpty()) {
            val firstBotId = _relayState.value.botIds[0]
            val svc = botConnectionPool.get(firstBotId)
            if (svc != null) {
                val contact = _contacts.value.find { it.id == firstBotId }
                val connected = contact != null && botConnectionPool.ensureConnected(firstBotId, contact)
                if (connected) svc.sendTextContent(content)
                else android.util.Log.w(TAG, "sendMessage: 首bot连接失败")
            }
        }
    }

    suspend fun sendMessage(groupId: String, content: String) = sendMessageToGroup(groupId, content)

    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatRepository.updateGroupName(groupId, newName); loadGroups()
    }

    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatRepository.addBotToGroup(groupId, botId)
        _relayState.update { it.copy(botIds = it.botIds + botId) }
        loadGroups()
    }

    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        _relayState.update { it.copy(botIds = it.botIds - botId) }
        loadGroups()
    }

    suspend fun clearGroupMessages(groupId: String) {
        groupChatRepository.clearGroupMessages(groupId)
    }

    fun exportGroupMessages(groupId: String, context: android.content.Context) {
        viewModelScope.launch {
            val messages = groupChatRepository.getGroupMessages(groupId).first()
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                "[${if (msg.direction == MessageDirection.SENT) "我" else msg.botName}]: ${msg.content}"
            }
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "群聊记录")
            })
        }
    }

    fun disconnectGroup() {
        stopVoiceInput()
        sttCollectorJob?.cancel()
        stuckRelayWatcher?.cancel()
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        recentReplyKeys.clear()
        _relayState.value = RelayState()
        android.util.Log.i(TAG, "disconnectGroup: 群聊资源已全部释放")
    }

    override fun onCleared() {
        super.onCleared()
        disconnectGroup()
        audioRecorder.release()
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GroupChatViewModel::class.java)) {
                return GroupChatViewModel() as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
