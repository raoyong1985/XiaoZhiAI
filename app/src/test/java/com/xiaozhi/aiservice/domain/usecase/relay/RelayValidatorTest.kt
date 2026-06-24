package com.xiaozhi.aiservice.domain.usecase.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayValidatorTest {

    private val validator = RelayValidator()

    // ── isGoodbye ──

    @Test
    fun `isGoodbye blank reply returns false`() {
        assertFalse(validator.isGoodbye(""))
        assertFalse(validator.isGoodbye("  "))
    }

    @Test
    fun `isGoodbye exact keyword match at end`() {
        // "再见" length 2, matches keyword "再见" exactly
        assertTrue("exact match should be true", validator.isGoodbye("再见", keywords = listOf("再见")))
    }

    @Test
    fun `isGoodbye keyword with prefix followed by non-Chinese`() {
        assertTrue(validator.isGoodbye("拜拜123", keywords = listOf("拜拜")))
    }

    @Test
    fun `isGoodbye english bye`() {
        assertTrue(validator.isGoodbye("bye", keywords = listOf("bye")))
    }

    @Test
    fun `isGoodbye long reply over 20 chars returns false`() {
        assertFalse(validator.isGoodbye("今天我们聊了很多内容，再见，下次再继续聊吧"))
    }

    @Test
    fun `isGoodbye keyword then more Chinese returns false`() {
        assertFalse("keyword followed by more Chinese should be false",
            validator.isGoodbye("再见我们明天继续", keywords = listOf("再见")))
    }

    @Test
    fun `isGoodbye keyword with non-chinese suffix`() {
        assertTrue(validator.isGoodbye("再见123", keywords = listOf("再见")))
    }

    // ── isMaxRoundReached ──

    @Test
    fun `isMaxRoundReached always false with MAX_FORWARD_ROUND as Int MAX_VALUE`() {
        assertFalse(validator.isMaxRoundReached(0, 3))
        assertFalse(validator.isMaxRoundReached(100, 3))
    }

    // ── isSingleBot ──

    @Test
    fun `isSingleBot 0 bots returns true`() {
        assertTrue(validator.isSingleBot(emptyList()))
    }

    @Test
    fun `isSingleBot 1 bot returns true`() {
        assertTrue(validator.isSingleBot(listOf("bot1")))
    }

    @Test
    fun `isSingleBot 2 bots returns false`() {
        assertFalse(validator.isSingleBot(listOf("bot1", "bot2")))
    }

    // ── hasCandidates ──

    @Test
    fun `hasCandidates empty returns false`() {
        assertFalse(validator.hasCandidates(emptyList<Any>()))
    }

    @Test
    fun `hasCandidates non-empty returns true`() {
        assertTrue(validator.hasCandidates(listOf("bot1")))
    }

    // ── isReplyBlank ──

    @Test
    fun `isReplyBlank blank returns true`() {
        assertTrue(validator.isReplyBlank(""))
        assertTrue(validator.isReplyBlank("  "))
    }

    @Test
    fun `isReplyBlank non-blank returns false`() {
        assertFalse(validator.isReplyBlank("你好"))
    }

    // ── isLongAudio ──

    @Test
    fun `isLongAudio null returns false`() {
        assertFalse(validator.isLongAudio(null))
    }

    @Test
    fun `isLongAudio empty returns false`() {
        assertFalse(validator.isLongAudio(emptyList<ByteArray>()))
    }

    @Test
    fun `isLongAudio under threshold returns false`() {
        assertFalse(validator.isLongAudio(List(10) { ByteArray(100) }))
    }

    @Test
    fun `isLongAudio over threshold returns true`() {
        assertTrue(validator.isLongAudio(List(RelayConfig.LONG_AUDIO_THRESHOLD + 1) { ByteArray(100) }))
    }

    // ── estimateAudioMs ──

    @Test
    fun `estimateAudioMs null returns 0`() {
        assertEquals(0L, validator.estimateAudioMs(null))
    }

    @Test
    fun `estimateAudioMs empty returns 0`() {
        assertEquals(0L, validator.estimateAudioMs(emptyList()))
    }

    @Test
    fun `estimateAudioMs calculates duration`() {
        assertEquals(10L, validator.estimateAudioMs(listOf(ByteArray(12800))))
    }

    @Test
    fun `estimateAudioMs multiple buffers`() {
        assertEquals(10L, validator.estimateAudioMs(listOf(ByteArray(6400), ByteArray(6400))))
    }

    @Test
    fun `estimateAudioMs caps at 60000`() {
        assertTrue(validator.estimateAudioMs(listOf(ByteArray(10_000_000))) <= 60000L)
    }
}
