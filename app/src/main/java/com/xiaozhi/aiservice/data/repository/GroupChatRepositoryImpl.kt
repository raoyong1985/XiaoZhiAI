package com.xiaozhi.aiservice.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import com.xiaozhi.aiservice.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GroupChatRepo"

/**
 * GroupChatRepository 的实现
 *
 * 存储方案：Jetpack DataStore（Preferences 类型），通过 Gson JSON 序列化。
 * 为什么不用 Room？群聊元信息和消息量不大，DataStore 零配置，启动即用。
 *
 * 写入策略：
 * - 群聊元信息（名称/成员等）：每次修改直接写 DataStore（低频操作，无压力）
 * - 群聊消息：先写内存 MutableStateFlow（实时推送到 UI），
 *   再通过「dirty 标记 + 500ms 防抖」批量写磁盘。
 *   高频写入场景（接龙时多个 bot 消息涌入）不会频繁触发磁盘 I/O。
 *
 * 数据分区：
 * - group_chats DataStore：存储所有群聊的元信息列表（同一 key）
 * - group_messages DataStore：每个群聊的消息独立存储，key = "messages_{groupId}"
 */
@Singleton
class GroupChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : GroupChatRepository {

    /**
     * IO 协程作用域
     * - SupervisorJob：子协程失败不影响其他协程（flush 失败不污染新消息写入）
     * - Dispatchers.IO：所有磁盘操作在 IO 线程执行
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 两个 DataStore 实例 ──
    // group_chats：存储群聊元信息列表
    private val Context.groupDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_chats")
    // group_messages：存储各群聊的消息列表（按 groupId 分区）
    private val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_messages")

    // ── 内存缓存 + 批量写入 ──

    /** 消息缓存：groupId → MutableStateFlow（内存中的实时消息列表） */
    private val messagesCache = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()

    /**
     * dirty 标记集合
     * - 元素：已修改但尚未写盘的 groupId
     * - 特点：LinkedHashSet 保证插入顺序 + 去重
     * - 配合 flushJob 实现防抖批量写入
     */
    private val dirtyGroups = LinkedHashSet<String>()

    /** 当前正在排队的 flush 任务（null=无待写数据） */
    private var flushJob: Job? = null

    /** 已从磁盘加载过的 groupId 集合，防止重复加载 */
    private val loadedFromDisk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    companion object {
        /** DataStore 中存储群聊列表的 key */
        private val GROUPS_KEY = stringPreferencesKey("groups")
    }

    /** 获取存储指定群聊消息的 DataStore key */
    private fun messagesKey(groupId: String) = stringPreferencesKey("messages_$groupId")

    // ═══════════════════════════════════════════
    // 群聊 CRUD（直接写 DataStore）
    // ═══════════════════════════════════════════

    /**
     * 创建群聊
     * 1. 生成 UUID 作为群聊 ID
     * 2. 读取 DataStore 中的群聊列表，追加新群聊后写回
     */
    override suspend fun createGroup(name: String, botIds: List<String>): GroupChat {
        val group = GroupChat(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            botIds = botIds
        )

        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            mutableGroups.add(group)
            preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
        }

        return group
    }

    /**
     * 获取所有群聊列表
     * 直接从 DataStore 读取（数据量小，无缓存必要）
     */
    override suspend fun getGroups(): List<GroupChat> {
        return try {
            context.groupDataStore.data.map { preferences ->
                val json = preferences[GROUPS_KEY] ?: "[]"
                val type = object : TypeToken<List<GroupChat>>() {}.type
                gson.fromJson<List<GroupChat>>(json, type) ?: emptyList()
            }.first()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取单个群聊（遍历列表查找） */
    override suspend fun getGroup(groupId: String): GroupChat? {
        return getGroups().find { it.id == groupId }
    }

    /**
     * 删除群聊
     * 1. 从群聊列表中移除该群聊
     * 2. 删除该群聊对应的消息数据
     * 3. 清除内存缓存
     */
    override suspend fun deleteGroup(groupId: String) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            mutableGroups.removeAll { it.id == groupId }
            preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
        }

        // 同时删除消息数据
        context.messagesDataStore.edit { preferences ->
            preferences.remove(messagesKey(groupId))
        }
        messagesCache.remove(groupId)
    }

    /** 更新群聊名称 */
    override suspend fun updateGroupName(groupId: String, newName: String) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            val index = mutableGroups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                mutableGroups[index] = mutableGroups[index].copy(name = newName)
                preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
            }
        }
    }

    /** 更新群聊输入模式（0=语音，1=文字） */
    override suspend fun updateGroupInputMode(groupId: String, inputMode: Int) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            val index = mutableGroups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                mutableGroups[index] = mutableGroups[index].copy(inputMode = inputMode)
                preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
            }
        }
    }

    /** 向群聊中添加智能体成员（去重） */
    override suspend fun addBotToGroup(groupId: String, botId: String) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            val index = mutableGroups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                val group = mutableGroups[index]
                if (!group.botIds.contains(botId)) {
                    mutableGroups[index] = group.copy(botIds = group.botIds + botId)
                    preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
                }
            }
        }
    }

    /** 从群聊中移除智能体成员 */
    override suspend fun removeBotFromGroup(groupId: String, botId: String) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            val index = mutableGroups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                val group = mutableGroups[index]
                mutableGroups[index] = group.copy(botIds = group.botIds - botId)
                preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
            }
        }
    }

    // ═══════════════════════════════════════════
    // 消息缓存 + 批量写入
    // ═══════════════════════════════════════════

    /**
     * 获取或创建指定群聊的消息内存缓存 Flow
     *
     * 每个 groupId 对应一个独立的 MutableStateFlow，
     * ViewModel 通过它实时接收消息变更通知。
     */
    private fun getOrCreateFlow(groupId: String): MutableStateFlow<List<ChatMessage>> {
        return messagesCache.getOrPut(groupId) {
            MutableStateFlow(emptyList())
        }
    }

    /**
     * 标记指定群聊为「脏数据」+ 触发防抖写盘
     *
     * 防抖机制：
     * 1. 首个 dirty 标记 → 启动 flushJob（延迟 500ms）
     * 2. 500ms 内再有新 dirty → 合并到同一批
     * 3. 500ms 到 → 执行 flushDirtyInternal()
     * 4. flush 完成后若新 dirty 已在排队 → 再调度一轮
     *
     * 目的：接龙场景下多条消息瞬间涌入，只触发一次磁盘写入。
     */
    private fun markDirty(groupId: String) {
        val needFlush: Boolean
        synchronized(dirtyGroups) {
            needFlush = dirtyGroups.isEmpty()
            dirtyGroups.add(groupId)
        }
        if (needFlush) {
            flushJob = scope.launch {
                delay(500)
                flushDirtyInternal()
            }
        }
    }

    /**
     * 批量写盘（synchronized 保护下安全执行）
     *
     * 流程：
     * 1. 原子地取出所有 dirty groupId 并清空 dirtyGroups
     * 2. 逐个调用 writeToDisk 写盘
     * 3. 检查在写盘期间是否又有新标记，有则再调度一轮
     */
    private suspend fun flushDirtyInternal() {
        val ids: List<String>
        synchronized(dirtyGroups) {
            ids = dirtyGroups.toList()
            dirtyGroups.clear()
        }
        for (groupId in ids) {
            writeToDisk(groupId, getOrCreateFlow(groupId).value)
        }
        synchronized(dirtyGroups) {
            flushJob = null
            // 持久化期间又有新消息到来，继续调度下一次 flush
            if (dirtyGroups.isNotEmpty()) {
                flushJob = scope.launch {
                    delay(500)
                    flushDirtyInternal()
                }
            }
        }
    }

    /** 将指定群聊的消息列表序列化为 JSON 写入 DataStore */
    private suspend fun writeToDisk(groupId: String, messages: List<ChatMessage>) {
        try {
            context.messagesDataStore.edit { preferences ->
                preferences[messagesKey(groupId)] = gson.toJson(messages)
            }
        } catch (e: Exception) { Logger.w(TAG, "writeToDisk failed: ${e.message}") }
    }

    // ═══════════════════════════════════════════
    // 消息操作
    // ═══════════════════════════════════════════

    /**
     * 清空指定群聊的消息
     * - 同步清空内存缓存和磁盘
     * - 不清除群聊本身，仅清消息
     */
    override suspend fun clearGroupMessages(groupId: String) {
        getOrCreateFlow(groupId).value = emptyList()
        writeToDisk(groupId, emptyList())
    }

    /**
     * 获取指定群聊的消息 Flow
     *
     * 关键设计：延迟加载（lazy load from disk）
     * - 首次调用时检查 loadedFromDisk，若从未加载过，
     *   启动一个 IO 协程从 DataStore 读取历史消息写入内存 Flow
     * - 仅在 flow.value 为空时才写入（防止并发写入期间覆盖新消息）
     * - 后续调用直接返回已有 Flow（不二次加载）
     */
    override fun getGroupMessages(groupId: String): Flow<List<ChatMessage>> {
        val flow = getOrCreateFlow(groupId)
        Logger.i("GroupChatRepo", "getGroupMessages groupId=$groupId currentSize=${flow.value.size}")
        if (loadedFromDisk.putIfAbsent(groupId, true) == null) {
            scope.launch {
                val stored = loadMessagesFromDisk(groupId)
                Logger.i("GroupChatRepo", "disk load groupId=$groupId loaded=${stored.size} currentMem=${flow.value.size}")
                // 仅在内存仍为空时才写入，防止覆盖加载期间到达的新消息
                if (stored.isNotEmpty() && flow.value.isEmpty()) {
                    flow.value = stored
                    Logger.i("GroupChatRepo", "disk merge groupId=$groupId finalSize=${flow.value.size}")
                }
            }
        }
        return flow.asStateFlow()
    }

    /** 从 DataStore 加载指定群聊的历史消息 */
    private suspend fun loadMessagesFromDisk(groupId: String): List<ChatMessage> {
        return try {
            val preferences = context.messagesDataStore.data.first()
            val json = preferences[messagesKey(groupId)] ?: "[]"
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            val messages: List<ChatMessage> = gson.fromJson(json, type) ?: emptyList()
            // 安全处理：Gson 反序列化可能把 Kotlin 非空字段设为 null
            messages.map { msg ->
                msg.copy(
                    content = msg.content,
                    botName = msg.botName,
                    id = msg.id,
                    botId = msg.botId
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 发送一条群聊消息
     * 1. 追加到内存 Flow（UI 立即更新）
     * 2. 标记 dirty（触发防抖写盘）
     */
    override suspend fun sendMessage(groupId: String, message: ChatMessage) {
        val flow = getOrCreateFlow(groupId)
        flow.value = flow.value + message
        Logger.i("GroupChatRepo", "sendMessage groupId=$groupId msgId=${message.id} content='${message.content.take(20)}' dir=${message.direction} total=${flow.value.size}")
        markDirty(groupId)
    }

    /** sendMessage 的别名 */
    override suspend fun addMessage(groupId: String, message: ChatMessage) {
        sendMessage(groupId, message)
    }

    /**
     * 更新指定消息的文本内容
     *
     * 用途：AI 流式回答过程中，初始文本可能不全，
     * 后续收到完整文本后调用此方法修正。
     * 使用 map 遍历替换匹配的消息，不生硬。
     */
    override suspend fun updateMessageText(groupId: String, messageId: String, newText: String) {
        val flow = getOrCreateFlow(groupId)
        flow.value = flow.value.map { msg ->
            if (msg.id == messageId) msg.copy(content = newText) else msg
        }
        markDirty(groupId)
    }
}
