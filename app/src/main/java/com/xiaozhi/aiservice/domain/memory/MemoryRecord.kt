package com.xiaozhi.aiservice.domain.memory

/**
 * 一条本地记忆记录
 */
data class MemoryRecord(
    val id: Long = 0,
    val agentId: String = "",
    val role: String = "",          // "user" / "assistant"
    val text: String = "",
    val keywords: String = "",       // 逗号分隔的关键词
    val importance: Float = 0.5f,    // 0.0 ~ 1.0
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 搜索结果
 */
data class SearchResult(
    val record: MemoryRecord,
    val score: Float                 // 0.0 ~ 1.0 相似度
)
