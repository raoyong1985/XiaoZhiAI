package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.model.BotContact
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioForwardHandlerTest {

    // ── truncateReply ──

    @Test
    fun `truncateReply short text returns as-is`() {
        val handler = handler()
        assertEquals("你好", handler.truncateReply("你好", 24))
    }

    @Test
    fun `truncateReply blank returns empty`() {
        val handler = handler()
        assertEquals("", handler.truncateReply(""))
        assertEquals("", handler.truncateReply("  "))
    }

    @Test
    fun `truncateReply cuts BEFORE the sentence-end punctuation`() {
        val handler = handler()
        // "今天天气真好。我们出去玩吧" = 14 chars, maxLen=8
        // sentenceEnd = indexOf('。') = 6. 6 in 1..8 → true
        // substring(0, 6) = "今天天气真好" (without 。)
        val result = handler.truncateReply("今天天气真好。我们出去玩吧", 8)
        assertEquals("今天天气真好", result)
    }

    @Test
    fun `truncateReply cuts at last boundary within maxLen`() {
        val handler = handler()
        // "abc,def,ghi" = 11 chars, maxLen=9
        // sentenceEnd = -1 (no Chinese/fullstop chars)
        // boundary = lastIndexOfAny([',', ...], 9) on [0..9]: ',' at 3 and 7. Last at 7.
        // 7 > 3 → substring(0, 7) = "abc,def"
        val result = handler.truncateReply("abc,def,ghi", 9)
        assertEquals("abc,def", result)
    }

    @Test
    fun `truncateReply hard cut when no punctuation found`() {
        val handler = handler()
        val text = "abcdefghijklmnopqrstuvwxyz"
        val result = handler.truncateReply(text, 10)
        assertEquals("abcdefghij", result)
    }

    @Test
    fun `truncateReply uses question mark as sentence end`() {
        val handler = handler()
        val text = "你好吗？我很好啊今天"
        // sentenceEnd = indexOf('？') = 3. 3 in 1..8 → true
        // substring(0, 3) = "你好吗" (without ？)
        assertEquals("你好吗", handler.truncateReply(text, 8))
    }

    @Test
    fun `truncateReply newline as sentence end`() {
        val handler = handler()
        val text = "第一行\n第二行第三行"
        // sentenceEnd = indexOf('\n') = 3. 3 in 1..6 → true
        // substring(0, 3) = "第一行"
        assertEquals("第一行", handler.truncateReply(text, 6))
    }

    @Test
    fun `truncateReply does not cut boundary within first 3 chars`() {
        val handler = handler()
        val text = "a,bcdefghijklmnop"
        // sentenceEnd = -1 (no Chinese punctuation)
        // boundary = lastIndexOfAny(',', maxLen=12) in [0..12]
        //    ',' at index 1. 1 > 3? No → fallback
        // hard cut to maxLen=12
        val result = handler.truncateReply(text, 12)
        assertEquals("a,bcdefghijk", result)
    }

    // ── buildForwardMessage ──

    @Test
    fun `buildForwardMessage blank reply returns empty`() {
        val handler = handler()
        assertEquals("", handler.buildForwardMessage(""))
    }

    @Test
    fun `buildForwardMessage with speaker wraps in json`() {
        val handler = handler(contacts = listOf(
            BotContact(id = "bot1", name = "小明")
        ))
        val result = handler.buildForwardMessage("你好呀", "bot1")
        assertEquals("""{"speaker":"小明","content":"你好呀"}""", result)
    }

    @Test
    fun `buildForwardMessage escapes double quotes in content`() {
        val handler = handler(contacts = listOf(
            BotContact(id = "bot1", name = "小明")
        ))
        val result = handler.buildForwardMessage("他叫\"小明\"", "bot1")
        assertEquals("""{"speaker":"小明","content":"他叫\"小明\""}""", result)
    }

    @Test
    fun `buildForwardMessage unknown botId returns raw reply`() {
        val handler = handler()
        val result = handler.buildForwardMessage("你好呀", "unknown_bot")
        assertEquals("你好呀", result)
    }

    @Test
    fun `buildForwardMessage null botId returns raw reply`() {
        val handler = handler()
        val result = handler.buildForwardMessage("你好呀", null)
        assertEquals("你好呀", result)
    }

    private fun handler(
        contacts: List<BotContact> = emptyList()
    ) = AudioForwardHandler(
        audioForwardBuffer = HashMap(),
        isRecording = { false },
        getContacts = { contacts }
    )
}
