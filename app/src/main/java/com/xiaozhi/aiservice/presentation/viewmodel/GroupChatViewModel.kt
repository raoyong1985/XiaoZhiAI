package com.xiaozhi.aiservice.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.AudioRecorder
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.AiStateStr
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.RelayState
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import com.xiaozhi.aiservice.domain.usecase.relay.AudioRouter
import com.xiaozhi.aiservice.domain.usecase.relay.DeadloopDetector
import com.xiaozhi.aiservice.domain.usecase.relay.DeduplicationUtil
import com.xiaozhi.aiservice.domain.usecase.relay.RelayConfig
import com.xiaozhi.aiservice.domain.usecase.relay.RelayEvent
import com.xiaozhi.aiservice.domain.usecase.relay.RelayOrchestrator
import com.xiaozhi.aiservice.domain.usecase.relay.RelayPhase
import com.xiaozhi.aiservice.domain.usecase.relay.SmartForwarder
import com.xiaozhi.aiservice.domain.usecase.relay.SpeakerScorer
import com.xiaozhi.aiservice.domain.usecase.group.GroupChatUseCase
import com.xiaozhi.aiservice.domain.PlatformLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 群聊 ViewModel —— 多智能体语音聊天核心
 *
 * 架构：
 * - ViewModel 只管 UI 状态（薄层）
 * - RelayOrchestrator 管 relay 状态机
 * - AudioRouter 管音频路由
 * - SmartForwarder 管转发策略
 */
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupChatRepository: GroupChatRepository,
    private val contactsRepository: ContactsRepository,
    private val botConnectionPool: BotConnectionPoolRepository,
    private val playbackManager: AudioPlaybackManager,
    private val audioService: AudioService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── 核心组件 ──
    private lateinit var orchestrator: RelayOrchestrator
    private lateinit var audioRouter: AudioRouter
    private lateinit var smartForwarder: SmartForwarder

    // ── 辅助组件 ──
    private val botObserverJobs = ConcurrentHashMap<String, Job>()
    private val audioForwardBuffer = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val botLastAudioTime = ConcurrentHashMap<String, Long>()
    private val deadloopDetector = DeadloopDetector()
    private val deduplicationUtil = DeduplicationUtil()
    private val speakerScorer = SpeakerScorer(
        { botConnectionPool.isDead(it) },
        { _contacts.value }
    )
    private val _relayState = MutableStateFlow(RelayState())

    // ── UI 状态 ──
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()
    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    val contacts: StateFlow<List<BotContact>> = _contacts.asStateFlow()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // ── 录音 ──
    private val audioRecorder = AudioRecorder(audioService)
    private var currentGroupId = ""
    private var sttCollectorJob: Job? = null
    private var observeJob: Job? = null
    private var stuckRelayWatcher: Job? = null
    private var serviceReplacedJob: Job? = null

    // ── CRUD ──
    private val groupChatUseCase = GroupChatUseCase(
        groupChatRepository, botConnectionPool, playbackManager,
        _relayState, audioForwardBuffer, deduplicationUtil, deadloopDetector,
        botObserverJobs, { _contacts.value },
        onGroupsChanged = { loadGroups() }, tag = TAG
    )

    companion object {
        private const val TAG = "GroupChatVM"
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
        if (_contacts.value.isNotEmpty()) return
        viewModelScope.launch {
            contactsRepository.getContacts().collect { _contacts.value = it }
        }
    }

    /**
     * 连接群聊
     */
    fun connectGroup(groupId: String) {
        observeJob?.cancel()
        observeJob = null
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        botConnectionPool.disconnectAll()
        playbackManager.releaseAll()
        _relayState.value = RelayState()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deduplicationUtil.clear()
        deadloopDetector.reset()
        currentGroupId = groupId
        _isConnecting.value = true

        // 初始化核心组件
        audioRouter = AudioRouter(playbackManager, context)
        smartForwarder = SmartForwarder({ _contacts.value })
        orchestrator = RelayOrchestrator(
            botConnectionPool, _relayState, audioRouter, smartForwarder,
            speakerScorer, deadloopDetector, deduplicationUtil,
            audioForwardBuffer, botLastAudioTime,
            { _contacts.value }, { _isRecording.value }, viewModelScope,
            onRelaySwitch = { nextBotId ->
                Log.i(TAG, "Relay 切换到 $nextBotId")
            },
            onConversationFinished = {
                Log.i(TAG, "对话结束")
            }
        )

        // 多 bot 并行连接
        observeJob = viewModelScope.launch {
            try {
                loadGroups()
                val group = groupChatRepository.getGroup(groupId)
                group?.let { g ->
                    // 设置 2人群模式
                    _relayState.update { it.copy(isTwoPersonMode = g.botIds.size == 2, botIds = g.botIds) }
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
                                Log.w(TAG, "连接bot[$botId]失败", e)
                            }
                        }
                    }.forEach { it.await() }
                }
            } finally {
                _isConnecting.value = false
            }
        }

        // 监听 service 重建
        serviceReplacedJob?.cancel()
        serviceReplacedJob = viewModelScope.launch {
            botConnectionPool.serviceReplaced.collect { (botId, newService) ->
                val contact = _contacts.value.find { it.id == botId } ?: return@collect
                botObserverJobs[botId]?.cancel()
                observeBotEvents(botId, currentGroupId, contact.name, newService)
            }
        }

        // 看门狗（简化版：只做死 bot 清理）
        stuckRelayWatcher?.cancel()
        stuckRelayWatcher = viewModelScope.launch {
            var lastCleanup = 0L
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                if (now - lastCleanup > 120_000) {
                    lastCleanup = now
                    botConnectionPool.clearDead()
                }
            }
        }
    }

    fun setGroupInputMode(groupId: String, mode: Int) {
        viewModelScope.launch { groupChatUseCase.updateGroupInputMode(groupId, mode, _groups) }
    }

    // ═══════════════════════════════════════════
    // 录音
    // ═══════════════════════════════════════════

    fun startVoiceInput(groupId: String) {
        if (_isRecording.value) return
        if (_relayState.value.botIds.isEmpty()) return

        currentGroupId = groupId
        viewModelScope.launch { orchestrator.onEvent(RelayEvent.UserStartSpeaking) }

        // flush decoder
        val serverRate = 24000 // 默认值，实际会在收到音频时更新
        audioRouter.flush(groupId, serverRate)

        val firstBotId = _relayState.value.botIds[0]
        val service = botConnectionPool.get(firstBotId) ?: return
        service.sendListenStart("auto")

        if (!audioRecorder.start(
                owner = groupId,
                scope = viewModelScope,
                enableVAD = true,
                onOpusPacket = { packet -> service.sendAudio(packet) },
                onSilenceDetected = {
                    Log.i(TAG, "VAD触发")
                    viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
                }
            )) {
            Log.w(TAG, "录音启动失败")
            service.sendListenStop()
            return
        }
        _isRecording.value = true

        // 收集 STT 结果
        sttCollectorJob?.cancel()
        sttCollectorJob = viewModelScope.launch {
            var lastCollectedText = ""
            service.textMessage.collect { text ->
                if (text.isNotBlank() && text != lastCollectedText) {
                    lastCollectedText = text
                    val userMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(), groupId = currentGroupId,
                        botId = "", botName = "我", content = text,
                        type = MessageType.TEXT, direction = MessageDirection.SENT
                    )
                    groupChatRepository.addMessage(currentGroupId, userMessage)
                }
            }
        }
    }

    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        if (!_isRecording.value) return
        _isRecording.value = false
        Log.i(TAG, "停止录音 userExplicitStop=$userExplicitStop")

        viewModelScope.launch { orchestrator.onEvent(RelayEvent.UserStopSpeaking) }

        if (userExplicitStop) {
            botConnectionPool.abortAll("user_stop")
        }

        audioRecorder.stop(owner = currentGroupId)
        audioService.abandonAudioFocus()
        sttCollectorJob?.cancel()

        try { botConnectionPool.get(_relayState.value.botIds.firstOrNull() ?: "")?.sendListenStopOnly() } catch (e: Exception) { Log.w(TAG, "sendListenStopOnly failed: ${e.message}") }
    }

    // ═══════════════════════════════════════════
    // Bot 事件监听
    // ═══════════════════════════════════════════

    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: BotConnection) {
        botObserverJobs[botId]?.cancel()
        val parentJob = Job(viewModelScope.coroutineContext[Job])
        botObserverJobs[botId] = parentJob

        val eventChannel = Channel<BotEvent>(Channel.BUFFERED)

        // 流 1：ttsText
        viewModelScope.launch(parentJob) {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) eventChannel.send(BotEvent.TtsText(text))
            }
        }

        // 流 2：chatMessages
        viewModelScope.launch(parentJob) {
            service.chatMessages.collect { msg ->
                eventChannel.send(BotEvent.Message(msg))
            }
        }

        // 流 3：aiState
        viewModelScope.launch(parentJob) {
            service.aiState.collect { state ->
                eventChannel.send(BotEvent.AiState(state))
            }
        }

        // 流 3.5：ttsStopped
        viewModelScope.launch(parentJob) {
            service.ttsStopped.collect {
                eventChannel.send(BotEvent.TtsStopped)
            }
        }

        // 流 4：audioData（独立协程，不走事件通道）
        viewModelScope.launch(parentJob) {
            service.audioData.collect { audioData ->
                if (audioData.isEmpty()) return@collect
                botLastAudioTime[botId] = System.currentTimeMillis()

                // 路由音频
                val serverRate = service.getServerSampleRate()
                val serverCh = service.getServerChannels()
                audioRouter.routeAudio(
                    groupId, botId, serverRate, serverCh,
                    audioData, orchestrator.getExpectedSpeakerId()
                )

                // 缓存音频用于转发
                val buf = audioForwardBuffer.getOrPut(botId) { java.util.Collections.synchronizedList(mutableListOf()) }
                synchronized(buf) {
                    buf.add(audioData)
                    if (buf.size > RelayConfig.MAX_FORWARD_BUFFER_SIZE) {
                        buf.removeAt(0)
                    }
                }
            }
        }

        // WebSocket 重连恢复
        viewModelScope.launch(parentJob) {
            var wasDisconnected = false
            service.connectionState.collect { state ->
                when (state) {
                    is BotConnectionState.Error, is BotConnectionState.Disconnected -> wasDisconnected = true
                    is BotConnectionState.Connected -> {
                        if (wasDisconnected) {
                            wasDisconnected = false
                            Log.i(TAG, "[$botName] WebSocket重连完成")
                        }
                    }
                    else -> {}
                }
            }
        }

        // 事件消费（简化版：全部委托给 orchestrator）
        viewModelScope.launch(parentJob) {
            for (event in eventChannel) {
                when (event) {
                    is BotEvent.TtsText -> {
                        // 只有预期 speaker 的流式文字才追加
                        val expected = orchestrator.getExpectedSpeakerId()
                        if (expected.isEmpty() || botId == expected) {
                            orchestrator.appendReplyText(event.text)
                        }
                    }
                    is BotEvent.Message -> {
                        val msg = event.msg
                        if (msg.direction == MessageDirection.RECEIVED && msg.content.isNotBlank()) {
                            if (isDuplicateReply(botId, msg.content)) continue
                            // 非预期 speaker 的消息不显示
                            val expected = orchestrator.getExpectedSpeakerId()
                            if (expected.isNotEmpty() && botId != expected) continue
                            groupChatRepository.addMessage(groupId, ChatMessage(
                                id = java.util.UUID.randomUUID().toString(), groupId = groupId,
                                botId = botId, botName = botName, content = msg.content,
                                type = MessageType.TEXT, direction = MessageDirection.RECEIVED
                            ))
                        }
                    }
                    is BotEvent.AiState -> {
                        val state = event.state
                        Log.i(TAG, "[$botName] aiState=$state")

                        when (state) {
                            AiStateStr.SPEAKING -> {
                                audioService.requestAudioFocus()
                                // 首 bot 开始说话 → 停止录音
                                if (botId == _relayState.value.botIds.firstOrNull()) {
                                    if (_isRecording.value) {
                                        stopVoiceInput(userExplicitStop = false)
                                    }
                                    sttCollectorJob?.cancel()
                                }
                                // BotStartSpeaker: 非预期bot也更新expectedSpeaker让relay跟着服务器
                                val expected = orchestrator.getExpectedSpeakerId()
                                if (expected.isEmpty() || botId == expected) {
                                    orchestrator.onEvent(RelayEvent.BotStartSpeaking)
                                } else {
                                    Log.w(TAG, "[$botName] BotStartSpeaking 非预期(期望=${expected.take(8)})，更新为$botId")
                                    // speaker 切换 → flush 旧音频避免混音
                                    val serverRate = botConnectionPool.get(botId)?.getServerSampleRate() ?: 24000
                                    audioRouter.flush(currentGroupId, serverRate)
                                    orchestrator.updateExpectedSpeaker(botId)
                                    orchestrator.onEvent(RelayEvent.BotStartSpeaking)
                                }
                            }
                            AiStateStr.IDLE -> {
                                // IDLE 不再触发 relay，由 TtsStopped 驱动
                            }
                        }
                    }
                    is BotEvent.TtsStopped -> {
                        val expected = orchestrator.getExpectedSpeakerId()
                        if (botId == expected) {
                            // 当前预期 speaker 的 TTS 停了 → 正常推进 relay
                            Log.i(TAG, "[$botName] ttsStopped")
                            orchestrator.onEvent(RelayEvent.BotStopSpeaking)
                        } else if (expected.isEmpty()) {
                            // 无预期 speaker（首 bot 场景）→ 允许触发
                            Log.i(TAG, "[$botName] ttsStopped (无预期speaker)")
                            orchestrator.onEvent(RelayEvent.BotStopSpeaking)
                        } else {
                            // 非预期 speaker 的迟到 ttsStopped → 丢弃，不推进 relay
                            Log.w(TAG, "[$botName] ttsStopped 丢弃(期望=${expected.take(8)})")
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════

    private fun isDuplicateReply(botId: String, content: String): Boolean {
        return deduplicationUtil.isDuplicate(botId, content)
    }

    // ═══════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════

    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        return groupChatUseCase.createGroup(name, selectedBotIds)
    }

    suspend fun deleteGroup(groupId: String) {
        groupChatUseCase.deleteGroup(groupId)
    }

    fun getGroupMessages(groupId: String): Flow<List<ChatMessage>> {
        return groupChatUseCase.getGroupMessages(groupId)
    }

    suspend fun sendMessageToGroup(groupId: String, content: String) {
        groupChatUseCase.sendMessageToGroup(groupId, content)
    }

    suspend fun sendMessage(groupId: String, content: String) = sendMessageToGroup(groupId, content)

    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatUseCase.updateGroupName(groupId, newName)
    }

    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatUseCase.addBotToGroup(groupId, botId)
    }

    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatUseCase.removeBotFromGroup(groupId, botId)
    }

    suspend fun clearGroupMessages(groupId: String) {
        groupChatUseCase.clearGroupMessages(groupId)
    }

    fun exportGroupMessages(groupId: String, context: Context) {
        viewModelScope.launch {
            val messages = groupChatRepository.getGroupMessages(groupId).first()
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                "[${if (msg.direction == MessageDirection.SENT) "我" else msg.botName}]: ${msg.content}"
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "群聊记录")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "导出群聊记录"))
        }
    }

    fun disconnectGroup() {
        stopVoiceInput()
        sttCollectorJob?.cancel()
        stuckRelayWatcher?.cancel()
        serviceReplacedJob?.cancel()
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        audioRouter.releaseAll()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deduplicationUtil.clear()
        deadloopDetector.reset()
        _relayState.value = RelayState()
        Log.i(TAG, "disconnectGroup: 群聊资源已全部释放")
    }

    override fun onCleared() {
        super.onCleared()
        disconnectGroup()
    }
}
