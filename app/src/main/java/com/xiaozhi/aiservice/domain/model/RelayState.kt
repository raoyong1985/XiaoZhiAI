package com.xiaozhi.aiservice.domain.model

data class RelayState(
    val botIds: List<String> = emptyList(),
    val currentSpeakerIndex: Int = 0,
    val waitingSpeakerIndex: Int = -1,
    val forwardRound: Int = 0,
    val sessionId: Long = 0L,
    val spokenInThisRound: Set<Int> = emptySet(),
    val spokenRoundCounter: Int = 0,
    val originalUserQuery: String = "",
    val transitionHistory: List<Pair<String, String>> = emptyList()
) {
    fun nextSession(): RelayState = copy(
        sessionId = sessionId + 1,
        forwardRound = 0,
        spokenInThisRound = emptySet(),
        spokenRoundCounter = 0,
        transitionHistory = emptyList()
    )

    fun markSpoken(index: Int): RelayState = copy(
        spokenInThisRound = spokenInThisRound + index,
        spokenRoundCounter = spokenRoundCounter + 1
    )

    fun moveToSpeaker(index: Int): RelayState = copy(
        currentSpeakerIndex = index,
        waitingSpeakerIndex = index
    )

    fun clearWaiting(): RelayState = copy(waitingSpeakerIndex = -1)

    fun recordTransition(fromId: String, toId: String): RelayState = copy(
        transitionHistory = (transitionHistory + (fromId to toId)).takeLast(20)
    )
}
