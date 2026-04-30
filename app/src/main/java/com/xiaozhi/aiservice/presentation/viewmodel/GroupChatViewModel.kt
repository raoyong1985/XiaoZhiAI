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
import android.media.MediaCodec
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private var originalUserQuery = ""
    private var prevBotName = ""

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
        lastSequentialContent = ""
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

        forwardRound = 0
        currentSpeakerIndex = 0
        waitingSpeakerIndex = -1
        lastSequentialContent = ""
        originalUserQuery = ""
        prevBotName = ""

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

        try { botConnections[sequentialBotIds.firstOrNull()]?.sendListenStopOnly() } catch (_: Exception) { }
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
                        waitingSpeakerIndex = -1
                    }
                }
            }
        }

        viewModelScope.launch {
            service.aiState.collect { state ->
                android.util.Log.i("GroupChatVM", "[$botName] aiState=$state reply='${replyContent.take(20)}' fwd=$forwardRound ws=$waitingSpeakerIndex")
                if (state == "speaking" && botId == sequentialBotIds.firstOrNull() && _isRecording.value) {
                    android.util.Log.i("GroupChatVM", "AI开始说话，自动停止录音")
                    stopVoiceInput(userExplicitStop = false)
                }
                if (state == "idle" && replyContent.isNotBlank()) {
                    speakerMutex.withLock {
                        val currentBots = sequentialBotIds
                        val botIndex = currentBots.indexOf(botId)
                        if (botIndex < 0) return@withLock
                        val isExpected = waitingSpeakerIndex == -1 || botIndex == waitingSpeakerIndex
                        if (isExpected && forwardRound < MAX_FORWARD_ROUNDS && currentBots.size > 1) {
                            lastSequentialContent = replyContent
                            prevBotName = botName
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
                    decoder.decodePacket(audioData)
                }
            }
        }
    }

    private suspend fun triggerNextSpeakerUnsafe() {
        val currentBots = sequentialBotIds
        if (currentBots.size <= 1) return
        val prevIndex = currentSpeakerIndex
        val available = currentBots.filterIndexed { i, _ -> i != prevIndex }
        if (available.isEmpty()) return
        val nextIndex = currentBots.indexOf(available.random())
        if (nextIndex < 0) return
        val speakerId = currentBots[nextIndex]
        val service = botConnections[speakerId]
        if (service == null || lastSequentialContent.isBlank()) return
        waitingSpeakerIndex = nextIndex
        currentSpeakerIndex = nextIndex
        // 不释放前一个 decoder，让已在队列中的音频自然播完
        // 构造带上下文的完整文本
        val fullText = buildString {
            if (originalUserQuery.isNotEmpty()) append("用户的问题：").append(originalUserQuery).append("。")
            if (prevBotName.isNotEmpty()) append(prevBotName).append("说：")
            append(lastSequentialContent).append("。你怎么看？")
        }
        if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
            android.util.Log.w("GroupChatVM", "triggerNextSpeaker: $speakerId disconnected, reconnecting...")
            val contact = _contacts.value.find { it.id == speakerId }
            if (contact != null) service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
            speakerMutex.unlock()
            try {
                withTimeoutOrNull(5000) {
                    service.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
                }
            } finally { speakerMutex.lock() }
            if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                waitingSpeakerIndex = -1; return
            }
        }
        // 先试 TTS 音频投递（无长度限制），失败则回退 8 字文本
        var ttsOk = false
        try {
            val pcmData = withTimeoutOrNull(15000L) { textToPcm(fullText) } ?: throw Exception("TTS timeout")
            if (pcmData.isNotEmpty()) {
                val opusPackets = pcmToOpus(pcmData, 16000)
                if (opusPackets.isNotEmpty()) {
                    service.sendListenStart("auto")
                    opusPackets.forEach { service.sendAudio(it) }
                    service.sendListenStopOnly()
                    ttsOk = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GroupChatVM", "TTS投递失败: ${e.message}")
        }
        if (!ttsOk) {
            val cleaned = fullText.replace(Regex("[，。！？、；：\\n\\s\"'「」『』【】《》（）]"), "")
                .replace(Regex("[${'\\'}u2600-${'\\'}u27BF${'\\'}uFE00-${'\\'}uFE0F${'\\'}u200D]"), "")
                .replace(Regex("[${'\\'}uD800-${'\\'}uDFFF]"), "")
            service.sendTextContent(cleaned.takeLast(8))
        }
    }

    private suspend fun textToPcm(text: String): ByteArray = withContext(kotlinx.coroutines.Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val context = AppContainer.context
            val latch = java.util.concurrent.CountDownLatch(1)
            val initResult = arrayOfNulls<Int>(1)
            val tts = TextToSpeech(context) { status ->
                initResult[0] = status
                latch.countDown()
            }
            if (!latch.await(8000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                tts.shutdown()
                if (!cont.isCancelled) cont.resumeWithException(Exception("TTS init timeout"))
                return@suspendCancellableCoroutine
            }
            if (initResult[0] != TextToSpeech.SUCCESS) {
                tts.shutdown()
                if (!cont.isCancelled) cont.resumeWithException(Exception("TTS init: ${initResult[0]}"))
                return@suspendCancellableCoroutine
            }
            tts.language = Locale.CHINESE
            tts.setPitch(1.0f)
            tts.setSpeechRate(1.0f)
            val file = File(context.cacheDir, "tts_${System.nanoTime()}.wav")
            val utteranceId = java.util.UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(id: String?) {
                    val pcm = try { val r = file.readBytes(); extractPcmFromWav(r) } catch (e: Exception) { null }
                    file.delete(); tts.shutdown()
                    if (pcm != null && !cont.isCancelled) cont.resume(pcm)
                    else if (!cont.isCancelled) cont.resumeWithException(Exception("TTS read failed"))
                }
                override fun onError(id: String?) { file.delete(); tts.shutdown(); if (!cont.isCancelled) cont.resumeWithException(Exception("TTS error")) }
                override fun onStart(id: String?) {}
            })
            val extras = android.os.Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            val result = tts.synthesizeToFile(text, extras, file, utteranceId)
            if (result != TextToSpeech.SUCCESS) { file.delete(); tts.shutdown(); if (!cont.isCancelled) cont.resumeWithException(Exception("TTS failed: $result")) }
        }
    }

    private fun extractPcmFromWav(wavData: ByteArray): ByteArray? {
        var offset = 12
        while (offset + 8 <= wavData.size) {
            val chunkId = String(wavData, offset, 4)
            val chunkSize = ((wavData[offset + 7].toInt() and 0xFF) shl 24) or
                ((wavData[offset + 6].toInt() and 0xFF) shl 16) or
                ((wavData[offset + 5].toInt() and 0xFF) shl 8) or (wavData[offset + 4].toInt() and 0xFF)
            if (chunkId == "data") return wavData.copyOfRange(offset + 8, offset + 8 + chunkSize)
            offset += 8 + chunkSize; if (chunkSize % 2 != 0) offset++
        }
        return null
    }

    private fun pcmToOpus(pcmData: ByteArray, sampleRate: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputIndex = encoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val buf = encoder.getInputBuffer(inputIndex); buf?.clear(); buf?.put(pcmData)
            encoder.queueInputBuffer(inputIndex, 0, pcmData.size, 0, 0)
        }
        inputIndex = encoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 20000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (outIndex < 0) continue
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoder.releaseOutputBuffer(outIndex, false); break }
            val buf = encoder.getOutputBuffer(outIndex)
            if (buf != null && bufferInfo.size > 0) {
                val opus = ByteArray(bufferInfo.size)
                buf.position(bufferInfo.offset); buf.get(opus, 0, bufferInfo.size)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) result.add(opus)
            }
            encoder.releaseOutputBuffer(outIndex, false)
        }
        encoder.stop(); encoder.release()
        return result
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
        originalUserQuery = content
        prevBotName = ""
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
