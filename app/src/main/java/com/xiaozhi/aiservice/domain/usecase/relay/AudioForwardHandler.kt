package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.PlatformLogger
import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import kotlinx.coroutines.delay

/**
 * 音频转发处理器 —— 管理 bot 间音频/文字转发的核心逻辑
 *
 * 职责：
 * 1. forwardAudioToBot：将上一位 bot 的 Opus 音频缓存转发给下一位 bot
 * 2. buildForwardMessage：音频转发失败时，构建文字兜底消息（带说话人标识）
 * 3. truncateReply：智能截断文本（优先句号/叹号/问号，其次逗号/空格）
 */
class AudioForwardHandler(
    private val audioForwardBuffer: MutableMap<String, MutableList<ByteArray>>,
    private val isRecording: () -> Boolean,
    private val getContacts: () -> List<BotContact>,
    private val logger: PlatformLogger = PlatformLogger.EMPTY,
) {
    private val tag = "AudioFwdHandler"

    /**
     * 将上一位 bot 的 Opus 音频转发给目标 bot
     *
     * 发送策略：
     * 1. 发送 listen/start 通知目标 bot 准备接收
     * 2. 攒批发送：每 5 包合并，前 2 批宽松延迟，后面每 4 批 10ms
     * 3. 发送 listen/stop 结束
     *
     * @param prevBotId 上一位发言 bot 的 ID（用于查找缓存）
     * @param service 目标 bot 的 WebSocket 连接
     * @return true=转发成功，false=失败（连接断开/无缓存/用户录音中）
     */
    suspend fun forwardAudioToBot(prevBotId: String?, service: BotConnection): Boolean {
        if (isRecording()) {
            logger.w(tag, "用户正在录音，跳过音频转发")
            return false
        }
        if (service.connectionState.value !is BotConnectionState.Connected) {
            logger.w(tag, "音频转发失败：WebSocket未连接")
            return false
        }
        if (prevBotId == null) {
            logger.w(tag, "音频转发失败：prevBotId为null")
            return false
        }
        val cachedList = audioForwardBuffer[prevBotId]
        if (cachedList == null) {
            logger.w(tag, "音频转发失败：audioForwardBuffer[${prevBotId.take(8)}] 不存在")
            return false
        }
        if (cachedList.isEmpty()) {
            logger.w(tag, "音频转发失败：audioForwardBuffer[${prevBotId.take(8)}] 为空")
            return false
        }
        val cachedOpus = cachedList.toList()
        logger.i(tag, "音频转发 ${cachedOpus.size}包/${cachedOpus.sumOf { it.size }}B prev=$prevBotId")
        try {
            service.sendListenStart("auto")
            val batchSize = 5
            cachedOpus.chunked(batchSize).forEachIndexed { batchIdx, batch ->
                batch.forEach { packet -> service.sendAudio(packet) }
                when {
                    batchIdx < 2 -> delay(40)
                    batchIdx % 4 == 0 -> delay(10)
                }
            }
            // 等服务器处理完音频再发 listenStop
            // 长音频 ≈ 9秒(150包×60ms) 给服务器足够时间开始 ASR 处理
            val audioDurationMs = cachedOpus.size * 60L  // 每包约60ms
            val settleMs = (audioDurationMs / 4).coerceIn(200, 2000)
            delay(settleMs)
            service.sendListenStopOnly()
            return service.connectionState.value is BotConnectionState.Connected
        } catch (e: Exception) {
            logger.w(tag, "音频转发失败: ${e.message}")
            return false
        }
    }

    /**
     * 构建转发文本，带上说话人标识
     *
     * 格式：{"speaker":"说话人名称","content":"完整回复内容"}
     * 服务器端会解析 JSON 格式，提取说话人信息后传递给 LLM。
     */
    fun buildForwardMessage(previousReply: String, prevBotId: String? = null): String {
        if (previousReply.isBlank()) return ""
        val name = prevBotId?.let { id ->
            getContacts().find { id == it.id }?.name
        } ?: return previousReply
        // 过滤 MVU 标签和调试输出，防止污染下游 LLM
        val clean = previousReply
            .replace(Regex("<update>[^<]*</update>\\s*"), "")  // 去掉 <update> 标签
            .replace(Regex("<FirstMes>[^<]*</FirstMes>\\s*"), "")  // 去掉 FirstMes 标签
            .replace(Regex("FirstMes:.*"), "")  // 去掉调试输出的配置信息
            .trim()
        return """{"speaker":"${name}","content":"${clean.replace("\"", "\\\"")}"}"""
    }

    /**
     * 智能截断文本
     *
     * 优先在句号/叹号/问号处截断，其次在逗号/空格处，最后硬截断到 maxLen。
     */
    fun truncateReply(text: String, maxLen: Int = 24): String {
        if (text.isBlank()) return ""
        val clean = text.trim()
        if (clean.length <= maxLen) return clean
        val sentenceEnd = clean.indexOfAny(charArrayOf('。', '！', '？', '\n'))
        if (sentenceEnd in 1..maxLen) return clean.substring(0, sentenceEnd)
        val boundary = clean.lastIndexOfAny(charArrayOf('，', '、', '；', '：', ' ', ','), maxLen)
        if (boundary > 3) return clean.substring(0, boundary)
        return clean.substring(0, maxLen)
    }
}
