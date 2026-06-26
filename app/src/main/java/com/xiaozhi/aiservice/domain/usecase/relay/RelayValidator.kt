package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 接龙验证器 —— 纯逻辑验证，无副作用
 *
 * 包含接龙流程中的各种判断条件。
 * 从 RelayEngine 中抽出，保持行为一致。
 */
class RelayValidator {

    /** 判断回复是否为结束语，仅当回复很短（≤20字）且包含结束关键词时判定 */
    fun isGoodbye(previousReply: String, keywords: List<String> = RelayConfig.GOODBYE_KEYWORDS): Boolean {
        if (previousReply.isBlank()) return false
        val clean = previousReply.trim()
        // 仅当回复全文较短（≤20字）时才判定为"专门说再见结束对话"
        // 长回复（正常聊天）中包含"再见"等词不切断接龙
        if (clean.length > 20) return false
        return keywords.any { keyword ->
            val idx = clean.indexOf(keyword)
            idx >= 0 && (idx + keyword.length >= clean.length ||
                clean.substring(idx + keyword.length).none { it in '\u4E00'..'\u9FA5' })
        }
    }

    /** 判断是否已达最大转发轮次 */
    fun isMaxRoundReached(forwardRound: Int, botCount: Int): Boolean {
        val maxRounds = if (RelayConfig.MAX_FORWARD_ROUND > 0) RelayConfig.MAX_FORWARD_ROUND else botCount - 1
        return forwardRound >= maxRounds
    }

    /** 判断是否只有一个 bot（不需要接龙） */
    fun isSingleBot(botIds: List<String>): Boolean = botIds.size <= 1

    /** 判断是否为长音频（超过阈值就跳过纯音频转发，用文字兜底） */
    fun isLongAudio(audioBuf: List<*>?): Boolean = audioBuf != null && audioBuf.size > RelayConfig.LONG_AUDIO_THRESHOLD

    /** 估算音频时长（ms），基于 32kbps Opus、50fps、2字节/帧 */
    fun estimateAudioMs(audioBuf: List<ByteArray>?): Long {
        if (audioBuf == null) return 0
        val totalBytes = audioBuf.sumOf { it.size }
        return (totalBytes / (32000.0 / 50.0 * 2)).toLong().coerceIn(0, 60000)
    }
}
