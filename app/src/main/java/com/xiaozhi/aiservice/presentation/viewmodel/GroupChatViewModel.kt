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

class GroupChatViewModel : ViewModel() {
    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository

    private val botConnections = mutableMapOf<String, XiaoZhiBackendService>()
    private val botAudioDecoders = mutableMapOf<String, NativeOpusDecoder>()

    private var sequentialBotIds = listOf<String>()
    private var currentSpeakerIndex = 0
    private var forwardRound = 0
    private val MAX_FORWARD_ROUNDS = 15
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
        currentGroupId = groupId

        viewModelScope.launch {
            val group = groupChatRepository.getGroup(groupId)
            group?.let { g ->
                g.botIds.map { botId ->
                    async {
                        val contact = _contacts.value.find { it.id == botId }
                        contact?.let { c ->
                            if (!botConnections.containsKey(botId)) {
                                val service = XiaoZhiBackendService(AppContainer.gson)
                                botConnections[botId] = service
                                observeBotReplies(botId, groupId, c.name, service)
                                service.connect(c.serverUrl, c.otaUrl, c.deviceId, c.clientId)
                            }
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
        val firstBotId = sequentialBotIds[0]
        val service = botConnections[firstBotId] ?: return

        // 递增轮次ID，让旧轮次的 aiState 回调不再触发下一人
        relaySessionId++

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = 0
        originalUserQuery = ""
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
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
        var replyContent = ""

        viewModelScope.launch {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) {
                    replyContent = if (replyContent.isBlank()) text else "$replyContent $text"
                }
            }
        }

        viewModelScope.launch {
            service.chatMessages.collect { chatMsg ->
                if (chatMsg.direction == MessageDirection.RECEIVED && chatMsg.content.isNotBlank()) {
                    // 内容去重：同一 bot 的相同内容跳过（防止循环接龙重复）
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
        var speakerSession = 0L  // 记录此bot开始说话时的轮次ID

        viewModelScope.launch {
            service.aiState.collect { state ->
                android.util.Log.i("GroupChatVM", "[$botName] aiState=$state reply='${replyContent.take(20)}' fwd=$forwardRound ws=$waitingSpeakerIndex")
                if (state == "speaking") {
                    // 非预期发言（不是本轮选中的 bot），立即 abort 阻止
                    val currentBots = sequentialBotIds
                    val botIndex = currentBots.indexOf(botId)
                    if (botIndex != waitingSpeakerIndex && botIndex >= 0) {
                        android.util.Log.w("GroupChatVM", "[$botName] 非预期发言(idx=$botIndex, expected=$waitingSpeakerIndex)，发送 abort")
                        service.sendAbort("unexpected_speaker")
                        return@collect
                    }
                    hasSpoken = true
                    speakerSession = relaySessionId  // 记住当前轮次
                    if (botId == sequentialBotIds.firstOrNull() && _isRecording.value) {
                        android.util.Log.i("GroupChatVM", "AI开始说话，自动停止录音")
                        stopVoiceInput(userExplicitStop = false)
                    }
                }
                if (state == "idle" && hasSpoken) {
                    hasSpoken = false
                    speakerMutex.withLock {
                        // 如果本轮次已被新输入取代（relaySessionId已递增），不触发下一人
                        if (speakerSession != relaySessionId) {
                            android.util.Log.i("GroupChatVM", "[$botName] 跳过旧轮次触发 speakerSession=$speakerSession currentSession=$relaySessionId")
                            return@withLock
                        }
                        val currentBots = sequentialBotIds
                        val botIndex = currentBots.indexOf(botId)
                        if (botIndex < 0) return@withLock
                        val isExpected = botIndex == waitingSpeakerIndex
                        if (isExpected && forwardRound < MAX_FORWARD_ROUNDS && currentBots.size > 1) {
                            val reply = replyContent
                            replyContent = ""
                            forwardRound++
                            android.util.Log.i("GroupChatVM", "[$botName] triggering next, fwd=$forwardRound reply='${reply.take(30)}'")
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
                    var decoder = botAudioDecoders[botId]
                    if (decoder == null) {
                        val serverRate = service.getServerSampleRate()
                        val serverCh = service.getServerChannels()
                        decoder = NativeOpusDecoder(serverRate, 16000, serverCh)
                        decoder.init()
                        botAudioDecoders[botId] = decoder
                        android.util.Log.i("GroupChatVM", "[$botName] 创建解码器")
                    }
                    // 解码播放
                    decoder.decodePacket(audioData)
                    val buf = audioForwardBuffer.getOrPut(botId) { mutableListOf() }
                    buf.add(audioData)
                }
            }
        }
    }

    private val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")

    private suspend fun triggerNextSpeakerUnsafe(previousReply: String = "") {
        val currentBots = sequentialBotIds
        if (currentBots.size <= 1) return
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

            val prevBotId = currentBots.getOrNull(prevIndex)
            drainPreviousBotAudio(prevBotId)

            // 文本关键字优先（清洁文本，无STT退化）
            val textToSend = buildForwardMessage(previousReply)
            if (textToSend.isNotBlank()) {
                android.util.Log.i("GroupChatVM", "文字转发 ${textToSend.length}字 → $speakerId: '$textToSend'")
                try {
                    service.sendTextContent(textToSend)
                    spokenInThisRound.add(targetIndex)
                    if (previousReply.isNotBlank()) originalUserQuery = previousReply.take(20)
                    android.util.Log.i("GroupChatVM", "文本关键字转发成功")
                    return
                } catch (e: Exception) {
                    android.util.Log.w("GroupChatVM", "文字转发异常: ${e.message}")
                }
            }

            // 文本无内容时，音频兜底
            val audioOk = forwardAudioToBot(prevIndex, service)
            if (audioOk) {
                spokenInThisRound.add(targetIndex)
                if (previousReply.isNotBlank()) originalUserQuery = previousReply.take(20)
                android.util.Log.i("GroupChatVM", "音频兜底转发成功")
                return
            }
        }

        android.util.Log.w("GroupChatVM", "无可用bot转发，跳过本轮")
        waitingSpeakerIndex = -1
    }

    // Fix 2: 智能 speaker 选择
    private fun resolveSpeakerCandidates(
        prevIndex: Int,
        previousReply: String,
        currentBots: List<String>
    ): List<Pair<Int, String>> {
        val targets = mutableListOf<Pair<Int, String>>()

        // 优先: @BotName 定向提及
        if (previousReply.isNotBlank()) {
            val mentionMatch = Regex("@([\\w\\u4e00-\\u9fff]+)").find(previousReply)
            if (mentionMatch != null) {
                val mentionedName = mentionMatch.groupValues[1]
                val contact = _contacts.value.find {
                    it.name == mentionedName || it.id.startsWith(mentionedName)
                }
                if (contact != null) {
                    val idx = currentBots.indexOf(contact.id)
                    if (idx >= 0 && idx != prevIndex) {
                        android.util.Log.i("GroupChatVM", "@提及 $mentionedName → index=$idx")
                        targets.add(idx to contact.id)
                        return targets
                    }
                }
            }
        }

        // 轮询: 优先本轮未发言的bot
        val unspoken = currentBots.indices.filter {
            it != prevIndex && it !in spokenInThisRound
        }
        if (unspoken.isNotEmpty()) {
            targets.addAll(unspoken.shuffled().map { it to currentBots[it] })
            return targets
        }

        // 全部已发言: 重置轮次并打乱
        spokenInThisRound.clear()
        val allExceptPrev = currentBots.indices.filter { it != prevIndex }.shuffled()
        targets.addAll(allExceptPrev.map { it to currentBots[it] })
        return targets
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

    // Fix 3: 等待上一个 bot 的音频流自然排空（不硬编码800ms）
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
    }

    // Fix 3: 转发音频包，失败返回false以便fallback
    private suspend fun forwardAudioToBot(
        prevIndex: Int,
        service: XiaoZhiBackendService
    ): Boolean {
        val prevBotId = sequentialBotIds.getOrNull(prevIndex) ?: return false
        val cachedOpus = audioForwardBuffer.remove(prevBotId) ?: return false
        if (cachedOpus.isEmpty()) return false
        val totalBytes = cachedOpus.sumOf { it.size }
        android.util.Log.i("GroupChatVM", "音频转发 ${cachedOpus.size}包/${totalBytes}B prev=$prevBotId")
        try {
            service.sendListenStart("auto")
            cachedOpus.forEach { packet ->
                service.sendAudio(packet)
                delay(50)
            }
            service.sendListenStopOnly()
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
            originalUserQuery.take(6)
        } else {
            truncateReply(previousReply, 6)
        }
        android.util.Log.i("GroupChatVM", "文本关键字(≤8字): '$hint'")
        return hint
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
        spokenInThisRound.clear()
        originalUserQuery = content

        if (sequentialBotIds.isNotEmpty()) {
            val firstBotId = sequentialBotIds[0]
            botConnections[firstBotId]?.sendTextContent(content)
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
