package com.xiaozhi.aiservice.data.remote

import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.utils.Logger
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BotConnectionPool"

/**
 * Bot WebSocket 连接池：管理群聊中多个 bot 的长连接。
 *
 * 职责：
 * - 创建/缓存 XiaoZhiBackendService 实例
 * - 连接/断开/重连
 * - 死 bot 标记与清理
 * - 批量操作（断开全部、发送 abort）
 *
 * 不负责：接龙逻辑、音频播放、UI 状态 —— 这些由 GroupChatViewModel/RelayEngine 管理。
 */
class BotConnectionPool(private val gson: Gson) {

    private val connections = ConcurrentHashMap<String, XiaoZhiBackendService>()
    private val deadBotIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 获取或创建并连接指定 bot。
     */
    fun getOrConnect(botId: String, contact: BotContact): XiaoZhiBackendService {
        return connections.getOrPut(botId) {
            XiaoZhiBackendService(gson).also { service ->
                service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
                Logger.i(TAG, "连接 bot[$botId] (${contact.name})")
            }
        }
    }

    /**
     * 获取已有连接，不存在返回 null。
     */
    fun get(botId: String): XiaoZhiBackendService? = connections[botId]

    /** 所有连接（只读快照） */
    val all: Map<String, XiaoZhiBackendService> get() = connections.toMap()

    /** 连接数 */
    val size: Int get() = connections.size

    /**
     * 确保 bot 已连接，未连接/断线时尝试重连。
     * @return 是否处于已连接状态
     */
    suspend fun ensureConnected(botId: String, contact: BotContact): Boolean {
        if (deadBotIds.contains(botId)) return false
        val service = connections[botId]
        if (service != null && service.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) {
            return true
        }
        if (service != null && service.connectionState.value is XiaoZhiBackendService.ConnectionState.Error) {
            Logger.w(TAG, "bot[$botId] 连接失败(Error)，标记死亡")
            deadBotIds.add(botId)
            return false
        }
        Logger.w(TAG, "重新连接: $botId")
        // 覆盖前断开旧连接，防止泄漏
        connections[botId]?.disconnect()
        val newService = XiaoZhiBackendService(gson)
        connections[botId] = newService
        newService.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
        return withTimeoutOrNull(5000) {
            newService.connectionState.first { it is XiaoZhiBackendService.ConnectionState.Connected }
            true
        } ?: false
    }

    /** 标记 bot 为死亡（不再参与接龙） */
    fun markDead(botId: String) {
        deadBotIds.add(botId)
    }

    /** 清除所有死亡标记 */
    fun clearDead() {
        deadBotIds.clear()
    }

    fun isDead(botId: String): Boolean = deadBotIds.contains(botId)

    /** 向所有 bot 发送 abort */
    fun abortAll(reason: String) {
        connections.values.forEach { svc ->
            try { svc.sendAbort(reason) } catch (_: Exception) {}
        }
    }

    /** 断开指定 bot */
    fun disconnect(botId: String) {
        connections.remove(botId)?.disconnect()
    }

    /** 断开所有 bot */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        deadBotIds.clear()
    }
}
