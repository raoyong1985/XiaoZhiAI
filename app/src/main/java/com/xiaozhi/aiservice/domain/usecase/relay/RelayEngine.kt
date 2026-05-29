package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.PlatformLogger
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.domain.model.AiStateStr
import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.RelayState
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 接龙引擎 —— 多 bot 音频接龙的核心流转逻辑
 *
 * 职责：
 * 1. triggerNextSpeaker：上一位 bot 说完后，选下一个 bot 转发音频/文字
 * 2. drainPreviousBotAudio：切换 speaker 前排空当前音频
 *
 * 线程安全：relayLock 防止 watchdog 与正常路径并发触发
 */
class RelayEngine(
    private val botConnectionPool: BotConnectionPoolRepository,
    private val audioForwardBuffer: ConcurrentHashMap<String, MutableList<ByteArray>>,
    private val relayState: MutableStateFlow<RelayState>,
    private val getContacts: () -> List<BotContact>,
    private val isRecording: () -> Boolean,
    private val botLastAudioTime: ConcurrentHashMap<String, Long>,
    private val playbackManager: AudioPlaybackManager,
    private val speakerMutex: Mutex,
    private val audioForwardHandler: AudioForwardHandler,
    private val speakerScorer: SpeakerScorer,
    private val deadloopDetector: DeadloopDetector,
    private val relayValidator: RelayValidator = RelayValidator(),
    private val logger: PlatformLogger = PlatformLogger.EMPTY,
    private val tag: String = "RelayEngine",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val relayMutex = Mutex()
    private var isRelaying = false

    // 委派方法: checkAndClearOnDeadloop (保持与原行为一致)
    private fun checkAndClearOnDeadloop(hint: String, speakerId: String? = null): Boolean {
        val isDead = deadloopDetector.check(hint, speakerId)
        if (isDead) relayState.update { it.clearWaiting() }
        return isDead
    }

    // ── 接龙引擎 ──

    /**
     * 触发下一个 speaker
     *
     * 当前 bot 说完(idle)后调用此方法，决定下一个由谁说话并转发音频/文字。
     */
    suspend fun triggerNextSpeaker(previousReply: String = "") {
        relayMutex.withLock {
            if (isRelaying) {
                logger.w(tag, "triggerNextSpeaker 已在执行中，跳过并发调用")
                return
            }
            isRelaying = true
        }
        try {
            innerTriggerNextSpeaker(previousReply)
        } finally {
            relayMutex.withLock { isRelaying = false }
        }
    }

    private suspend fun innerTriggerNextSpeaker(previousReply: String = "") {
        val rs = relayState.value
        if (relayValidator.isSingleBot(rs.botIds)) {
            relayState.update { it.clearWaiting() }
            return
        }
        if (isRecording()) {
            logger.w(tag, "用户正在录音，跳过转发")
            relayState.update { it.clearWaiting() }
            return
        }

        if (relayValidator.isGoodbye(previousReply)) {
            logger.i(tag, "检测到结束语，停止接龙: '${previousReply.take(20)}'")
            relayState.update { it.clearWaiting() }
            return
        }

        val prevIndex = rs.currentSpeakerIndex
        val prevBotId = rs.botIds.getOrNull(prevIndex)

        if (relayValidator.isMaxRoundReached(rs.forwardRound, rs.botIds.size)) {
            logger.i(tag, "接龙已达最大轮次，停止转发")
            relayState.update { it.clearWaiting() }
            return
        }

        // 原子状态更新：选人
        var candidates: List<Pair<Int, String>> = emptyList()
        relayState.update { current ->
            val (result, newState) = speakerScorer.resolveCandidates(prevIndex, previousReply, current.botIds, current)
            candidates = result
            newState
        }

        // 过滤已连接的候选
        val connected = candidates.filter { (_, id) ->
            val svc = botConnectionPool.get(id)
            svc != null && (svc.connectionState.value is BotConnectionState.Connected
                || run {
                    val contact = getContacts().find { it.id == id }
                    contact != null && botConnectionPool.ensureConnected(id, contact)
                })
        }

        // 终止前一个 bot 的 TTS
        if (prevBotId != null && prevBotId != connected.firstOrNull()?.second) {
            val prevSvc = botConnectionPool.get(prevBotId)
            if (prevSvc != null) {
                logger.i(tag, "终止前 speaker[$prevBotId] 的 TTS")
                prevSvc.sendAbort("relay_switch")
            }
        }

        // 遍历候选 bot 尝试转发
        for ((targetIndex, speakerId) in connected) {
            val service = botConnectionPool.get(speakerId) ?: continue
            relayState.update { it.copy(waitingSpeakerIndex = targetIndex) }
            botLastAudioTime[speakerId] = System.currentTimeMillis()

            val audioBuf = audioForwardBuffer[prevBotId]
            val isLongAudio = relayValidator.isLongAudio(audioBuf)
            val audioOk = if (isLongAudio) {
                logger.i(tag, "超长语音(${audioBuf!!.size}包)，跳过音频转发")
                false
            } else {
                audioForwardHandler.forwardAudioToBot(prevBotId, service)
            }

                if (audioOk) {
                // 不等 SPEAKING 状态——等第一个音频包到达即可（比 tts start 更早）
                // 音频包到了就说明下一个 bot 已在推声音，relay 可以放行
                val initAudioTs = botLastAudioTime[speakerId] ?: 0L
                val timeoutMs = 30_000L
                logger.i(tag, "等待 $speakerId 音频到达 (${timeoutMs}ms超时)")

                val audioReceived = withTimeoutOrNull(timeoutMs) {
                    // botLastAudioTime 从初始值变为更新值 = 收到第一个音频包
                    while (botLastAudioTime[speakerId] == initAudioTs) {
                        delay(50)
                    }
                    true
                }
                if (audioReceived != null) {
                    val hint = if (previousReply.isNotBlank()) relayState.value.lastForwardedText.take(8) else ""
                    if (checkAndClearOnDeadloop(hint, speakerId)) {
                        logger.i(tag, "死循环终止，跳过转发")
                        relayState.update { it.clearWaiting() }
                        return
                    }
                    relayState.update {
                        it.copy(
                            currentSpeakerIndex = targetIndex,
                            forwardRound = it.forwardRound + 1
                        )
                            .markSpoken(targetIndex)
                            .recordTransition(prevBotId ?: "", speakerId)
                    }
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(lastForwardedText = previousReply.take(20)) }
                    }
                    if (prevBotId != null) audioForwardBuffer.replace(prevBotId, mutableListOf())
                    logger.i(tag, "音频转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                    return
                }
                logger.w(tag, "音频转发超时($speakerId 10s未响应)，尝试下一候选")
                relayState.update { it.clearWaiting() }
            }

            // 文本转发（备用路径）— 统一 JSON 格式
            val textToSend = if (previousReply.isNotBlank()) {
                audioForwardHandler.buildForwardMessage(previousReply, prevBotId)
            } else ""
            if (checkAndClearOnDeadloop(textToSend, speakerId)) {
                logger.i(tag, "死循环终止，跳过本轮转发")
                relayState.update { it.clearWaiting() }
                return
            }
            if (textToSend.isNotBlank()) {
                if (service.connectionState.value !is BotConnectionState.Connected) {
                    logger.w(tag, "文本转发失败: $speakerId WebSocket未连接")
                    relayState.update { it.clearWaiting() }
                    continue
                }
                if (prevBotId != null) audioForwardBuffer.replace(prevBotId, mutableListOf())
                val logText = "JSON(${textToSend.length}字)"
                logger.i(tag, "文本转发 $logText → $speakerId: '${textToSend.take(60)}'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(lastForwardedText = previousReply.take(20)) }
                    }
                    // 同样等音频到达而非 SPEAKING 状态
                    val initTs = botLastAudioTime[speakerId] ?: 0L
                    val audioReceived = withTimeoutOrNull(30_000) {
                        while (botLastAudioTime[speakerId] == initTs) { delay(50) }
                        true
                    }
                    if (audioReceived != null) {
                        relayState.update {
                            it.copy(
                                currentSpeakerIndex = targetIndex,
                                forwardRound = it.forwardRound + 1
                            )
                                .markSpoken(targetIndex)
                                .recordTransition(prevBotId ?: "", speakerId)
                        }
                        logger.i(tag, "文本转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                        return
                    }
                    logger.w(tag, "文本转发超时($speakerId 30s未响应)，尝试下一候选")
                    relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    logger.w(tag, "文本转发异常: ${e.message}")
                    relayState.update { it.clearWaiting() }
                }
            }
        }

        if (prevBotId != null) audioForwardBuffer.replace(prevBotId, mutableListOf())

        // 所有候选不可用 → 重连重试
        if (candidates.isNotEmpty() && relayState.value.forwardRound >= 0) {
            logger.w(tag, "无可用bot转发，尝试重连所有候选")
            botConnectionPool.clearDead()
            for ((origIdx, speakerId) in candidates) {
                val contact = getContacts().find { it.id == speakerId }
                if (contact != null) {
                    val reconnected = botConnectionPool.ensureConnected(speakerId, contact)
                    if (reconnected) {
                        logger.i(tag, "重连成功 → $speakerId，延迟继续转发")
                        relayState.update { it.copy(waitingSpeakerIndex = origIdx) }
                        scope.launch {
                            delay(100)
                            triggerNextSpeaker(previousReply)
                        }
                        return
                    }
                }
            }
        }

        logger.w(tag, "无可用bot转发，跳过本轮")
        relayState.update { it.clearWaiting() }
        val firstAlive = relayState.value.botIds.firstOrNull()
        if (firstAlive != null) {
            relayState.update {
                it.copy(currentSpeakerIndex = it.botIds.indexOf(firstAlive).coerceAtLeast(0))
            }
        }
    }

    // ── 排空音频 ──

    /**
     * 排空上一 bot 的音频播放
     *
     * tts stop 到达时 decoder 队列里只剩最后几帧 PCM（<200ms），
     * 不值得阻塞等待。直接放行，让最后几帧自然播完。
     */
    suspend fun drainPreviousBotAudio(botId: String?, sourceId: String?) {
        // 不等待——tts stop 时音频已基本播完，剩余 <200ms
        // 阻塞等待只会增加 relay 切换延迟
    }
}
