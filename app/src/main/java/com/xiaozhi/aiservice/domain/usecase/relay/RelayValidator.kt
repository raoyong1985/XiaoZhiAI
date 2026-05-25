package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 接龙验证器 —— 纯逻辑验证，无副作用
 *
 * 包含接龙流程中的各种判断条件。
 * 从 RelayEngine 中抽出，保持行为一致。
 */
class RelayValidator {

    /** 判断回复是否为结束语（再见/拜拜等），含中文后缀检查 */
    fun isGoodbye(previousReply: String, keywords: List<String> = RelayConfig.GOODBYE_KEYWORDS): Boolean {
        if (previousReply.isBlank()) return false
        val tail = previousReply.takeLast(35)
        return keywords.any { keyword ->
            val idx = tail.indexOf(keyword)
            idx >= 0 && (idx + keyword.length >= tail.length ||
                tail.substring(idx + keyword.length).none { it in '\u4E00'..'\u9FA5' })
        }
    }

    /** 判断是否已达最大转发轮次 */
    fun isMaxRoundReached(forwardRound: Int, botCount: Int): Boolean {
        val maxRounds = if (RelayConfig.MAX_FORWARD_ROUND > 0) RelayConfig.MAX_FORWARD_ROUND else botCount - 1
        return forwardRound >= maxRounds
    }

    /** 判断是否只有一个 bot（不需要接龙） */
    fun isSingleBot(botIds: List<String>): Boolean = botIds.size <= 1

    /** 判断是否有候选 bot 可以发言 */
    fun hasCandidates(candidates: List<*>): Boolean = candidates.isNotEmpty()

    /** 判断回复文本是否为空 */
    fun isReplyBlank(reply: String): Boolean = reply.isBlank()

    /** 判断是否为长音频（超过 200 包） */
    fun isLongAudio(audioBuf: List<*>?): Boolean = audioBuf != null && audioBuf.size > 200

    /** 估算音频时长（ms），基于 32kbps Opus、50fps、2字节/帧 */
    fun estimateAudioMs(audioBuf: List<ByteArray>?): Long {
        if (audioBuf == null) return 0
        val totalBytes = audioBuf.sumOf { it.size }
        return (totalBytes / (32000.0 / 50.0 * 2)).toLong().coerceIn(0, 60000)
    }
}
