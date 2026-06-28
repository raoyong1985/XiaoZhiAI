package com.xiaozhi.aiservice.data.memory

import android.content.Context
import com.xiaozhi.aiservice.domain.memory.LocalRAGMemory
import com.xiaozhi.aiservice.domain.memory.MemoryRecord
import com.xiaozhi.aiservice.domain.memory.SearchResult
import com.xiaozhi.aiservice.utils.Logger

/**
 * 本地 RAG 记忆实现
 *
 * 双层检索：
 * 1. ONNX 向量嵌入搜索（如果模型可用）
 * 2. SQL LIKE 关键词搜索（降级兜底）
 *
 * 用法：
 * ```kotlin
 * val rag = RAGMemoryImpl(context)
 * rag.addMemory("1", "assistant", "用户喜欢被叫哥哥", "称呼,偏好")
 * val results = rag.search("1", "哥哥喜欢什么")
 * val context = rag.getRecentContext("1")
 * ```
 */
class RAGMemoryImpl(context: Context) : LocalRAGMemory {

    companion object {
        private const val TAG = "RAGMemory"
    }

    private val db = MemoryDatabase(context)
    private val embedder = OnnxEmbedder(context)
    private var initialized = false

    init {
        embedder.init()
        initialized = true
        Logger.i(TAG, "本地 RAG 记忆初始化完成 (ONNX=${embedder.isAvailable()})")
    }

    override fun isInitialized(): Boolean = initialized

    // ── 写入 ──────────────────────────────────────────────

    override fun addMemory(agentId: String, role: String, text: String, keywords: String) {
        if (text.isBlank()) return

        try {
            // 提取关键词
            val extractedKeywords = if (keywords.isBlank()) extractKeywords(text) else keywords
            val record = MemoryRecord(
                agentId = agentId,
                role = role,
                text = text,
                keywords = extractedKeywords,
                importance = if (role == "user") 0.6f else 0.5f // 用户消息权重更高
            )

            // 尝试计算嵌入向量
            var embedding: FloatArray? = null
            if (embedder.isAvailable()) {
                embedding = embedder.embed(text)
            }

            db.insert(record, embedding)
        } catch (e: Exception) {
            Logger.w(TAG, "添加记忆失败: ${e.message}")
        }
    }

    // ── 搜索 ──────────────────────────────────────────────

    override fun search(agentId: String, query: String, topK: Int): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        try {
            // 优先向量搜索
            if (embedder.isAvailable()) {
                val queryVec = embedder.embed(query)
                if (queryVec != null) {
                    val vecResults = db.searchByVector(agentId, queryVec, topK)
                    if (vecResults.isNotEmpty()) {
                        return vecResults
                    }
                }
            }

            // 降级：关键词搜索
            val keywordResults = db.searchByKeyword(agentId, query, topK)
            return keywordResults.map {
                SearchResult(it, 0.5f)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "搜索记忆失败: ${e.message}")
            return emptyList()
        }
    }

    // ── 上下文 ──────────────────────────────────────────────

    override fun getRecentContext(agentId: String, limit: Int): String {
        try {
            val recent = db.getRecent(agentId, limit)
            if (recent.isEmpty()) return ""

            val sb = StringBuilder()
            sb.appendLine("=== 最近记忆 ===")
            for (r in recent) {
                val label = if (r.role == "user") "你" else "我"
                sb.appendLine("- [$label] ${r.text}")
            }
            val result = sb.toString()
            Logger.i(TAG, "获取最近上下文: ${recent.size} 条, 共 ${result.length} 字符")
            return result
        } catch (e: Exception) {
            Logger.w(TAG, "获取上下文失败: ${e.message}")
            return ""
        }
    }

    // ── 管理 ──────────────────────────────────────────────

    override fun clear(agentId: String) {
        try {
            db.deleteByAgent(agentId)
            Logger.i(TAG, "已清除 agent=$agentId 的所有记忆")
        } catch (e: Exception) {
            Logger.w(TAG, "清除记忆失败: ${e.message}")
        }
    }

    override fun close() {
        try {
            embedder.close()
            db.close()
        } catch (_: Exception) {}
    }

    // ── 工具 ──────────────────────────────────────────────

    /**
     * 从文本中提取关键词
     * 简单实现：取高频词、特殊名词
     */
    private fun extractKeywords(text: String): String {
        // 提取中文词（2~6字）和英文词（≥3字母）
        val words = mutableSetOf<String>()
        // 中文双字词
        val re = Regex("[\u4e00-\u9fff]{2,6}")
        for (match in re.findAll(text)) {
            val word = match.value
            if (word.length in 2..6) {
                words.add(word)
            }
        }
        // 英文词
        val enRe = Regex("[a-zA-Z]{3,}")
        for (match in enRe.findAll(text)) {
            words.add(match.value.lowercase())
        }
        return words.take(8).joinToString(",")
    }
}
