package com.xiaozhi.aiservice.domain.usecase.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicationUtilTest {

    @Test
    fun `isDuplicate first occurrence returns false`() {
        val dedup = DeduplicationUtil()
        assertFalse(dedup.isDuplicate("bot1", "你好"))
    }

    @Test
    fun `isDuplicate same bot same content returns true`() {
        val dedup = DeduplicationUtil()
        dedup.isDuplicate("bot1", "你好")
        assertTrue(dedup.isDuplicate("bot1", "你好"))
    }

    @Test
    fun `isDuplicate different bot same content returns false`() {
        val dedup = DeduplicationUtil()
        dedup.isDuplicate("bot1", "你好")
        assertFalse(dedup.isDuplicate("bot2", "你好"))
    }

    @Test
    fun `isDuplicate same bot different content returns false`() {
        val dedup = DeduplicationUtil()
        dedup.isDuplicate("bot1", "你好")
        assertFalse(dedup.isDuplicate("bot1", "哈喽"))
    }

    @Test
    fun `evictions work correctly with small buffer`() {
        val dedup = DeduplicationUtil(maxRecentReplies = 3)
        assertFalse(dedup.isDuplicate("bot1", "a")) // 1st
        assertFalse(dedup.isDuplicate("bot1", "b")) // 2nd
        assertFalse(dedup.isDuplicate("bot1", "c")) // 3rd → buffer full (size=3)
        assertFalse(dedup.isDuplicate("bot1", "d")) // 4th → "a" evicted, size stays 3
        assertFalse(dedup.isDuplicate("bot1", "a")) // "a" was evicted, treated as new
        assertTrue(dedup.isDuplicate("bot1", "d"))  // "d" still in buffer
    }

    @Test
    fun `clear resets all state`() {
        val dedup = DeduplicationUtil()
        dedup.isDuplicate("bot1", "你好")
        assertTrue(dedup.isDuplicate("bot1", "你好"))
        dedup.clear()
        assertFalse(dedup.isDuplicate("bot1", "你好"))
    }

    @Test
    fun `multiple bots do not interfere`() {
        val dedup = DeduplicationUtil()
        assertFalse(dedup.isDuplicate("bot1", "hi"))
        assertFalse(dedup.isDuplicate("bot2", "hi"))
        assertFalse(dedup.isDuplicate("bot3", "hi"))
        assertTrue(dedup.isDuplicate("bot1", "hi"))
        assertTrue(dedup.isDuplicate("bot2", "hi"))
        assertTrue(dedup.isDuplicate("bot3", "hi"))
    }

    @Test
    fun `default maxRecentReplies evicts oldest after 41 entries`() {
        val dedup = DeduplicationUtil() // default maxRecentReplies=40
        // Fill with 41 unique entries → 1st gets evicted
        for (i in 1..41) {
            assertFalse(dedup.isDuplicate("bot1", "msg$i"))
        }
        // msg1 was evicted
        assertFalse("msg1 should have been evicted", dedup.isDuplicate("bot1", "msg1"))
        // Checking msg1 adds it back and evicts msg2
        // msg3 should still be in buffer
        assertTrue("msg3 should still be in buffer", dedup.isDuplicate("bot1", "msg3"))
    }
}
