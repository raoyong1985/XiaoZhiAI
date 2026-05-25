package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.GroupMessage
import kotlinx.coroutines.flow.Flow

/**
 * 群聊数据仓库接口
 *
 * 功能：定义群聊 CRUD + 消息流操作的抽象层。
 *
 * 分层说明：
 * - 群聊元信息（名称、成员列表、输入模式）→ 按需读取，实时写入
 * - 群聊消息列表 → 通过 Flow 提供实时更新，内存缓存 + DataStore 后台落盘
 * - 接口调用方：GroupChatViewModel、GroupChatUseCase（将来拆分后）
 * - 实现方：GroupChatRepositoryImpl
 */
interface GroupChatRepository {

    // ── 群聊管理 ──

    /** 创建新群聊，返回带 UUID 的 GroupChat 对象 */
    suspend fun createGroup(name: String, botIds: List<String>): GroupChat

    /** 获取所有群聊列表 */
    suspend fun getGroups(): List<GroupChat>

    /** 根据 groupId 查找单个群聊，不存在时返回 null */
    suspend fun getGroup(groupId: String): GroupChat?

    /** 删除群聊（同时删除其所有消息记录） */
    suspend fun deleteGroup(groupId: String)

    /** 修改群聊名称 */
    suspend fun updateGroupName(groupId: String, newName: String)

    /** 切换输入模式（0=语音, 1=文字） */
    suspend fun updateGroupInputMode(groupId: String, inputMode: Int)

    /** 向群聊中添加一个新的智能体成员 */
    suspend fun addBotToGroup(groupId: String, botId: String)

    /** 从群聊中移除指定智能体成员 */
    suspend fun removeBotFromGroup(groupId: String, botId: String)

    /** 清空指定群聊的所有消息记录 */
    suspend fun clearGroupMessages(groupId: String)

    // ── 消息管理 ──

    /**
     * 获取指定群聊的消息流
     *
     * 返回一个 StateFlow「热流」：
     * - 首次订阅时从 DataStore 加载历史，后续实时增量推送
     * - UI 只需 collect，无需手动刷新
     */
    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>>

    /** 向群聊发送一条消息（写入内存 + 触发延时落盘） */
    suspend fun sendMessage(groupId: String, message: GroupMessage)

    /** sendMessage 的别名，语义更明确 */
    suspend fun addMessage(groupId: String, message: GroupMessage)

    /** 更新指定消息的文本内容（用于流式回答修正） */
    suspend fun updateMessageText(groupId: String, messageId: String, newText: String)
}
