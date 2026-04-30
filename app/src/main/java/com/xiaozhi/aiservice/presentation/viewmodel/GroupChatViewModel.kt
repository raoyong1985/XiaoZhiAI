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

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        viewModelScope.launch {
            val sampleRate = audioService.getSampleRate()
            val encoder = OpusEncoder(sampleRate = sampleRate)
            opusEncoder = encoder

            audioService.setOnSilenceDetected {
                viewModelScope.launch { stopVoiceInput() }
            }

            _isRecording.value = true

            service.sendListenStart("auto")
            kotlinx.coroutines.delay(200)

            if (!encoder.init { opusPacket ->
                    service.sendAudio(opusPacket)
                }
            ) {
                android.util.Log.w("GroupChatVM", "编码器初始化失败")
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
                service.sendListenStop()
                opusEncoder?.stop()
                opusEncoder = null
                _isRecording.value = false
            }
        }
    }

    fun stopVoiceInput() {
        if (!_isRecording.value) return

        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        val firstBotId = sequentialBotIds.firstOrNull()
        botConnections[firstBotId]?.sendListenStop()

        _isRecording.value = false
    }

    private fun observeBotReplies(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        var replyContent = ""
        var ttsTextContent = ""

        viewModelScope.launch {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) {
                    ttsTextContent = text
                }
            }
        }

        viewModelScope.launch {
            service.chatMessages.collect { chatMsg ->
                android.util.Log.i("GroupChatVM", "bot=$botName dir=${chatMsg.direction} content=${chatMsg.content.take(50)}")
                if (chatMsg.content.isNotBlank()) {
                    if (chatMsg.direction == MessageDirection.RECEIVED) {
                        replyContent = chatMsg.content
                    }
                    val isBot = chatMsg.direction == MessageDirection.RECEIVED
                    val replyMessage = GroupMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        groupId = groupId,
                        botId = if (isBot) botId else "",
                        botName = if (isBot) botName else "我",
                        content = chatMsg.content,
                        type = MessageType.TEXT,
                        direction = chatMsg.direction
                    )
                    groupChatRepository.addMessage(groupId, replyMessage)
                    android.util.Log.i("GroupChatVM", "save groupMsg ok botName=${replyMessage.botName}")
                }
            }
        }

        viewModelScope.launch {
            service.connectionState.collect { state ->
                if (state is XiaoZhiBackendService.ConnectionState.Connected) {
                    forwardRound = 0
                }
            }
        }

        viewModelScope.launch {
            service.aiState.collect { state ->
                val text = replyContent.ifBlank { ttsTextContent }
                android.util.Log.i("GroupChatVM", "aiState=$state replyContent='${text.take(20)}' bots=${sequentialBotIds.size} forwardRound=$forwardRound")
                if (state == "idle" && text.isNotBlank()) {
                    lastSequentialContent = text
                    replyContent = ""
                    ttsTextContent = ""
                    if (forwardRound < MAX_FORWARD_ROUNDS && sequentialBotIds.size > 1) {
                        forwardRound++
                        android.util.Log.i("GroupChatVM", "calling triggerNextSpeaker forwardRound=$forwardRound")
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

    private fun triggerNextSpeaker() {
        if (sequentialBotIds.size <= 1) {
            android.util.Log.i("GroupChatVM", "triggerNextSpeaker: only 1 bot, skip")
            return
        }
        viewModelScope.launch {
            currentSpeakerIndex = (currentSpeakerIndex + 1) % sequentialBotIds.size
            val speakerId = sequentialBotIds[currentSpeakerIndex]
            val service = botConnections[speakerId]
            if (service == null) {
                android.util.Log.w("GroupChatVM", "triggerNextSpeaker: no service for $speakerId")
                return@launch
            }

            if (lastSequentialContent.isBlank()) {
                android.util.Log.w("GroupChatVM", "triggerNextSpeaker: lastSequentialContent is blank")
                return@launch
            }

            val sentences = lastSequentialContent.split(Regex("[，。！？、；：\\n]"))
            val nonEmpty = sentences.map { it.trim() }.filter { it.isNotBlank() }
            val trigger = if (nonEmpty.size >= 2) {
                nonEmpty.takeLast(2).joinToString("")
            } else {
                nonEmpty.lastOrNull()?.trim() ?: lastSequentialContent.trim()
            }.take(30)

            if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                android.util.Log.w("GroupChatVM", "triggerNextSpeaker: $speakerId not connected, reconnecting...")
                val contact = _contacts.value.find { it.id == speakerId }
                if (contact != null) {
                    service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
                }
                kotlinx.coroutines.delay(2000)
            }

            android.util.Log.i("GroupChatVM", "triggerNextSpeaker: send to speakerIdx=$currentSpeakerIndex id=$speakerId trigger='$trigger'")
            service.sendTextContent(trigger)
        }
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
