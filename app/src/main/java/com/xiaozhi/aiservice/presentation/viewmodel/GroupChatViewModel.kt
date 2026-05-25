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
import com.xiaozhi.aiservice.domain.usecase.relay.RelayConfig
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

/**
 * 群聊 ViewModel —— 多智能体语音聊天核心
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  架构：单 ViewModel + 多 bot WebSocket 连接                     │
 * │                                                                │
 * │  用户 → 录音 → Opus音频(首bot) → bot回复(tts音频) →           │
 * │        转发到下一bot → 循环 → 轮次结束                         │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 核心模块：
 * ┌────────────────────┬──────────────────────────────────────────┐
 * │ 模块                │ 职责                                     │
 * ├────────────────────┼──────────────────────────────────────────┤
 * │ 录音（voice input） │ 录音 → 发送 Opus 到首 bot                │
 * │ Bot 事件监听        │ 合并 4 个流（ttsText/msg/state/audio）    │
 * │ 接龙引擎（relay）    │ speaker 选择、音频转发、文字兜底           │
 * │ 看门狗（watchdog）  │ 卡死检测、自动恢复                       │
 * │ CRUD                │ 群聊管理操作                             │
 * └────────────────────┴──────────────────────────────────────────┘
 *
 * 关键设计决策：
 * 1. 音频不走事件通道 → 避免 decodePacket 阻塞事件处理
 * 2. 所有 bot 共享单个 AudioTrack → 解决多 AudioTrack 无声问题
 * 3. RelayState 不可变 → 通过 StateFlow 原子更新，消除竞态
 * 4. 接龙轮次自动限制 → MAX_FORWARD_ROUND = bot数-1，防无限循环
 * 5. 死循环检测 → hint 去重 + speaker 去重双重检测
 *
 * 架构限制（来自 ARCHITECTURE_REVIEW.md）：
 * - ❌ 直接依赖 Data 层（AudioPlaybackManager/AudioRecorder/BotConnectionPool）
 * - ❌ Service Locator 模式（AppContainer 全局访问）
 * - ❌ 代码过长（~1000 行，应拆分为 RelayEngine + GroupChatUseCase）
 */
class GroupChatViewModel : ViewModel() {

    // ═══════════════════════════════════════════
    // 依赖注入（直接引用 AppContainer - Service Locator 模式）
    // ═══════════════════════════════════════════

    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository

    // ── WebSocket 连接池：管理所有 bot 的长连接 ──
    private val botConnectionPool = BotConnectionPool(AppContainer.gson)

    // ── 音频播放管理器：解码 Opus 并播放 ──
    private val playbackManager = AudioPlaybackManager()

    // ── Bot 事件监听协程 Job 表：botId → Job，用于清理 ──
    private val botObserverJobs = ConcurrentHashMap<String, Job>()

    // ═══════════════════════════════════════════
    // 核心状态：接龙运行时状态（不可变，通过 StateFlow 原子更新）
    // ═══════════════════════════════════════════

    /**
     * 接龙运行时状态
     *
     * 包含：当前 speaker、等待中的 speaker、转发轮次、会话 ID、
     * 已发言集合、转发历史等。全部封装在 RelayState 中。
     *
     * 为什么用 MutableStateFlow 而不是多个 var？
     * 多协程并发读写 15 个 var 会出现竞态（一个协程读了 A 后
     * 另一个改了 B，导致逻辑不一致）。RelayState 不可变，
     * 每次 update{} 原子地修改全部相关字段。
     */
    private val _relayState = MutableStateFlow(RelayState())

    /**
     * 音频转发缓存：botId → List<Opus音频包>
     *
     * 在 bot A 回复期间把它的音频数据缓存下来，
     * 当 bot A 说完后，通过 triggerNextSpeaker 把这些
     * 音频包转发给下一个 bot B 作为输入。
     *
     * 为什么需要缓存？
     * WebSocket 的音频数据是顺序到达的流的片段，无法重放。
     * 要转发给下一个 bot，必须把收到的所有音频包存起来。
     */
    private val audioForwardBuffer = ConcurrentHashMap<String, MutableList<ByteArray>>()

    /** 各 bot 最近一次收到音频包的时间戳（ms），用于看门狗检测卡死 */
    private val botLastAudioTime = ConcurrentHashMap<String, Long>()

    // ── UI 状态（暴露给 View 层 collect） ──

    /** 群聊列表 */
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()

    /** 联系人列表（所有可用的智能体） */
    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    val contacts: StateFlow<List<BotContact>> = _contacts.asStateFlow()

    /** 是否正在录音 */
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** 是否正在连接 bot */
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // ── 录音相关 ──
    private val audioService = AppContainer.audioService
    private val audioRecorder = AudioRecorder(audioService)
    private var currentGroupId = ""

    // ── 协程 Job 引用（用于取消） ──
    private var sttCollectorJob: Job? = null   // 语音转文字结果收集
    private var observeJob: Job? = null        // 主监听协程
    private var stuckRelayWatcher: Job? = null // 看门狗

    /** speakerMutex：保护 speaker 切换操作，避免并发触发两次触发下一 bot */
    private val speakerMutex = Mutex()

    // ── 接龙互斥锁：防止 watchdog 与正常路径并发调用 triggerNextSpeaker ──
    // triggerNextSpeaker 是 suspend 函数，forwardAudioToBot 会挂起，
    // 此时看门狗可能检测到「假卡死」并发起第二次转发，导致重复
    private var isRelaying = false
    private val relayLock = Any()

    // 防死循环（synchronized 保护：多协程并发写）
    private val deadloopLock = Any()
    private val recentHints = mutableListOf<String>()
    private val recentCandidates = mutableListOf<String>()
    private var deadloopCount = 0
    private val MAX_DEADLOOP = 5

    /** 用于音频过滤的 grace 窗口：bot 进入 speaking 状态后 N 毫秒内允许音频通过 */
    private val botSpeakingTime = ConcurrentHashMap<String, Long>()

    // ── 消息去重 ──
    private val recentReplyKeys = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val MAX_RECENT_REPLIES = 40  // 最多保留 40 条最近回复的 key

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

    /** 从仓库加载群聊列表 */
    private fun loadGroups() {
        viewModelScope.launch { _groups.value = groupChatRepository.getGroups() }
    }

    /** 从仓库加载联系人列表（可用的智能体） */
    private fun loadContacts() {
        viewModelScope.launch {
            contactsRepository.getContacts().collect { _contacts.value = it }
        }
    }

    /**
     * 连接群聊（进入群聊聊天界面时调用）
     *
     * 完整流程：
     * 1. 清理旧状态（观察者 Job、连接、播放器、缓存）
     * 2. 读取群聊信息
     * 3. 对每个 bot 创建 WebSocket 连接 + 注册事件监听
     * 4. 设置看门狗，定期检查接龙是否卡死
     *
     * @param groupId 目标群聊的 UUID
     */
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

        // ── 多 bot 并行连接 ──
        // 使用 async/await 并行：所有 bot 同时发起 WebSocket 连接，
        // 全部完成后继续（forEach { it.await() }）
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
                                android.util.Log.w(TAG, "连接bot[$botId]失败", e)
                            }
                        }
                    }.forEach { it.await() }
                    _relayState.update { it.copy(botIds = g.botIds) }
                }
            } finally {
                _isConnecting.value = false
            }
        }

        // ── 接龙看门狗（15 秒周期） ──
        // 职责：
        // 1. 检测接龙是否卡死（等待某个 bot 但 >30 秒无响应）
        // 2. 定期清理死 bot 标记（每 2 分钟一次）
        // 3. 就绪状态时如果长时间无活动，自动触发下一轮
        stuckRelayWatcher?.cancel()
        stuckRelayWatcher = viewModelScope.launch {
            var lastCleanup = 0L
            while (true) {
                delay(15_000)

                // 每 2 分钟清理一次死 bot 标记（给重连机会）
                val now = System.currentTimeMillis()
                if (now - lastCleanup > 120_000) {
                    lastCleanup = now
                    botConnectionPool.clearDead()
                    android.util.Log.i(TAG, "清理死 bot 列表")
                }

                val rs = _relayState.value
                if (_isRecording.value) continue  // 用户正在录音，跳过检测

                if (rs.waitingSpeakerIndex >= 0) {
                    // 模式1：有等待的 bot——检查是否超时
                    val waitingBotId = rs.botIds.getOrNull(rs.waitingSpeakerIndex) ?: continue
                    val lastAudio = botLastAudioTime[waitingBotId] ?: 0L
                    // 如果 lastAudio == 0，说明该 bot 从未收到过音频（刚被选中，正在转发/处理中）
                    // ——不是卡死，给更长的 60s 超时
                    val timeoutMs = if (lastAudio == 0L) 60_000 else 30_000
                    val silenceDuration = System.currentTimeMillis() - lastAudio
                    if (silenceDuration > timeoutMs) {
                        android.util.Log.w(TAG, "接龙卡死检测：等待[$waitingBotId] 已静默 ${silenceDuration/1000}s（超${timeoutMs/1000}s），清除等待状态并重启接龙")
                        _relayState.update { it.clearWaiting() }
                        botConnectionPool.clearDead()
                        viewModelScope.launch { triggerNextSpeaker("") }
                    }
                } else if (rs.forwardRound >= 0 && rs.botIds.isNotEmpty()) {
                    // 模式2：无等待 bot 但对话未结束——所有 bot 静默 >30s，自动恢复
                    val anyAudioRecently = rs.botIds.any {
                        val t = botLastAudioTime[it] ?: 0L
                        t > 0 && System.currentTimeMillis() - t < 30_000
                    }
                    if (!anyAudioRecently) {
                        android.util.Log.w(TAG, "接龙空闲恢复：所有bot静默 >30s，清理死bot并重启接龙")
                        botConnectionPool.clearDead()
                        // 重置轮次，防止卡在"已达最大轮次"
                        _relayState.update { it.copy(forwardRound = 0) }
                        viewModelScope.launch { triggerNextSpeaker("") }
                    }
                }
            }
        }
    }

    /** 设置群聊输入模式（0=语音，1=文字） */
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

    /**
     * 开始语音输入
     *
     * 流程：
     * 1. 中断所有 bot 的当前回复（abortAll）
     * 2. 重置接龙状态（新 session，从首 bot 开始）
     * 3. 通知首 bot 开始监听（sendListenStart）
     * 4. 启动录音 → Opus 音频直接发给首 bot
     * 5. 监听首 bot 的语音转文字结果（用于 UI 显示和去重）
     *
     * @param groupId 目标群聊 ID（用于播放器 key）
     */
    fun startVoiceInput(groupId: String) {
        if (_isRecording.value) return
        if (_relayState.value.botIds.isEmpty()) return

        currentGroupId = groupId
        botConnectionPool.abortAll("user_interrupt")

        // 不清除共享解码器——只排空队列，保持 AudioTrack 活过整个群聊会话
        // 避免华为上反复创建/释放 AudioTrack 导致的无声问题
        val sharedId = "${groupId}_shared"
        if (playbackManager.contains(sharedId)) {
            playbackManager.flushSource(sharedId)
        } else {
            playbackManager.releaseAll()
        }
        // 不取消 botObserverJobs！监听器跨录音会话存活，
        // 取消会导致 aiState/audioData 无人处理，机器人有回复但听不到
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        synchronized(deadloopLock) {
            deadloopCount = 0
            recentHints.clear()
            recentCandidates.clear()
        }

        val firstBotId = _relayState.value.botIds[0]
        val service = botConnectionPool.get(firstBotId) ?: return

        // 新会话：递增 sessionId，重置轮次
        _relayState.update { it.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0,
            waitingSpeakerIndex = 0, originalUserQuery = ""
        ) }
        recentReplyKeys.clear()

        service.sendListenStart("auto")

        // ── 启动录音 ──
        // onOpusPacket：每个 Opus 音频包实时转发到首 bot 的 WebSocket
        // onSilenceDetected：VAD 检测到静音后自动停止录音
        if (!audioRecorder.start(
                owner = groupId,  // 传 groupId 标识，保护不与私聊录音冲突
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

        // ── 收集首 bot 的语音转文字结果 ──
        // 用户说的话经过首 bot 的 ASR 识别后通过 textMessage flow 返回
        // 这里收集文本用于 UI 显示和死循环检测
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

    /**
     * 停止语音输入
     *
     * @param userExplicitStop true=用户主动点击停止（发送 abort），
     *                         false=VAD 自动停止（不 abort，让 AI 继续回复）
     */
    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        if (!_isRecording.value) return
        _isRecording.value = false
        android.util.Log.i(TAG, "停止录音 userExplicitStop=$userExplicitStop")

        if (userExplicitStop) {
            botConnectionPool.abortAll("user_stop")
        }

        audioRecorder.stop(owner = currentGroupId)
        audioService.abandonAudioFocus()
        sttCollectorJob?.cancel()

        try { botConnectionPool.get(_relayState.value.botIds.firstOrNull() ?: "")?.sendListenStopOnly() } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════
    // Bot 事件驱动（B1：合并 4 个流为一个事件通道）
    // ═══════════════════════════════════════════

    /**
     * 监听单个 bot 的事件流
     *
     * 每个 bot 有 4 个独立 Flow（ttsText / chatMessages / aiState / audioData）
     * 和 1 个状态 Flow（connectionState）。
     *
     * 合并策略：
     * - ttsText / chatMessages / aiState → 合并到 BUFFERED Channel
     *   保证顺序处理，不阻塞发送方
     * - audioData → 独立协程处理（decodePacket 是 CPU 密集操作，
     *   走 Channel 会阻塞控制流处理）
     * - connectionState → 独立监听，用于重连恢复
     *
     * 注意：所有协程都挂在 parentJob 下，cancel parentJob 即可
     * 干净地停止该 bot 的所有监听。
     *
     * @param botId 智能体 ID
     * @param groupId 群聊 ID（用于播放器 key）
     * @param botName 智能体名称（仅用于日志）
     * @param service WebSocket 连接实例
     */
    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        botObserverJobs[botId]?.cancel()
        val parentJob = Job(viewModelScope.coroutineContext[Job])
        botObserverJobs[botId] = parentJob

        // BUFFERED channel：缓冲 64 个事件，不阻塞发送方，不丢事件
        // 不用 CONFLATED：它会丢弃中间事件（ttsText 在多句子时只剩最后一个）
        val eventChannel = Channel<BotEvent>(Channel.BUFFERED)

        // ── 流 1：ttsText（AI 正在说的文本，流式逐句推送） ──
        viewModelScope.launch(parentJob) {
            service.ttsText.collect { text ->
                if (text.isNotBlank()) eventChannel.send(BotEvent.TtsText(text))
            }
        }

        // ── 流 2：chatMessages（完整的消息对象） ──
        viewModelScope.launch(parentJob) {
            service.chatMessages.collect { msg ->
                eventChannel.send(BotEvent.Message(msg))
            }
        }

        // ── 流 3：aiState（AI 状态变化） ──
        // idle → listening → speaking → idle
        // 关键点：speaking→idle 过渡触发 speaker 切换
        viewModelScope.launch(parentJob) {
            service.aiState.collect { state ->
                eventChannel.send(BotEvent.AiState(state))
            }
        }

        // ── 流 4：audioData（Opus 音频数据） ──
        // 独立协程解码播放，不走事件通道。
        // decodePacket 是 CPU 密集操作，走 Channel 会阻塞
        // aiState/ttsText 处理，导致「音频堆积 → 无声音」
        //
        // 处理逻辑：
        // 1. 检查当前 speaker 是否是自己（非预期 bot 的音频丢弃）
        // 2. 创建/复用群聊共享解码器（所有 bot 共用一个 AudioTrack）
        // 3. 解码播放
        // 4. 缓存音频包用于后续转发到下一个 bot
        viewModelScope.launch(parentJob) {
            service.audioData.collect { audioData ->
                if (audioData.isEmpty()) return@collect
                botLastAudioTime[botId] = System.currentTimeMillis()
                val rs = _relayState.value
                val idx = rs.botIds.indexOf(botId)
                if (idx >= 0 && rs.waitingSpeakerIndex >= 0 && idx != rs.waitingSpeakerIndex) {
                    // 放宽过滤：如果该 bot 刚在 3 秒内进入 speaking 状态，允许音频通过
                    // 解决 triggerNextSpeaker 还没更新 ws、但 bot 已经开始推音频的竞态
                    val recentSpeaking = botSpeakingTime[botId] ?: 0L
                    if (System.currentTimeMillis() - recentSpeaking > 3000) {
                        android.util.Log.w(TAG, "[$botName] 音频被ws过滤(idx=$idx, ws=${rs.waitingSpeakerIndex}), ${audioData.size}B")
                        return@collect
                    }
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
                            // 先等旧解码器的队列排空再释放，避免切换时丢失 bot 的音频首帧
                            playbackManager.waitForQueueEmpty(currentSourceId, 500)
                            playbackManager.release(currentSourceId)
                            playbackManager.getOrCreate(currentSourceId, serverRate, serverCh, volume)
                        }
                    }
                }
                playbackManager.decodeAndPlay(currentSourceId, serverRate, serverCh, audioData, volume)
                val buf = audioForwardBuffer.getOrPut(botId) { mutableListOf() }
                buf.add(audioData)
                if (buf.size > RelayConfig.MAX_FORWARD_BUFFER_SIZE) {
                    buf.removeAt(0) // 超出上限时丢弃最早的数据
                }
            }
        }

        // ── WebSocket 重连恢复 ──
        // 当 bot 的 ws 断线重连后，如果接龙正等待该 bot，清除等待状态
        // 不通过 eventChannel，直接修改 relayState 避免事件队列阻塞
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

        // ── 单协程消费事件通道（保证顺序处理） ──
        //
        // 事件处理流程（重点关注 aiState 的 speaking → idle 过渡）：
        //
        // speaking 到达：
        //   • 记录 replyText（累积 ttsText）
        //   • 检查是否自己正在被等待（waitingSpeakerIndex）
        //   • 如果不是预期 speaker → 丢弃
        //   • 如果是首 bot → 自动停止录音
        //
        // idle 到达（hasSpoken=true）：
        //   • 排空该 bot 的音频播放
        //   • 在 speakerMutex 保护下判断是否触发下一轮
        //   • 条件：自己是 waitingSpeaker + bot 数 > 1 → 触发下一 speaker
        //   • 条件：ws=-1 但 forwardRound >= 0 → 自动继续
        var replyText = ""
        var hasSpoken = false
        var speakerSession = 0L

        viewModelScope.launch(parentJob) {
            for (event in eventChannel) {
                when (event) {
                    is BotEvent.TtsText -> {
                        // 流式文本累积：多个 ttsText 拼接为完整回复
                        replyText = if (replyText.isBlank()) event.text else "$replyText ${event.text}"
                    }
                    is BotEvent.Message -> {
                        val msg = event.msg
                        if (msg.direction == MessageDirection.RECEIVED && msg.content.isNotBlank()) {
                            // 去重检查：同一 bot 的重复文本跳过
                            if (isDuplicateReply(botId, msg.content)) {
                                android.util.Log.w(TAG, "[$botName] 跳过重复消息: ${msg.content.take(30)}")
                                // 不清空 replyText！重复消息的文本仍需要作为转发上下文
                                // 给下一个 bot（仅跳过 UI 显示），避免下一 bot 收到空白
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
                            // ── AI 开始说话 ──
                            audioService.requestAudioFocus()
                            // 记录该 bot 进入 speaking 的时间，audioData 过滤据此做 3 秒 grace
                            botSpeakingTime[botId] = System.currentTimeMillis()
                            val botIndex = rs.botIds.indexOf(botId)
                            if (rs.waitingSpeakerIndex >= 0 && botIndex != rs.waitingSpeakerIndex && botIndex >= 0) {
                                android.util.Log.w(TAG, "[$botName] 非预期发言(idx=$botIndex, expected=${rs.waitingSpeakerIndex})，丢弃音频")
                                replyText = ""  // 清空累积文本，避免下次转发旧数据
                                continue
                            }
                            hasSpoken = true
                            speakerSession = rs.sessionId
                            // 首 bot 开始说话 → 自动停止用户录音
                            if (botId == rs.botIds.firstOrNull()) {
                                if (_isRecording.value) {
                                    android.util.Log.i(TAG, "AI开始说话，自动停止录音")
                                    stopVoiceInput(userExplicitStop = false)
                                }
                                sttCollectorJob?.cancel()
                            }
                            // 预创建共享解码器（减少音频到达时的首帧延迟）
                            // 注意：用 groupId_shared 而非 botId，audioData 实际解码用的是共享解码器
                            val currentSourceId = "${groupId}_shared"
                            if (!playbackManager.contains(currentSourceId)) {
                                val serverRate = service.getServerSampleRate()
                                val serverCh = service.getServerChannels()
                                val volume = AudioSettings.getOutputVolume(AppContainer.context)
                                playbackManager.getOrCreate(currentSourceId, serverRate, serverCh, volume)
                                android.util.Log.i(TAG, "[$botName] 预创建共享解码器 (${serverRate}Hz, 音量=$volume%)")
                            }
                        }

                        if (state == "idle" && hasSpoken) {
                            // ── AI 说完话 → 触发下一轮 ──
                            hasSpoken = false
                            // 排空当前 bot 的音频缓冲区，确保全部播放完毕
                            drainPreviousBotAudio(botId, "${groupId}_shared")

                            var shouldTrigger = false
                            var replyToForward = ""
                            speakerMutex.withLock {
                                // mutex 内重读 relayState，避免竞态
                                // （外边读的快照可能已过时，比如另一个 bot 刚更新了状态）
                                val currentRs = _relayState.value
                                if (speakerSession != currentRs.sessionId) {
                                    android.util.Log.i(TAG, "[$botName] 跳过旧轮次 session=$speakerSession current=${currentRs.sessionId}")
                                    return@withLock
                                }
                                val botIndex = currentRs.botIds.indexOf(botId)
                                if (botIndex < 0) return@withLock

                                // 条件 A：我是被等待的 speaker + 有多个 bot → 触发下一轮
                                if (botIndex == currentRs.waitingSpeakerIndex && currentRs.botIds.size > 1) {
                                    replyToForward = replyText.also { replyText = "" }
                                    shouldTrigger = true
                                    android.util.Log.i(TAG, "[$botName] triggering next, fwd=${currentRs.forwardRound} reply='${replyToForward.take(60)}'")
                                }
                                // 条件 B：ws=-1 但还有活跃 bot → 自动继续下一轮
                                else if (currentRs.waitingSpeakerIndex < 0 && currentRs.botIds.size > 1 && currentRs.forwardRound >= 0) {
                                    replyToForward = replyText.also { replyText = "" }
                                    shouldTrigger = true
                                    android.util.Log.i(TAG, "[$botName] 自动继续下一轮(ws=-1), fwd=${currentRs.forwardRound} reply='${replyToForward.take(60)}'")
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

    /**
     * 触发下一个 speaker
     *
     * 这是接龙的核心流程。当前 bot 说完(idle)后调用此方法，
     * 决定下一个由谁说话并转发音频/文字。
     *
     * 优先级（从高到低）：
     * 1. 结束语检测：回复包含"再见"等 → 不转发
     * 2. 轮次限制：已达最大轮次 → 不转发
     * 3. 音频转发：将上一位 bot 的音频发送给候选 bot
     * 4. 文字兜底：音频转发失败时，将文本作为提示发送
     * 5. 重连重试：所有候选都不可用时清死标记 + 重连后重试
     *
     * @param previousReply 上一位 bot 的回复文本（空字符串表示用户语音触发）
     */
    private suspend fun triggerNextSpeaker(previousReply: String = "") {
        // ── 互斥锁：防止 watchdog 和正常路径并发调用 ──
        // triggerNextSpeaker 是 suspend 函数，forwardAudioToBot 会挂起，
        // 此时看门狗可能检测到「假卡死」（botLastAudioTime=0→silenceDuration>30s）
        // 并发起第二次转发，导致 bot 收到重复输入
        synchronized(relayLock) {
            if (isRelaying) {
                android.util.Log.w(TAG, "triggerNextSpeaker 已在执行中，跳过并发调用")
                return
            }
            isRelaying = true
        }
        try {
            innerTriggerNextSpeaker(previousReply)
        } finally {
            synchronized(relayLock) { isRelaying = false }
        }
    }

    private suspend fun innerTriggerNextSpeaker(previousReply: String = "") {
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
        // 结束语检测：如果 bot 说了"再见"等，自然结束接龙
        // 使用正则整词匹配（不是 contains，防止"下次聊点别的"误触发）
        // 匹配规则：关键词必须在句子末尾 8 个字符内出现，避免句子中间误杀
        if (previousReply.isNotBlank()) {
            val tail = previousReply.takeLast(35) // 只看末尾 35 个字符
            val isGoodbye = RelayConfig.GOODBYE_KEYWORDS.any { keyword ->
                // 关键词在末尾出现，且后面没有中文字符跟随
                val idx = tail.indexOf(keyword)
                idx >= 0 && (idx + keyword.length >= tail.length || 
                    tail.substring(idx + keyword.length).none { it in '一'..'龥' })
            }
            if (isGoodbye) {
                android.util.Log.i(TAG, "检测到结束语，停止接龙: '${previousReply.take(20)}'")
                _relayState.update { it.clearWaiting() }
                return
            }
        }

        val prevIndex = rs.currentSpeakerIndex
        val prevBotId = rs.botIds.getOrNull(prevIndex)

        // 轮次限制
        val maxRounds = if (RelayConfig.MAX_FORWARD_ROUND > 0) RelayConfig.MAX_FORWARD_ROUND else rs.botIds.size - 1
        if (rs.forwardRound >= maxRounds) {
            android.util.Log.i(TAG, "接龙已达最大轮次($maxRounds)，停止转发")
            _relayState.update { it.clearWaiting() }
            return
        }

        // 原子地读取状态、计算候选、写入更新（避免读-计算-写之间的竞态）
        var candidates: List<Pair<Int, String>> = emptyList()
        _relayState.update { current ->
            val (result, newState) = resolveSpeakerCandidates(prevIndex, previousReply, current.botIds, current)
            candidates = result
            newState
        }

        // 过滤出已连接的候选 bot
        val connected = candidates.filter { (_, id) ->
            val svc = botConnectionPool.get(id)
            svc != null && (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected
                || run {
                    val contact = _contacts.value.find { it.id == id }
                    contact != null && botConnectionPool.ensureConnected(id, contact)
                })
        }

        // ── 终止前一个 bot 的 TTS，防止两个 bot 同时说话 ──
        // 旧的 server session 可能还在往外吐音频，abort 告诉服务器停下
        if (prevBotId != null && prevBotId != connected.firstOrNull()?.second) {
            val prevSvc = botConnectionPool.get(prevBotId)
            if (prevSvc != null) {
                android.util.Log.i(TAG, "终止前 speaker[$prevBotId] 的 TTS")
                prevSvc.sendAbort("relay_switch")
            }
        }

        // ── 遍历候选 bot，尝试转发 ──
        for ((targetIndex, speakerId) in connected) {
            val service = botConnectionPool.get(speakerId) ?: continue
            _relayState.update { it.moveToSpeaker(targetIndex) }
            botLastAudioTime[speakerId] = System.currentTimeMillis()  // 重置看门狗计时

            // ── 音频转发（主路径）：发送原始 Opus 音频，通用（任何服务器都支持） ──
            // 接收 bot 的 ASR 转写后生成回复，不携带说话人标识。
            val audioBuf = audioForwardBuffer[prevBotId]
            val isLongAudio = audioBuf != null && audioBuf.size > 200
            val audioOk = if (isLongAudio) {
                android.util.Log.i(TAG, "超长语音(${audioBuf!!.size}包)，跳过音频转发")
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
                        ).markSpoken(targetIndex).recordTransition(prevBotId ?: "", speakerId)
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

            // ── 文本转发（备用路径）：音频转发失败时兜底 ──
            // 根据服务器类型选择格式：
            //   本地服务器 → JSON 格式（带说话人标识，需服务器方能解析）
            //   官方服务器 → 短文本（≤8字），官方 detect 模式限制长文本
            val isLocal = service.isLocalServer()
            val textToSend = if (isLocal) {
                buildForwardMessage(previousReply, prevBotId)  // JSON 格式
            } else {
                if (previousReply.isNotBlank()) truncateReply(previousReply, 8) else ""  // 8字短文本
            }
            if (checkDeadloop(textToSend, speakerId)) {
                android.util.Log.i(TAG, "死循环终止，跳过本轮转发")
                _relayState.update { it.clearWaiting() }
                return
            }
            if (textToSend.isNotBlank()) {
                if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                    android.util.Log.w(TAG, "文本转发失败: $speakerId WebSocket未连接")
                    _relayState.update { it.clearWaiting() }
                    continue
                }
                audioForwardBuffer.remove(prevBotId)
                val logText = if (isLocal) textToSend.let { "JSON(${it.length}字)" } else "短文本(${textToSend.length}字)"
                android.util.Log.i(TAG, "文本转发 $logText → $speakerId: '${textToSend.take(60)}'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        _relayState.update { it.copy(originalUserQuery = previousReply.take(20)) }
                    }
                    val startConfirmed = withTimeoutOrNull(30_000) {
                        service.aiState.first { it == "speaking" }
                    }
                    if (startConfirmed != null) {
                        _relayState.update {
                            it.copy(forwardRound = it.forwardRound + 1)
                                .markSpoken(targetIndex)
                                .recordTransition(prevBotId ?: "", speakerId)
                        }
                        android.util.Log.i(TAG, "文本转发成功 → $speakerId fwd=${_relayState.value.forwardRound}")
                        return
                    }
                    android.util.Log.w(TAG, "文本转发超时($speakerId 30s未响应)，尝试下一候选")
                    _relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "文本转发异常: ${e.message}")
                    _relayState.update { it.clearWaiting() }
                }
            }
        }

        audioForwardBuffer.remove(prevBotId)

        // ── 所有候选 bot 都不可用 → 清死标记 + 重连每个候选一次 ──
        if (candidates.isNotEmpty() && _relayState.value.forwardRound >= 0) {
            android.util.Log.w(TAG, "无可用bot转发，尝试重连所有候选")
            botConnectionPool.clearDead()
            for ((origIdx, speakerId) in candidates) {
                val contact = _contacts.value.find { it.id == speakerId }
                if (contact != null) {
                    val reconnected = botConnectionPool.ensureConnected(speakerId, contact)
                    if (reconnected) {
                        android.util.Log.i(TAG, "重连成功 → $speakerId，继续转发")
                        _relayState.update { it.moveToSpeaker(origIdx) }
                        triggerNextSpeaker(previousReply)
                        return
                    }
                }
            }
        }

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

    /**
     * 计算下一候选 speaker
     *
     * 评分机制（分数越高越优先）：
     * +100  @提及（如"@小明"）
     * +30   名字匹配（回复中包含 bot 名称）
     * +20   本轮尚未发言
     * +0~15 随机抖动（避免完全确定性选择）
     * -20×N  重复轮转惩罚（同一 from→to ≥2 次）
     *
     * 特殊情况：无强信号（@提及或名字匹配）且分数扁平时 →
     * fallback 到 round_robin（按列表顺序轮转）
     *
     * 返回：List<Pair<原始索引, botId>> + 更新后的 RelayState
     *
     * @param prevIndex 上一位发言 bot 的索引
     * @param previousReply 上一位 bot 的回复文本
     * @param botIds 当前 bot 列表
     * @param rs 当前 RelayState
     */
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

        // ── 打分 ──
        var hasSignal = false  // 是否有强信号（@提及或名字匹配）
        if (previousReply.isNotBlank()) {
            // @提及 +100
            val mentionMatch = Regex("@([\\w\\u4e00-\\u9fff]+)").find(previousReply)
            if (mentionMatch != null) {
                val mentionedName = mentionMatch.groupValues[1]
                candidates.forEach { c ->
                    val contact = _contacts.value.find { it.id == c.id }
                    if (contact != null && (contact.name == mentionedName || contact.id.startsWith(mentionedName))) {
                        c.score += 100f
                        hasSignal = true
                    }
                }
            }
            // 名字匹配 +30
            candidates.forEach { c ->
                val contact = _contacts.value.find { it.id == c.id }
                if (contact != null && contact.name.isNotBlank() && previousReply.contains(contact.name)) {
                    c.score += 30f
                    hasSignal = true
                }
            }
        }

        // ── 重复轮转惩罚：同一组 (from→to) 出现太多次则降权 ──
        val transitionPenalty = 20f
        if (prevBotId != null) {
            val recentTransitions = rs.transitionHistory
            candidates.forEach { c ->
                val count = recentTransitions.count { it.first == prevBotId && it.second == c.id }
                if (count >= 2) {
                    c.score -= transitionPenalty * count
                    android.util.Log.w(TAG, "  惩罚 [$prevBotId→${c.id}] 出现${count}次, score=${c.score}")
                }
            }
        }

        // 未发言 +20
        candidates.forEach { c ->
            if (c.index !in compactedSpoken) c.score += 20f
        }

        // ── 无强信号 + 得分扁平 → fallback 到 round_robin ──
        // round_robin: 选列表中 prevBotId 的下一个存活 bot
        val useRoundRobin = !hasSignal && prevBotId != null
        if (useRoundRobin) {
            val aliveIdx = aliveBotIds.indexOf(prevBotId)
            if (aliveIdx >= 0) {
                for (offset in 1..aliveBotIds.size) {
                    val nextIdx = (aliveIdx + offset) % aliveBotIds.size
                    val nextId = aliveBotIds[nextIdx]
                    val origIdx = botIds.indexOf(nextId)
                    val result = listOf(origIdx to nextId)
                    val newRs = rs.copy(
                        botIds = aliveBotIds,
                        spokenInThisRound = compactedSpoken,
                        spokenRoundCounter = rs.spokenRoundCounter
                    )
                    android.util.Log.i(TAG, "选人: round_robin [$prevBotId→$nextId] (无强信号)")
                    return result to newRs
                }
            }
        }

        // 随机抖动 +0~15
        candidates.forEach { c -> c.score += (Math.random().toFloat() * 15f) }

        // 排序：分数降序 → 随机二级排序
        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.score }
            .thenBy { Math.random() })

        // 压缩索引后的 spokenInThisRound 重置逻辑
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
    // 由 BotConnectionPool 管理

    /**
     * 排空上一 bot 的音频播放
     *
     * 在切换 speaker 之前，确保上一个 bot 的音频全部播放完毕。
     * 分两步：
     * 1. 等待 botLastAudioTime 静默超过 AUDIO_DRAIN_SILENCE_MS
     * 2. 等待解码器队列排空
     */
    private suspend fun drainPreviousBotAudio(botId: String?, sourceId: String?) {
        if (botId == null || sourceId == null) return
        withTimeoutOrNull(RelayConfig.AUDIO_DRAIN_TIMEOUT_MS) {
            while (true) {
                val lastTs = botLastAudioTime[botId] ?: 0L
                if (lastTs <= 0) break
                if (System.currentTimeMillis() - lastTs > RelayConfig.AUDIO_DRAIN_SILENCE_MS) break
                delay(100)
            }
        }
        delay(200) // 等待 in-flight 包
        if (!playbackManager.waitForQueueEmpty(sourceId, 2000L)) {
            android.util.Log.w(TAG, "drainPreviousBotAudio 超时")
        }
    }

    // ── 音频转发 ──

    /**
     * 将上一位 bot 的缓存音频转发给下一个 bot
     *
     * 音频转发的意义：让下一个 bot "听到"当前 bot 说了什么，
     * 从而实现多 bot 之间的上下文感知对话。
     *
     * 流程：
     * 1. 检查条件（不是录音中、WebSocket 已连接、有缓存音频）
     * 2. 发送 listenStart 通知 bot 准备接收音频
     * 3. 攒批发送：每 5 包一组，加适当延迟避免 WebSocket 拥塞
     * 4. 发送 listenStop 通知 bot 音频结束
     *
     * 跳过条件：
     * - 用户正在录音
     * - WebSocket 未连接
     * - 无缓存音频
     * - 音频包 > 200 包（超长语音直接文字兜底，在 caller 判断）
     *
     * @param prevBotId 上一位 bot 的 ID（用于取出缓存音频）
     * @param service 目标 bot 的 WebSocket 服务实例
     * @return true=音频转发成功（服务端开始处理）
     */
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

    /**
     * 构建转发用的文本摘要
     *
     * 当音频转发失败时，将上一 bot 的回复文本提取前几个字
     * 作为提示发送给下一 bot。文本简短精炼，让下一 bot
     * 能大概知道对话上下文即可。
     */
    /**
     * 构建转发文本，带上说话人标识
     *
     * 格式：{"speaker":"说话人名称","content":"完整回复内容"}
     * 服务器端会解析 JSON 格式，提取说话人信息后传递给 LLM。
     * LLM prompt 中的 @[说话人:XXX] 标记让 bot 知道当前谁在说话。
     *
     * 注意：必须使用 previousReply（上一轮的完整回复内容），
     * 不得使用 truncated 的 originalUserQuery（那里存的是截断版历史，会丢失上下文）
     */
    private fun buildForwardMessage(previousReply: String, prevBotId: String? = null): String {
        if (previousReply.isBlank()) return ""
        val name = prevBotId?.let { id ->
            _contacts.value.find { id == it.id }?.name
        } ?: return "$previousReply"
        // JSON 格式，服务器端 startToChat 会解析 speaker/content 字段
        return "{\"speaker\":\"${name}\",\"content\":\"${previousReply.replace("\"", "\\\"")}\"}"
    }

    // ── 死循环检测 ──

    /**
     * 检测接龙是否陷入死循环
     *
     * 检测规则：
     * 1. 同一 hint 连续出现 ≥8 次（原为 5 次，提升阈值减少误杀）
     * 2. 同一 speakerId 连续出现 ≥8 次（2-bot 场景下交替必然出现相同 speaker）
     *
     * 两种条件 OR 触发，连续出现 ≥8 次 → 终止接龙
     * 放宽阈值的理由：
     * - 2-bot 场景下 bot1→bot2→bot1 交替，speaker 最多 2 个 distinct
     * - 短回复如"好的""明白"截断后 hint 容易相同
     *
     * @param hint 文本提示
     * @param speakerId 候选的 speaker ID
     * @return true=检测到死循环，应终止本轮转发
     */
    private fun checkDeadloop(hint: String, speakerId: String? = null): Boolean {
        synchronized(deadloopLock) {
            var isDead = false
            if (hint.isNotBlank()) {
                recentHints.add(hint)
                if (recentHints.size > MAX_DEADLOOP) recentHints.removeAt(0)
                // 要求 hint 全部相同（distinct == 1）才触发，排除"好的"+"嗯"交替的误杀
                if (recentHints.size >= MAX_DEADLOOP && recentHints.distinct().size == 1) isDead = true
            }
            if (speakerId != null) {
                recentCandidates.add(speakerId)
                if (recentCandidates.size > MAX_DEADLOOP) recentCandidates.removeAt(0)
                // 同一 bot 连续被选为候选 ≥5 次
                if (recentCandidates.size >= MAX_DEADLOOP && recentCandidates.distinct().size == 1) isDead = true
            }
            if (isDead) {
                deadloopCount++
                // 触发条件连续出现 ≥8 次才终止（原 5 次，调高减少误杀）
                if (deadloopCount >= 8) {
                    deadloopCount = 0; recentHints.clear(); recentCandidates.clear()
                    _relayState.update { it.clearWaiting() }
                    return true
                }
            } else deadloopCount = 0
            return false
        }
    }

    // ── 消息截断 ──

    /**
     * 智能截断文本
     *
     * 用于构建文字兜底时的提示文本。
     * 优先在句号/叹号/问号处截断，其次在逗号/空格处，
     * 最后硬截断到 maxLen 字符。
     */
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

    /**
     * 消息去重检测
     *
     * 存储 botId|content 的 key，确保同一 bot 不会发送
     * 完全相同的内容两次。使用 ConcurrentLinkedDeque 去重，
     * 最多保留 MAX_RECENT_REPLIES（40）条。
     *
     * 为什么需要去重？
     * 接龙时偶尔会遇到 bot1 重复返回同一句话（服务端 bug），
     * 不处理会导致 UI 大量重复消息 + 死循环。
     */
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
    // CRUD（群聊管理操作）
    // ═══════════════════════════════════════════

    /** 创建群聊 */
    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        val group = groupChatRepository.createGroup(name, selectedBotIds)
        loadGroups()
        return group
    }

    /** 删除群聊（同时断开所有连接） */
    suspend fun deleteGroup(groupId: String) {
        groupChatRepository.deleteGroup(groupId)
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        loadGroups()
    }

    /** 获取指定群聊的消息流 */
    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        return groupChatRepository.getGroupMessages(groupId)
    }

    /**
     * 发送文字消息到群聊
     *
     * 触发接龙流程：用户文字 → 首 bot 处理 → 自动转发到下一 bot
     */
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
        synchronized(deadloopLock) {
            recentHints.clear()
            recentCandidates.clear()
        }

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

    /** sendMessageToGroup 的缩写 */
    suspend fun sendMessage(groupId: String, content: String) = sendMessageToGroup(groupId, content)

    /** 修改群聊名称 */
    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatRepository.updateGroupName(groupId, newName); loadGroups()
    }

    /** 向群聊添加智能体成员 */
    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatRepository.addBotToGroup(groupId, botId)
        _relayState.update { it.copy(botIds = it.botIds + botId) }
        loadGroups()
    }

    /** 从群聊移除智能体成员 */
    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        _relayState.update { it.copy(botIds = it.botIds - botId) }
        loadGroups()
    }

    /** 清空群聊消息记录 */
    suspend fun clearGroupMessages(groupId: String) {
        groupChatRepository.clearGroupMessages(groupId)
    }

    /**
     * 导出群聊消息
     *
     * 通过 Android 分享 Intent 将消息文本发送到其他应用
     */
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

    /**
     * 断开群聊连接
     *
     * 清理所有资源：录音、监听 Job、WebSocket 连接、播放器、缓存
     */
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
