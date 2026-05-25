package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotContact

interface BotConnectionPoolRepository {
    fun getOrConnect(botId: String, contact: BotContact): BotConnection

    fun get(botId: String): BotConnection?

    val all: Map<String, BotConnection>

    val size: Int

    suspend fun ensureConnected(botId: String, contact: BotContact): Boolean

    fun isDead(botId: String): Boolean

    fun clearDead()

    fun abortAll(reason: String)

    fun disconnect(botId: String)

    fun disconnectAll()
}
