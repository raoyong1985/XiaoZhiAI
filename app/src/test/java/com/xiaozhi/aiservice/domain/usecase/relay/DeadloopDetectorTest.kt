package com.xiaozhi.aiservice.domain.usecase.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadloopDetectorTest {

    @Test
    fun `same hint repeated triggers deadloop`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        // First 3 calls: accumulating, not dead
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        // 4th call with same hint + same speaker: triggers deadloop
        assertTrue(detector.check("hello", "bot1"))
    }

    @Test
    fun `different hints do not trigger deadloop`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("world"))
        assertFalse(detector.check("foo"))
        assertFalse(detector.check("hello", "bot1"))
    }

    @Test
    fun `reset clears deadloop state`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        detector.reset()
        assertFalse(detector.check("hello", "bot1"))
    }
}
