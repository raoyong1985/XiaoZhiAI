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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val relayMutex = Mutex()
    @Volatile
    private var isRelaying = false
    private var retryCount = 0
    private val maxRetries = 3

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
        // 全局超时兜底：防止 isRelaying 被锁死（接龙卡在某 bot 等待上）
        val overallTimeoutMs = 45_000L
        val deadline = System.currentTimeMillis() + overallTimeoutMs
        withTimeoutOrNull(overallTimeoutMs) {
            innerTriggerNextSpeakerImpl(previousReply, deadline)
        }
        if (relayState.value.waitingSpeakerIndex >= 0) {
            relayState.update { it.clearWaiting() }
        }
    }

    private suspend fun innerTriggerNextSpeakerImpl(previousReply: String, deadline: Long) {
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
        logger.i(tag, "候选人=${candidates.size}: ${candidates.map { it.second }.take(4)}")

        // 过滤已连接的候选
        val connected = candidates.filter { (_, id) ->
            val svc = botConnectionPool.get(id)
            svc != null && (svc.connectionState.value is BotConnectionState.Connected
                || run {
                    val contact = getContacts().find { it.id == id }
                    contact != null && botConnectionPool.ensureConnected(id, contact)
                })
        }
        logger.i(tag, "已连接候选人=${connected.size}: ${connected.map { it.second }.take(4)}")

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
            if (System.currentTimeMillis() > deadline) {
                logger.w(tag, "接龙全局超时，跳过本轮")
                break
            }
            val service = botConnectionPool.get(speakerId) ?: continue
            relayState.update { it.copy(waitingSpeakerIndex = targetIndex) }
            botLastAudioTime[speakerId] = System.currentTimeMillis()

            val isOfficial = service.isOfficialServer()

            if (!isOfficial) {
                // ── 非官方服务器：纯文本转发（全量） ──
                val textToSend = audioForwardHandler.buildForwardMessage(previousReply, prevBotId)
                if (textToSend.isBlank()) {
                    logger.i(tag, "文本为空，跳过 $speakerId")
                    continue
                }
                if (checkAndClearOnDeadloop(textToSend, speakerId)) {
                    logger.i(tag, "死循环终止，跳过本轮转发")
                    relayState.update { it.clearWaiting() }
                    return
                }
                if (service.connectionState.value !is BotConnectionState.Connected) {
                    logger.w(tag, "文本转发失败: $speakerId WebSocket未连接")
                    relayState.update { it.clearWaiting() }
                    continue
                }
                if (prevBotId != null) audioForwardBuffer.compute(prevBotId) { _, _ -> mutableListOf() }
                logger.i(tag, "文本转发(全量 JSON ${textToSend.length}字) → $speakerId: '${textToSend.take(60)}'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(lastForwardedText = previousReply.take(20)) }
                    }
                    val startConfirmed = withTimeoutOrNull(30_000) {
                        service.aiState.first { it == AiStateStr.SPEAKING }
                    }
                    if (startConfirmed != null) {
                        relayState.update {
                            it.copy(
                                currentSpeakerIndex = targetIndex,
                                forwardRound = it.forwardRound + 1
                            )
                                .markSpoken(targetIndex)
                                .recordTransition(prevBotId ?: "", speakerId)
                        }
                        logger.i(tag, "文本转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                        retryCount = 0
                        return
                    }
                    logger.w(tag, "文本转发超时($speakerId 30s未响应)，尝试下一候选")
                    relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    logger.w(tag, "文本转发异常: ${e.message}")
                    relayState.update { it.clearWaiting() }
                }
                continue
            }

            // ── 官方服务器：纯音频转发，无文本兜底 ──
            val audioOk = audioForwardHandler.forwardAudioToBot(prevBotId, service)

            if (audioOk) {
                val estimatedAudioMs = relayValidator.estimateAudioMs(audioForwardBuffer[prevBotId])
                val timeoutMs = (estimatedAudioMs + 15_000).coerceIn(10_000, 120_000)
                logger.i(tag, "等待 $speakerId 开始说话 (${timeoutMs}ms超时, 音频≈${estimatedAudioMs}ms)")

                val startConfirmed = withTimeoutOrNull(timeoutMs) {
                    service.aiState.first { it == AiStateStr.SPEAKING }
                }
                if (startConfirmed != null) {
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
                    if (prevBotId != null) audioForwardBuffer.compute(prevBotId) { _, _ -> mutableListOf() }
                    logger.i(tag, "音频转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                    retryCount = 0
                    return
                }
                logger.w(tag, "音频转发超时($speakerId ${timeoutMs}ms未响应)，尝试下一候选")
                relayState.update { it.clearWaiting() }
            } else {
                logger.i(tag, "音频转发失败(audioOk=false), $speakerId, 尝试文本转发")
            }

            // ── 官方服务器文本兜底：全量文本（不截8字）──
            // 音频转发不可靠（官方服务器 TTS 路由可能导到别的 bot），
            // 失败后走全量文本，确保接龙能继续
            if (previousReply.isNotBlank() && !audioOk) {
                val textToSend = previousReply
                if (checkAndClearOnDeadloop(textToSend, speakerId)) {
                    logger.i(tag, "死循环终止，跳过本轮转发")
                    relayState.update { it.clearWaiting() }
                    return
                }
                if (service.connectionState.value !is BotConnectionState.Connected) {
                    logger.w(tag, "文本转发失败: $speakerId WebSocket未连接")
                    relayState.update { it.clearWaiting() }
                    continue
                }
                if (prevBotId != null) audioForwardBuffer.compute(prevBotId) { _, _ -> mutableListOf() }
                logger.i(tag, "文本转发(全量 ${textToSend.length}字) → $speakerId: '${textToSend.take(60)}'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(lastForwardedText = previousReply.take(20)) }
                    }
                    val startConfirmed = withTimeoutOrNull(30_000) {
                        service.aiState.first { it == AiStateStr.SPEAKING }
                    }
                    if (startConfirmed != null) {
                        relayState.update {
                            it.copy(
                                currentSpeakerIndex = targetIndex,
                                forwardRound = it.forwardRound + 1
                            )
                                .markSpoken(targetIndex)
                                .recordTransition(prevBotId ?: "", speakerId)
                        }
                        logger.i(tag, "文本转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                        retryCount = 0
                        return
                    }
                    logger.w(tag, "文本转发超时($speakerId 30s未响应)，尝试下一候选")
                    relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    logger.w(tag, "文本转发异常: ${e.message}")
                    relayState.update { it.clearWaiting() }
                }
            }

            relayState.update { it.clearWaiting() }
        }

        // 所有候选不可用 → 重连重试
        // ⚠️ buffer 保留不清理，让 retry 能读到音频数据
        // 本轮重试结束，复位计数
        if (retryCount > 0) retryCount = 0

        if (candidates.isNotEmpty()) {
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
                            if (retryCount >= maxRetries) {
                                logger.w(tag, "重试已达上限(${maxRetries}次)，停止本轮重试")
                                relayState.update { it.clearWaiting() }
                                retryCount = 0
                                return@launch
                            }
                            retryCount++
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
     */
    suspend fun drainPreviousBotAudio(botId: String?, sourceId: String?) {
        if (botId == null || sourceId == null) return
        withTimeoutOrNull(RelayConfig.AUDIO_DRAIN_TIMEOUT_MS) {
            while (true) {
                val lastTs = botLastAudioTime[botId] ?: 0L
                if (lastTs <= 0) break
                if (System.currentTimeMillis() - lastTs > RelayConfig.AUDIO_DRAIN_SILENCE_MS) break
                delay(100)
            }
        }
        delay(200)
        if (!playbackManager.waitForQueueEmpty(sourceId, 2000L)) {
            logger.w(tag, "drainPreviousBotAudio 超时")
        }
    }
}
