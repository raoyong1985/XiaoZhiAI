package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.PlatformLogger
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.RelayState

/**
 * Speaker 评分器 —— 为接龙选择下一个发言的 bot
 *
 * 评分机制（分数越高越优先）：
 * +100  @提及（如"@小明"）
 * +30   名字匹配（回复中包含 bot 名称）
 * +20   本轮尚未发言
 * +0~15 随机抖动（避免完全确定性选择）
 * -20×N 重复轮转惩罚（同一 from→to ≥2 次）
 *
 * 特殊情况：无强信号且分数扁平时 → fallback 到 round_robin
 */
class SpeakerScorer(
    private val isDead: (String) -> Boolean,
    private val getContacts: () -> List<BotContact>,
    private val logger: PlatformLogger = PlatformLogger.EMPTY,
) {
    private val tag = "SpeakerScorer"

    /**
     * 计算下一候选 speaker
     *
     * @param prevIndex 上一位发言 bot 的索引
     * @param previousReply 上一位 bot 的回复文本
     * @param botIds 当前 bot 列表
     * @param rs 当前 RelayState
     * @return Pair(排序后的候选列表, 更新后的 RelayState)
     */
    fun resolveCandidates(
        prevIndex: Int, previousReply: String, botIds: List<String>, rs: RelayState
    ): Pair<List<Pair<Int, String>>, RelayState> {
        if (botIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        val aliveBotIds = botIds.filter { !isDead(it) }
        if (aliveBotIds.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        val prevBotId = botIds.getOrNull(prevIndex)
        val compactedPrevIndex = if (prevBotId != null) aliveBotIds.indexOf(prevBotId) else -1
        val compactedSpoken = rs.spokenInThisRound.mapNotNull { i ->
            botIds.getOrNull(i)?.let { aliveBotIds.indexOf(it) }
        }.filter { it >= 0 }.toSet()

        data class Candidate(val index: Int, val id: String, var score: Float)
        val candidates = aliveBotIds.indices
            .filter { it != compactedPrevIndex }
            .map { Candidate(it, aliveBotIds[it], 0f) }
        if (candidates.isEmpty()) return emptyList<Pair<Int, String>>() to rs

        val contacts = getContacts()
        var hasSignal = false

        if (previousReply.isNotBlank()) {
            val mentionMatch = Regex("@([\\w\\u4e00-\\u9fff]+)").find(previousReply)
            if (mentionMatch != null) {
                val mentionedName = mentionMatch.groupValues[1]
                candidates.forEach { c ->
                    val contact = contacts.find { it.id == c.id }
                    if (contact != null && (contact.name == mentionedName || contact.id.startsWith(mentionedName))) {
                        c.score += 100f
                        hasSignal = true
                    }
                }
            }
            candidates.forEach { c ->
                val contact = contacts.find { it.id == c.id }
                if (contact != null && contact.name.isNotBlank() && previousReply.contains(contact.name)) {
                    c.score += 30f
                    hasSignal = true
                }
            }
        }

        val transitionPenalty = 20f
        if (prevBotId != null) {
            val recentTransitions = rs.transitionHistory
            candidates.forEach { c ->
                val count = recentTransitions.count { it.first == prevBotId && it.second == c.id }
                if (count >= 2) {
                    c.score -= transitionPenalty * count
                    logger.w(tag, "惩罚 [$prevBotId→${c.id}] 出现${count}次, score=${c.score}")
                }
            }
        }

        candidates.forEach { c ->
            if (c.index !in compactedSpoken) c.score += 20f
        }

        val useRoundRobin = !hasSignal && prevBotId != null
        if (useRoundRobin) {
            val aliveIdx = aliveBotIds.indexOf(prevBotId)
            if (aliveIdx >= 0) {
                for (offset in 1..aliveBotIds.size) {
                    val nextIdx = (aliveIdx + offset) % aliveBotIds.size
                    val nextId = aliveBotIds[nextIdx]
                    val origIdx = botIds.indexOf(nextId)
                    val result = listOf(origIdx to nextId)
                    val newRs = rs.copy(
                        botIds = aliveBotIds,
                        spokenInThisRound = compactedSpoken,
                        spokenRoundCounter = rs.spokenRoundCounter
                    )
                    logger.i(tag, "选人: round_robin [$prevBotId→$nextId] (无强信号)")
                    return result to newRs
                }
            }
        }

        candidates.forEach { c -> c.score += (Math.random().toFloat() * 15f) }

        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.score }
            .thenBy { Math.random() })

        val allSpoken = sorted.all { it.index in compactedSpoken }
        val forceReset = rs.spokenRoundCounter >= maxOf(aliveBotIds.size * 2, 4)
        val newRs = if (allSpoken || forceReset) {
            rs.copy(
                botIds = aliveBotIds,
                spokenInThisRound = emptySet(),
                spokenRoundCounter = 0
            )
        } else {
            rs.copy(
                botIds = aliveBotIds,
                spokenInThisRound = compactedSpoken
            )
        }

        return sorted.map { it.index to it.id } to newRs
    }
}
