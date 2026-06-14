package com.xiaozhi.aiservice.data.remote

import com.xiaozhi.aiservice.domain.model.BotConnection
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import com.xiaozhi.aiservice.utils.Logger
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BotConnectionPool"

/**
 * Bot WebSocket 连接池实现
 *
 * 管理群聊场景下多个智能体（bot）的 WebSocket 长连接。
 */

@Singleton
class BotConnectionPoolImpl @Inject constructor(
    private val gson: Gson,
    private val mcpToolRegistry: MCPToolRegistry,
    private val audioService: AudioService
) : BotConnectionPoolRepository {

    /** botId → BotConnection（WebSocket 连接实例） */
    private val connections = ConcurrentHashMap<String, BotConnection>()

    /**
     * 已标记为「死亡」的 bot 集合
     * 死亡原因：WebSocket 连接无法恢复（Error 状态）
     * 死亡后的行为：所有涉及该 bot 的接龙操作跳过
     * 恢复方式：clearDead() 手动清理（每 2 分钟自动清理一次）
     */
    private val deadBotIds = ConcurrentHashMap.newKeySet<String>()

    /** service 被重建时的事件流 */
    private val _serviceReplaced = MutableSharedFlow<Pair<String, BotConnection>>(extraBufferCapacity = 4)
    override val serviceReplaced: SharedFlow<Pair<String, BotConnection>> = _serviceReplaced.asSharedFlow()

    // ── 连接管理 ──

    /**
     * 获取或创建并连接指定 bot
     *
     * 如果 bot 已有连接实例，直接返回；
     * 否则创建新的 XiaoZhiBackendService 并调用 connect()。
     *
     * @param botId 智能体唯一标识
     * @param contact 包含 serverUrl/otaUrl/deviceId/clientId 的连接配置
     * @return XiaoZhiBackendService 实例（可能尚未完全 connected）
     */
    override fun getOrConnect(botId: String, contact: BotContact): BotConnection {
        return connections.getOrPut(botId) {
            XiaoZhiBackendService(gson, mcpToolRegistry, audioService).also { service ->
                service.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
                Logger.i(TAG, "连接 bot[$botId] (${contact.name})")
            }
        }
    }

    /** 获取已有连接（不存在返回 null，不触发新建） */
    override fun get(botId: String): BotConnection? = connections[botId]

    /** 所有连接实例的快照（只读） */
    override val all: Map<String, BotConnection> get() = connections.toMap()

    /** 当前连接数 */
    override val size: Int get() = connections.size

    /**
     * 确保 bot 已连接，未连接/断线时尝试自动重连
     *
     * 重连策略：
     * 1. 死 bot 直接返回 false
     * 2. 已有连接且状态 Connected → 直接 true
     * 3. 已有连接但状态 Error → 标记死，返回 false
     * 4. 无连接或断线 → 断开旧连接，创建新连接，等待 5s 内连上
     *
     * 重建段用 `synchronized(connections)` 保护，并发调用时第二次会等第一次
     * 写回 map 后 recheck state——若期间新 service 已 Connected 则直接复用。
     *
     * @return 重连后是否处于 Connected 状态
     */
    override suspend fun ensureConnected(botId: String, contact: BotContact): Boolean {
        if (deadBotIds.contains(botId)) return false

        // ── 快路径（无锁）：已连/正连 → 直接返回 ──
        val current = connections[botId]
        if (current != null && current.connectionState.value is BotConnectionState.Connected) {
            return true
        }
        if (current != null && current.connectionState.value is BotConnectionState.Connecting) {
            Logger.i(TAG, "bot[$botId] 正在连接中，等待完成")
            return withTimeoutOrNull(5000) {
                current.connectionState.first { it is BotConnectionState.Connected }
                true
            } ?: false
        }

        // ── 慢路径：重建连接 ──
        // 防止两个并发 ensureConnected(botId) 互相覆盖 newService
        // synchronized 块只能包含非 suspend 操作（Kotlin 限制），所以
        // 「拿锁 + 销毁旧 + 写新 + 启动 connect」放锁内；5s 等待放锁外。
        val newService: XiaoZhiBackendService
        synchronized(connections) {
            // 拿锁后 recheck：可能另一个线程刚刚连上
            val rechecked = connections[botId]
            if (rechecked != null && rechecked.connectionState.value is BotConnectionState.Connected) {
                return true
            }
            Logger.w(TAG, "重新连接: $botId (state=${rechecked?.connectionState?.value})")
            // 旧 service 即将被丢弃，destroy() 取消其 supervisorJob 防止协程泄漏
            // （disconnect() 故意保留 scope 以支持重连，池里被替换的 service 不再需要重连）
            rechecked?.destroy()
            newService = XiaoZhiBackendService(gson, mcpToolRegistry, audioService)
            connections[botId] = newService
            newService.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId)
        }
        val result = withTimeoutOrNull(5000) {
            newService.connectionState.first { it is BotConnectionState.Connected }
            true
        } ?: false
        if (result) {
            _serviceReplaced.tryEmit(botId to newService)
        } else {
            // 5s 内未连上 → 标记死亡（与 KDoc L85 "Error → 标记死" 对齐）
            // SpeakerScorer.resolveCandidates 会过滤掉死 bot，避免 relay 空转
            // 看门狗每 2 分钟会 clearDead()，允许 bot 网络恢复后重新接入
            markDead(botId)
            Logger.w(TAG, "bot[$botId] 5s 未连上，标记死亡")
        }
        return result
    }

    // ── 死亡标记管理 ──

    /** 标记 bot 死亡（不再参与接龙） */
    fun markDead(botId: String) {
        deadBotIds.add(botId)
    }

    /** 清除所有死亡标记（看门狗每 2 分钟调用一次） */
    override fun clearDead() {
        deadBotIds.clear()
    }

    /** 查询 bot 是否已被标记为死亡 */
    override fun isDead(botId: String): Boolean = deadBotIds.contains(botId)

    // ── 批量操作 ──

    /**
     * 向所有已连接的 bot 发送 abort 信号
     *
     * 触发场景：用户开始录音（中断所有 bot 的回复）、用户主动停止
     * 每个 bot 独立 try-catch，某个 bot 失败不影响其他 bot
     */
    override fun abortAll(reason: String) {
        connections.values.forEach { svc ->
            try { svc.sendAbort(reason) } catch (e: Exception) { Logger.w(TAG, "abortAll bot failed: ${e.message}") }
        }
    }

    /** 断开指定 bot 的连接并清理缓存 */
    override fun disconnect(botId: String) {
        // 池里移除的 service 不会复用，destroy() 取消其 scope 防止协程泄漏
        connections.remove(botId)?.destroy()
    }

    /** 断开所有 bot 连接，清理全部状态 */
    override fun disconnectAll() {
        connections.values.forEach { it.destroy() }
        connections.clear()
        deadBotIds.clear()
    }
}
