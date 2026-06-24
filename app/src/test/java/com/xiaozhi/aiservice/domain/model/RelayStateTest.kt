package com.xiaozhi.aiservice.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayStateTest {

    @Test
    fun `nextSession increments sessionId and resets state`() {
        val state = RelayState(
            botIds = listOf("bot1", "bot2"),
            currentSpeakerIndex = 1,
            waitingSpeakerIndex = 0,
            forwardRound = 3,
            sessionId = 1L
        )
        val next = state.nextSession()
        assertEquals(2L, next.sessionId)
        assertEquals(0, next.forwardRound)
        assertEquals(emptySet<Int>(), next.spokenInThisRound)
    }

    @Test
    fun `clearWaiting sets waitingSpeakerIndex to -1`() {
        val state = RelayState(waitingSpeakerIndex = 0)
        val cleared = state.clearWaiting()
        assertEquals(-1, cleared.waitingSpeakerIndex)
    }

    @Test
    fun `markSpoken adds index to spokenInThisRound`() {
        val state = RelayState()
        val marked = state.markSpoken(0)
        assertEquals(setOf(0), marked.spokenInThisRound)
        assertEquals(1, marked.spokenRoundCounter)
    }
}
