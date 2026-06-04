package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 接龙引擎配置常量
 *
 * 从 GroupChatViewModel 的 companion object 提取，
 * 供 RelayEngine / DeadloopDetector / AudioForwardHandler 等 usecase 共享。
 */
object RelayConfig {
    /** GPT 回复最长等待时间（30 秒） */
    const val MAX_GPT_WAIT_MS = 30000L

    /**
     * 结束语关键词
     * 如果 bot 回复包含这些词，认为对话已自然结束，不继续转发
     */
    val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")

    /** 音频排空超时（等待 bot 音频播放完毕的最长时间） */
    const val AUDIO_DRAIN_TIMEOUT_MS = 5000L

    /** 音频静默判定阈值：超过 1 秒无音频视为「已安静」 */
    const val AUDIO_DRAIN_SILENCE_MS = 1000L

    /** 音频转发缓存单 bot 上限（约200包=12秒@60ms帧） */
    const val MAX_FORWARD_BUFFER_SIZE = 300
    /** 音频转发判断为「超长」的阈值（超过此数量走文字兜底） */
    const val LONG_AUDIO_THRESHOLD = 150

    /**
     * 接龙最大轮次
     * 0（默认）：自动设为 botIds.size - 1（每人说一轮即止）
     * >0：固定上限
     */
    const val MAX_FORWARD_ROUND = Int.MAX_VALUE  // 无限制，聊天自然结束
}
