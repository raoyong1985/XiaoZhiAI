package com.xiaozhi.aiservice.domain.usecase.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadloopDetectorTest {

    @Test
    fun `three identical hints trigger deadloop`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        assertTrue(detector.check("hello")) // 3rd call triggers
    }

    @Test
    fun `different hints do not trigger deadloop`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("world"))
        assertFalse(detector.check("foo"))
        assertFalse(detector.check("hello")) // not 3 identical in a row
    }

    @Test
    fun `reset clears deadloop state`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        detector.reset()
        assertFalse(detector.check("hello")) // fresh start
        assertFalse(detector.check("hello"))
        assertTrue(detector.check("hello")) // 3rd call after reset
    }

    @Test
    fun `three identical speakers trigger deadloop`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("", speakerId = "bot1"))
        assertFalse(detector.check("", speakerId = "bot1"))
        assertTrue(detector.check("", speakerId = "bot1")) // 3rd call triggers
    }

    @Test
    fun `different speakers do not trigger`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 1)
        assertFalse(detector.check("", speakerId = "bot1"))
        assertFalse(detector.check("", speakerId = "bot2"))
        assertFalse(detector.check("", speakerId = "bot3"))
        assertFalse(detector.check("", speakerId = "bot1"))
    }

    @Test
    fun `maxCount higher requires multiple accumulations`() {
        val detector = DeadloopDetector(maxDeadloop = 3, maxCount = 2)
        // 3 identical hints: isDead=true, count=1 < threshold=2 → false
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        assertFalse(detector.check("hello"))
        // Hint queue still has 3 identical hints, so 4th call is also isDead=true
        // count=2, threshold=maxOf(1, 2-2/2)=1 → 2>=1 → true
        assertTrue(detector.check("hello"))
    }
}
