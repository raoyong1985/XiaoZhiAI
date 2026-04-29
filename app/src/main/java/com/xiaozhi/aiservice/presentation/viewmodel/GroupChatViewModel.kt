package com.xiaozhi.aiservice.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.GroupMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GroupChatViewModel : ViewModel() {
    private val groupChatRepository = AppContainer.groupChatRepository
    private val contactsRepository = AppContainer.contactsRepository
    
    // 每个智能体的连接: botId -> Pair<XiaoZhiBackendService, ConnectionConfig>
    private val botConnections = mutableMapOf<String, Pair<XiaoZhiBackendService, com.xiaozhi.aiservice.domain.model.ConnectionConfig>>()
    
    // 防止无限循环：记录每条用户消息触发的最大转发轮次
    private var currentUserMessageId: String = ""
    private var forwardRound = 0
    private val MAX_FORWARD_ROUNDS = 2  // 每轮用户消息，智能体之间最多互动2轮
    
    private val _groups = MutableStateFlow<List<GroupChat>>(emptyList())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()
    
    private val _contacts = MutableStateFlow<List<BotContact>>(emptyList())
    val contacts: StateFlow<List<BotContact>> = _contacts.asStateFlow()
    
    init {
        loadGroups()
        loadContacts()
    }
    
    private fun loadGroups() {
        viewModelScope.launch {
            _groups.value = groupChatRepository.getGroups()
        }
    }
    
    private fun loadContacts() {
        viewModelScope.launch {
            contactsRepository.getContacts().collect { contacts ->
                _contacts.value = contacts
            }
        }
    }
    
    // 连接群聊中的所有智能体
    fun connectGroup(groupId: String) {
        viewModelScope.launch {
            val group = groupChatRepository.getGroup(groupId)
            group?.let { g ->
                g.botIds.forEach { botId ->
                    val contact = _contacts.value.find { it.id == botId }
                    contact?.let { c ->
                        if (!botConnections.containsKey(botId)) {
                            val config = com.xiaozhi.aiservice.domain.model.ConnectionConfig(
                                serverUrl = c.serverUrl,
                                otaUrl = c.otaUrl,
                                deviceId = c.deviceId,
                                clientId = c.clientId
                            )
                            val service = XiaoZhiBackendService(AppContainer.gson)
                            botConnections[botId] = Pair(service, config)
                            
                            // 监听这个智能体的回复
                            observeBotReplies(botId, groupId, c.name, service)
                            
                            // 连接
                            service.connect(c.serverUrl, c.otaUrl, c.deviceId, c.clientId)
                        }
                    }
                }
            }
        }
    }
    
    // 监听智能体回复，并转发给其他智能体
    private fun observeBotReplies(botId: String, groupId: String, botName: String, service: XiaoZhiBackendService) {
        // 监听聊天消息
        viewModelScope.launch {
            service.chatMessages.collect { chatMsg ->
                if (chatMsg.direction == MessageDirection.RECEIVED && chatMsg.content.isNotBlank()) {
                    // 保存这个回复到群聊
                    val replyMessage = GroupMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        groupId = groupId,
                        botId = botId,
                        botName = botName,
                        content = chatMsg.content,
                        type = MessageType.TEXT,
                        direction = MessageDirection.RECEIVED
                    )
                    groupChatRepository.addMessage(groupId, replyMessage)
                    
                    // 如果是当前用户消息触发的回复，且未达到最大转发轮次，转发给其他智能体
                    if (forwardRound < MAX_FORWARD_ROUNDS && currentUserMessageId.isNotEmpty()) {
                        forwardRound++
                        
                        // 转发纯文本内容，让其他智能体看到
                        val forwardText = chatMsg.content
                        
                        botConnections.forEach { (otherBotId, pair) ->
                            if (otherBotId != botId) {
                                val (otherService, _) = pair
                                // 直接发送文本内容，触发其他智能体的回复
                                otherService.sendTextContent(forwardText)
                            }
                        }
                    }
                }
            }
        }
        
        // 也监听 textMessage（作为备用）
        viewModelScope.launch {
            service.textMessage.collect { text ->
                if (text.isNotBlank()) {
                    // 检查是否已经是转发格式的消息，避免重复处理
                    if (!text.startsWith("[")) {
                        val replyMessage = GroupMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            groupId = groupId,
                            botId = botId,
                            botName = botName,
                            content = text,
                            type = MessageType.TEXT,
                            direction = MessageDirection.RECEIVED
                        )
                        // 不重复添加，因为 chatMessages 可能已经处理了
                    }
                }
            }
        }
    }
    
    suspend fun createGroup(name: String, selectedBotIds: List<String>): GroupChat {
        val group = groupChatRepository.createGroup(name, selectedBotIds)
        loadGroups()
        return group
    }
    
    suspend fun deleteGroup(groupId: String) {
        groupChatRepository.deleteGroup(groupId)
        botConnections.clear()
        loadGroups()
    }
    
    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        return groupChatRepository.getGroupMessages(groupId)
    }
    
    // 发送消息到群聊中的所有智能体
    suspend fun sendMessageToGroup(groupId: String, content: String) {
        // 保存用户消息
        val userMessageId = java.util.UUID.randomUUID().toString()
        currentUserMessageId = userMessageId
        forwardRound = 0  // 重置转发轮次
        
        val userMessage = GroupMessage(
            id = userMessageId,
            groupId = groupId,
            botId = "",
            botName = "我",
            content = content,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT
        )
        groupChatRepository.addMessage(groupId, userMessage)
        
        // 发送给所有智能体
        botConnections.forEach { (botId, pair) ->
            val (service, _) = pair
            service.sendTextContent(content)
        }
    }
    
    suspend fun sendMessage(groupId: String, content: String, botId: String = "", botName: String = "我") {
        sendMessageToGroup(groupId, content)
    }
    
    suspend fun updateGroupName(groupId: String, newName: String) {
        groupChatRepository.updateGroupName(groupId, newName)
        loadGroups()
    }
    
    suspend fun addBotToGroup(groupId: String, botId: String) {
        groupChatRepository.addBotToGroup(groupId, botId)
        loadGroups()
    }
    
    suspend fun removeBotFromGroup(groupId: String, botId: String) {
        groupChatRepository.removeBotFromGroup(groupId, botId)
        loadGroups()
    }
    
    suspend fun clearGroupMessages(groupId: String) {
        groupChatRepository.clearGroupMessages(groupId)
    }
    
    fun exportGroupMessages(groupId: String, context: android.content.Context) {
        viewModelScope.launch {
            val messages = groupChatRepository.getGroupMessages(groupId).first()
            if (messages.isEmpty()) return@launch
            val text = messages.joinToString("\n") { msg ->
                val sender = if (msg.direction == MessageDirection.SENT) "我" else msg.botName
                "[${sender}]: ${msg.content}"
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "群聊记录")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "导出群聊记录"))
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        botConnections.clear()
    }
    
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GroupChatViewModel::class.java)) {
                return GroupChatViewModel() as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
