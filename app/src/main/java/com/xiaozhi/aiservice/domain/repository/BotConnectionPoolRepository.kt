package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotContact
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BotConnectionPoolRepository {
    fun getOrConnect(botId: String, contact: BotContact): BotConnection

    fun get(botId: String): BotConnection?

    val all: Map<String, BotConnection>

    /**
     * 池内 botId 集合的变化流。
     * 连接状态聚合必须跟着它走：只在进入界面时取一次 all 快照，
     * 后面异步连上的 bot 不在快照里，地球图标就会一直显示错误颜色。
     */
    val membership: StateFlow<List<String>>

    val size: Int

    suspend fun ensureConnected(botId: String, contact: BotContact): Boolean

    /** service 被重建时通知（如 Error 断线后重新创建连接） */
    val serviceReplaced: SharedFlow<Pair<String, BotConnection>>

    fun isDead(botId: String): Boolean

    fun clearDead()

    fun abortAll(reason: String)

    fun disconnect(botId: String)

    fun disconnectAll()
}
