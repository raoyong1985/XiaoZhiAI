package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 群聊接力状态机
 *
 * 状态转换图:
 *   IDLE → USER_SPEAKING → BOT_PROCESSING → BOT_SPEAKING → RELAY_SWITCH → BOT_PROCESSING → ...
 *   任何状态 → FINISHED（超时/结束语/用户中断）
 *   FINISHED → IDLE（下一轮）
 */
enum class RelayPhase {
    /** 等待用户输入 */
    IDLE,
    /** 用户正在说话（录音中） */
    USER_SPEAKING,
    /** Bot 正在处理（LLM + TTS 生成中，尚未有音频） */
    BOT_PROCESSING,
    /** Bot 正在播放音频 */
    BOT_SPEAKING,
    /** 切换到下一个 bot（选人 + 转发） */
    RELAY_SWITCH,
    /** 本轮对话结束 */
    FINISHED
}

/**
 * 触发状态转换的事件
 */
sealed class RelayEvent {
    /** 用户开始录音 */
    data object UserStartSpeaking : RelayEvent()
    /** 用户停止录音（VAD 或手动） */
    data object UserStopSpeaking : RelayEvent()
    /** Bot 开始播放 TTS（收到 tts start） */
    data object BotStartSpeaking : RelayEvent()
    /** Bot TTS 结束（收到 tts stop） */
    data object BotStopSpeaking : RelayEvent()
    /** 对话结束（结束语/最大轮次/超时） */
    data object ConversationFinished : RelayEvent()
    /** 用户中断（按下停止/新的语音输入） */
    data object UserInterrupt : RelayEvent()
}
