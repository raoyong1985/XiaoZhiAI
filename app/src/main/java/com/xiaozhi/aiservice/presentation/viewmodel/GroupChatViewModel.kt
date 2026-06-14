package com.xiaozhi.aiservice.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.audio.AudioRecorder
import com.xiaozhi.aiservice.data.logging.AndroidLogger
import com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.data.settings.AudioSettings
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
import com.xiaozhi.aiservice.domain.usecase.relay.AudioForwardHandler
import com.xiaozhi.aiservice.domain.usecase.relay.DeadloopDetector
import com.xiaozhi.aiservice.domain.usecase.relay.DeduplicationUtil
import com.xiaozhi.aiservice.domain.usecase.relay.RelayConfig
import com.xiaozhi.aiservice.domain.usecase.relay.RelayEngine
import com.xiaozhi.aiservice.domain.usecase.relay.SpeakerScorer
import com.xiaozhi.aiservice.domain.usecase.group.GroupChatUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject

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
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupChatRepository: GroupChatRepository,
    private val contactsRepository: ContactsRepository,
    private val botConnectionPool: BotConnectionPoolRepository,
    private val playbackManager: AudioPlaybackManager,
    private val audioService: AudioService,
    private val connectionRepository: ConnectionRepositoryImpl,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ═══════════════════════════════════════════
    // Bot 事件监听协程 Job 表：botId → Job，用于清理
    // ═══════════════════════════════════════════

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

    /** 乒乓模式：AI 正在回复中，等待回复完成（仅双人群聊生效） */
    private val _isAiResponding = MutableStateFlow(false)
    val isAiResponding: StateFlow<Boolean> = _isAiResponding.asStateFlow()

    // ── 录音相关 ──
    private val audioRecorder = AudioRecorder(audioService)
    private var currentGroupId = ""

    // ── 协程 Job 引用（用于取消） ──
    private var sttCollectorJob: Job? = null   // 语音转文字结果收集
    private var observeJob: Job? = null        // 主监听协程
    private var stuckRelayWatcher: Job? = null // 看门狗
    /** 监听 service 重建事件，重建 bot 事件观察者 */
    private var serviceReplacedJob: Job? = null

    /** speakerMutex：保护 speaker 切换操作，避免并发触发两次触发下一 bot */
    private val speakerMutex = Mutex()

    // 防死循环（Thread-safe：DeadloopDetector 内部使用 synchronized）
    private val deadloopDetector = DeadloopDetector()
    private val relayLogger = AndroidLogger()
    private val audioForwardHandler = AudioForwardHandler(
        audioForwardBuffer,
        { _isRecording.value },
        { _contacts.value },
        logger = relayLogger
    )
    private val speakerScorer = SpeakerScorer(
        { botConnectionPool.isDead(it) },
        { _contacts.value },
        logger = relayLogger
    )
    private val relayEngine = RelayEngine(
        botConnectionPool, audioForwardBuffer, _relayState,
        { _contacts.value }, { _isRecording.value }, botLastAudioTime,
        playbackManager, speakerMutex, audioForwardHandler, speakerScorer, deadloopDetector,
        tag = TAG, scope = viewModelScope, logger = relayLogger
    )
    /** 用于音频过滤的 grace 窗口：bot 进入 speaking 状态后 N 毫秒内允许音频通过 */
    private val botSpeakingTime = ConcurrentHashMap<String, Long>()
    /** 等待 speaker 索引最后变更的时间戳（ms），用于 grace 窗口计算 */
    private var lastWaitingChangeMs = 0L
    /** 空闲自动恢复连续计数，达到阈值后停止恢复 */
    private var continuousIdleRecoveries = 0
    /** 解码器缓存：采样率 → sourceId，避免采样率变化时频繁重建 */
    private val decoderCache = ConcurrentHashMap<Int, String>()

    // ── 消息去重 ──
    private val deduplicationUtil = DeduplicationUtil()

    // ── 群聊 CRUD UseCase ──
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

    /** 从仓库加载群聊列表 */
    private fun loadGroups() {
        viewModelScope.launch { _groups.value = groupChatRepository.getGroups() }
    }

    /** 从仓库加载联系人列表（可用的智能体，仅首次加载） */
    private fun loadContacts() {
        if (_contacts.value.isNotEmpty()) return
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
        connectionRepository.suppressAutoListening = true
        observeJob?.cancel()
        observeJob = null
        // 先 cancel 所有观察者，再断开连接（防止旧事件通道消费断线状态变更）
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        botConnectionPool.disconnectAll()
        playbackManager.releaseAll()
        _relayState.value = RelayState()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deduplicationUtil.clear()
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
                                Logger.w(TAG, "连接bot[$botId]失败: ${e.message}")
                            }
                        }
                    }.forEach { it.await() }
                    _relayState.update { it.copy(botIds = g.botIds) }
                }
            } finally {
                _isConnecting.value = false
            }
        }

        // ── 监听 service 重建（断线重连时重建观察者） ──
        serviceReplacedJob?.cancel()
        serviceReplacedJob = viewModelScope.launch {
            botConnectionPool.serviceReplaced.collect { (botId, newService) ->
                val contact = _contacts.value.find { it.id == botId }
                if (contact == null) {
                    Logger.w(TAG, "serviceReplaced: 找不到 bot[$botId] 的联系人信息")
                    return@collect
                }
                Logger.i(TAG, "serviceReplaced: bot[$botId] 连接已重建，重建事件观察者")
                // 取消旧观察者
                botObserverJobs[botId]?.cancel()
                // 注册新观察者
                observeBotEvents(botId, currentGroupId, contact.name, newService)
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
                    Logger.i(TAG, "清理死 bot 列表")
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
                        Logger.w(TAG, "接龙卡死检测：等待[$waitingBotId] 已静默 ${silenceDuration/1000}s（超${timeoutMs/1000}s），清除等待状态并重启接龙")
                        _relayState.update { it.clearWaiting() }
                        botConnectionPool.clearDead()
                        viewModelScope.launch {
                            var retries = 0
                            while (retries < 5) {
                                triggerNextSpeaker("")
                                if (_relayState.value.waitingSpeakerIndex >= 0) break
                                delay(1000)
                                retries++
                            }
                        }
                    }
                } else if (rs.forwardRound >= 0 && rs.botIds.isNotEmpty()) {
                    // 模式2：无等待 bot 但对话未结束——所有 bot 静默 >30s，自动恢复
                    val anyAudioRecently = rs.botIds.any {
                        val t = botLastAudioTime[it] ?: 0L
                        t > 0 && System.currentTimeMillis() - t < 30_000
                    }
                    if (!anyAudioRecently) {
                        // 检测是否包含结束语，避免无限接龙
                        val lastText = _relayState.value.lastForwardedText.lowercase()
                        val endKeywords = listOf("再见", "拜拜", "下次", "结束", "bye", "goodbye")
                        if (endKeywords.any { lastText.contains(it) }) {
                            continuousIdleRecoveries = 0
                            Logger.i(TAG, "检测到结束语，跳过空闲恢复")
                            continue
                        }
                        continuousIdleRecoveries++
                        if (continuousIdleRecoveries >= 3) {
                            Logger.w(TAG, "连续空闲恢复≥3次，停止自动恢复")
                            continue
                        }
                        Logger.w(TAG, "接龙空闲恢复：所有bot静默 >30s，清理死bot并重启接龙")
                        botConnectionPool.clearDead()
                        // 重置轮次，防止卡在"已达最大轮次"
                        _relayState.update { it.copy(forwardRound = 0) }
                        viewModelScope.launch {
                            var retries = 0
                            while (retries < 5) {
                                triggerNextSpeaker("")
                                if (_relayState.value.waitingSpeakerIndex >= 0) break
                                delay(1000)
                                retries++
                            }
                        }
                    }
                }
            }
        }  // stuckRelayWatcher

        // ── 乒乓模式：监听接龙结束，解锁输入（仅双人群聊生效） ──
        viewModelScope.launch {
            _relayState.collectLatest { state ->
                if (_isAiResponding.value && state.waitingSpeakerIndex < 0) {
                    delay(1500)
                    if (_isAiResponding.value && _relayState.value.waitingSpeakerIndex < 0) {
                        _isAiResponding.value = false
                    }
                }
            }
        }
    }

    /** 设置群聊输入模式（0=语音，1=文字） */
    fun setGroupInputMode(groupId: String, mode: Int) {
        viewModelScope.launch { groupChatUseCase.updateGroupInputMode(groupId, mode, _groups) }
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
        deadloopDetector.reset()

        val firstBotId = _relayState.value.botIds[0]
        val service = botConnectionPool.get(firstBotId) ?: return

        // 新会话：递增 sessionId，重置轮次
        _relayState.update { it.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0,
            waitingSpeakerIndex = 0, lastForwardedText = ""
        ) }
        lastWaitingChangeMs = System.currentTimeMillis()
        deduplicationUtil.clear()

        service.sendListenStart("auto")

        // ── 启动录音 ──
        // onOpusPacket：每个 Opus 音频包实时转发到首 bot 的 WebSocket
        // onSilenceDetected：VAD 检测到静音后自动停止录音
        if (!audioRecorder.startLive(
                owner = groupId,  // 传 groupId 标识，保护不与私聊录音冲突
                scope = viewModelScope,
                enableVAD = true,
                onOpusPacket = { packet -> service.sendAudio(packet) },
                onSilenceDetected = {
                    Logger.i(TAG, "VAD触发")
                    viewModelScope.launch { stopVoiceInput(userExplicitStop = false) }
                }
            )) {
            Logger.w(TAG, "录音启动失败")
            service.sendListenStop()
            return
        }
        _isRecording.value = true

        // ── 收集首 bot 的语音转文字结果 ──
        // 用户说的话经过首 bot 的 ASR 识别后通过 textMessage flow 返回
        // 这里收集文本用于 UI 显示和死循环检测
        sttCollectorJob?.cancel()
        sttCollectorJob = viewModelScope.launch {
            // 防御性吸收 StateFlow 当前残留值（sendListenStart 已同步清空，正常是 ""）
            // 避免极端时序下残留干扰"真正的 STT"与 lastCollectedText 的匹配
            var lastCollectedText = service.textMessage.value
            Logger.i(TAG, "sttCollector 启动, 初始基线='$lastCollectedText' (sendListenStart 后应为空)")
            service.textMessage.collect { text ->
                if (text.isNotBlank() && text != lastCollectedText) {
                    lastCollectedText = text
                    Logger.i(TAG, "[群聊STT] 收到用户语音文字='$text', 写入 groupId='$currentGroupId'")
                    if (_relayState.value.lastForwardedText.isEmpty()) {
                        _relayState.update { it.copy(lastForwardedText = text) }
                    }
                    val userMessage = ChatMessage(
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
        Logger.i(TAG, "停止录音 userExplicitStop=$userExplicitStop")

        if (userExplicitStop) {
            botConnectionPool.abortAll("user_stop")
        }

        audioRecorder.stop(owner = currentGroupId)
        audioService.abandonAudioFocus()
        // 不取消 sttCollectorJob！服务器可能还在处理 ASR，STT 结果稍后才到
        // 取消会导致文字收不到、对话框空白。下次 startVoiceInput 会取消旧的。

        try { botConnectionPool.get(_relayState.value.botIds.firstOrNull() ?: "")?.sendListenStopOnly() } catch (e: Exception) { Logger.w(TAG, "sendListenStopOnly failed: ${e.message}") }
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
    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: BotConnection) {
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
                    // 放宽过滤：grace 窗口内允许非预期 bot 的音频通过
                    // 解决 triggerNextSpeaker 还没更新 ws、但 bot 已经开始推音频的竞态
                    // 修复 3+ 人群：800ms → 2000ms，覆盖自建服务器 TTS 路由延迟
                    if (System.currentTimeMillis() - lastWaitingChangeMs > 2000) {
                        Logger.w(TAG, "[$botName] 音频被ws过滤(idx=$idx, ws=${rs.waitingSpeakerIndex}), ${audioData.size}B")
                        return@collect
                    }
                }
                Logger.i(TAG, "[$botName] 收到音频 ${audioData.size}B idx=$idx ws=${rs.waitingSpeakerIndex}")

                val serverRate = service.getServerSampleRate()
                val serverCh = service.getServerChannels()
                val volume = AudioSettings.getOutputVolume(context)
                // 按采样率缓存解码器，避免频繁重建导致短暂无声
                val currentSourceId = "${groupId}_shared_${serverRate}"
                if (!playbackManager.contains(currentSourceId)) {
                    // 先等旧解码器的队列排空再持锁释放，避免切换时丢失音频首帧
                    val oldCached = decoderCache[serverRate]
                    if (oldCached != null && playbackManager.contains(oldCached)) {
                        playbackManager.waitForQueueEmpty(oldCached, 500)
                    }
                    speakerMutex.withLock {
                        if (!playbackManager.contains(currentSourceId)) {
                            decoderCache[serverRate] = currentSourceId
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
                    is BotConnectionState.Error,
                    is BotConnectionState.Disconnected -> {
                        wasDisconnected = true
                    }
                    is BotConnectionState.Connected -> {
                        if (wasDisconnected) {
                            wasDisconnected = false
                            Logger.i(TAG, "[$botName] WebSocket重连完成，检查接龙状态")
                            val rs = _relayState.value
                            val idx = rs.botIds.indexOf(botId)
                            if (idx >= 0 && rs.waitingSpeakerIndex >= 0 && idx == rs.waitingSpeakerIndex) {
                                Logger.i(TAG, "[$botName] 接龙正等待本bot，清除等待状态")
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
        // 事件处理被拆成 3 个独立 handler：handleTtsEvent / handleMessageEvent /
        // handleAiStateEvent，共享 SpeakerContext 内的 replyText/hasSpoken/speakerSession。
        // handleAiStateEvent 返回 (shouldTrigger, replyToForward)，本函数负责发起 triggerNextSpeaker。
        val ctx = SpeakerContext()

        viewModelScope.launch(parentJob) {
            for (event in eventChannel) {
                val trigger: Pair<Boolean, String>? = when (event) {
                    is BotEvent.TtsText -> {
                        handleTtsEvent(event, ctx)
                        null
                    }
                    is BotEvent.Message -> {
                        handleMessageEvent(event, ctx, botId, groupId, botName)
                        null
                    }
                    is BotEvent.AiState -> handleAiStateEvent(event, ctx, botId, groupId, botName, service)
                }
                if (trigger != null && trigger.first) {
                    val reply = trigger.second
                    viewModelScope.launch { triggerNextSpeaker(reply) }
                }
            }
        }
    }  // observeBotEvents

    /**
     * 单 bot 观察者内的可变状态，被 3 个 handler 共享：
     * - replyText：流式累积 ttsText
     * - hasSpoken：本 bot 是否进入过 SPEAKING（用于 IDLE 时是否触发下一轮）
     * - speakerSession：进入 SPEAKING 时的 relaySessionId（用于 IDLE 时判断是否旧轮次）
     */
    private class SpeakerContext {
        var replyText: String = ""
        var hasSpoken: Boolean = false
        var speakerSession: Long = 0L
    }

    /** Handler: 累积 TTS 文本到 ctx.replyText */
    private fun handleTtsEvent(event: BotEvent.TtsText, ctx: SpeakerContext) {
        // 流式文本累积：多个 ttsText 拼接为完整回复
        ctx.replyText = if (ctx.replyText.isBlank()) event.text else "${ctx.replyText} ${event.text}"
    }

    /**
     * Handler: 收到完整消息 → 去重检查 + 写入群聊消息流
     *
     * 去重时不清空 ctx.replyText —— 重复消息的文本仍需作为转发上下文
     * 给下一个 bot（仅跳过 UI 显示），避免下一 bot 收到空白。
     */
    @Suppress("UNUSED_PARAMETER")  // ctx 是统一接口签名的一部分，Tts/AiState handler 需要它
    private suspend fun handleMessageEvent(
        event: BotEvent.Message,
        ctx: SpeakerContext,
        botId: String,
        groupId: String,
        botName: String
    ) {
        val msg = event.msg
        if (msg.direction == MessageDirection.RECEIVED && msg.content.isNotBlank()) {
            if (isDuplicateReply(botId, msg.content)) {
                Logger.w(TAG, "[$botName] 跳过重复消息: ${msg.content.take(30)}")
                return  // 等价于原 for 循环内的 `continue` —— 跳过当前事件处理
            }
            groupChatRepository.addMessage(groupId, ChatMessage(
                id = java.util.UUID.randomUUID().toString(), groupId = groupId,
                botId = botId, botName = botName, content = msg.content,
                type = MessageType.TEXT, direction = MessageDirection.RECEIVED
            ))
        }
    }

    /**
     * Handler: AI 状态机——SPEAKING 准备 / IDLE 触发下一轮
     *
     * 关键点：
     * - SPEAKING：记录时间/会话/共享解码器预创建；非预期发言清文本但保留状态机
     * - IDLE+hasSpoken：排空音频后，在 speakerMutex 保护下判断是否触发下一轮
     *   路径 A：自己是 waitingSpeaker → 正常触发
     *   路径 B：ws=-1 + forwardRound>=0 → 自动继续
     *   路径 C：3+ 人群非预期 bot 完成 → 主动清 ws + 重选人
     *
     * @return (shouldTrigger, replyToForward)；非触发场景返回 null
     */
    private suspend fun handleAiStateEvent(
        event: BotEvent.AiState,
        ctx: SpeakerContext,
        botId: String,
        groupId: String,
        botName: String,
        service: BotConnection
    ): Pair<Boolean, String>? {
        val state = event.state
        val rs = _relayState.value
        Logger.i(TAG, "[$botName] aiState=$state reply='${ctx.replyText.take(60)}' fwd=${rs.forwardRound} ws=${rs.waitingSpeakerIndex}")

        if (state == AiStateStr.SPEAKING) {
            // ── AI 开始说话 ──
            audioService.requestAudioFocus()
            // 记录该 bot 进入 speaking 的时间，audioData 过滤据此做 3 秒 grace
            botSpeakingTime[botId] = System.currentTimeMillis()
            val botIndex = rs.botIds.indexOf(botId)
            // 修复 3+ 人群：非预期发言不 continue，让后续 IDLE 也能进回收路径
            // 双人群 ws 100% 命中 → 此条件不成立；3+ 人服务器 TTS 路由错乱时非预期 bot 也会完成
            val isUnexpected = rs.waitingSpeakerIndex >= 0 && botIndex >= 0 && botIndex != rs.waitingSpeakerIndex
            if (isUnexpected && System.currentTimeMillis() - lastWaitingChangeMs > 2000) {
                Logger.w(TAG, "[$botName] 非预期发言(idx=$botIndex, expected=${rs.waitingSpeakerIndex})，清文本但保留状态机")
                ctx.replyText = ""  // 清空累积文本，避免下次转发旧数据
            }
            ctx.hasSpoken = true
            ctx.speakerSession = rs.sessionId
            // 首 bot 开始说话 → 自动停止用户录音
            if (botId == rs.botIds.firstOrNull()) {
                if (_isRecording.value) {
                    Logger.i(TAG, "AI开始说话，自动停止录音")
                    stopVoiceInput(userExplicitStop = false)
                }
                // ⚠️ 不取消 sttCollectorJob！服务器 ASR 可能在录音停止后才返回结果，
                // 取消会导致 STT 文字收不到、聊天框不显示用户语音内容。
                // 下次 startVoiceInput 会自动取消旧的 sttCollectorJob。
            }
            // 预创建共享解码器（减少音频到达时的首帧延迟）
            // 注意：按采样率区分 sourceId，避免切换 bot 时频繁重建
            val serverRate = service.getServerSampleRate()
            val serverCh = service.getServerChannels()
            val volume = AudioSettings.getOutputVolume(context)
            val currentSourceId = "${groupId}_shared_${serverRate}"
            if (!playbackManager.contains(currentSourceId)) {
                decoderCache[serverRate] = currentSourceId
                playbackManager.getOrCreate(currentSourceId, serverRate, serverCh, volume)
                Logger.i(TAG, "[$botName] 预创建共享解码器 (${serverRate}Hz, 音量=$volume%)")
            }
        }

        if (state == AiStateStr.IDLE && ctx.hasSpoken) {
            // ── AI 说完话 → 触发下一轮 ──
            ctx.hasSpoken = false
            val drainRate = service.getServerSampleRate()
            drainPreviousBotAudio(botId, "${groupId}_shared_${drainRate}")

            var shouldTrigger = false
            var replyToForward = ""
            speakerMutex.withLock {
                val currentRs = _relayState.value
                val botIndex = currentRs.botIds.indexOf(botId)

                // ═══ 诊断日志 ═══
                Logger.i(TAG, "[$botName] IDLE诊断: session(speaker=${ctx.speakerSession}/current=${currentRs.sessionId}) " +
                    "botIdx=$botIndex ws=${currentRs.waitingSpeakerIndex} fwd=${currentRs.forwardRound} " +
                    "botCnt=${currentRs.botIds.size} reply='${ctx.replyText.take(60)}' bufSize=${audioForwardBuffer[botId]?.size ?: -1}")

                if (ctx.speakerSession != currentRs.sessionId) {
                    Logger.i(TAG, "[$botName] 跳过旧轮次 session=${ctx.speakerSession} current=${currentRs.sessionId}")
                    return@withLock
                }
                if (botIndex < 0) return@withLock

                if (botIndex == currentRs.waitingSpeakerIndex && currentRs.botIds.size > 1) {
                    replyToForward = ctx.replyText.also { ctx.replyText = "" }
                    shouldTrigger = true
                    Logger.i(TAG, "[$botName] triggering next, fwd=${currentRs.forwardRound} reply='${replyToForward.take(60)}'")
                }
                else if (currentRs.waitingSpeakerIndex < 0 && currentRs.botIds.size > 1 && currentRs.forwardRound >= 0) {
                    replyToForward = ctx.replyText.also { ctx.replyText = "" }
                    shouldTrigger = true
                    Logger.i(TAG, "[$botName] 自动继续下一轮(ws=-1), fwd=${currentRs.forwardRound} reply='${replyToForward.take(60)}'")
                }
                // 路径 C：3+ 人群服务器 TTS 路由错乱时，非预期 bot 完成 → 主动清 ws + 重选人
                // 不等 watchdog 30s 兜底，立即重试
                else if (botIndex != currentRs.waitingSpeakerIndex && currentRs.waitingSpeakerIndex >= 0 && currentRs.botIds.size > 1) {
                    replyToForward = ctx.replyText.also { ctx.replyText = "" }
                    shouldTrigger = true
                    Logger.w(TAG, "[$botName] 非预期bot完成(idx=$botIndex, ws=${currentRs.waitingSpeakerIndex})，清ws主动重试")
                    _relayState.update { it.clearWaiting() }
                    botConnectionPool.clearDead()
                } else {
                    Logger.i(TAG, "[$botName] 不满足触发条件: ws=${currentRs.waitingSpeakerIndex} botCnt=${currentRs.botIds.size} fwd=${currentRs.forwardRound} " +
                        "matchA=${botIndex == currentRs.waitingSpeakerIndex} matchB=${currentRs.waitingSpeakerIndex < 0 && currentRs.botIds.size > 1 && currentRs.forwardRound >= 0}")
                    ctx.replyText = ""
                }
            }
            return if (shouldTrigger) Pair(true, replyToForward) else null
        }
        return null
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
        relayEngine.triggerNextSpeaker(previousReply)
        lastWaitingChangeMs = System.currentTimeMillis()
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
        relayEngine.drainPreviousBotAudio(botId, sourceId)
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
        return deduplicationUtil.isDuplicate(botId, content)
    }

    // ═══════════════════════════════════════════
    // CRUD（群聊管理操作 — 委派给 GroupChatUseCase）
    // ═══════════════════════════════════════════

    /** 创建群聊 */
    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        return groupChatUseCase.createGroup(name, selectedBotIds)
    }

    /** 删除群聊（同时断开所有连接） */
    suspend fun deleteGroup(groupId: String) {
        groupChatUseCase.deleteGroup(groupId)
    }

    /** 获取指定群聊的消息流 */
    fun getGroupMessages(groupId: String): Flow<List<ChatMessage>> {
        return groupChatUseCase.getGroupMessages(groupId)
    }

    /** 发送文字消息到群聊 */
    suspend fun sendMessageToGroup(groupId: String, content: String) {
        // 乒乓模式：仅双人群聊锁定输入
        val group = groupChatRepository.getGroup(groupId)
        if (group?.botIds?.size == 2) {
            _isAiResponding.value = true
        }
        groupChatUseCase.sendMessageToGroup(groupId, content)
    }

    /** sendMessageToGroup 的缩写 */
    suspend fun sendMessage(groupId: String, content: String) = sendMessageToGroup(groupId, content)

    /** 修改群聊名称 */
    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatUseCase.updateGroupName(groupId, newName)
    }

    /** 向群聊添加智能体成员 */
    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatUseCase.addBotToGroup(groupId, botId)
    }

    /** 从群聊移除智能体成员 */
    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatUseCase.removeBotFromGroup(groupId, botId)
    }

    /** 清空群聊消息记录 */
    suspend fun clearGroupMessages(groupId: String) {
        groupChatUseCase.clearGroupMessages(groupId)
    }

    /** 导出群聊消息 */
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

    /**
     * 断开群聊连接
     *
     * 清理所有资源：录音、监听 Job、WebSocket 连接、播放器、缓存
     */
    fun disconnectGroup() {
        connectionRepository.suppressAutoListening = false
        stopVoiceInput()
        sttCollectorJob?.cancel()
        stuckRelayWatcher?.cancel()
        serviceReplacedJob?.cancel()
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
        deduplicationUtil.clear()
        _relayState.value = RelayState()
        Logger.i(TAG, "disconnectGroup: 群聊资源已全部释放")
    }

    override fun onCleared() {
        super.onCleared()
        disconnectGroup()
        audioRecorder.release()
    }
}
