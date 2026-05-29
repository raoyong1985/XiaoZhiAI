package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.PlatformLogger
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

    /**
     * 获取当前预期 speaker ID
     */
    fun getExpectedSpeakerId(): String = relayState.value.expectedSpeakerId

    // ── 状态处理 ──

    private suspend fun handleUserStartSpeaking(current: RelayState) {
        relayMutex.lock()
        try {
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
        } finally {
            relayMutex.unlock()
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
        relayMutex.lock()
        try {
            if (isRecording()) {
                relayState.update { it.copy(phase = RelayPhase.IDLE) }
                return
            }

            val prevIndex = current.currentSpeakerIndex
            val prevBotId = current.botIds.getOrNull(prevIndex)

            // 死循环检测
            val hint = current.currentReplyText.take(8)
            if (hint.isNotBlank() && deadloopDetector.check(hint, prevBotId)) {
                logger.i(TAG, "死循环终止")
                onEvent(RelayEvent.ConversationFinished)
                return
            }

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
        } finally {
            relayMutex.unlock()
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
