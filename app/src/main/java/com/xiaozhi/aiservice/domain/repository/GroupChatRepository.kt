package com.xiaozhi.aiservice.domain.repository

import com.xiaozhi.aiservice.domain.model.GroupChat
import com.xiaozhi.aiservice.domain.model.GroupMessage
import kotlinx.coroutines.flow.Flow

interface GroupChatRepository {
    suspend fun createGroup(name: String, botIds: List<String>): GroupChat
    suspend fun getGroups(): List<GroupChat>
    suspend fun getGroup(groupId: String): GroupChat?
    suspend fun deleteGroup(groupId: String)
    suspend fun updateGroupName(groupId: String, newName: String)
    suspend fun updateGroupInputMode(groupId: String, inputMode: Int)
    suspend fun addBotToGroup(groupId: String, botId: String)
    suspend fun removeBotFromGroup(groupId: String, botId: String)
    suspend fun clearGroupMessages(groupId: String)
    
    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>>
    suspend fun sendMessage(groupId: String, message: GroupMessage)
    suspend fun addMessage(groupId: String, message: GroupMessage)
}
