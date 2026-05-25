package com.xiaozhi.aiservice.presentation.viewmodel

/**
 * 群聊接龙不可变状态对象
 *
 * 封装所有与 bot 接龙流转相关的运行时状态。
 * 替代原本 15 个分散的 var 字段，通过 MutableStateFlow<RelayState>
 * 原子管理，消除竞态条件。
 *
 * 设计原则：
 * - 不可变（data class copy）
 * - 所有状态变更通过成员函数，确保副作用一致
 * - 全局唯一：_relayState: MutableStateFlow<RelayState>
 *
 * 状态字段说明：
 * ┌─────────────────┬──────────────────────────────────────────┐
 * │ 字段             │ 含义                                     │
 * ├─────────────────┼──────────────────────────────────────────┤
 * │ botIds           │ 当前群聊的 bot 顺序列表                   │
 * │ currentSpeakerIndex│ 当前正在说话的 bot 索引                  │
 * │ waitingSpeakerIndex│ 预期下一个说话的 bot 索引（-1=无预期）    │
 * │ forwardRound     │ 当前会话已完成的转发轮次                   │
 * │ sessionId        │ 会话 ID（每轮录音递增，防旧回调干扰）       │
 * │ spokenInThisRound│ 本轮已发言的 bot 索引集合                  │
 * │ spokenRoundCounter│ 发言计数器，超限时强制重置 spokenInThisRound │
 * │ originalUserQuery│ 用户的原始查询（用于转发时的 hint 构建）    │
 * │ transitionHistory│ 最近 20 次 from→to 转发记录（防重复轮转）   │
 * └─────────────────┴──────────────────────────────────────────┘
 *
 * 接龙流转典型路径：
 * 用户语音 → bot1 回复(ttsText+speaking) → bot1 idle →
 * triggerNextSpeaker → 转发音频到 bot2 → bot2 回复 → ... → 轮次结束
 */
data class RelayState(
    /** 当前群聊的 bot 顺序列表（决定接龙流转方向） */
    val botIds: List<String> = emptyList(),

    /** 当前正在说话的 bot 索引 */
    val currentSpeakerIndex: Int = 0,

    /**
     * 预期的下一个说话 bot 索引
     * -1 = 无预期（接龙结束或空闲）
     * >=0 = 接龙正在等待该 bot 响应（看门狗据此检测卡死）
     */
    val waitingSpeakerIndex: Int = -1,

    /** 转发轮次（一轮 = 一次 bot→bot 转发） */
    val forwardRound: Int = 0,

    /**
     * 递增的会话 ID
     * 每轮新录音或文字发送时自增(+1)。
     * 用于判断 aiState/idle 回调是否属于当前轮次：
     * 旧轮次的 idle 会因 sessionId 不匹配而被跳过。
     */
    val sessionId: Long = 0L,

    /** 本轮已发言的 bot 索引集合（用于 speaker 选人：优先选未发言的） */
    val spokenInThisRound: Set<Int> = emptySet(),

    /**
     * 发言计数器
     * 每次 markSpoken 时 +1。
     * 当超过 botIds.size×2 或所有 bot 均已发言时，
     * 强制重置 spokenInThisRound → 开始新一轮循环
     */
    val spokenRoundCounter: Int = 0,

    /** 用户的原始查询文本的前 20 字符，用于构建转发时的 hint */
    val originalUserQuery: String = "",

    /**
     * 最近 20 条 (fromBotId, toBotId) 转发记录
     * 用于 speaker 评分时的轮转惩罚：同一对 from→to 出现 ≥2 次则降权，
     * 避免 bot 之间来回对话的「死循环」
     */
    val transitionHistory: List<Pair<String, String>> = emptyList()
) {
    /**
     * 新会话
     * 自增 sessionId，清除上一轮的状态（轮次、转发历史等）。
     * 调用时机：用户开始新录音或发送新文字消息。
     */
    fun nextSession(): RelayState = copy(
        sessionId = sessionId + 1,
        forwardRound = 0,
        spokenInThisRound = emptySet(),
        spokenRoundCounter = 0,
        transitionHistory = emptyList()
    )

    /** 标记指定 bot 已发言（加入 spokenInThisRound） */
    fun markSpoken(index: Int): RelayState = copy(
        spokenInThisRound = spokenInThisRound + index,
        spokenRoundCounter = spokenRoundCounter + 1
    )

    /** 切换到下一个 speaker（同时更新 current 和 waiting） */
    fun moveToSpeaker(index: Int): RelayState = copy(
        currentSpeakerIndex = index,
        waitingSpeakerIndex = index
    )

    /** 清除等待状态（接龙结束，waitingSpeakerIndex = -1） */
    fun clearWaiting(): RelayState = copy(waitingSpeakerIndex = -1)

    /**
     * 记录一次 from→to 转发
     * 保留最近 20 条，用于 speaker 评分时检测重复轮转模式
     */
    fun recordTransition(fromId: String, toId: String): RelayState = copy(
        transitionHistory = (transitionHistory + (fromId to toId)).takeLast(20)
    )
}
