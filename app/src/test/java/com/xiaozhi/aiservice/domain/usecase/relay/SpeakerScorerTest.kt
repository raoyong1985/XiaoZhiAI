package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.RelayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerScorerTest {

    private val contacts = listOf(
        BotContact(id = "bot1", name = "小明"),
        BotContact(id = "bot2", name = "小红"),
        BotContact(id = "bot3", name = "小刚")
    )

    private fun scorer(
        deadIds: Set<String> = emptySet(),
        contacts: List<BotContact> = this.contacts
    ) = SpeakerScorer(
        isDead = { it in deadIds },
        getContacts = { contacts }
    )

    // ── Edge cases with botIds ──

    @Test
    fun `empty botIds returns empty`() {
        val s = scorer()
        val (result, _) = s.resolveCandidates(0, "", emptyList(), RelayState())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all bots dead returns empty`() {
        val s = scorer(deadIds = setOf("bot1", "bot2"))
        val (result, _) = s.resolveCandidates(0, "", listOf("bot1", "bot2"), RelayState())
        assertTrue(result.isEmpty())
    }

    // ── @mention scoring ──

    @Test
    fun `mentioned bot gets priority when not the previous speaker`() {
        val s = scorer()
        // prev = bot1 (idx 0). Mention @小红 targets bot2 (idx 1)
        // bot1 is excluded as prev, candidates: [bot2]
        // With signal (@小红), bot2 gets +100
        val (result, _) = s.resolveCandidates(
            prevIndex = 0,
            previousReply = "接下来@小红 来说说",
            botIds = listOf("bot1", "bot2"),
            rs = RelayState()
        )
        assertEquals("bot2", result.firstOrNull()?.second)
    }

    @Test
    fun `mention with whitespace around still matches`() {
        val s = scorer()
        val (result, _) = s.resolveCandidates(
            prevIndex = 0,
            previousReply = " @小明 回答",
            botIds = listOf("bot1", "bot2"),
            rs = RelayState()
        )
        assertEquals("bot2", result.firstOrNull()?.second) // only candidate
    }

    // ── Name match scoring ──

    @Test
    fun `name matched bot gets priority when not the previous speaker`() {
        val s = scorer()
        // prev = bot3 (idx 2), reply mentions "小明"
        // bot3 excluded. Candidates: bot1 (name="小明") +30, bot2 (no match)
        val (result, _) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "我觉得小明说得对",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState()
        )
        assertEquals("bot1", result.firstOrNull()?.second)
    }

    @Test
    fun `name match with empty name field does not trigger`() {
        val contactsWithEmptyName = listOf(
            BotContact(id = "bot1", name = ""),
            BotContact(id = "bot2", name = "小红")
        )
        val s = SpeakerScorer(
            isDead = { false },
            getContacts = { contactsWithEmptyName }
        )
        // prev=bot1 (idx 0), reply mentions "小红"
        // bot1 excluded. Candidates: [bot2]. bot2's name "小红" is in reply → +30
        val (result, _) = s.resolveCandidates(
            prevIndex = 0,
            previousReply = "小红你来答",
            botIds = listOf("bot1", "bot2"),
            rs = RelayState()
        )
        assertEquals("bot2", result.firstOrNull()?.second)
    }

    // ── Round_robin fallback ──

    @Test
    fun `no signal uses round_robin to next alive bot`() {
        val s = scorer()
        // prev = bot1 (idx 0), no signal
        // candidates exclude prev: [bot2, bot3]
        // round_robin: next after bot1 (aliveIdx=0) = offset=1 → bot2
        val (result, _) = s.resolveCandidates(
            prevIndex = 0,
            previousReply = "随便说点啥",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState()
        )
        assertEquals("bot2", result.firstOrNull()?.second)
    }

    @Test
    fun `round_robin wraps around to first bot`() {
        val s = scorer()
        // prev = bot3 (idx 2), no signal
        // candidates exclude prev: [bot1, bot2]
        // round_robin: aliveIdx=2, next offset=1 → (2+1)%3=0 → bot1
        val (result, _) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "随便说点啥",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState()
        )
        assertEquals("bot1", result.firstOrNull()?.second)
    }

    // ── Spoken tracking and reset ──

    @Test
    fun `new round resets spokenInThisRound when reset condition met`() {
        val s = scorer()
        // With signal present, scoring mode (not round_robin)
        // prev = bot3 (idx 2), reply mentions 小明 → signal exists
        // All bots (except prev) have been spoken
        val (result, newState) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "小明说得好",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState(
                spokenInThisRound = setOf(0, 1), // bot1, bot2 already spoken
                spokenRoundCounter = 2
            )
        )
        // After compacting with alive: both non-prev bots already spoken → all spoken
        // forceReset: spokenRoundCounter=2 >= maxOf(3*2,4)=6? No, 2 < 6
        // allSpoken: bot1(idx 0) in compactedSpoken, bot2(idx 1) in compactedSpoken → both spoken
        // allSpoken=true → reset!
        assertEquals("bot1", result.firstOrNull()?.second)
        assertTrue(newState.spokenInThisRound.isEmpty())
        assertEquals(0, newState.spokenRoundCounter)
    }

    // ── Transition penalty ──

    @Test
    fun `transition penalty remains after repeated transitions`() {
        val s = scorer()
        // prev = bot3 (idx 2), reply "@小红 说" → signal for bot2
        // bot3 excluded. Candidates: [bot1, bot2]
        // transitionHistory has bot1→bot2 3 times → bot2 gets -60 penalty
        // bot2: +100(mention) -60(penalty) = 40
        // bot1: 0 + 20(unspoken) = 20
        // bot2 wins
        val (result, _) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "@小红 说",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState(
                transitionHistory = listOf(
                    "bot1" to "bot2",
                    "bot1" to "bot2",
                    "bot1" to "bot2" // 3 times
                )
            )
        )
        // bot2 still wins (40 > 20)
        assertEquals("bot2", result.firstOrNull()?.second)
    }

    // ── Dead bot filtering ──

    @Test
    fun `dead bots excluded from candidates`() {
        val s = scorer(deadIds = setOf("bot2"))
        // prev = bot3 (idx 2), reply mentions 小红
        // alive: bot1, bot3. bot3 excluded as prev. Candidate: [bot1]
        // bot1 has no name match, bot2 is dead.
        val (result, _) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "@小红 回答",
            botIds = listOf("bot1", "bot2", "bot3"),
            rs = RelayState()
        )
        // Only bot1 is a candidate
        assertEquals("bot1", result.firstOrNull()?.second)
    }

    // ── Candidates format ──

    @Test
    fun `result preserves original botIds index`() {
        val s = scorer(deadIds = setOf("bot1"))
        // prev = bot3 (idx 2), no signal
        // alive: bot2, bot3. bot3 excluded as prev. Candidate: [bot2]
        // bot2 is original index 1
        val (result, _) = s.resolveCandidates(
            prevIndex = 2,
            previousReply = "随便说",
            botIds = listOf("bot1", "bot2", "bot3"), // bot1 dead
            rs = RelayState()
        )
        assertEquals(1, result.firstOrNull()?.first) // original index 1
        assertEquals("bot2", result.firstOrNull()?.second)
    }
}
