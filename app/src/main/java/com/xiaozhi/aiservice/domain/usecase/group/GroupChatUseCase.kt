package com.xiaozhi.aiservice.domain.usecase.group

import com.xiaozhi.aiservice.domain.PlatformLogger
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import com.xiaozhi.aiservice.domain.usecase.relay.DeduplicationUtil
import com.xiaozhi.aiservice.domain.usecase.relay.DeadloopDetector
import com.xiaozhi.aiservice.domain.model.RelayState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊 CRUD UseCase
 *
 * 封装群聊管理操作：创建/删除、消息收发、成员管理等。
 * 从 GroupChatViewModel 抽出，保持行为一致。
 */
class GroupChatUseCase(
    private val groupChatRepository: GroupChatRepository,
    private val botConnectionPool: BotConnectionPoolRepository,
    private val playbackManager: AudioPlaybackManager,
    private val relayState: MutableStateFlow<RelayState>,
    private val audioForwardBuffer: ConcurrentHashMap<String, MutableList<ByteArray>>,
    private val deduplicationUtil: DeduplicationUtil,
    private val deadloopDetector: DeadloopDetector,
    private val botObserverJobs: ConcurrentHashMap<String, Job>,
    private val getContacts: () -> List<BotContact>,
    private val onGroupsChanged: () -> Unit,
    private val logger: PlatformLogger = PlatformLogger.EMPTY,
    private val tag: String = "GroupChatVM"
) {

    /** 创建群聊 */
    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        val group = groupChatRepository.createGroup(name, selectedBotIds)
        onGroupsChanged()
        return group
    }

    /** 删除群聊（同时断开所有连接） */
    suspend fun deleteGroup(groupId: String) {
        groupChatRepository.deleteGroup(groupId)
        botConnectionPool.disconnectAll()
        botObserverJobs.values.forEach { it.cancel() }
        botObserverJobs.clear()
        playbackManager.releaseAll()
        onGroupsChanged()
    }

    /** 获取指定群聊的消息流 */
    fun getGroupMessages(groupId: String): Flow<List<ChatMessage>> {
        return groupChatRepository.getGroupMessages(groupId)
    }

    /**
     * 发送文字消息到群聊
     *
     * 触发接龙流程：用户文字 → 首 bot 处理 → 自动转发到下一 bot
     */
    suspend fun sendMessageToGroup(groupId: String, content: String) {
        val userMessage = ChatMessage(
            id = java.util.UUID.randomUUID().toString(), groupId = groupId,
            botId = "", botName = "我", content = content,
            type = MessageType.TEXT, direction = MessageDirection.SENT
        )
        groupChatRepository.addMessage(groupId, userMessage)

        relayState.update { it.nextSession().copy(
            forwardRound = 0, currentSpeakerIndex = 0, waitingSpeakerIndex = 0,
            originalUserQuery = content
        ) }
        audioForwardBuffer.clear()
        deduplicationUtil.clear()
        deadloopDetector.reset()

        if (relayState.value.botIds.isNotEmpty()) {
            val firstBotId = relayState.value.botIds[0]
            val svc = botConnectionPool.get(firstBotId)
            if (svc != null) {
                val contact = getContacts().find { it.id == firstBotId }
                val connected = contact != null && botConnectionPool.ensureConnected(firstBotId, contact)
                if (connected) svc.sendTextContent(content)
                else logger.w(tag, "sendMessage: 首bot连接失败")
            }
        }
    }

    /** sendMessageToGroup 的缩写 */
    suspend fun sendMessage(groupId: String, content: String) = sendMessageToGroup(groupId, content)

    /** 修改群聊名称 */
    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatRepository.updateGroupName(groupId, newName); onGroupsChanged()
    }

    /** 向群聊添加智能体成员 */
    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatRepository.addBotToGroup(groupId, botId)
        relayState.update { it.copy(botIds = it.botIds + botId) }
        onGroupsChanged()
    }

    /** 从群聊移除智能体成员 */
    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        relayState.update { it.copy(botIds = it.botIds - botId) }
        onGroupsChanged()
    }

    /** 清空群聊消息记录 */
    suspend fun clearGroupMessages(groupId: String) {
        groupChatRepository.clearGroupMessages(groupId)
    }

    /** 设置群聊输入模式 */
    suspend fun updateGroupInputMode(groupId: String, mode: Int, groups: MutableStateFlow<List<GroupChat>>) {
        groupChatRepository.updateGroupInputMode(groupId, mode)
        val currentGroups = groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id == groupId }
        if (index >= 0) {
            currentGroups[index] = currentGroups[index].copy(inputMode = mode)
            groups.value = currentGroups
        }
    }
}
