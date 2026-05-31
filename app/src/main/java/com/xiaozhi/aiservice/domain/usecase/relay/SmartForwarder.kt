package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.PlatformLogger
import kotlinx.coroutines.delay
import com.google.gson.JsonObject

/**
 * 智能转发器 —— 根据服务器类型选择转发策略
 *
 * 官方服务器 (api.tenclass.net / xiaozhi.me)：
 *   音频优先 → 文字兜底（服务器能处理转发音频生成 TTS）
 *
 * 第三方/本地服务器：
 *   文字优先 → 音频兜底（服务器不支持音频转发，文字更可靠）
 */
 class SmartForwarder(
     private val getContacts: () -> List<BotContact>,
     private val logger: PlatformLogger = PlatformLogger.EMPTY
 ) {
     private val tag = "SmartForwarder"

     /** 最小音频包大小（字节），小于此值视为静音 */
     private val MIN_AUDIO_PACKET_SIZE = 50

     /** 官方服务器域名关键词 */
     private val OFFICIAL_HOSTS = listOf("api.tenclass.net", "xiaozhi.me")

     /** 判断目标 bot 是否为官方服务器 */
     fun isOfficialServer(targetBotId: String): Boolean {
         val contact = getContacts().find { it.id == targetBotId }
         val url = contact?.serverUrl?.lowercase() ?: return false
         return OFFICIAL_HOSTS.any { url.contains(it) }
     }

    /**
     * 转发上一位 bot 的回复到下一位 bot
     *
     * @param isOfficial true=官方服务器(音频优先), false=第三方(文字优先)
     */
    suspend fun forward(
        prevBotId: String?,
        previousReply: String,
        audioBuffer: List<ByteArray>?,
        nextBot: BotSession,
        isOfficial: Boolean
    ): ForwardResult {
        if (prevBotId == null) return ForwardResult.SKIPPED

        if (isOfficial) {
            // ── 官方服务器：音频优先 → 文字兜底 ──
            if (!audioBuffer.isNullOrEmpty()) {
                val filtered = filterSilence(audioBuffer)
                if (filtered.isNotEmpty()) {
                    val audioOk = sendAudio(filtered, nextBot.connection)
                    if (audioOk) {
                        logger.i(tag, "[官方] 音频转发成功(${filtered.size}包) → ${nextBot.botId}")
                        return ForwardResult.AUDIO_SENT
                    }
                }
            }
            // 音频失败 → 文字兜底
            return sendTextFallback(previousReply, prevBotId, nextBot)
        } else {
            // ── 第三方/本地服务器：文字优先 → 音频兜底 ──
            val textResult = sendTextFallback(previousReply, prevBotId, nextBot)
            if (textResult == ForwardResult.TEXT_SENT) {
                return textResult
            }
            // 文字失败 → 尝试音频兜底
            if (!audioBuffer.isNullOrEmpty()) {
                val filtered = filterSilence(audioBuffer)
                if (filtered.isNotEmpty()) {
                    val audioOk = sendAudio(filtered, nextBot.connection)
                    if (audioOk) {
                        logger.i(tag, "[第三方] 音频兜底转发成功(${filtered.size}包) → ${nextBot.botId}")
                        return ForwardResult.AUDIO_SENT
                    }
                }
            }
            return ForwardResult.FAILED
        }
    }

    /** 文字转发辅助方法 */
    private suspend fun sendTextFallback(previousReply: String, prevBotId: String?, nextBot: BotSession): ForwardResult {
        if (previousReply.isBlank()) return ForwardResult.FAILED
        val textMsg = buildForwardMessage(previousReply, prevBotId)
        if (textMsg.isNotBlank() && nextBot.connection.connectionState.value is BotConnectionState.Connected) {
            try {
                nextBot.connection.sendTextContent(textMsg)
                logger.i(tag, "文字转发 → ${nextBot.botId}: '${previousReply.take(30)}'")
                return ForwardResult.TEXT_SENT
            } catch (e: Exception) {
                logger.w(tag, "文字转发失败: ${e.message}")
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
        return JsonObject().apply {
            addProperty("speaker", name)
            addProperty("content", previousReply)
        }.toString()
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
