package com.xiaozhi.aiservice.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.audio.OpusEncoder
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
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

    private val botConnections = ConcurrentHashMap<String, XiaoZhiBackendService>()
    private val botAudioDecoders = ConcurrentHashMap<String, NativeOpusDecoder>()
    private val botObserverJobs = ConcurrentHashMap<String, Job>()

    // ── 接龙状态（不可变 RelayState，每次变更用 copy） ──
    @Volatile private var relayState: RelayState = RelayState()

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

    private var opusEncoder: OpusEncoder? = null
    private val audioService = AppContainer.audioService
    private var currentGroupId = ""
    private var sttCollectorJob: Job? = null
    private var recordingGeneration = 0
    private var timeoutJob: Job? = null
    private var observeJob: Job? = null

    private val speakerMutex = Mutex()

    // 防死循环
    private val recentHints = mutableListOf<String>()
    private val recentCandidates = mutableListOf<String>()
    private var deadloopCount = 0
    private val MAX_DEADLOOP = 3

    // 去重
    private val recentReplyKeys = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val MAX_RECENT_REPLIES = 40

    companion object {
        private const val TAG = "GroupChatVM"
        private val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")
        private const val AUDIO_DRAIN_TIMEOUT_MS = 3000L
        private const val AUDIO_DRAIN_SILENCE_MS = 300L
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
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
        relayState = RelayState()
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
                                    if (!botConnections.containsKey(botId)) {
                                        val service = XiaoZhiBackendService(AppContainer.gson)
                                        botConnections[botId] = service
                                        observeBotEvents(botId, groupId, c.name, service)
                                        service.connect(c.serverUrl, c.otaUrl, c.deviceId, c.clientId)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "连接bot[$botId]失败: ${e.message}")
                            }
                        }
                    }.forEach { it.await() }
                    relayState = relayState.copy(botIds = g.botIds)
                }
            } finally {
                _isConnecting.value = false
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
        if (relayState.botIds.isEmpty()) return

        currentGroupId = groupId
        botConnections.forEach { (_, svc) ->
            try { svc.sendAbort("user_interrupt") } catch (_: Exception) {}
        }
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
        // 不取消 botObserverJobs！监听器跨录音会话存活，
        // 取消会导致 aiState/audioData 无人处理，机器人有回复但听不到
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deadloopCount = 0
        recentHints.clear()
        recentCandidates.clear()

        val firstBotId = relayState.botIds[0]
        val service = botConnections[firstBotId] ?: return

        relayState = relayState.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0,
            waitingSpeakerIndex = 0, originalUserQuery = ""
        )
        recentReplyKeys.clear()

        recordingGeneration++
        val currentGen = recordingGeneration

        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        _isRecording.value = true
        service.sendListenStart("auto")

        audioService.setOnSilenceDetected {
            android.util.Log.i(TAG, "VAD触发")
            if (recordingGeneration == currentGen) {
                viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
            }
        }

        timeoutJob = viewModelScope.launch {
            delay(8_000)
            if (_isRecording.value && recordingGeneration == currentGen) {
                android.util.Log.w(TAG, "8秒超时触发")
                stopVoiceInput(userExplicitStop = false)
            }
        }

        val sampleRate = audioService.getSampleRate()
        val encoder = OpusEncoder(sampleRate = sampleRate)
        opusEncoder = encoder

        if (!encoder.init { opusPacket -> service.sendAudio(opusPacket) }) {
            android.util.Log.w(TAG, "编码器初始化失败")
            service.sendListenStop()
            opusEncoder = null
            _isRecording.value = false
            return
        }

        if (!audioService.startRecording({ pcmData -> encoder.encode(pcmData) }, enableVAD = true)) {
            android.util.Log.w(TAG, "录音启动失败")
            service.sendListenStop()
            opusEncoder?.stop()
            opusEncoder = null
            _isRecording.value = false
            return
        }

        sttCollectorJob?.cancel()
        sttCollectorJob = viewModelScope.launch {
            service.textMessage.drop(1).collect { text ->
                if (text.isNotBlank()) {
                    if (relayState.originalUserQuery.isEmpty()) {
                        relayState = relayState.copy(originalUserQuery = text)
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

        timeoutJob?.cancel()
        timeoutJob = null

        if (userExplicitStop) {
            botConnections.forEach { (_, svc) ->
                try { svc.sendAbort("user_stop") } catch (_: Exception) {}
            }
        }

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null
        audioService.abandonAudioFocus()

        try { botConnections[relayState.botIds.firstOrNull()]?.sendListenStopOnly() } catch (_: Exception) {}
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
                val rs = relayState
                val idx = rs.botIds.indexOf(botId)
                if (idx >= 0 && rs.waitingSpeakerIndex >= 0 && idx != rs.waitingSpeakerIndex) return@collect

                val existingDecoder = botAudioDecoders[botId]
                val finalDecoder = if (existingDecoder == null) {
                    drainPreviousDecodersBeforeSwitch()
                    speakerMutex.withLock {
                        var d = botAudioDecoders[botId]
                        if (d == null) {
                            releaseOldDecoders()
                            val serverRate = service.getServerSampleRate()
                            val serverCh = service.getServerChannels()
                            d = NativeOpusDecoder(serverRate, 16000, serverCh).also {
                                it.init()
                                botAudioDecoders[botId] = it
                            }
                        }
                        d!!
                    }
                } else existingDecoder
                finalDecoder.decodePacket(audioData)
                audioForwardBuffer.getOrPut(botId) { mutableListOf() }.add(audioData)
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
                        val rs = relayState
                        android.util.Log.i(TAG, "[$botName] aiState=$state reply='${replyText.take(60)}' fwd=${rs.forwardRound} ws=${rs.waitingSpeakerIndex}")

                        if (state == "speaking") {
                            val botIndex = rs.botIds.indexOf(botId)
                            if (rs.waitingSpeakerIndex >= 0 && botIndex != rs.waitingSpeakerIndex && botIndex >= 0) {
                                android.util.Log.w(TAG, "[$botName] 非预期发言(idx=$botIndex, expected=${rs.waitingSpeakerIndex})，丢弃音频")
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
                            if (botAudioDecoders[botId] == null) {
                                val serverRate = service.getServerSampleRate()
                                val serverCh = service.getServerChannels()
                                NativeOpusDecoder(serverRate, 16000, serverCh).also {
                                    it.init()
                                    botAudioDecoders[botId] = it
                                }
                                android.util.Log.i(TAG, "[$botName] 预创建解码器 (${serverRate}Hz)")
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
                                }
                            }
                            if (shouldTrigger) {
                                triggerNextSpeaker(replyToForward)
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
        val rs = relayState
        if (rs.botIds.size <= 1) {
            relayState = rs.clearWaiting()
            return
        }
        if (_isRecording.value) {
            android.util.Log.w(TAG, "用户正在录音，跳过转发")
            relayState = rs.clearWaiting()
            return
        }
        if (previousReply.isNotBlank() && GOODBYE_KEYWORDS.any { previousReply.contains(it) }) {
            android.util.Log.i(TAG, "检测到结束语，停止接龙: '${previousReply.take(20)}'")
            relayState = rs.clearWaiting()
            return
        }

        val prevIndex = rs.currentSpeakerIndex
        val candidates = resolveSpeakerCandidates(prevIndex, previousReply, rs.botIds)

        val connected = candidates.filter { (_, id) ->
            val svc = botConnections[id]
            svc != null && (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected
                || ensureBotConnected(svc, id))
        }

        for ((targetIndex, speakerId) in connected) {
            val service = botConnections[speakerId] ?: continue
            relayState = relayState.moveToSpeaker(targetIndex)

            val audioOk = forwardAudioToBot(prevIndex, service)
            if (audioOk) {
                android.util.Log.i(TAG, "等待 $speakerId 开始说话 (10s超时)")
                val startConfirmed = withTimeoutOrNull(10_000) {
                    service.aiState.first { it == "speaking" }
                }
                if (startConfirmed != null) {
                    val hint = if (previousReply.isNotBlank()) relayState.originalUserQuery.take(8) else ""
                    if (checkDeadloop(hint, speakerId)) {
                        android.util.Log.i(TAG, "死循环终止，跳过转发")
                        relayState = relayState.clearWaiting()
                        return
                    }
                    relayState = relayState.copy(
                        forwardRound = relayState.forwardRound + 1
                    ).markSpoken(targetIndex)
                    if (previousReply.isNotBlank()) {
                        relayState = relayState.copy(originalUserQuery = previousReply.take(20))
                    }
                    audioForwardBuffer.remove(rs.botIds.getOrNull(prevIndex))
                    android.util.Log.i(TAG, "音频转发成功 → $speakerId fwd=${relayState.forwardRound}")
                    return
                }
                android.util.Log.w(TAG, "音频转发超时($speakerId 10s未响应)，尝试下一候选")
                relayState = relayState.clearWaiting()
            }

            val textToSend = buildForwardMessage(previousReply)
            if (checkDeadloop(textToSend)) {
                android.util.Log.i(TAG, "死循环终止，跳过本轮转发")
                relayState = relayState.clearWaiting()
                return
            }
            if (textToSend.isNotBlank()) {
                if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                    android.util.Log.w(TAG, "文字兜底失败: $speakerId WebSocket未连接")
                    relayState = relayState.clearWaiting()
                    continue
                }
                android.util.Log.i(TAG, "文字兜底 ${textToSend.length}字 → $speakerId: '$textToSend'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        relayState = relayState.copy(originalUserQuery = previousReply.take(20))
                    }
                    val startConfirmed = withTimeoutOrNull(10_000) {
                        service.aiState.first { it == "speaking" }
                    }
                    if (startConfirmed != null) {
                        relayState = relayState.markSpoken(targetIndex)
                        android.util.Log.i(TAG, "文字兜底转发成功")
                        return
                    }
                    android.util.Log.w(TAG, "文字兜底超时($speakerId 10s未响应)")
                    relayState = relayState.clearWaiting()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "文字兜底异常: ${e.message}")
                    relayState = relayState.clearWaiting()
                }
            }
        }

        audioForwardBuffer.remove(rs.botIds.getOrNull(prevIndex))
        android.util.Log.w(TAG, "无可用bot转发，跳过本轮")
        relayState = rs.clearWaiting().copy(
            currentSpeakerIndex = rs.botIds.indexOf(rs.botIds.firstOrNull()).coerceAtLeast(0)
        )
    }

    // ── 智能 speaker 选择 ──
    private fun resolveSpeakerCandidates(prevIndex: Int, previousReply: String, botIds: List<String>): List<Pair<Int, String>> {
        if (botIds.isEmpty()) return emptyList()
        data class Candidate(val index: Int, val id: String, var score: Float)
        val candidates = botIds.indices
            .filter { it != prevIndex }
            .map { Candidate(it, botIds[it], 0f) }
        if (candidates.isEmpty()) return emptyList()

        val rs = relayState

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
            if (c.index !in rs.spokenInThisRound) c.score += 20f
        }
        candidates.forEach { c -> c.score += (Math.random().toFloat() * 15f) }

        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.score }
            .thenBy { Math.random() })

        // spokenInThisRound 重置
        val allSpoken = sorted.all { it.index in rs.spokenInThisRound }
        val forceReset = rs.spokenRoundCounter >= maxOf(botIds.size * 2, 4)
        if (allSpoken || forceReset) {
            relayState = relayState.resetSpokenRound()
        }

        return sorted.map { it.index to it.id }
    }

    // ── 连接保活 ──
    private suspend fun ensureBotConnected(service: XiaoZhiBackendService, botId: String): Boolean {
        if (service.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) return true
        android.util.Log.w(TAG, "重新连接: $botId")
        val contact = _contacts.value.find { it.id == botId } ?: return false
        service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
        return withTimeoutOrNull(5000) {
            service.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
        } != null
    }

    // ── 音频排空 ──
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
        delay(500) // in-flight 包
        val decoder = botAudioDecoders[prevBotId]
        if (decoder != null && !decoder.isReleased) {
            if (!decoder.waitForQueueEmpty(2000L)) {
                android.util.Log.w(TAG, "drainPreviousBotAudio 超时")
            }
        }
        delay(200) // AudioTrack 硬件缓冲补偿
    }

    private suspend fun drainPreviousDecodersBeforeSwitch() {
        val currentId = relayState.currentBotId
        for ((_, decoder) in botAudioDecoders.filterKeys { it != currentId }) {
            if (decoder.isReleased) continue
            if (!decoder.waitForQueueEmpty(2000L)) {
                android.util.Log.w(TAG, "drainPreviousDecoders 超时")
            }
        }
    }

    private fun releaseOldDecoders() {
        val currentId = relayState.currentBotId
        val toRelease = botAudioDecoders.filterKeys { it != currentId }
        toRelease.values.forEach { if (!it.isReleased) it.release() }
        botAudioDecoders.keys.removeAll(toRelease.keys)
    }

    // ── 音频转发 ──
    private suspend fun forwardAudioToBot(prevIndex: Int, service: XiaoZhiBackendService): Boolean {
        // 用户正在录音时禁止转发音频，避免录音与音频发送冲突
        if (_isRecording.value) {
            android.util.Log.w(TAG, "用户正在录音，跳过音频转发")
            return false
        }
        val prevBotId = relayState.botIds.getOrNull(prevIndex) ?: return false
        val cachedList = audioForwardBuffer[prevBotId] ?: return false
        if (cachedList.isEmpty()) return false
        val cachedOpus = cachedList.toList()
        android.util.Log.i(TAG, "音频转发 ${cachedOpus.size}包/${cachedOpus.sumOf { it.size }}B prev=$prevBotId")
        try {
            service.sendListenStart("auto")
            cachedOpus.forEachIndexed { i, packet ->
                service.sendAudio(packet)
                when {
                    i < 10 -> delay(60)
                    i % 20 == 0 -> delay(10)
                }
            }
            delay(500)
            service.sendListenStopOnly()
            return true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "音频转发失败: ${e.message}")
            return false
        }
    }

    // ── 文字兜底 ──
    private fun buildForwardMessage(previousReply: String): String {
        if (previousReply.isBlank()) return ""
        val hint = if (relayState.originalUserQuery.isNotBlank()) relayState.originalUserQuery.take(8)
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
                relayState = relayState.clearWaiting()
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
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
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

        relayState = relayState.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0, waitingSpeakerIndex = 0,
            originalUserQuery = content
        )
        audioForwardBuffer.clear()
        recentReplyKeys.clear()
        recentHints.clear()
        recentCandidates.clear()

        if (relayState.botIds.isNotEmpty()) {
            val firstBotId = relayState.botIds[0]
            val svc = botConnections[firstBotId]
            if (svc != null) {
                val connected = withTimeoutOrNull(5_000) {
                    if (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) return@withTimeoutOrNull true
                    val contact = _contacts.value.find { it.id == firstBotId }
                    if (contact != null) svc.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
                    svc.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }; true
                }
                if (connected == true) svc.sendTextContent(content)
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
        relayState = relayState.copy(botIds = relayState.botIds + botId)
        loadGroups()
    }

    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        relayState = relayState.copy(botIds = relayState.botIds - botId)
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

    override fun onCleared() {
        super.onCleared()
        stopVoiceInput()
        sttCollectorJob?.cancel()
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
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
