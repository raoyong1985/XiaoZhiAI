package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.model.BotContact

/**
 * Bot WebSocket 连接池抽象
 *
 * 管理群聊场景下多个 bot 的 WebSocket 长连接。
 * 实现层负责连接创建/缓存/重连/死亡标记。
 */
interface BotConnectionPoolRepository {
    /** 获取或创建并连接指定 bot */
    fun getOrConnect(botId: String, contact: BotContact): XiaoZhiBackendService

    /** 获取已有连接（不存在返回 null） */
    fun get(botId: String): XiaoZhiBackendService?

    /** 所有连接实例的快照 */
    val all: Map<String, XiaoZhiBackendService>

    /** 当前连接数 */
    val size: Int

    /** 确保 bot 已连接，未连接时自动重连 */
    suspend fun ensureConnected(botId: String, contact: BotContact): Boolean

    /** 查询 bot 是否已被标记为死亡 */
    fun isDead(botId: String): Boolean

    /** 清除所有死亡标记 */
    fun clearDead()

    /** 向所有已连接的 bot 发送 abort 信号 */
    fun abortAll(reason: String)

    /** 断开指定 bot 的连接并清理缓存 */
    fun disconnect(botId: String)

    /** 断开所有 bot 连接，清理全部状态 */
    fun disconnectAll()
}
