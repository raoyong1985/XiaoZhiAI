# 群聊 Relay 重构计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将群聊 ViewModel 从 ~910 行巨型类拆分为状态机驱动的分层架构，解决 relay 竞态、音频丢失、文字乱码问题。

**Architecture:** 状态机(RelayPhase) + 中介者(RelayOrchestrator) + 音频路由(AudioRouter) + 转发策略(SmartForwarder) + 2人群乒乓模式。ViewModel 只管 UI 状态。

**Tech Stack:** Kotlin, Coroutines, StateFlow, SharedFlow, Android AudioTrack

---

## 现有问题

1. **relay 竞态**：aiState IDLE 和 ttsStopped 竞态导致 hasSpoken 被提前清除
2. **音频丢失**：sentence_end 3s 超时触发提前 IDLE，speaker 过滤丢弃剩余音频
3. **文字乱码**：非预期 speaker 的 TTS 文字未过滤 + 转发音频导致 STT 乱码
4. **代码耦合**：ViewModel 910 行，混合 UI/relay/音频/连接管理
5. **2人群缺失**：没有乒乓对话模式，2 人群和多人群用同一套逻辑

## 设计原则

1. **状态机驱动**：所有状态转换通过 RelayOrchestrator.onEvent()，不允许外部直接修改 phase
2. **音频优先 + 文本兜底**：首选转发 Opus 音频（官方服务器），失败时降级到文本
3. **2人群乒乓**：2 bot 时简单交替，不需要 SpeakerScorer 评分
4. **超时全覆盖**：每个环节都有超时保护，不会卡死
5. **非预期 speaker 静默**：音频和文字都过滤，用户只看到预期 bot 的内容

## 目录结构（重构后）

```
domain/usecase/relay/
├── RelayPhase.kt              # 新：状态机枚举 + 事件
├── RelayOrchestrator.kt       # 重写：状态机 + 调度（替换 RelayEngine）
├── AudioRouter.kt             # 新：音频路由（从 ViewModel 抽出）
├── SmartForwarder.kt          # 重写：音频优先 + 文本兜底（替换 AudioForwardHandler）
├── SpeakerScorer.kt           # 保留（加 2人群判断）
├── DeadloopDetector.kt        # 保留
├── DeduplicationUtil.kt       # 保留
├── RelayValidator.kt          # 保留
├── RelayConfig.kt             # 保留（加新配置）
└── BotSessionPool.kt          # 新：连接池包装

domain/model/
└── RelayState.kt              # 重写：增加 phase、expectedSpeakerId

presentation/viewmodel/
├── GroupChatViewModel.kt      # 重写：瘦到 ~300 行
└── BotEvent.kt                # 保留
```

---

## 场景与策略

### 2人群 vs 多人群

| | 2人群 | 多人群(3+) |
|---|------|-----------|
| 模式 | **乒乓对话**：A→B→A→B | 轮流接龙：A→B→C→... |
| 选人 | 不需要选，只有对方 | SpeakerScorer 评分选人 |
| @提及 | 不需要（只有两个人） | 需要，+100 分 |
| 自然结束 | 一方说"再见"就停 | 所有人都说过一轮 |
| 转发 | 回复质量更重要 | 每个人都要有参与感 |

### 转发策略

```
首选: 转发 Opus 音频（官方服务器）
  ↓ 失败
兜底: 转发文本摘要（带说话人标识）
  ↓ 失败
跳过: 该 bot 不参与本轮
```

### 超时策略

| 超时 | 值 | 触发动作 |
|------|-----|---------|
| 用户说话超时 | 10s | VAD 没触发就手动停 |
| Bot 响应超时 | 15s | 转发后等 bot 回复，超时跳过 |
| Bot 播放超时 | 30s | TTS 播放最长 30s |
| Relay 切换超时 | 5s | 切换到下一个 bot |
| 总对话超时 | 120s | 整轮对话最长 2 分钟 |
| 静默超时 | 30s | 无音频自动结束 |

### 特殊场景处理

| 场景 | 处理方式 |
|------|---------|
| Bot 断连 | 跳过，换下一个 |
| 所有 bot 都断连 | 结束本轮 |
| 用户录音中来了 relay | abort 所有 bot |
| Bot 回复为空 | 跳过，换下一个 |
| 同一 bot 连续说 3 轮 | 惩罚降权 |
| 轮次超过 bot 数×2 | 自动结束 |
| 音频转发失败 | 降级到文本转发 |
| 多 bot 同时推 TTS | 客户端只播放预期 bot 的音频 |

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
 *   FINISHED → IDLE（下一轮）
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
    /** 切换到下一个 bot（选人 + 转发） */
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
    /** Bot 开始播放 TTS（收到 tts start） */
    data object BotStartSpeaking : RelayEvent()
    /** Bot TTS 结束（收到 tts stop） */
    data object BotStopSpeaking : RelayEvent()
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

**Objective:** 在 RelayState 中增加 phase 和 expectedSpeakerId 字段

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
    val currentReplyText: String = "",
    /** 是否为 2人群模式 */
    val isTwoPersonMode: Boolean = false
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
# 预期: 可能有引用旧字段的编译错误，后续 Task 修复
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
 * 1. 所有 bot 的音频写入同一个 shared decoder（OPPO/MTK 多 AudioTrack 会无声）
 * 2. speaker 过滤：只播放预期 bot 的音频
 * 3. decoder 生命周期管理
 */
class AudioRouter(
    private val playbackManager: AudioPlaybackManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRouter"
        /** speaker 切换后的 grace 窗口（ms），允许非预期 bot 的音频通过 */
        const val SPEAKER_GRACE_MS = 800L
    }

    /** 按采样率缓存的 sourceId */
    private val decoderCache = ConcurrentHashMap<Int, String>()

    /** 上次 speaker 切换的时间戳（ms） */
    @Volatile
    var lastSpeakerChangeMs: Long = 0L

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
            if (System.currentTimeMillis() - lastSpeakerChangeMs > SPEAKER_GRACE_MS) {
                Log.w(TAG, "音频被过滤: $botId != expected=$expectedSpeakerId")
                return false
            }
        }

        val sourceId = getSharedSourceId(groupId, serverRate)
        val volume = AudioSettings.getOutputVolume(context)

        if (!playbackManager.contains(sourceId)) {
            decoderCache[serverRate] = sourceId
            playbackManager.getOrCreate(sourceId, serverRate, serverCh, volume)
            Log.i(TAG, "创建 shared decoder: $sourceId (${serverRate}Hz)")
        }

        playbackManager.decodeAndPlay(sourceId, serverRate, serverCh, audioData, volume)
        return true
    }

    fun onSpeakerChanged() {
        lastSpeakerChangeMs = System.currentTimeMillis()
    }

    fun flush(groupId: String, serverRate: Int) {
        val sourceId = getSharedSourceId(groupId, serverRate)
        if (playbackManager.contains(sourceId)) {
            playbackManager.flushSource(sourceId)
        }
    }

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

**Objective:** 重写转发逻辑，支持音频优先 + 文本兜底 + 静音过滤

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/SmartForwarder.kt`

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
 * 2. 兜底：转发文本摘要（带说话人标识）
 * 3. 跳过：转发失败且无文本
 *
 * 音频预处理：
 * - 过滤过小的音频包（静音段）
 * - 跳过连续的空包
 */
class SmartForwarder(
    private val getContacts: () -> List<BotContact>,
    private val logger: PlatformLogger = PlatformLogger.EMPTY
) {
    private val tag = "SmartForwarder"

    /** 最小音频包大小（字节），小于此值视为静音 */
    private val MIN_AUDIO_PACKET_SIZE = 50

    /**
     * 转发上一位 bot 的回复到下一位 bot
     */
    suspend fun forward(
        prevBotId: String?,
        previousReply: String,
        audioBuffer: List<ByteArray>?,
        nextBot: BotSession
    ): ForwardResult {
        if (prevBotId == null) return ForwardResult.SKIPPED

        // 1. 尝试音频转发
        if (!audioBuffer.isNullOrEmpty()) {
            val filtered = filterSilence(audioBuffer)
            if (filtered.isNotEmpty()) {
                val audioOk = sendAudio(filtered, nextBot.connection)
                if (audioOk) {
                    logger.i(tag, "音频转发成功(${filtered.size}包) → ${nextBot.botId}")
                    return ForwardResult.AUDIO_SENT
                }
            }
        }

        // 2. 音频转发失败/为空 → 文本兜底
        if (previousReply.isNotBlank()) {
            val textMsg = buildForwardMessage(previousReply, prevBotId)
            if (textMsg.isNotBlank() && nextBot.connection.connectionState.value is BotConnectionState.Connected) {
                try {
                    nextBot.connection.sendTextContent(textMsg)
                    logger.i(tag, "文本兜底转发 → ${nextBot.botId}: '${previousReply.take(30)}'")
                    return ForwardResult.TEXT_SENT
                } catch (e: Exception) {
                    logger.w(tag, "文本转发失败: ${e.message}")
                }
            }
        }

        return ForwardResult.FAILED
    }

    /**
     * 过滤静音段：移除过小的音频包
     */
    private fun filterSilence(audioBuffer: List<ByteArray>): List<ByteArray> {
        return audioBuffer.filter { it.size >= MIN_AUDIO_PACKET_SIZE }
    }

    /**
     * 批量发送音频包
     */
    private suspend fun sendAudio(packets: List<ByteArray>, connection: BotConnection): Boolean {
        if (connection.connectionState.value !is BotConnectionState.Connected) return false
        try {
            connection.sendListenStart("auto")
            val batchSize = 5
            packets.chunked(batchSize).forEachIndexed { batchIdx, batch ->
                batch.forEach { packet -> connection.sendAudio(packet) }
                when {
                    batchIdx < 2 -> delay(40)
                    batchIdx % 4 == 0 -> delay(10)
                }
            }
            delay(200)
            connection.sendListenStopOnly()
            return connection.connectionState.value is BotConnectionState.Connected
        } catch (e: Exception) {
            logger.w(tag, "音频发送失败: ${e.message}")
            return false
        }
    }

    /**
     * 构建转发文本，带上说话人标识
     *
     * 格式：{"speaker":"说话人名称","content":"完整回复内容"}
     */
    private fun buildForwardMessage(previousReply: String, prevBotId: String?): String {
        if (previousReply.isBlank()) return ""
        val name = prevBotId?.let { id ->
            getContacts().find { id == it.id }?.name
        } ?: return previousReply
        return """{"speaker":"${name}","content":"${previousReply.replace("\"", "\\\"")}"}"""
    }

    data class BotSession(
        val botId: String,
        val connection: BotConnection
    )

    enum class ForwardResult {
        AUDIO_SENT,
        TEXT_SENT,
        FAILED,
        SKIPPED
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

## Task 5: 更新 SpeakerScorer —— 支持 2人群乒乓模式

**Objective:** SpeakerScorer 增加 2人群判断，2人群直接交替不需要评分

**Files:**
- Modify: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/SpeakerScorer.kt`

**Step 1: 在 resolveCandidates 开头加 2人群快速路径**

```kotlin
fun resolveCandidates(
    prevIndex: Int, previousReply: String, botIds: List<String>, rs: RelayState
): Pair<List<Pair<Int, String>>, RelayState> {
    if (botIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

    val aliveBotIds = botIds.filter { !isDead(it) }
    if (aliveBotIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

    // ── 2人群乒乓模式：直接交替，不需要评分 ──
    if (aliveBotIds.size == 2) {
        val prevBotId = aliveBotIds.getOrNull(0)?.let { if (botIds.indexOf(it) == prevIndex) it else null }
            ?: aliveBotIds.getOrNull(1)?.let { if (botIds.indexOf(it) == prevIndex) it else null }
        val nextId = if (prevBotId == aliveBotIds[0]) aliveBotIds[1] else aliveBotIds[0]
        val nextIndex = botIds.indexOf(nextId)
        logger.i(tag, "2人群乒乓: [$prevBotId → $nextId]")
        return listOf(nextIndex to nextId) to rs
    }

    // ── 多人群：走评分逻辑 ──
    // ... 原有评分代码不变 ...
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/SpeakerScorer.kt
git commit -m "feat(relay): add 2-person ping-pong mode to SpeakerScorer"
```

---

## Task 6: 更新 RelayConfig —— 完整超时配置

**Objective:** 统一所有超时常量

**Files:**
- Modify: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayConfig.kt`

**Step 1: 重写 RelayConfig**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

object RelayConfig {
    /** Bot 响应超时：转发后等 bot 回复 */
    const val BOT_RESPONSE_TIMEOUT_MS = 15_000L
    /** Bot 播放超时：TTS 播放最长 */
    const val BOT_PLAYBACK_TIMEOUT_MS = 30_000L
    /** Relay 切换超时 */
    const val RELAY_SWITCH_TIMEOUT_MS = 5_000L
    /** 总对话超时 */
    const val TOTAL_CONVERSATION_TIMEOUT_MS = 120_000L
    /** 静默超时：无音频自动结束 */
    const val SILENCE_TIMEOUT_MS = 30_000L
    /** Speaker 切换 grace 窗口 */
    const val SPEAKER_GRACE_MS = 800L
    /** 音频转发缓存单 bot 上限 */
    const val MAX_FORWARD_BUFFER_SIZE = 200
    /** 接龙最大轮次（Int.MAX_VALUE = 无限制） */
    const val MAX_FORWARD_ROUND = Int.MAX_VALUE
    /** 结束语关键词 */
    val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")
}
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayConfig.kt
git commit -m "feat(relay): update RelayConfig with comprehensive timeouts"
```

---

## Task 7: 创建 RelayOrchestrator —— 状态机核心

**Objective:** 用状态机替换现有 RelayEngine，消除所有竞态

**Files:**
- Create: `app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayOrchestrator.kt`

**Step 1: 创建 RelayOrchestrator**

```kotlin
package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.RelayState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.BotConnectionState
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
 *
 * 支持两种模式：
 * - 2人群：乒乓对话（A→B→A→B）
 * - 多人群：轮流接龙（A→B→C→...→A）
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
    private val onRelaySwitch: suspend (nextBotId: String) -> Unit,
    private val onConversationFinished: suspend () -> Unit,
    private val logger: PlatformLogger = PlatformLogger.EMPTY
) {
    companion object {
        private const val TAG = "RelayOrchestrator"
    }

    private val relayMutex = Mutex()
    private var timeoutJob: Job? = null
    private val validator = RelayValidator()

    /**
     * 处理状态转换事件（唯一入口）
     */
    suspend fun onEvent(event: RelayEvent) {
        val current = relayState.value
        when (event) {
            is RelayEvent.UserStartSpeaking -> handleUserStartSpeaking(current)
            is RelayEvent.UserStopSpeaking -> handleUserStopSpeaking(current)
            is RelayEvent.BotStartSpeaking -> handleBotStartSpeaking(current)
            is RelayEvent.BotStopSpeaking -> handleBotStopSpeaking(current)
            is RelayEvent.ConversationFinished -> handleFinished(current)
            is RelayEvent.UserInterrupt -> handleUserInterrupt(current)
        }
    }

    /**
     * 更新当前 speaker 的回复文本（外部调用）
     */
    fun appendReplyText(text: String) {
        relayState.update {
            it.copy(currentReplyText = if (it.currentReplyText.isBlank()) text else "${it.currentReplyText} $text")
        }
    }

    // ── 状态处理 ──

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
        relayState.update { it.copy(phase = RelayPhase.BOT_PROCESSING) }
        startTimeout(RelayConfig.BOT_RESPONSE_TIMEOUT_MS) {
            logger.w(TAG, "Bot 响应超时，结束本轮")
            onEvent(RelayEvent.ConversationFinished)
        }
    }

    private fun handleBotStartSpeaking(current: RelayState) {
        cancelTimeout()
        relayState.update { it.copy(phase = RelayPhase.BOT_SPEAKING, currentReplyText = "") }
        startTimeout(RelayConfig.BOT_PLAYBACK_TIMEOUT_MS) {
            logger.w(TAG, "Bot 播放超时")
            onEvent(RelayEvent.BotStopSpeaking)
        }
    }

    private suspend fun handleBotStopSpeaking(current: RelayState) {
        cancelTimeout()
        if (current.phase != RelayPhase.BOT_SPEAKING) return

        val reply = current.currentReplyText

        // 结束语检测
        if (reply.isNotBlank() && validator.isGoodbye(reply)) {
            logger.i(TAG, "检测到结束语: '${reply.take(20)}'")
            onEvent(RelayEvent.ConversationFinished)
            return
        }

        // 最大轮次检测
        if (validator.isMaxRoundReached(current.forwardRound, current.botIds.size)) {
            logger.i(TAG, "已达最大轮次: ${current.forwardRound}")
            onEvent(RelayEvent.ConversationFinished)
            return
        }

        // 2人群连续说 3 轮检测
        if (current.isTwoPersonMode && current.spokenRoundCounter >= current.botIds.size * 3) {
            logger.i(TAG, "2人群对话过长，结束")
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
                svc != null && svc.connectionState.value is BotConnectionState.Connected
            }

            if (connected.isEmpty()) {
                logger.w(TAG, "无可用 bot，结束本轮")
                onEvent(RelayEvent.ConversationFinished)
                return
            }

            // 转发并等待
            for ((targetIndex, speakerId) in connected) {
                val service = botConnectionPool.get(speakerId) ?: continue
                audioRouter.onSpeakerChanged()
                relayState.update { it.moveToSpeaker(targetIndex, speakerId) }

                val audioBuf = audioForwardBuffer[prevBotId]?.toList()
                val result = smartForwarder.forward(
                    prevBotId, current.currentReplyText, audioBuf,
                    SmartForwarder.BotSession(speakerId, service)
                )

                if (result == SmartForwarder.ForwardResult.SKIPPED ||
                    result == SmartForwarder.ForwardResult.FAILED) {
                    continue
                }

                // 等待 bot 开始说话（音频到达 or aiState=speaking）
                val initTs = botLastAudioTime[speakerId] ?: 0L
                val ready = withTimeoutOrNull(RelayConfig.BOT_RESPONSE_TIMEOUT_MS) {
                    while (botLastAudioTime[speakerId] == initTs) {
                        delay(50)
                        if (service.aiState.value == "speaking") break
                    }
                    true
                }

                if (ready != null) {
                    relayState.update {
                        it.copy(
                            currentSpeakerIndex = targetIndex,
                            forwardRound = it.forwardRound + 1
                        ).markSpoken(targetIndex)
                            .recordTransition(prevBotId ?: "", speakerId)
                    }
                    logger.i(TAG, "Relay → $speakerId round=${relayState.value.forwardRound}")
                    onRelaySwitch(speakerId)
                    return
                }

                logger.w(TAG, "$speakerId 响应超时，尝试下一候选")
            }

            // 所有候选失败
            logger.w(TAG, "所有候选失败，结束本轮")
            onEvent(RelayEvent.ConversationFinished)
        }
    }

    private suspend fun handleFinished(current: RelayState) {
        cancelTimeout()
        relayState.update {
            it.copy(
                phase = RelayPhase.FINISHED,
                expectedSpeakerId = "",
                currentReplyText = ""
            )
        }
        onConversationFinished()
    }

    private suspend fun handleUserInterrupt(current: RelayState) {
        cancelTimeout()
        botConnectionPool.abortAll("user_interrupt")
        relayState.update { it.nextSession().copy(phase = RelayPhase.IDLE) }
        audioForwardBuffer.clear()
        botLastAudioTime.clear()
    }

    // ── 工具方法 ──

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

## Task 8: 重写 GroupChatViewModel —— 薄层 UI

**Objective:** 用 RelayOrchestrator 替换 ViewModel 中的所有 relay 逻辑，瘦到 ~300 行

**Files:**
- Rewrite: `app/src/main/java/com/xiaozhi/aiservice/presentation/viewmodel/GroupChatViewModel.kt`

**核心改动：**

1. **删除**所有 relay 状态管理代码（~400 行）
2. **替换为**调用 `RelayOrchestrator.onEvent()`
3. **保留**UI 状态（_isRecording, _isConnecting, groups, contacts）
4. **保留**bot 事件监听（observeBotEvents），但事件处理简化

**关键代码结构：**

```kotlin
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupChatRepository: GroupChatRepository,
    private val contactsRepository: ContactsRepository,
    private val botConnectionPool: BotConnectionPoolRepository,
    private val playbackManager: AudioPlaybackManager,
    private val audioService: AudioService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── 核心 ──
    private lateinit var orchestrator: RelayOrchestrator
    private lateinit var audioRouter: AudioRouter
    private lateinit var smartForwarder: SmartForwarder

    // ── UI 状态 ──
    private val _isRecording = MutableStateFlow(false)
    private val _isConnecting = MutableStateFlow(false)
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    private val _relayState = MutableStateFlow(RelayState())

    // ── 连接池 ──
    private val audioForwardBuffer = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val botLastAudioTime = ConcurrentHashMap<String, Long>()
    private val botObserverJobs = ConcurrentHashMap<String, Job>()

    fun connectGroup(groupId: String) {
        // 1. 初始化组件
        audioRouter = AudioRouter(playbackManager, context)
        smartForwarder = SmartForwarder({ _contacts.value })
        orchestrator = RelayOrchestrator(
            botConnectionPool, _relayState, audioRouter, smartForwarder,
            speakerScorer, deadloopDetector, deduplicationUtil,
            audioForwardBuffer, botLastAudioTime,
            { _contacts.value }, { _isRecording.value }, viewModelScope,
            onRelaySwitch = { /* UI 更新 */ },
            onConversationFinished = { /* 回到 IDLE */ }
        )

        // 2. 连接 bots + 注册事件监听
        // 3. 启动看门狗
    }

    private fun observeBotEvents(botId: String, groupId: String, botName: String, service: BotConnection) {
        // 简化：所有事件统一通过 orchestrator.onEvent() 处理
        // 音频通过 audioRouter.routeAudio() 处理
        // 文字通过 orchestrator.appendReplyText() 累积
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

**Step 1: 重写 ViewModel**

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: 安装测试**

```bash
/home/ethan/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor(relay): rewrite GroupChatViewModel with RelayOrchestrator"
```

---

## Task 9: 清理旧代码

**Objective:** 删除不再使用的旧代码

**Files:**
- Delete: `app/.../relay/RelayEngine.kt`
- Delete: `app/.../relay/AudioForwardHandler.kt`

**Step 1: 删除旧文件**

```bash
rm app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/RelayEngine.kt
rm app/src/main/java/com/xiaozhi/aiservice/domain/usecase/relay/AudioForwardHandler.kt
```

**Step 2: 编译验证**

```bash
cd /home/ethan/XiaoZhiAI && ./gradlew assembleDebug 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add -A
git commit -m "refactor(relay): remove old RelayEngine and AudioForwardHandler"
```

---

## Task 10: 端到端测试

**Objective:** 验证所有场景正常工作

**测试场景：**

1. **2人群乒乓**：悠悠↔微微，验证交替对话
2. **多人群接龙**：5 bot，验证轮次和 speaker 选择
3. **@提及优先**：悠悠回复"@微微 你说呢" → 微微优先接话
4. **结束语**：bot 回复包含"再见" → 自动停止
5. **用户中断**：接龙中用户再次说话 → 所有 bot abort
6. **音频过滤**：非预期 speaker 的音频和文字都不显示
7. **文本兜底**：音频转发失败时自动降级到文本
8. **超时恢复**：bot 15s 无响应 → 跳过换下一个
9. **Bot 断连**：模拟 bot 断连 → 跳过换下一个

**验证命令：**

```bash
adb logcat -s GroupChatVM:* RelayOrchestrator:* AudioRouter:* SmartForwarder:*
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
Task 1  (RelayPhase)         ──┐
Task 2  (RelayState)         ──┤ 基础设施
Task 3  (AudioRouter)        ──┤
Task 4  (SmartForwarder)     ──┤
Task 5  (SpeakerScorer 更新)  ──┤
Task 6  (RelayConfig 更新)    ──┘
Task 7  (RelayOrchestrator)     ── 核心逻辑
Task 8  (ViewModel 重写)         ── 集成
Task 9  (清理旧代码)              ── 收尾
Task 10 (端到端测试)              ── 验证
```

预估工时：Task 1-6 各 5 分钟，Task 7-8 各 15 分钟，Task 9-10 各 10 分钟。总计 ~80 分钟。
