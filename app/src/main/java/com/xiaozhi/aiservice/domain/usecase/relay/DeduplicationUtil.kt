package com.xiaozhi.aiservice.domain.usecase.relay

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 消息去重工具
 *
 * 存储 botId|content 的 key，确保同一 bot 不会发送完全相同的内容两次。
 * 使用 ConcurrentLinkedDeque，最多保留 MAX_RECENT_REPLIES（40）条。
 *
 * 为什么需要去重？
 * 接龙时偶尔会遇到 bot 重复返回同一句话（服务端 bug），
 * 不处理会导致 UI 大量重复消息 + 死循环。
 */
class DeduplicationUtil(
    private val maxRecentReplies: Int = 40
) {
    private val recentReplyKeys = ConcurrentLinkedDeque<String>()

    /**
     * 检测是否为重复消息
     *
     * @param botId 发送消息的 bot ID
     * @param content 消息内容
     * @return true=已存在相同消息，应跳过
     */
    fun isDuplicate(botId: String, content: String): Boolean {
        val key = "$botId|$content"
        synchronized(recentReplyKeys) {
            if (recentReplyKeys.contains(key)) return true
            recentReplyKeys.addFirst(key)
            if (recentReplyKeys.size > maxRecentReplies) recentReplyKeys.removeLast()
        }
        return false
    }

    /** 清空所有去重记录 */
    fun clear() {
        synchronized(recentReplyKeys) { recentReplyKeys.clear() }
    }
}
