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
    private val MAX_FORWARD_ROUNDS = 100
    private var originalUserQuery = ""

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
        recentBotReplies.clear()
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
        waitingSpeakerIndex = -1
        originalUserQuery = ""
        audioForwardBuffer.clear()
        recentBotReplies.clear()

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

        // 取消 STT 收集器，防止后续轮次误添加旧 STT 结果
        sttCollectorJob?.cancel()
        sttCollectorJob = null

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

        if (botId == sequentialBotIds.firstOrNull()) {
            viewModelScope.launch {
                service.connectionState.collect { state ->
                    if (state is XiaoZhiBackendService.ConnectionState.Connected && forwardRound != 0) {
                        forwardRound = 0
                        audioForwardBuffer.clear()
                        waitingSpeakerIndex = -1
                    }
                }
            }
        }

        var hasSpoken = false
        var speakerSession = 0L  // 记录此bot开始说话时的轮次ID

        viewModelScope.launch {
            service.aiState.collect { state ->
                android.util.Log.i("GroupChatVM", "[$botName] aiState=$state reply='${replyContent.take(20)}' fwd=$forwardRound ws=$waitingSpeakerIndex")
                if (state == "speaking") {
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
                        val isExpected = waitingSpeakerIndex == -1 || botIndex == waitingSpeakerIndex
                        if (isExpected && forwardRound < MAX_FORWARD_ROUNDS && currentBots.size > 1) {
                            replyContent = ""
                            forwardRound++
                            android.util.Log.i("GroupChatVM", "[$botName] triggering next, fwd=$forwardRound")
                            triggerNextSpeakerUnsafe()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            service.audioData.collect { audioData ->
                if (audioData.isNotEmpty()) {
                    var decoder = botAudioDecoders[botId]
                    if (decoder == null) {
                        val serverRate = service.getServerSampleRate()
                        val serverCh = service.getServerChannels()
                        decoder = NativeOpusDecoder(serverRate, 16000, serverCh)
                        decoder.init()
                        botAudioDecoders[botId] = decoder
                    }
                    // 解码播放
                    decoder.decodePacket(audioData)
                    val buf = audioForwardBuffer.getOrPut(botId) { mutableListOf() }
                    buf.add(audioData)
                }
            }
        }
    }

    private suspend fun triggerNextSpeakerUnsafe() {
        val currentBots = sequentialBotIds
        if (currentBots.size <= 1) return
        // 用户正在录音时禁止转发（防止干扰）
        if (_isRecording.value) {
            android.util.Log.w("GroupChatVM", "triggerNextSpeaker: 用户正在录音，跳过转发")
            waitingSpeakerIndex = -1
            return
        }
        val prevIndex = currentSpeakerIndex
        val available = currentBots.filterIndexed { i, _ -> i != prevIndex }
        if (available.isEmpty()) return
        val nextIndex = currentBots.indexOf(available.random())
        if (nextIndex < 0) return
        val speakerId = currentBots[nextIndex]
        val service = botConnections[speakerId]
        if (service == null) return
        waitingSpeakerIndex = nextIndex
        currentSpeakerIndex = nextIndex
        if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
            android.util.Log.w("GroupChatVM", "triggerNextSpeaker: $speakerId disconnected, reconnecting...")
            val contact = _contacts.value.find { it.id == speakerId }
            if (contact != null) service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
            val connected = withTimeoutOrNull(5000) {
                service.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
            }
            if (connected == null) {
                waitingSpeakerIndex = -1; return
            }
        }
        // 转发上一bot的Opus音频给下一bot
        val prevBotId = sequentialBotIds.getOrNull(prevIndex)
        // 等待音频数据全部落盘（避免最后几帧协程还没跑到就被拿走）
        kotlinx.coroutines.delay(500)
        val cachedOpus = prevBotId?.let { audioForwardBuffer.remove(it) }
        if (!cachedOpus.isNullOrEmpty()) {
            val totalBytes = cachedOpus.sumOf { it.size }
            android.util.Log.i("GroupChatVM", "转发: ${cachedOpus.size}个包/${totalBytes}B → $speakerId, 首包=${cachedOpus.firstOrNull()?.size}B")
            try {
                service.sendListenStart("auto")
                // 模拟自然语音节奏，逐帧发送（每帧间隔≈实际帧时长）
                cachedOpus.forEach { packet ->
                    service.sendAudio(packet)
                    kotlinx.coroutines.delay(30)  // 30ms间隔 ≈ 60ms帧半速
                }
                service.sendListenStopOnly()
                return
            } catch (e: Exception) {
                android.util.Log.w("GroupChatVM", "转发失败: ${e.message}")
            }
        }
        android.util.Log.w("GroupChatVM", "无音频缓存，跳过本轮")
        waitingSpeakerIndex = -1
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
        waitingSpeakerIndex = -1
        audioForwardBuffer.clear()
        recentBotReplies.clear()
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
        loadGroups()
    }

    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        sequentialBotIds = sequentialBotIds - botId
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
