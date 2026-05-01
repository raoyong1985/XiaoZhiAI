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

    private val audioForwardBuffer = mutableMapOf<String, MutableList<ByteArray>>()

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
        audioForwardBuffer.clear()
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
        audioForwardBuffer.clear()

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
                        audioForwardBuffer.clear()
                        waitingSpeakerIndex = -1
                    }
                }
            }
        }

        var hasSpoken = false

        viewModelScope.launch {
            service.aiState.collect { state ->
                android.util.Log.i("GroupChatVM", "[$botName] aiState=$state reply='${replyContent.take(20)}' fwd=$forwardRound ws=$waitingSpeakerIndex")
                if (state == "speaking") {
                    hasSpoken = true
                    if (botId == sequentialBotIds.firstOrNull() && _isRecording.value) {
                        android.util.Log.i("GroupChatVM", "AI开始说话，自动停止录音")
                        stopVoiceInput(userExplicitStop = false)
                    }
                }
                if (state == "idle" && hasSpoken) {
                    hasSpoken = false
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
                    audioForwardBuffer.getOrPut(botId) { mutableListOf() }.add(audioData)
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
        if (service == null) return
        waitingSpeakerIndex = nextIndex
        currentSpeakerIndex = nextIndex
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
        // 直接转发前一个 bot 的 Opus 缓存语音
        val prevBotId = sequentialBotIds.getOrNull(prevIndex)
        val cachedOpus = prevBotId?.let { audioForwardBuffer.remove(it) }
        if (!cachedOpus.isNullOrEmpty()) {
            android.util.Log.i("GroupChatVM", "语音转发: ${cachedOpus.size}个Opus包 → $speakerId")
            try {
                service.sendListenStart("auto")
                cachedOpus.forEach { service.sendAudio(it) }
                service.sendListenStopOnly()
                return
            } catch (e: Exception) {
                android.util.Log.w("GroupChatVM", "语音转发失败: ${e.message}")
            }
        }

        android.util.Log.w("GroupChatVM", "语音缓存为空，跳过本轮转发")
        waitingSpeakerIndex = -1
    }

    private var ttsFailed = false

    private suspend fun textToPcm(text: String): ByteArray {
        if (ttsFailed) throw Exception("TTS previously failed")
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        // 依次尝试多个免费 TTS API
        val urls = listOf(
            "https://dict.youdao.com/dictvoice?audio=$encoded&le=zh",
            "https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=zh-CN&client=tw-ob",
        )
        for (urlStr in urls) {
            try {
                val mp3Data = withContext(Dispatchers.IO) {
                    val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
                }
                if (mp3Data.isNotEmpty() && mp3Data.size > 1000) {
                    android.util.Log.i("GroupChatVM", "TTS OK via $urlStr, size=${mp3Data.size}")
                    return decodeMp3ToPcm(mp3Data)
                }
            } catch (_: Exception) { continue }
        }
        ttsFailed = true
        throw Exception("All TTS APIs failed")
    }

    private fun decodeMp3ToPcm(mp3Data: ByteArray): ByteArray {
        val tempFile = File(AppContainer.context.cacheDir, "tts_${System.nanoTime()}.mp3")
        tempFile.writeBytes(mp3Data)
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(tempFile.absolutePath)
        var trackIdx = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIdx = i; break }
        }
        if (trackIdx < 0) { tempFile.delete(); extractor.release(); throw Exception("No audio track") }
        extractor.selectTrack(trackIdx)
        val fmt = extractor.getTrackFormat(trackIdx)
        val sampleRate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val decoder = MediaCodec.createDecoderByType("audio/mpeg")
        decoder.configure(fmt, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        val pcmList = mutableListOf<ByteArray>()
        var eos = false
        while (true) {
            if (!eos) {
                val inIdx = decoder.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) { decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, 10000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = decoder.getOutputBuffer(outIdx)!!
                    val p = ByteArray(info.size)
                    buf.position(info.offset); buf.get(p, 0, info.size)
                    pcmList.add(p)
                }
                decoder.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        decoder.stop(); decoder.release(); extractor.release(); tempFile.delete()
        val total = pcmList.sumOf { it.size }
        val all = ByteArray(total)
        var off = 0
        for (p in pcmList) { System.arraycopy(p, 0, all, off, p.size); off += p.size }
        return if (sampleRate == 16000) all else resamplePcm(all, sampleRate, 16000)
    }

    private fun resamplePcm(input: ByteArray, inRate: Int, outRate: Int): ByteArray {
        val inSamples = input.size / 2
        val outSamples = (inSamples.toLong() * outRate / inRate).toInt()
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val src = i.toDouble() * inRate / outRate
            val si = src.toInt(); val frac = src - si
            val s0 = if (si < inSamples) readSample(input, si) else 0
            val s1 = if (si + 1 < inSamples) readSample(input, si + 1) else s0
            val v = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32767, 32767)
            out[i * 2] = (v and 0xFF).toByte(); out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun readSample(data: ByteArray, idx: Int): Short =
        ((data[idx * 2].toInt() and 0xFF) or ((data[idx * 2 + 1].toInt()) shl 8)).toShort()

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
        audioForwardBuffer.clear()
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
