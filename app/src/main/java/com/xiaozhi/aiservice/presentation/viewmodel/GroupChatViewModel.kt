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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GroupChatViewModel : ViewModel() {
    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository

    private val botConnections = mutableMapOf<String, XiaoZhiBackendService>()
    private val botAudioDecoders = mutableMapOf<String, NativeOpusDecoder>()

    private var sequentialBotIds = listOf<String>()
    private var currentSpeakerIndex = 0
    private var forwardRound = 0
    private val MAX_FORWARD_ROUNDS = 100
    private var lastSequentialContent = ""

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
        currentGroupId = groupId
        viewModelScope.launch {
            val group = groupChatRepository.getGroup(groupId)
            group?.let { g ->
                g.botIds.forEach { botId ->
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

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = -1
        lastSequentialContent = ""

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        viewModelScope.launch {
            audioService.setOnSilenceDetected {
                android.util.Log.i("GroupChatVM", "VAD触发自动停止")
                viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
            }

            service.sendListenStart("auto")
            _isRecording.value = true

            // 安全兜底：15秒后强制停止（防止VAD因环境噪音不触发）
            val safetyJob = launch {
                kotlinx.coroutines.delay(15_000)
                if (_isRecording.value) {
                    android.util.Log.w("GroupChatVM", "15秒超时强制停止录音")
                    stopVoiceInput(userExplicitStop = false)
                }
            }

            kotlinx.coroutines.delay(500)

            val sampleRate = audioService.getSampleRate()
            val encoder = OpusEncoder(sampleRate = sampleRate)
            opusEncoder = encoder

            if (!encoder.init { opusPacket ->
                    service.sendAudio(opusPacket)
                }
            ) {
                android.util.Log.w("GroupChatVM", "编码器初始化失败")
                safetyJob.cancel()
                service.sendListenStop()
                opusEncoder = null
                _isRecording.value = false
                return@launch
            }

            if (!audioService.startRecording({ pcmData ->
                    encoder.encode(pcmData)
                }, enableVAD = true)
            ) {
                android.util.Log.w("GroupChatVM", "录音启动失败")
                safetyJob.cancel()
                service.sendListenStop()
                opusEncoder?.stop()
                opusEncoder = null
                _isRecording.value = false
                return@launch
            }

            // 监听语音STT结果，保存为用户消息（取消上一次的收集器避免重复）
            sttCollectorJob?.cancel()
            sttCollectorJob = launch {
                service.textMessage.drop(1).collect { text ->
                    if (text.isNotBlank()) {
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
                    }
                }
            }
        }
    }

    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        if (!_isRecording.value) return

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        sttCollectorJob?.cancel()
        sttCollectorJob = null

        // 用户手动停止才发送 listen stop；VAD 自动停止由服务器处理
        val firstBotId = sequentialBotIds.firstOrNull()
        if (userExplicitStop) {
            botConnections[firstBotId]?.sendListenStopOnly()
        }

        _isRecording.value = false
    }

    private fun observeBotReplies(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        var replyContent = ""

        viewModelScope.launch {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) {
                    replyContent = text
                }
            }
        }

        viewModelScope.launch {
            service.chatMessages.collect { chatMsg ->
                if (chatMsg.direction == MessageDirection.RECEIVED && chatMsg.content.isNotBlank()) {
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

        viewModelScope.launch {
            service.connectionState.collect { state ->
                if (state is XiaoZhiBackendService.ConnectionState.Connected && botId == sequentialBotIds.firstOrNull() && forwardRound != 0) {
                    forwardRound = 0
                    waitingSpeakerIndex = -1
                }
            }
        }

        viewModelScope.launch {
            service.aiState.collect { state ->
                if (state == "idle" && replyContent.isNotBlank()) {
                    val currentBots = sequentialBotIds
                    val botIndex = currentBots.indexOf(botId)
                    if (botIndex < 0) return@collect
                    val isExpected = waitingSpeakerIndex == -1 || botIndex == waitingSpeakerIndex
                    if (isExpected && forwardRound < MAX_FORWARD_ROUNDS && currentBots.size > 1) {
                        lastSequentialContent = replyContent
                        replyContent = ""
                        forwardRound++
                        triggerNextSpeaker()
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
                    decoder.decodePacket(audioData)
                }
            }
        }
    }

    private suspend fun triggerNextSpeaker() {
        val currentBots = sequentialBotIds
        if (currentBots.size <= 1) return
        val available = currentBots.filterIndexed { i, _ -> i != currentSpeakerIndex }
        if (available.isEmpty()) return
        val nextIndex = currentBots.indexOf(available.random())
        if (nextIndex < 0) return
        val speakerId = currentBots[nextIndex]
        val service = botConnections[speakerId]
        if (service == null || lastSequentialContent.isBlank()) return
        waitingSpeakerIndex = nextIndex
        currentSpeakerIndex = nextIndex
        val cleaned = lastSequentialContent.replace(Regex("[，。！？、；：\\n\\s\"'「」『』【】《》（）]"), "")
            .replace(Regex("[${'\\'}u2600-${'\\'}u27BF${'\\'}uFE00-${'\\'}uFE0F${'\\'}u200D]"), "")
            .replace(Regex("[${'\\'}uD800-${'\\'}uDFFF]"), "")
        if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
            android.util.Log.w("GroupChatVM", "triggerNextSpeaker: $speakerId disconnected, reconnecting...")
            val contact = _contacts.value.find { it.id == speakerId }
            if (contact != null) {
                service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
            }
            withTimeoutOrNull(5000) {
                service.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
            }
            if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                android.util.Log.w("GroupChatVM", "triggerNextSpeaker: $speakerId reconnect failed, skip")
                waitingSpeakerIndex = -1
                return
            }
        }
        service.sendTextContent(cleaned.takeLast(8))
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

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = -1
        lastSequentialContent = content

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
