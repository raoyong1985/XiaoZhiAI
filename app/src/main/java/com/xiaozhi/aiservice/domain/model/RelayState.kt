package com.xiaozhi.aiservice.domain.model

import com.xiaozhi.aiservice.domain.usecase.relay.RelayPhase

data class RelayState(
    val phase: RelayPhase = RelayPhase.IDLE,
    val botIds: List<String> = emptyList(),
    val currentSpeakerIndex: Int = 0,
    val waitingSpeakerIndex: Int = -1,
    val forwardRound: Int = 0,
    val sessionId: Long = 0L,
    val spokenInThisRound: Set<Int> = emptySet(),
    val spokenRoundCounter: Int = 0,
    val lastForwardedText: String = "",
    val transitionHistory: List<Pair<String, String>> = emptyList(),
    /** 当前预期 speaker 的 botId（音频过滤用） */
    val expectedSpeakerId: String = "",
    /** 当前轮次的回复文本（转发给下一个 bot 用） */
    val currentReplyText: String = "",
    /** 是否为 人群模式 */
    val isTwoPersonMode: Boolean = false
) {
    fun nextSession() = copy(
        phase = RelayPhase.IDLE,
        sessionId = sessionId + 1,
        forwardRound = 0,
        spokenInThisRound = emptySet(),
        spokenRoundCounter = 0,
        transitionHistory = emptyList(),
        expectedSpeakerId = "",
        currentReplyText = ""
    )

    fun markSpoken(index: Int) = copy(
        spokenInThisRound = spokenInThisRound + index,
        spokenRoundCounter = spokenRoundCounter + 1
    )

    fun moveToSpeaker(index: Int, botId: String) = copy(
        currentSpeakerIndex = index,
        waitingSpeakerIndex = index,
        expectedSpeakerId = botId,
        phase = RelayPhase.BOT_PROCESSING
    )

    fun clearWaiting() = copy(waitingSpeakerIndex = -1, expectedSpeakerId = "")

    fun recordTransition(fromId: String, toId: String) = copy(
        transitionHistory = (transitionHistory + (fromId to toId)).takeLast(20)
    )
}
