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
