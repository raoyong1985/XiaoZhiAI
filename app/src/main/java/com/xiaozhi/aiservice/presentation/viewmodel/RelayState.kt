package com.xiaozhi.aiservice.presentation.viewmodel

/**
 * 群聊接龙不可变状态对象
 * 封装所有与 bot 接龙相关的状态，替代原本 15 个分散的 var 字段
 * 每次状态变更通过 copy() 创建新实例，天然消除 race condition
 */
data class RelayState(
    /** 当前群聊的 bot 顺序列表 */
    val botIds: List<String> = emptyList(),
    /** 当前正在说话的 bot 索引 */
    val currentSpeakerIndex: Int = 0,
    /** 预期的下一个说话 bot 索引（-1 表示无预期） */
    val waitingSpeakerIndex: Int = -1,
    /** 转发轮次 */
    val forwardRound: Int = 0,
    /** 递增的会话 ID，用于判断 aiState 回调是否属于当前轮次 */
    val sessionId: Long = 0L,
    /** 本轮已发言的 bot 索引集合 */
    val spokenInThisRound: Set<Int> = emptySet(),
    /** spokenInThisRound 重置计数器，超过 botIds.size×2 时强制重置 */
    val spokenRoundCounter: Int = 0,
    /** 用户的原始查询文本 */
    val originalUserQuery: String = ""
) {
    /** 递增 sessionId */
    fun nextSession(): RelayState = copy(sessionId = sessionId + 1)

    /** 标记 bot 已发言 */
    fun markSpoken(index: Int): RelayState = copy(
        spokenInThisRound = spokenInThisRound + index,
        spokenRoundCounter = spokenRoundCounter + 1
    )

    /** 切换到下一个 speaker */
    fun moveToSpeaker(index: Int): RelayState = copy(
        currentSpeakerIndex = index,
        waitingSpeakerIndex = index
    )

    /** 清除预期 speaker（接龙结束） */
    fun clearWaiting(): RelayState = copy(waitingSpeakerIndex = -1)
}
