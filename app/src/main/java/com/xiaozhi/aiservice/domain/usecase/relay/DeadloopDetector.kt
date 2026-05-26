package com.xiaozhi.aiservice.domain.usecase.relay

/**
 * 死循环检测器 —— 检测 bot 接龙中的重复发言模式
 *
 * 检测策略（双维度）：
 * 1. hint 维度：连续 5 次 hint 完全相同 → 疑似死循环
 * 2. speaker 维度：连续 5 次候选 speaker 是同一个 bot → 疑似死循环
 * 3. 触发条件连续出现 ≥8 次才终止（调高减少误杀）
 *
 * 线程安全：全部操作在 synchronized(lock) 保护下
 */
class DeadloopDetector(
    private val maxDeadloop: Int = 5,
    private val maxCount: Int = 8
) {
    private val lock = Any()
    private val recentHints = mutableListOf<String>()
    private val recentCandidates = mutableListOf<String>()
    private var deadloopCount = 0

    /**
     * 检测是否为死循环
     *
     * @param hint 本轮 hint 文本（用于检测重复 hint）
     * @param speakerId 候选 speaker ID（用于检测重复 speaker）
     * @return true = 检测到死循环，调用者应终止本轮转发
     */
    fun check(hint: String, speakerId: String? = null): Boolean {
        synchronized(lock) {
            var isDead = false
            if (hint.isNotBlank()) {
                recentHints.add(hint)
                if (recentHints.size > maxDeadloop) recentHints.removeAt(0)
                // 要求 hint 全部相同（distinct == 1）才触发，排除"好的"+"嗯"交替的误杀
                if (recentHints.size >= maxDeadloop && recentHints.distinct().size == 1) isDead = true
            }
            if (speakerId != null) {
                recentCandidates.add(speakerId)
                if (recentCandidates.size > maxDeadloop) recentCandidates.removeAt(0)
                // 同一 bot 连续被选为候选 ≥5 次
                if (recentCandidates.size >= maxDeadloop && recentCandidates.distinct().size == 1) isDead = true
            }
            if (isDead) {
                deadloopCount++
                // 触发条件连续出现 ≥8 次才终止
                if (deadloopCount >= maxCount) {
                    deadloopCount = 0
                    recentHints.clear()
                    recentCandidates.clear()
                    return true
                }
            } else {
                deadloopCount = 0
            }
            return false
        }
    }

    /** 重置所有状态 */
    fun reset() {
        synchronized(lock) {
            deadloopCount = 0
            recentHints.clear()
            recentCandidates.clear()
        }
    }
}
