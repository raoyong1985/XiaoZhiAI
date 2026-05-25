package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotContact
import kotlinx.coroutines.flow.SharedFlow

interface BotConnectionPoolRepository {
    fun getOrConnect(botId: String, contact: BotContact): BotConnection

    fun get(botId: String): BotConnection?

    val all: Map<String, BotConnection>

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
