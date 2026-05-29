# 群聊 Relay 重构计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将群聊 ViewModel 从 ~910 行巨型类拆分为状态机驱动的分层架构，解决 relay 竞态、音频丢失、文字乱码问题。

**Architecture:** 状态机(RelayPhase) + 中介者(RelayOrchestrator) + 音频路由(AudioRouter) + 转发策略(SmartForwarder)。ViewModel 只管 UI 状态。

**Tech Stack:** Kotlin, Coroutines, StateFlow, SharedFlow, Android AudioTrack

---

## 现有问题

1. **relay 竞态**：aiState IDLE 和 ttsStopped 竞态导致 hasSpoken 被提前清除
2. **音频丢失**：sentence_end 3s 超时触发提前 IDLE，speaker 过滤丢弃剩余音频
3. **文字乱码**：非预期 speaker 的 TTS 文字未过滤 + 转发音频导致 STT 乱码
4. **代码耦合**：ViewModel 910 行，混合 UI/relay/音频/连接管理

## 目录结构（重构后）

```
domain/usecase/relay/
├── RelayPhase.kt              # 新：状态机枚举
├── RelayEvent.kt              # 新：状态转换事件
├── RelayOrchestrator.kt       # 重写：状态机 + 调度（替换 RelayEngine）
├── AudioRouter.kt             # 新：音频路由（从 ViewModel 抽出）
├── SmartForwarder.kt          # 重写：音频优先 + 文本兜底（替换 AudioForwardHandler）
├── SpeakerScorer.kt           # 保留
├── DeadloopDetector.kt        # 保留
├── DeduplicationUtil.kt       # 保留
├── RelayValidator.kt          # 保留
├── RelayConfig.kt             # 保留（加新配置）
└── BotSessionPool.kt          # 新：连接池包装（从 ViewModel 抽出）

domain/model/
└── RelayState.kt              # 重写：增加 phase 字段

presentation/viewmodel/
├── GroupChatViewModel.kt      # 重写：瘦到 ~300 行
└── BotEvent.kt                # 保留
```

---

## Task 1: 创建 RelayPhase 状态机枚举

**Objective:** 定义群聊 relay 的所有状态和转换事件

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayPhase.kt`

**Step 1: 创建状态机**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 群聊接力状态机
 *
 * 状态转换图:
 *   IDLE → USER_SPEAKING → BOT_PROCESSING → BOT_SPEAKING → RELAY_SWITCH → BOT_PROCESSING → ...
 *   任何状态 → FINISHED（超时/结束语/用户中断）
 */
enum class RelayPhase {
    /** 等待用户输入 */
    IDLE,
    /** 用户正在说话（录音中） */
    USER_SPEAKING,
    /** Bot 正在处理（LLM + TTS 生成中，尚未有音频） */
    BOT_PROCESSING,
    /** Bot 正在播放音频 */
    BOT_SPEAKING,
    /** 切换到下一个 bot（ drain + 选人 + 转发） */
    RELAY_SWITCH,
    /** 本轮对话结束 */
    FINISHED
}

/**
 * 触发状态转换的事件
 */
sealed class RelayEvent {
    /** 用户开始录音 */
    data object UserStartSpeaking : RelayEvent()
    /** 用户停止录音（VAD 或手动） */
    data object UserStopSpeaking : RelayEvent()
    /** Bot 开始处理（收到 listen 后服务器开始推理） */
    data object BotStartProcessing : RelayEvent()
    /** Bot 开始播放 TTS（收到 tts start） */
    data object BotStartSpeaking : RelayEvent()
    /** Bot TTS 结束（收到 tts stop） */
    data object BotStopSpeaking : RelayEvent()
    /** 下一个 bot 已就绪（音频到达或超时） */
    data object NextBotReady : RelayEvent()
    /** 对话结束（结束语/最大轮次/超时） */
    data object ConversationFinished : RelayEvent()
    /** 用户中断（按下停止/新的语音输入） */
    data object UserInterrupt : RelayEvent()
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
# 预期: BUILD SUCCESSFUL
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayPhase.kt
git commit -m "feat(relay): add state machine enum and events"
```

---

## Task 2: 重写 RelayState —— 增加 phase 字段

**Objective:** 在 RelayState 中增加 phase 字段，替代分散的 15 个 var

**Files:**
- Modify: `app/src/main/java/com/xiaozhi/aiservice/domain/model/RelayState.kt`

**Step 1: 重写 RelayState**

```kotlin
package com.xiaozhi.aiservice.domain.model

import com.xiaozhi.aiservice.domain.usecase.relay.RelayPhase

data class RelayState(
    val phase: RelayPhase = RelayPhase.IDLE,
    val botIds: List<String> = emptyList(),
    val currentSpeakerIndex: Int = 0,
    val waitingSpeakerIndex: Int = -1,
    val forwardRound: Int = 0,
    val sessionId: Long = 0L,
    val spokenInThisRound: Set<Int> = emptySet(),
    val spokenRoundCounter: Int = 0,
    val lastForwardedText: String = "",
    val transitionHistory: List<Pair<String, String>> = emptyList(),
    /** 当前预期 speaker 的 botId（音频过滤用） */
    val expectedSpeakerId: String = "",
    /** 当前轮次的回复文本（转发给下一个 bot 用） */
    val currentReplyText: String = ""
) {
    fun nextSession() = copy(
        phase = RelayPhase.IDLE,
        sessionId = sessionId + 1,
        forwardRound = 0,
        spokenInThisRound = emptySet(),
        spokenRoundCounter = 0,
        transitionHistory = emptyList(),
        expectedSpeakerId = "",
        currentReplyText = ""
    )

    fun markSpoken(index: Int) = copy(
        spokenInThisRound = spokenInThisRound + index,
        spokenRoundCounter = spokenRoundCounter + 1
    )

    fun moveToSpeaker(index: Int, botId: String) = copy(
        currentSpeakerIndex = index,
        waitingSpeakerIndex = index,
        expectedSpeakerId = botId,
        phase = RelayPhase.BOT_PROCESSING
    )

    fun clearWaiting() = copy(waitingSpeakerIndex = -1, expectedSpeakerId = "")

    fun recordTransition(fromId: String, toId: String) = copy(
        transitionHistory = (transitionHistory + (fromId to toId)).takeLast(20)
    )
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
# 预期: BUILD SUCCESSFUL（可能有引用旧字段的编译错误，Task 3 修复）
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/model/RelayState.kt
git commit -m "feat(relay): rewrite RelayState with phase and expectedSpeakerId"
```

---

## Task 3: 创建 AudioRouter —— 音频路由独立模块

**Objective:** 从 ViewModel 抽出音频路由逻辑，管理 shared decoder 和 speaker 过滤

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/AudioRouter.kt`

**Step 1: 创建 AudioRouter**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.data.settings.AudioSettings
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import android.util.Log

/**
 * 音频路由 —— 管理 shared decoder 和 speaker 过滤
 *
 * 职责：
 * 1. 所有 bot 的音频写入同一个 shared decoder
 * 2. speaker 过滤：只播放预期 bot 的音频
 * 3. decoder 生命周期管理
 */
class AudioRouter(
    private val playbackManager: AudioPlaybackManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRouter"
    }

    /** 按采样率缓存的 sourceId */
    private val decoderCache = ConcurrentHashMap<Int, String>()

    /** 上次 speaker 切换的时间戳（ms），用于 grace 窗口 */
    @Volatile
    var lastSpeakerChangeMs: Long = 0L

    /**
     * 获取 shared sourceId
     */
    fun getSharedSourceId(groupId: String, serverRate: Int): String {
        return "${groupId}_shared_${serverRate}"
    }

    /**
     * 路由音频到 shared decoder
     *
     * @return true=音频已播放, false=被 speaker 过滤丢弃
     */
    fun routeAudio(
        groupId: String,
        botId: String,
        serverRate: Int,
        serverCh: Int,
        audioData: ByteArray,
        expectedSpeakerId: String
    ): Boolean {
        // speaker 过滤
        if (expectedSpeakerId.isNotEmpty() && botId != expectedSpeakerId) {
            // grace 窗口：切换后 800ms 内允许通过
            if (System.currentTimeMillis() - lastSpeakerChangeMs > 800) {
                Log.w(TAG, "音频被过滤: $botId != expected=$expectedSpeakerId")
                return false
            }
        }

        val sourceId = getSharedSourceId(groupId, serverRate)
        val volume = AudioSettings.getOutputVolume(context)

        // 懒创建 decoder
        if (!playbackManager.contains(sourceId)) {
            decoderCache[serverRate] = sourceId
            playbackManager.getOrCreate(sourceId, serverRate, serverCh, volume)
            Log.i(TAG, "创建 shared decoder: $sourceId (${serverRate}Hz)")
        }

        playbackManager.decodeAndPlay(sourceId, serverRate, serverCh, audioData, volume)
        return true
    }

    /**
     * 通知 speaker 已切换
     */
    fun onSpeakerChanged() {
        lastSpeakerChangeMs = System.currentTimeMillis()
    }

    /**
     * flush shared decoder（用户开始新录音时调用）
     */
    fun flush(groupId: String, serverRate: Int) {
        val sourceId = getSharedSourceId(groupId, serverRate)
        if (playbackManager.contains(sourceId)) {
            playbackManager.flushSource(sourceId)
        }
    }

    /**
     * 释放所有 decoder
     */
    fun releaseAll() {
        playbackManager.releaseAll()
        decoderCache.clear()
    }
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/AudioRouter.kt
git commit -m "feat(relay): add AudioRouter for shared decoder management"
```

---

## Task 4: 重写 SmartForwarder —— 音频优先 + 文本兜底

**Objective:** 重写 AudioForwardHandler，支持音频优先 + 文本兜底的转发策略

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/SmartForwarder.kt`
- Keep: `AudioForwardHandler.kt`（不删除，SmartForwarder 委托给它）

**Step 1: 创建 SmartForwarder**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.PlatformLogger
import kotlinx.coroutines.delay

/**
 * 智能转发器 —— 音频优先 + 文本兜底
 *
 * 转发策略：
 * 1. 首选：转发 Opus 音频（官方服务器）
 * 2. 兜底：转发文本摘要（第三方服务器 / 音频转发失败 / 音频为空）
 */
class SmartForwarder(
    private val audioForwardHandler: AudioForwardHandler,
    private val getContacts: () -> List<BotContact>,
    private val logger: PlatformLogger = PlatformLogger.EMPTY
) {
    private val tag = "SmartForwarder"

    /**
     * 转发上一位 bot 的回复到下一位 bot
     *
     * @return ForwardResult
     */
    suspend fun forward(
        prevBotId: String?,
        previousReply: String,
        nextBot: BotSession
    ): ForwardResult {
        if (prevBotId == null || previousReply.isBlank()) {
            return ForwardResult.SKIPPED
        }

        // 1. 尝试音频转发
        val audioOk = audioForwardHandler.forwardAudioToBot(prevBotId, nextBot.connection)
        if (audioOk) {
            logger.i(tag, "音频转发成功 → ${nextBot.botId}")
            return ForwardResult.AUDIO_SENT
        }

        // 2. 音频转发失败 → 文本兜底
        val textMsg = audioForwardHandler.buildForwardMessage(previousReply, prevBotId)
        if (textMsg.isNotBlank() && nextBot.connection.connectionState.value is BotConnectionState.Connected) {
            try {
                nextBot.connection.sendTextContent(textMsg)
                logger.i(tag, "文本兜底转发 → ${nextBot.botId}: '${previousReply.take(30)}'")
                return ForwardResult.TEXT_SENT
            } catch (e: Exception) {
                logger.w(tag, "文本转发失败: ${e.message}")
            }
        }

        return ForwardResult.FAILED
    }

    data class BotSession(
        val botId: String,
        val connection: BotConnection
    )

    enum class ForwardResult {
        AUDIO_SENT,   // 音频转发成功
        TEXT_SENT,    // 文本兜底成功
        FAILED,       // 转发失败
        SKIPPED       // 跳过（无内容）
    }
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/SmartForwarder.kt
git commit -m "feat(relay): add SmartForwarder with audio-first text-fallback"
```

---

## Task 5: 重写 RelayOrchestrator —— 状态机核心

**Objective:** 用状态机替换现有 RelayEngine，消除所有竞态

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayOrchestrator.kt`

**Step 1: 创建 RelayOrchestrator**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.RelayState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Relay 中介者 —— 状态机 + 调度
 *
 * 所有状态转换在这里，不允许外部直接修改 phase。
 * 每个状态有明确的进入条件、执行逻辑、退出条件和超时。
 */
class RelayOrchestrator(
    private val botConnectionPool: BotConnectionPoolRepository,
    private val relayState: MutableStateFlow<RelayState>,
    private val audioRouter: AudioRouter,
    private val smartForwarder: SmartForwarder,
    private val speakerScorer: SpeakerScorer,
    private val deadloopDetector: DeadloopDetector,
    private val deduplicationUtil: DeduplicationUtil,
    private val audioForwardBuffer: ConcurrentHashMap<String, MutableList<ByteArray>>,
    private val botLastAudioTime: ConcurrentHashMap<String, Long>,
    private val getContacts: () -> List<BotContact>,
    private val isRecording: () -> Boolean,
    private val scope: CoroutineScope,
    private val onRelaySwitch: suspend (nextBotId: String) -> Unit,  // 回调：通知 ViewModel 做 UI 更新
    private val logger: PlatformLogger = PlatformLogger.EMPTY
) {
    companion object {
        private const val TAG = "RelayOrchestrator"
        /** Bot 响应超时：转发后等多久 */
        const val BOT_RESPONSE_TIMEOUT_MS = 30_000L
        /** Relay 切换超时 */
        const val RELAY_SWITCH_TIMEOUT_MS = 5_000L
        /** 静默超时：无音频多久自动结束 */
        const val SILENCE_TIMEOUT_MS = 30_000L
    }

    private val relayMutex = Mutex()
    private var timeoutJob: Job? = null

    /**
     * 处理状态转换事件
     *
     * 这是唯一的入口。所有状态转换通过这里。
     */
    suspend fun onEvent(event: RelayEvent) {
        val current = relayState.value
        when (event) {
            is RelayEvent.UserStartSpeaking -> handleUserStartSpeaking(current)
            is RelayEvent.UserStopSpeaking -> handleUserStopSpeaking(current)
            is RelayEvent.BotStartProcessing -> handleBotStartProcessing(current)
            is RelayEvent.BotStartSpeaking -> handleBotStartSpeaking(current)
            is RelayEvent.BotStopSpeaking -> handleBotStopSpeaking(current)
            is RelayEvent.NextBotReady -> handleNextBotReady(current)
            is RelayEvent.ConversationFinished -> handleFinished(current)
            is RelayEvent.UserInterrupt -> handleUserInterrupt(current)
        }
    }

    private suspend fun handleUserStartSpeaking(current: RelayState) {
        relayMutex.withLock {
            cancelTimeout()
            relayState.update {
                it.nextSession().copy(
                    phase = RelayPhase.USER_SPEAKING,
                    waitingSpeakerIndex = 0
                )
            }
            audioForwardBuffer.clear()
            botLastAudioTime.clear()
            deadloopDetector.reset()
            deduplicationUtil.clear()
        }
    }

    private suspend fun handleUserStopSpeaking(current: RelayState) {
        if (current.phase != RelayPhase.USER_SPEAKING) return
        // 用户说完 → 等待第一个 bot 处理
        relayState.update { it.copy(phase = RelayPhase.BOT_PROCESSING) }
        startTimeout(BOT_RESPONSE_TIMEOUT_MS) {
            logger.w(TAG, "Bot 响应超时，跳过")
            onEvent(RelayEvent.ConversationFinished)
        }
    }

    private fun handleBotStartProcessing(current: RelayState) {
        // Bot 开始处理，重置超时
        cancelTimeout()
        relayState.update { it.copy(phase = RelayPhase.BOT_PROCESSING) }
    }

    private fun handleBotStartSpeaking(current: RelayState) {
        cancelTimeout()
        relayState.update {
            it.copy(phase = RelayPhase.BOT_SPEAKING, currentReplyText = "")
        }
        // 启动静默超时
        startTimeout(SILENCE_TIMEOUT_MS) {
            logger.w(TAG, "静默超时，结束本轮")
            onEvent(RelayEvent.BotStopSpeaking)
        }
    }

    private suspend fun handleBotStopSpeaking(current: RelayState) {
        cancelTimeout()
        if (current.phase != RelayPhase.BOT_SPEAKING) return

        // 检查结束条件
        val reply = current.currentReplyText
        if (RelayValidator().isGoodbye(reply)) {
            logger.i(TAG, "检测到结束语，停止接龙")
            onEvent(RelayEvent.ConversationFinished)
            return
        }
        if (RelayValidator().isMaxRoundReached(current.forwardRound, current.botIds.size)) {
            logger.i(TAG, "已达最大轮次，停止接龙")
            onEvent(RelayEvent.ConversationFinished)
            return
        }

        // 切换到下一个 bot
        relayState.update { it.copy(phase = RelayPhase.RELAY_SWITCH) }
        triggerNextSpeaker(current)
    }

    private suspend fun triggerNextSpeaker(current: RelayState) {
        relayMutex.withLock {
            if (isRecording()) {
                relayState.update { it.copy(phase = RelayPhase.IDLE) }
                return
            }

            val prevIndex = current.currentSpeakerIndex
            val prevBotId = current.botIds.getOrNull(prevIndex)

            // 选人
            val (candidates, newRs) = speakerScorer.resolveCandidates(
                prevIndex, current.currentReplyText, current.botIds, current
            )
            relayState.value = newRs

            // 过滤已连接的候选
            val connected = candidates.filter { (_, id) ->
                val svc = botConnectionPool.get(id)
                svc != null && svc.connectionState.value is com.xiaozhi.aiservice.domain.model.BotConnectionState.Connected
            }

            if (connected.isEmpty()) {
                logger.w(TAG, "无可用 bot，结束本轮")
                relayState.update { it.copy(phase = RelayPhase.FINISHED) }
                return
            }

            // 转发并等待
            for ((targetIndex, speakerId) in connected) {
                val service = botConnectionPool.get(speakerId) ?: continue
                audioRouter.onSpeakerChanged()
                relayState.update { it.moveToSpeaker(targetIndex, speakerId) }

                val result = smartForwarder.forward(
                    prevBotId, current.currentReplyText,
                    SmartForwarder.BotSession(speakerId, service)
                )

                if (result == SmartForwarder.ForwardResult.SKIPPED ||
                    result == SmartForwarder.ForwardResult.FAILED) {
                    continue
                }

                // 等待 bot 开始说话（音频到达）
                val initTs = botLastAudioTime[speakerId] ?: 0L
                val audioReceived = withTimeoutOrNull(BOT_RESPONSE_TIMEOUT_MS) {
                    while (botLastAudioTime[speakerId] == initTs) {
                        delay(50)
                        // 检查 bot 是否已进入 speaking 状态
                        if (service.aiState.value == "speaking") break
                    }
                    true
                }

                if (audioReceived != null) {
                    relayState.update {
                        it.copy(
                            currentSpeakerIndex = targetIndex,
                            forwardRound = it.forwardRound + 1
                        ).markSpoken(targetIndex)
                            .recordTransition(prevBotId ?: "", speakerId)
                    }
                    logger.i(TAG, "Relay 成功 → $speakerId round=${relayState.value.forwardRound}")
                    onRelaySwitch(speakerId)
                    return
                }
            }

            // 所有候选失败
            logger.w(TAG, "所有候选失败，结束本轮")
            relayState.update { it.copy(phase = RelayPhase.FINISHED) }
        }
    }

    private fun handleNextBotReady(current: RelayState) {
        // 已在 triggerNextSpeaker 中处理
    }

    private fun handleFinished(current: RelayState) {
        cancelTimeout()
        relayState.update {
            it.copy(
                phase = RelayPhase.FINISHED,
                expectedSpeakerId = "",
                currentReplyText = ""
            )
        }
    }

    private suspend fun handleUserInterrupt(current: RelayState) {
        cancelTimeout()
        botConnectionPool.abortAll("user_interrupt")
        relayState.update {
            it.nextSession().copy(phase = RelayPhase.IDLE)
        }
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
    }

    /**
     * 更新当前 speaker 的回复文本
     */
    fun appendReplyText(text: String) {
        relayState.update {
            it.copy(currentReplyText = if (it.currentReplyText.isBlank()) text else "${it.currentReplyText} $text")
        }
    }

    private fun startTimeout(timeoutMs: Long, onTimeout: suspend () -> Unit) {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (isActive) onTimeout()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        value = block(value)
    }
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayOrchestrator.kt
git commit -m "feat(relay): add RelayOrchestrator with state machine"
```

---

## Task 6: 重写 GroupChatViewModel —— 薄层 UI

**Objective:** 用 RelayOrchestrator 替换 ViewModel 中的所有 relay 逻辑，瘦到 ~300 行

**Files:**
- Rewrite: `app/src/main/java/com/xiaozhi/aiservice/presentation/viewmodel/GroupChatViewModel.kt`

**核心改动：**

1. **删除**所有 relay 状态管理代码（~400 行）
2. **替换为**调用 `RelayOrchestrator.onEvent()`
3. **保留**UI 状态（_isRecording, _isConnecting, groups, contacts）
4. **保留**bot 事件监听（observeBotEvents），但事件处理简化为调用 orchestrator

**关键代码结构：**

```kotlin
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    // ... 依赖注入
) : ViewModel() {

    // ── 核心：RelayOrchestrator ──
    private lateinit var orchestrator: RelayOrchestrator
    private lateinit var audioRouter: AudioRouter

    // ── UI 状态（保留） ──
    private val _isRecording = MutableStateFlow(false)
    private val _isConnecting = MutableStateFlow(false)
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())

    fun connectGroup(groupId: String) {
        // 1. 初始化 orchestrator
        audioRouter = AudioRouter(playbackManager, context)
        orchestrator = RelayOrchestrator(
            botConnectionPool, _relayState, audioRouter,
            smartForwarder, speakerScorer, deadloopDetector,
            deduplicationUtil, audioForwardBuffer, botLastAudioTime,
            { _contacts.value }, { _isRecording.value }, viewModelScope,
            onRelaySwitch = { /* UI 更新 */ },
            logger = PlatformLogger.DEFAULT
        )

        // 2. 连接 bots + 注册事件监听（简化版）
        // 3. 启动看门狗
    }

    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: BotConnection) {
        // 简化：所有事件统一通过 orchestrator.onEvent() 处理
        // 音频通过 audioRouter.routeAudio() 处理
        // 不再有复杂的 if/else 嵌套
    }

    fun startVoiceInput(groupId: String) {
        viewModelScope.launch {
            orchestrator.onEvent(RelayEvent.UserStartSpeaking)
            // ... 启动录音
        }
    }

    fun stopVoiceInput(userExplicitStop: Boolean = true) {
        viewModelScope.launch {
            orchestrator.onEvent(RelayEvent.UserStopSpeaking)
            // ... 停止录音
        }
    }
}
```

**Step 1: 重写 ViewModel（详细代码在实现时编写）**

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: 安装测试**

```bash
/home/ethan/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
# 手机操作：打开群聊 → 语音输入 → 验证接龙正常
```

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor(relay): rewrite GroupChatViewModel with RelayOrchestrator"
```

---

## Task 7: 清理旧代码

**Objective:** 删除不再使用的旧代码，更新引用

**Files:**
- Delete: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayEngine.kt`
- Delete: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/AudioForwardHandler.kt`（已被 SmartForwarder 替代）
- Modify: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayConfig.kt`（加新超时配置）

**Step 1: 删除旧文件**

```bash
rm app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayEngine.kt
rm app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/AudioForwardHandler.kt
```

**Step 2: 更新 RelayConfig**

```kotlin
object RelayConfig {
    const val BOT_RESPONSE_TIMEOUT_MS = 30_000L
    const val RELAY_SWITCH_TIMEOUT_MS = 5_000L
    const val SILENCE_TIMEOUT_MS = 30_000L
    const val SPEAKER_GRACE_MS = 800L
    const val MAX_FORWARD_BUFFER_SIZE = 200
    const val MAX_FORWARD_ROUND = Int.MAX_VALUE
    val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")
}
```

**Step 3: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor(relay): remove old RelayEngine and AudioForwardHandler"
```

---

## Task 8: 端到端测试

**Objective:** 验证所有场景正常工作

**Files:** 无新增，纯测试

**测试场景：**

1. **基础接龙**：2 bot，用户说话 → bot A 回复 → bot B 回复 → 结束
2. **多 bot 接龙**：5 bot，验证轮次和 speaker 选择
3. **结束语**：bot 回复包含"再见" → 自动停止
4. **用户中断**：接龙中用户再次说话 → 所有 bot abort
5. **音频过滤**：非预期 speaker 的音频和文字都不显示
6. **文本兜底**：音频转发失败时自动降级到文本
7. **超时恢复**：bot 15s 无响应 → 跳过换下一个

**验证命令：**

```bash
adb logcat -s GroupChatVM:* RelayOrchestrator:* AudioRouter:* SmartForwarder:*
```

**预期日志：**
```
RelayOrchestrator: Relay 成功 → botB round=1
AudioRouter: 音频被过滤: botC != expected=botB
SmartForwarder: 音频转发成功 → botB
```

**Step 1: 安装并逐场景测试**

**Step 2: 记录结果，修复发现的 bug**

**Step 3: Commit**

```bash
git add -A
git commit -m "test(relay): end-to-end testing and bug fixes"
```

---

## 实现顺序

```
Task 1 (RelayPhase)     ──┐
Task 2 (RelayState)     ──┤ 基础设施
Task 3 (AudioRouter)    ──┤
Task 4 (SmartForwarder) ──┘
Task 5 (RelayOrchestrator) ── 核心逻辑
Task 6 (ViewModel 重写)     ── 集成
Task 7 (清理旧代码)          ── 收尾
Task 8 (端到端测试)          ── 验证
```

预估工时：Task 1-4 各 5 分钟，Task 5-6 各 15 分钟，Task 7-8 各 10 分钟。总计 ~75 分钟。
