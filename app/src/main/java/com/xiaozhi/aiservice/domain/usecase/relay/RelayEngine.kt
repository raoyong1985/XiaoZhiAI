package com.xiaozhi.aiservice.domain.usecase.relay

import android.util.Log
import com.xiaozhi.aiservice.data.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import com.xiaozhi.aiservice.presentation.viewmodel.RelayState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
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
    private val tag: String = "RelayEngine"
) {
    private val relayLock = Any()
    private var isRelaying = false

    // 委派方法: checkDeadloop (保持与原行为一致)
    private fun checkDeadloop(hint: String, speakerId: String? = null): Boolean {
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
        synchronized(relayLock) {
            if (isRelaying) {
                Log.w(tag, "triggerNextSpeaker 已在执行中，跳过并发调用")
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
        val rs = relayState.value
        if (rs.botIds.size <= 1) {
            relayState.update { it.clearWaiting() }
            return
        }
        if (isRecording()) {
            Log.w(tag, "用户正在录音，跳过转发")
            relayState.update { it.clearWaiting() }
            return
        }

        if (previousReply.isNotBlank()) {
            val tail = previousReply.takeLast(35)
            val isGoodbye = RelayConfig.GOODBYE_KEYWORDS.any { keyword ->
                val idx = tail.indexOf(keyword)
                idx >= 0 && (idx + keyword.length >= tail.length ||
                    tail.substring(idx + keyword.length).none { it in '一'..'龥' })
            }
            if (isGoodbye) {
                Log.i(tag, "检测到结束语，停止接龙: '${previousReply.take(20)}'")
                relayState.update { it.clearWaiting() }
                return
            }
        }

        val prevIndex = rs.currentSpeakerIndex
        val prevBotId = rs.botIds.getOrNull(prevIndex)

        val maxRounds = if (RelayConfig.MAX_FORWARD_ROUND > 0) RelayConfig.MAX_FORWARD_ROUND else rs.botIds.size - 1
        if (rs.forwardRound >= maxRounds) {
            Log.i(tag, "接龙已达最大轮次($maxRounds)，停止转发")
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
            svc != null && (svc.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected
                || run {
                    val contact = getContacts().find { it.id == id }
                    contact != null && botConnectionPool.ensureConnected(id, contact)
                })
        }

        // 终止前一个 bot 的 TTS
        if (prevBotId != null && prevBotId != connected.firstOrNull()?.second) {
            val prevSvc = botConnectionPool.get(prevBotId)
            if (prevSvc != null) {
                Log.i(tag, "终止前 speaker[$prevBotId] 的 TTS")
                prevSvc.sendAbort("relay_switch")
            }
        }

        // 遍历候选 bot 尝试转发
        for ((targetIndex, speakerId) in connected) {
            val service = botConnectionPool.get(speakerId) ?: continue
            relayState.update { it.moveToSpeaker(targetIndex) }
            botLastAudioTime[speakerId] = System.currentTimeMillis()

            val audioBuf = audioForwardBuffer[prevBotId]
            val isLongAudio = audioBuf != null && audioBuf.size > 200
            val audioOk = if (isLongAudio) {
                Log.i(tag, "超长语音(${audioBuf!!.size}包)，跳过音频转发")
                false
            } else {
                audioForwardHandler.forwardAudioToBot(prevBotId, service)
            }

            if (audioOk) {
                val forwardAudioSize = audioForwardBuffer[prevBotId]?.sumOf { it.size } ?: 0
                val estimatedAudioMs = (forwardAudioSize / (32000.0 / 50.0 * 2)).toLong().coerceIn(0, 60000)
                val timeoutMs = (estimatedAudioMs + 15_000).coerceIn(10_000, 120_000)
                Log.i(tag, "等待 $speakerId 开始说话 (${timeoutMs}ms超时, 音频≈${estimatedAudioMs}ms)")

                val startConfirmed = withTimeoutOrNull(timeoutMs) {
                    service.aiState.first { it == "speaking" }
                }
                if (startConfirmed != null) {
                    val hint = if (previousReply.isNotBlank()) relayState.value.originalUserQuery.take(8) else ""
                    if (checkDeadloop(hint, speakerId)) {
                        Log.i(tag, "死循环终止，跳过转发")
                        relayState.update { it.clearWaiting() }
                        return
                    }
                    relayState.update {
                        it.copy(forwardRound = it.forwardRound + 1)
                            .markSpoken(targetIndex)
                            .recordTransition(prevBotId ?: "", speakerId)
                    }
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(originalUserQuery = previousReply.take(20)) }
                    }
                    audioForwardBuffer.remove(prevBotId)
                    Log.i(tag, "音频转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                    return
                }
                Log.w(tag, "音频转发超时($speakerId 10s未响应)，尝试下一候选")
                relayState.update { it.clearWaiting() }
            }

            // 文本转发（备用路径）
            val isLocal = service.isLocalServer()
            val textToSend = if (isLocal) {
                audioForwardHandler.buildForwardMessage(previousReply, prevBotId)
            } else {
                if (previousReply.isNotBlank()) audioForwardHandler.truncateReply(previousReply, 8) else ""
            }
            if (checkDeadloop(textToSend, speakerId)) {
                Log.i(tag, "死循环终止，跳过本轮转发")
                relayState.update { it.clearWaiting() }
                return
            }
            if (textToSend.isNotBlank()) {
                if (service.connectionState.value !is XiaoZhiBackendService.ConnectionState.Connected) {
                    Log.w(tag, "文本转发失败: $speakerId WebSocket未连接")
                    relayState.update { it.clearWaiting() }
                    continue
                }
                audioForwardBuffer.remove(prevBotId)
                val logText = if (isLocal) "JSON(${textToSend.length}字)" else "短文本(${textToSend.length}字)"
                Log.i(tag, "文本转发 $logText → $speakerId: '${textToSend.take(60)}'")
                try {
                    service.sendTextContent(textToSend)
                    if (previousReply.isNotBlank()) {
                        relayState.update { it.copy(originalUserQuery = previousReply.take(20)) }
                    }
                    val startConfirmed = withTimeoutOrNull(30_000) {
                        service.aiState.first { it == "speaking" }
                    }
                    if (startConfirmed != null) {
                        relayState.update {
                            it.copy(forwardRound = it.forwardRound + 1)
                                .markSpoken(targetIndex)
                                .recordTransition(prevBotId ?: "", speakerId)
                        }
                        Log.i(tag, "文本转发成功 → $speakerId fwd=${relayState.value.forwardRound}")
                        return
                    }
                    Log.w(tag, "文本转发超时($speakerId 30s未响应)，尝试下一候选")
                    relayState.update { it.clearWaiting() }
                } catch (e: Exception) {
                    Log.w(tag, "文本转发异常: ${e.message}")
                    relayState.update { it.clearWaiting() }
                }
            }
        }

        audioForwardBuffer.remove(prevBotId)

        // 所有候选不可用 → 重连重试
        if (candidates.isNotEmpty() && relayState.value.forwardRound >= 0) {
            Log.w(tag, "无可用bot转发，尝试重连所有候选")
            botConnectionPool.clearDead()
            for ((origIdx, speakerId) in candidates) {
                val contact = getContacts().find { it.id == speakerId }
                if (contact != null) {
                    val reconnected = botConnectionPool.ensureConnected(speakerId, contact)
                    if (reconnected) {
                        Log.i(tag, "重连成功 → $speakerId，继续转发")
                        relayState.update { it.moveToSpeaker(origIdx) }
                        triggerNextSpeaker(previousReply)
                        return
                    }
                }
            }
        }

        Log.w(tag, "无可用bot转发，跳过本轮")
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
            Log.w(tag, "drainPreviousBotAudio 超时")
        }
    }
}
