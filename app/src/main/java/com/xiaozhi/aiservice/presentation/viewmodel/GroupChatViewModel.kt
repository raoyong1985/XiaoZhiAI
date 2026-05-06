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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class GroupChatViewModel : ViewModel() {
    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository

    private val botConnections = mutableMapOf<String, XiaoZhiBackendService>()
    private val botAudioDecoders = mutableMapOf<String, NativeOpusDecoder>()

    private var sequentialBotIds = listOf<String>()
    private var currentSpeakerIndex = 0
    private var forwardRound = 0
    private val MAX_FORWARD_ROUNDS = 100
    private var originalUserQuery = ""
    private var spokenInThisRound = mutableSetOf<Int>()
    private val AUDIO_DRAIN_TIMEOUT_MS = 3000L
    private val AUDIO_DRAIN_SILENCE_MS = 300L

    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()

    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    val contacts: StateFlow<List<BotContact>> = _contacts.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var opusEncoder: OpusEncoder? = null
    private val audioService = AppContainer.audioService
    private var currentGroupId = ""
    private var waitingSpeakerIndex = -1
    private var sttCollectorJob: kotlinx.coroutines.Job? = null
    private var recordingGeneration = 0
    private var timeoutJob: kotlinx.coroutines.Job? = null
    private val speakerMutex = Mutex()
    private var relaySessionId = 0L  // 递增，用于判断aiState回调是否属于当前轮次

    private val audioForwardBuffer = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val botLastAudioTime = ConcurrentHashMap<String, Long>()  // botId → last audio arrival timestamp
    private var observeJob: kotlinx.coroutines.Job? = null

    // 防死循环：追踪最近转发 hint 的重复情况
    private val recentHints = mutableListOf<String>()
    private val recentCandidates = mutableListOf<String>()  // 最近选中的候选botId，防止同一bot被重复选中
    private var deadloopCount = 0
    private val MAX_DEADLOOP = 3
    private var hostBotId: String? = null

    // 文字兜底模式下的 bot，其回复不显示在对话框
    private val textFallbackBots = mutableSetOf<String>()

    init {
        loadGroups()
        loadContacts()
    }

    private fun loadGroups() {
        viewModelScope.launch {
            _groups.value = groupChatRepository.getGroups()
        }
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactsRepository.getContacts().collect { contacts ->
                _contacts.value = contacts
            }
        }
    }

    fun connectGroup(groupId: String) {
        // 取消旧群聊的观察协程
        observeJob?.cancel()
        observeJob = null
        // 断开旧群聊的所有连接
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = -1
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        recentBotReplies.clear()
        spokenInThisRound.clear()
        textFallbackBots.clear()
        currentGroupId = groupId

        observeJob = viewModelScope.launch {
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
                                    observeBotReplies(botId, groupId, c.name, service)
                                    service.connect(c.serverUrl, c.otaUrl, c.deviceId, c.clientId)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("GroupChatVM", "连接bot[$botId]失败: ${e.message}")
                        }
                    }
                }.forEach { it.await() }
                sequentialBotIds = g.botIds
            }
        }
    }

    fun setGroupInputMode(groupId: String, mode: Int) {
        viewModelScope.launch {
            groupChatRepository.updateGroupInputMode(groupId, mode)
            loadGroups()
        }
    }

    fun startVoiceInput(groupId: String) {
        if (_isRecording.value) return
        if (sequentialBotIds.isEmpty()) return

        currentGroupId = groupId
        // 用户干预：终止所有正在说话的 bot
        botConnections.forEach { (_, svc) ->
            try { svc.sendAbort("user_interrupt") } catch (_: Exception) {}
        }
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deadloopCount = 0
        recentHints.clear()
        recentCandidates.clear()
        hostBotId = null
        textFallbackBots.clear()

        val firstBotId = sequentialBotIds[0]
        val service = botConnections[firstBotId] ?: return

        // 递增轮次ID，让旧轮次的 aiState 回调不再触发下一人
        relaySessionId++

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = 0
        originalUserQuery = ""
        recentBotReplies.clear()
        spokenInThisRound.clear()

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
            android.util.Log.i("GroupChatVM", "VAD触发")
            if (recordingGeneration == currentGen) {
                viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
            }
        }

        timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            if (_isRecording.value && recordingGeneration == currentGen) {
                android.util.Log.w("GroupChatVM", "8秒超时触发")
                stopVoiceInput(userExplicitStop = false)
            }
        }

        val sampleRate = audioService.getSampleRate()
        val encoder = OpusEncoder(sampleRate = sampleRate)
        opusEncoder = encoder

        if (!encoder.init { opusPacket ->
                service.sendAudio(opusPacket)
            }
        ) {
            android.util.Log.w("GroupChatVM", "编码器初始化失败")
            service.sendListenStop()
            opusEncoder = null
            _isRecording.value = false
            return
        }

        if (!audioService.startRecording({ pcmData ->
                encoder.encode(pcmData)
            }, enableVAD = true)
        ) {
            android.util.Log.w("GroupChatVM", "录音启动失败")
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
                    if (originalUserQuery.isEmpty()) originalUserQuery = text
                    val userMessage = GroupMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        groupId = currentGroupId,
                        botId = "",
                        botName = "我",
                        content = text,
                        type = MessageType.TEXT,
                        direction = MessageDirection.SENT
                    )
                    groupChatRepository.addMessage(currentGroupId, userMessage)
                    // 加入去重缓存，防止后续 chatMessages 又收集到相同内容
                    isDuplicateReply("", text)
                }
            }
        }
    }

    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        if (!_isRecording.value) return
        _isRecording.value = false
        android.util.Log.i("GroupChatVM", "停止录音 userExplicitStop=$userExplicitStop")

        timeoutJob?.cancel()
        timeoutJob = null

        if (userExplicitStop) {
            try { botConnections[sequentialBotIds.firstOrNull()]?.sendAbort("user_stop") } catch (_: Exception) { }
        }

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        audioService.abandonAudioFocus()

        // 不取消 STT 收集器！STT 结果在停录后才会到达，等下一次 startVoiceInput 自然替换
        // sttCollectorJob?.cancel()  // 错误：会导致 STT 文字不显示

        try { botConnections[sequentialBotIds.firstOrNull()]?.sendListenStopOnly() } catch (_: Exception) { }
    }

    // 最近消息去重缓存：botId -> 最近 N 条消息内容
    private val recentBotReplies = ConcurrentHashMap<String, MutableList<String>>()
    private val MAX_RECENT_REPLIES = 20

    private fun isDuplicateReply(botId: String, content: String): Boolean {
        val recent = recentBotReplies.getOrPut(botId) { mutableListOf() }
        // 检查是否与最近的任何一条完全相同
        if (recent.contains(content)) return true
        recent.add(content)
        if (recent.size > MAX_RECENT_REPLIES) recent.removeAt(0)
        return false
    }

    private fun observeBotReplies(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        val replyContent = AtomicReference("")

        viewModelScope.launch {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) {
                    replyContent.getAndUpdate { old ->
                        if (old.isBlank()) text else "$old $text"
                    }
                }
            }
        }

        viewModelScope.launch {
            service.chatMessages.collect { chatMsg ->
                if (chatMsg.direction == MessageDirection.RECEIVED && chatMsg.content.isNotBlank()) {
                    if (isDuplicateReply(botId, chatMsg.content)) {
                        android.util.Log.w("GroupChatVM", "[$botName] 跳过重复消息: ${chatMsg.content.take(30)}")
                        return@collect
                    }
                    val replyMessage = GroupMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        groupId = groupId,
                        botId = botId,
                        botName = botName,
                        content = chatMsg.content,
                        type = MessageType.TEXT,
                        direction = MessageDirection.RECEIVED
                    )
                    groupChatRepository.addMessage(groupId, replyMessage)
                }
            }
        }

        var hasSpoken = false
        var speakerSession = 0L

        viewModelScope.launch {
            service.aiState.collect { state ->
                val curReply = replyContent.get()
                android.util.Log.i("GroupChatVM", "[$botName] aiState=$state reply='${curReply.take(60)}' fwd=$forwardRound ws=$waitingSpeakerIndex")
                if (state == "speaking") {
                    val currentBots = sequentialBotIds
                    val botIndex = currentBots.indexOf(botId)
                    if (botIndex != waitingSpeakerIndex && botIndex >= 0) {
                        android.util.Log.w("GroupChatVM", "[$botName] 非预期发言(idx=$botIndex, expected=$waitingSpeakerIndex)，发送 abort")
                        service.sendAbort("unexpected_speaker")
                        if (waitingSpeakerIndex >= 0 && waitingSpeakerIndex < currentBots.size) {
                            val expectedId = currentBots[waitingSpeakerIndex]
                            val expectedSvc = botConnections[expectedId]
                            val expectedConnected = expectedSvc?.connectionState?.value is XiaoZhiBackendService.ConnectionState.Connected
                            if (!expectedConnected) {
                                android.util.Log.i("GroupChatVM", "期待bot离线，尝试其他候选")
                                val prevBotId = sequentialBotIds.getOrNull(currentSpeakerIndex)
                                drainPreviousBotAudio(prevBotId)
                                viewModelScope.launch {
                                    speakerMutex.withLock {
                                        if (forwardRound < MAX_FORWARD_ROUNDS) {
                                            triggerNextSpeakerUnsafe(replyContent.getAndSet(""))
                                        }
                                    }
                                }
                            }
                        }
                        return@collect
                    }
                    hasSpoken = true
                    speakerSession = relaySessionId
                    if (botId == sequentialBotIds.firstOrNull()) {
                        if (_isRecording.value) {
                            android.util.Log.i("GroupChatVM", "AI开始说话，自动停止录音")
                            stopVoiceInput(userExplicitStop = false)
                        }
                        sttCollectorJob?.cancel()
                    }
                    // 预创建解码器，不等音频包到了才创建（避免第一包延迟播放）
                    // 不放 speakerMutex 内，避免被上一轮 idle handler 阻塞
                    if (botAudioDecoders[botId] == null) {
                        val serverRate = service.getServerSampleRate()
                        val serverCh = service.getServerChannels()
                        val newDecoder = NativeOpusDecoder(serverRate, 16000, serverCh)
                        newDecoder.init()
                        botAudioDecoders[botId] = newDecoder
                        android.util.Log.i("GroupChatVM", "[$botName] 预创建解码器 (${serverRate}Hz)")
                    }
                }
                if (state == "idle" && hasSpoken) {
                    hasSpoken = false
                    // 先排空前一个 bot 的音讯（不在 mutex 内，避免阻塞其他 bot 的音讯处理）
                    val prevBotId = sequentialBotIds.getOrNull(currentSpeakerIndex)
                    drainPreviousBotAudio(prevBotId)
                    speakerMutex.withLock {
                        if (speakerSession != relaySessionId) {
                            android.util.Log.i("GroupChatVM", "[$botName] 跳过旧轮次触发 speakerSession=$speakerSession currentSession=$relaySessionId")
                            return@withLock
                        }
                        val currentBots = sequentialBotIds
                        val botIndex = currentBots.indexOf(botId)
                        if (botIndex < 0) return@withLock
                        val isExpected = botIndex == waitingSpeakerIndex
                        if (isExpected && forwardRound < MAX_FORWARD_ROUNDS && currentBots.size > 1) {
                            val reply = replyContent.getAndSet("")
                            // forwardRound 在 triggerNextSpeakerUnsafe 内部转发成功时递增，避免文字兜底浪费轮次
                            android.util.Log.i("GroupChatVM", "[$botName] triggering next, fwd=$forwardRound reply='${reply.take(60)}'")
                            triggerNextSpeakerUnsafe(reply)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            service.audioData.collect { audioData ->
                if (audioData.isNotEmpty()) {
                    botLastAudioTime[botId] = System.currentTimeMillis()
                    // 非当前轮到的 bot，丢弃其音频（防止非预期发言的声音溢出）
                    val currentBots = sequentialBotIds
                    val idx = currentBots.indexOf(botId)
                    if (idx >= 0 && idx != waitingSpeakerIndex) return@collect
                    val existingDecoder = botAudioDecoders[botId]
                    val finalDecoder = if (existingDecoder == null) {
                        // 先排空前一个 bot 音讯（不在 mutex 内，避免阻塞音频处理）
                        drainPreviousDecodersBeforeSwitch()
                        speakerMutex.withLock {
                            var d = botAudioDecoders[botId]
                            if (d == null) {
                                releaseOldDecoders()
                                android.util.Log.i("GroupChatVM", "[$botName] 创建新解码器")
                                val serverRate = service.getServerSampleRate()
                                val serverCh = service.getServerChannels()
                                d = NativeOpusDecoder(serverRate, 16000, serverCh)
                                d.init()
                                botAudioDecoders[botId] = d
                            }
                            d
                        }
                    } else {
                        existingDecoder
                    }
                    finalDecoder.decodePacket(audioData)
                    val buf = audioForwardBuffer.getOrPut(botId) { mutableListOf() }
                    buf.add(audioData)
                }
            }
        }
    }

    private val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")

    private suspend fun triggerNextSpeakerUnsafe(previousReply: String = "") {
        val currentBots = sequentialBotIds
        if (currentBots.size <= 1) {
            waitingSpeakerIndex = -1
            return
        }
        if (_isRecording.value) {
            android.util.Log.w("GroupChatVM", "用户正在录音，跳过转发")
            waitingSpeakerIndex = -1
            return
        }

        // 检测结束语：bot互相说再见时提前终止接龙
        if (previousReply.isNotBlank() && GOODBYE_KEYWORDS.any { previousReply.contains(it) }) {
            android.util.Log.i("GroupChatVM", "检测到结束语，停止接龙: '${previousReply.take(20)}'")
            waitingSpeakerIndex = -1
            return
        }

        val prevIndex = currentSpeakerIndex

        // Fix 2+8: 智能候选列表 + 故障转移
        val candidates = resolveSpeakerCandidates(prevIndex, previousReply, currentBots)

        for ((targetIndex, speakerId) in candidates) {
            val service = botConnections[speakerId] ?: continue

            // 尽早声明预期speaker，减少非预期发言窗口
            waitingSpeakerIndex = targetIndex
            currentSpeakerIndex = targetIndex

            if (!ensureBotConnected(service, speakerId)) {
                android.util.Log.w("GroupChatVM", "跳过离线bot: $speakerId")
                continue
            }

            // 音频优先转发（保留完整声学特征，STT识别更准）
            val audioOk = forwardAudioToBot(prevIndex, service)
            if (audioOk) {
                android.util.Log.i("GroupChatVM", "等待 $speakerId 开始说话 (10s超时)")
                val startConfirmed = withTimeoutOrNull(10_000) {
                    service.aiState.first { it == "speaking" }
                }
                if (startConfirmed != null) {
                    val hint = if (previousReply.isNotBlank()) originalUserQuery.take(8) else ""
                    // 音频转发成功，检查死循环（hint重复 或 候选重复）
                    if (checkDeadloop(hint, speakerId)) {
                        android.util.Log.i("GroupChatVM", "死循环终止，跳过转发")
                        waitingSpeakerIndex = -1
                        forwardRound = MAX_FORWARD_ROUNDS
                        return
                    }
                    forwardRound++
                    spokenInThisRound.add(targetIndex)
                    if (previousReply.isNotBlank()) originalUserQuery = previousReply.take(20)
                    // 音频转发成功后，才能安全清理 buffer（服务器已收到并开始处理）
                    audioForwardBuffer.remove(sequentialBotIds.getOrNull(prevIndex))
                    android.util.Log.i("GroupChatVM", "音频转发成功 → $speakerId fwd=$forwardRound")
                    return
                }
                // 音频转发成功但 bot 10s 未确认 speaking：不清 buffer，不耗轮次
                // buffer 保留供下一候选使用（可能是服务器处理慢）
                android.util.Log.w("GroupChatVM", "音频转发超时($speakerId 10s未响应)，尝试下一候选")
                waitingSpeakerIndex = -1
            }

            // 音频无缓存或失败时，文字兜底
            val textToSend = buildForwardMessage(previousReply)
            // 防死循环检测（只检查hint重复，不检查候选，因为这是失败候选）
            if (checkDeadloop(textToSend)) {
                android.util.Log.i("GroupChatVM", "死循环终止，跳过本轮转发")
                waitingSpeakerIndex = -1
                return
            }
            if (textToSend.isNotBlank()) {
                // 发送前确认WebSocket已连接
                if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                    android.util.Log.w("GroupChatVM", "文字兜底失败: $speakerId WebSocket未连接，尝试下一候选")
                    waitingSpeakerIndex = -1
                    continue
                }
                android.util.Log.i("GroupChatVM", "文字兜底 ${textToSend.length}字 → $speakerId: '$textToSend'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) originalUserQuery = previousReply.take(20)
                    android.util.Log.i("GroupChatVM", "等待 $speakerId 开始说话 (10s超时)")
                    val startConfirmed = withTimeoutOrNull(10_000) {
                        service.aiState.first { it == "speaking" }
                    }
                    if (startConfirmed != null) {
                        textFallbackBots.add(speakerId)
                        spokenInThisRound.add(targetIndex)
                        android.util.Log.i("GroupChatVM", "文字兜底转发成功")
                        return
                    }
                    android.util.Log.w("GroupChatVM", "文字兜底超时($speakerId 10s未响应)，尝试下一候选")
                    waitingSpeakerIndex = -1
                } catch (e: Exception) {
                    android.util.Log.w("GroupChatVM", "文字兜底异常: ${e.message}")
                    waitingSpeakerIndex = -1
                }
            }
        }

        android.util.Log.w("GroupChatVM", "无可用bot转发，跳过本轮")
        waitingSpeakerIndex = -1
        currentSpeakerIndex = currentBots.indexOf(sequentialBotIds.firstOrNull())
        if (currentSpeakerIndex < 0) currentSpeakerIndex = 0
    }

    // 智能 speaker 选择：评分制 + 语义权重 + 随机性
    private fun resolveSpeakerCandidates(
        prevIndex: Int,
        previousReply: String,
        currentBots: List<String>
    ): List<Pair<Int, String>> {
        if (currentBots.isEmpty()) return emptyList()
        data class Candidate(val index: Int, val id: String, var score: Float)
        val candidates = currentBots.indices
            .filter { it != prevIndex }
            .map { Candidate(it, currentBots[it], 0f) }
        if (candidates.isEmpty()) return emptyList()

        if (previousReply.isNotBlank()) {
            // 1. @mention: 最高优先级
            val mentionMatch = Regex("@([\\w\\u4e00-\\u9fff]+)").find(previousReply)
            if (mentionMatch != null) {
                val mentionedName = mentionMatch.groupValues[1]
                candidates.forEach { c ->
                    val contact = _contacts.value.find { it.id == c.id }
                    if (contact != null && (contact.name == mentionedName || contact.id.startsWith(mentionedName))) {
                        c.score += 100f
                        android.util.Log.i("GroupChatVM", "@提及 ${contact.name} → score=100")
                    }
                }
            }

            // 2. 名字提及（不含 @）：部分匹配也行
            candidates.forEach { c ->
                val contact = _contacts.value.find { it.id == c.id }
                if (contact != null && contact.name.isNotBlank() && previousReply.contains(contact.name)) {
                    c.score += 30f
                    android.util.Log.i("GroupChatVM", "名字提及 ${contact.name} → score+=30")
                }
            }
        }

        // 3. 本轮未发言的 bot：加分优先拉入
        candidates.forEach { c ->
            if (c.index !in spokenInThisRound) {
                c.score += 20f
            }
        }

        // 4. 基础随机权重：让对话流动更自然（0~15 分随机）
        candidates.forEach { c ->
            c.score += (Math.random().toFloat() * 15f)
        }

        // 按评分降序排列，同分时打乱
        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.score }
            .thenBy { Math.random() })

        // 如果所有候选都已发言，重置 spokenInThisRound
        if (sorted.all { it.index in spokenInThisRound }) {
            spokenInThisRound.clear()
        }

        val result = sorted.map { it.index to it.id }
        android.util.Log.i("GroupChatVM", "候选排序: ${result.joinToString { i -> "${_contacts.value.find { c -> c.id == i.second }?.name ?: i.second}[${String.format("%.0f", candidates.find { it.index == i.first }?.score ?: 0f)}]" }}")
        return result
    }

    // Fix 8: 连接保活 + 超时跳过
    private suspend fun ensureBotConnected(
        service: XiaoZhiBackendService,
        botId: String
    ): Boolean {
        if (service.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) return true
        android.util.Log.w("GroupChatVM", "重新连接: $botId")
        val contact = _contacts.value.find { it.id == botId } ?: return false
        service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
        return withTimeoutOrNull(5000) {
            service.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
        } != null
    }

    // 等待上一个 bot 的音频流自然排空（网络静默 + AudioTrack缓冲排空）
    private suspend fun drainPreviousBotAudio(prevBotId: String?) {
        if (prevBotId == null) return
        // 1. 等待网络层面静默（确保服务器已停止发送）
        withTimeoutOrNull(AUDIO_DRAIN_TIMEOUT_MS) {
            while (true) {
                val lastTs = botLastAudioTime[prevBotId] ?: 0L
                if (lastTs <= 0) break
                if (System.currentTimeMillis() - lastTs > AUDIO_DRAIN_SILENCE_MS) break
                delay(100)
            }
        }
        // 2. 额外等待一段时间，让网络缓冲区中的 in-flight 包全部到达
        delay(500)
        // 3. 解码器内部缓冲排空（decoder 仍然在处理 late packets）
        val decoder = botAudioDecoders[prevBotId]
        if (decoder != null && !decoder.isReleased) {
            withTimeoutOrNull(2000L) {
                while (decoder.getRemainingBytes() > 0) {
                    delay(50)
                }
            }
        }
        // 4. AudioTrack 缓冲排空（等待旧 bot 的声音完全播放完再切换）
        //    releaseOldDecoders 由调用方在 speakerMutex 内执行
    }

    // 切换到新bot前，排空旧解码器的AudioTrack缓冲（只排空，不释放，不阻塞音频收集）
    private suspend fun drainPreviousDecodersBeforeSwitch() {
        val currentId = sequentialBotIds.getOrNull(currentSpeakerIndex)
        for ((_, decoder) in botAudioDecoders.filterKeys { it != currentId }) {
            if (decoder.isReleased) continue
            withTimeoutOrNull(2000L) {
                decoder.drainAudio(2000L)
            }
        }
    }

    // 释放旧解码器（快速操作，只做 release + remove，在 speakerMutex 内调用）
    private fun releaseOldDecoders() {
        val currentId = sequentialBotIds.getOrNull(currentSpeakerIndex)
        val toRelease = botAudioDecoders.filterKeys { it != currentId }
        toRelease.values.forEach { if (!it.isReleased) it.release() }
        botAudioDecoders.keys.removeAll(toRelease.keys)
        toRelease.keys.forEach { android.util.Log.i("GroupChatVM", "释放解码器: $it") }
    }

    // Fix 3: 转发音频包，失败返回false以便fallback
    private suspend fun forwardAudioToBot(
        prevIndex: Int,
        service: XiaoZhiBackendService
    ): Boolean {
        val prevBotId = sequentialBotIds.getOrNull(prevIndex) ?: return false
        // 快照复制，不破坏缓存（失败后下一候选还能用）
        val cachedList = audioForwardBuffer[prevBotId] ?: return false
        if (cachedList.isEmpty()) return false
        val cachedOpus = cachedList.toList()
        val totalBytes = cachedOpus.sumOf { it.size }
        android.util.Log.i("GroupChatVM", "音频转发 ${cachedOpus.size}包/${totalBytes}B prev=$prevBotId")
        try {
            service.sendListenStart("auto")
            // 前10帧慢速让ASR初始化，之后偶尔让出CPU
            cachedOpus.forEachIndexed { i, packet ->
                service.sendAudio(packet)
                when {
                    i < 10 -> delay(60)
                    i % 20 == 0 -> delay(10)
                }
            }
            // 等待最后一帧协程处理完成
            delay(500)
            service.sendListenStopOnly()
            //  buffer 由调用方在确认 bot speaking 后才清理（避免服务器还在处理时被清掉）
            return true
        } catch (e: Exception) {
            android.util.Log.w("GroupChatVM", "音频转发失败: ${e.message}")
            return false
        }
    }

    // 服务器协议限制 text ≤ 8 字符（仅支持 state="detect" 唤醒词模式）
    // 文本作为"关键字提示"，音频承载完整上下文
    private fun buildForwardMessage(previousReply: String): String {
        if (previousReply.isBlank()) return ""
        val hint = if (originalUserQuery.isNotBlank()) {
            originalUserQuery.take(8)
        } else {
            truncateReply(previousReply, 8)
        }
        android.util.Log.i("GroupChatVM", "文本关键字(≤8字): '$hint'")
        return hint
    }

    // 检测并处理死循环：连续 MAX_DEADLOOP 轮 hint 高度重复 或 同一候选重复
    private fun checkDeadloop(hint: String, speakerId: String? = null): Boolean {
        var isDeadloop = false
        // 检测 hint 重复（空白 hint 不参与检测）
        if (hint.isNotBlank()) {
            recentHints.add(hint)
            if (recentHints.size > MAX_DEADLOOP) recentHints.removeAt(0)
            if (recentHints.size >= MAX_DEADLOOP && recentHints.distinct().size <= 2) {
                android.util.Log.w("GroupChatVM", "死循环(hint重复) hint=$hint")
                isDeadloop = true
            }
        }
        // 检测候选重复：同一 bot 被连续选中
        if (speakerId != null) {
            recentCandidates.add(speakerId)
            if (recentCandidates.size > MAX_DEADLOOP) recentCandidates.removeAt(0)
            if (recentCandidates.size >= MAX_DEADLOOP && recentCandidates.distinct().size == 1) {
                android.util.Log.w("GroupChatVM", "死循环(候选重复) speakerId=$speakerId")
                isDeadloop = true
            }
        }
        if (isDeadloop) {
            deadloopCount++
            if (deadloopCount >= MAX_DEADLOOP) {
                android.util.Log.i("GroupChatVM", "死循环 $MAX_DEADLOOP 次，强制终止转发")
                deadloopCount = 0
                recentHints.clear()
                recentCandidates.clear()
                waitingSpeakerIndex = -1
                forwardRound = MAX_FORWARD_ROUNDS
                return true
            }
        } else {
            deadloopCount = 0
        }
        return false
    }

    // Fix 5: 按句末标点 + 上下文边界的智能截断
    private fun truncateReply(text: String, maxLen: Int = 24): String {
        if (text.isBlank()) return ""
        val clean = text.trim()
        if (clean.length <= maxLen) return clean
        val sentenceEnd = clean.indexOfAny(charArrayOf('。', '！', '？', '\n'))
        if (sentenceEnd in 1..maxLen) return clean.substring(0, sentenceEnd)
        val boundary = clean.lastIndexOfAny(
            charArrayOf('，', '、', '；', '：', ' ', ','),
            maxLen
        )
        if (boundary > 3) return clean.substring(0, boundary)
        return clean.substring(0, maxLen)
    }

    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        val group = groupChatRepository.createGroup(name, selectedBotIds)
        loadGroups()
        return group
    }

    suspend fun deleteGroup(groupId: String) {
        groupChatRepository.deleteGroup(groupId)
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
        botAudioDecoders.values.forEach { it.release() }
        botAudioDecoders.clear()
        loadGroups()
    }

    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        return groupChatRepository.getGroupMessages(groupId)
    }

    suspend fun sendMessageToGroup(groupId: String, content: String) {
        val userMessageId = java.util.UUID.randomUUID().toString()
        val userMessage = GroupMessage(
            id = userMessageId,
            groupId = groupId,
            botId = "",
            botName = "我",
            content = content,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT
        )
        groupChatRepository.addMessage(groupId, userMessage)

        relaySessionId++

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = 0
        audioForwardBuffer.clear()
        recentBotReplies.clear()
        recentHints.clear()
        recentCandidates.clear()
        spokenInThisRound.clear()
        textFallbackBots.clear()
        originalUserQuery = content

        if (sequentialBotIds.isNotEmpty()) {
            val firstBotId = sequentialBotIds[0]
            val svc = botConnections[firstBotId]
            if (svc != null) {
                val connected = withTimeoutOrNull(5_000) {
                    if (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) return@withTimeoutOrNull true
                    val contact = _contacts.value.find { it.id == firstBotId }
                    if (contact != null) {
                        svc.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
                    }
                    svc.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
                    true
                }
                if (connected == true) {
                    svc.sendTextContent(content)
                } else {
                    android.util.Log.w("GroupChatVM", "sendMessage: 首bot连接失败")
                }
            }
        }
    }

    suspend fun sendMessage(groupId: String, content: String) {
        sendMessageToGroup(groupId, content)
    }

    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatRepository.updateGroupName(groupId, newName)
        loadGroups()
    }

    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatRepository.addBotToGroup(groupId, botId)
        sequentialBotIds = sequentialBotIds + botId
        spokenInThisRound.clear()
        loadGroups()
    }

    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        sequentialBotIds = sequentialBotIds - botId
        spokenInThisRound.clear()
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
                val sender = if (msg.direction == MessageDirection.SENT) "我" else msg.botName
                "[${sender}]: ${msg.content}"
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "群聊记录")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "导出群聊记录"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceInput()
        sttCollectorJob?.cancel()
        botConnections.forEach { (_, service) -> service.disconnect() }
        botConnections.clear()
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
