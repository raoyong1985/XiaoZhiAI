package com.xiaozhi.aiservice.domain.memory

/**
 * 本地 RAG 记忆系统接口
 *
 * 实现可以基于关键词搜索或 ONNX 向量嵌入。
 * 所有方法应当线程安全。
 */
interface LocalRAGMemory {

    /** 是否已初始化（即使 ONNX 模型加载失败也应该返回 true，用关键词搜索兜底） */
    fun isInitialized(): Boolean

    /** 添加一条记忆 */
    fun addMemory(agentId: String, role: String, text: String, keywords: String = "")

    /**
     * 搜索与 query 最相关的记忆
     * @param topK 最多返回条数
     */
    fun search(agentId: String, query: String, topK: Int = 3): List<SearchResult>

    /**
     * 获取最近 N 条记忆，格式化为文本块
     * 用于追加到 LLM prompt 末尾
     */
    fun getRecentContext(agentId: String, limit: Int = 5): String

    /** 清除指定角色的所有记忆 */
    fun clear(agentId: String)

    /** 释放资源 */
    fun close()
}
