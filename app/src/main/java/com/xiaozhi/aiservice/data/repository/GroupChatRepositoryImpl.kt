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
import com.xiaozhi.aiservice.domain.model.GroupMessage
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class GroupChatRepositoryImpl(private val context: Context, private val gson: Gson) : GroupChatRepository {
    
    private val Context.groupDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_chats")
    private val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_messages")
    
    companion object {
        private val GROUPS_KEY = stringPreferencesKey("groups")
    }
    
    private fun messagesKey(groupId: String) = stringPreferencesKey("messages_$groupId")
    
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
    
    override suspend fun getGroup(groupId: String): GroupChat? {
        return getGroups().find { it.id == groupId }
    }
    
    override suspend fun deleteGroup(groupId: String) {
        context.groupDataStore.edit { preferences ->
            val json = preferences[GROUPS_KEY] ?: "[]"
            val type = object : TypeToken<List<GroupChat>>() {}.type
            val groups: List<GroupChat> = gson.fromJson(json, type) ?: emptyList()
            val mutableGroups = groups.toMutableList()
            mutableGroups.removeAll { it.id == groupId }
            preferences[GROUPS_KEY] = gson.toJson(mutableGroups)
        }
        
        // 删除消息
        context.messagesDataStore.edit { preferences ->
            preferences.remove(messagesKey(groupId))
        }
    }
    
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
    
    override fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        return context.messagesDataStore.data.map { preferences ->
            val json = preferences[messagesKey(groupId)] ?: "[]"
            val type = object : TypeToken<List<GroupMessage>>() {}.type
            gson.fromJson<List<GroupMessage>>(json, type) ?: emptyList()
        }
    }
    
    override suspend fun sendMessage(groupId: String, message: GroupMessage) {
        context.messagesDataStore.edit { preferences ->
            val json = preferences[messagesKey(groupId)] ?: "[]"
            val type = object : TypeToken<List<GroupMessage>>() {}.type
            val messages: List<GroupMessage> = gson.fromJson(json, type) ?: emptyList()
            val mutableMessages = messages.toMutableList()
            mutableMessages.add(message)
            preferences[messagesKey(groupId)] = gson.toJson(mutableMessages)
        }
    }
    
    override suspend fun addMessage(groupId: String, message: GroupMessage) {
        sendMessage(groupId, message)
    }
}
