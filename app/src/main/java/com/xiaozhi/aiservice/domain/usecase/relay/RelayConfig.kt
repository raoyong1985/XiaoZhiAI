package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 接龙引擎配置常量
 */
object RelayConfig {
    /** Bot 响应超时：转发后等 bot 回复 */
    const val BOT_RESPONSE_TIMEOUT_MS = 15_000L
    /** Bot 播放超时：TTS 播放最长 */
    const val BOT_PLAYBACK_TIMEOUT_MS = 30_000L
    /** Relay 切换超时 */
    const val RELAY_SWITCH_TIMEOUT_MS = 5_000L
    /** 总对话超时 */
    const val TOTAL_CONVERSATION_TIMEOUT_MS = 120_000L
    /** 静默超时：无音频自动结束 */
    const val SILENCE_TIMEOUT_MS = 30_000L
    /** Speaker 切换 grace 窗口 */
    const val SPEAKER_GRACE_MS = 800L
    /** 音频转发缓存单 bot 上限 */
    const val MAX_FORWARD_BUFFER_SIZE = 200
    /** 接龙最大轮次（Int.MAX_VALUE = 无限制） */
    const val MAX_FORWARD_ROUND = Int.MAX_VALUE
    /** 结束语关键词 */
    val GOODBYE_KEYWORDS = listOf("再见", "拜拜", "下次聊", "不打扰", "回头聊", "bye", "goodbye")
}
