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

class GroupChatRepositoryImpl(private val context: Context, private val gson: Gson) : GroupChatRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val Context.groupDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_chats")
    private val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_messages")

    // 内存缓存 + 批量写入
    private val messagesCache = ConcurrentHashMap<String, MutableStateFlow<List<GroupMessage>>>()
    private val dirtyGroups = LinkedHashSet<String>()
    private var flushJob: Job? = null
    private val loadedFromDisk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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

        context.messagesDataStore.edit { preferences ->
            preferences.remove(messagesKey(groupId))
        }
        messagesCache.remove(groupId)
    }

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

    private fun getOrCreateFlow(groupId: String): MutableStateFlow<List<GroupMessage>> {
        return messagesCache.getOrPut(groupId) {
            MutableStateFlow(emptyList())
        }
    }

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
            // 500ms内又有新消息，再次调度flush
            if (dirtyGroups.isNotEmpty()) {
                flushJob = scope.launch {
                    delay(500)
                    flushDirtyInternal()
                }
            }
        }
    }

    private suspend fun writeToDisk(groupId: String, messages: List<GroupMessage>) {
        try {
            context.messagesDataStore.edit { preferences ->
                preferences[messagesKey(groupId)] = gson.toJson(messages)
            }
        } catch (_: Exception) {}
    }

    override suspend fun clearGroupMessages(groupId: String) {
        getOrCreateFlow(groupId).value = emptyList()
        writeToDisk(groupId, emptyList())
    }

    override fun getGroupMessages(groupId: String): Flow<List<GroupMessage>> {
        val flow = getOrCreateFlow(groupId)
        android.util.Log.i("GroupChatRepo", "getGroupMessages groupId=$groupId currentSize=${flow.value.size}")
        if (loadedFromDisk.putIfAbsent(groupId, true) == null) {
            scope.launch {
                val stored = loadMessagesFromDisk(groupId)
                android.util.Log.i("GroupChatRepo", "disk load groupId=$groupId loaded=${stored.size} currentMem=${flow.value.size}")
                // 仅在内存仍为空时才写入，防止覆盖加载期间到达的新消息
                if (stored.isNotEmpty() && flow.value.isEmpty()) {
                    flow.value = stored
                    android.util.Log.i("GroupChatRepo", "disk merge groupId=$groupId finalSize=${flow.value.size}")
                }
            }
        }
        return flow.asStateFlow()
    }

    private suspend fun loadMessagesFromDisk(groupId: String): List<GroupMessage> {
        return try {
            val preferences = context.messagesDataStore.data.first()
            val json = preferences[messagesKey(groupId)] ?: "[]"
            val type = object : TypeToken<List<GroupMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun sendMessage(groupId: String, message: GroupMessage) {
        val flow = getOrCreateFlow(groupId)
        flow.value = flow.value + message
        android.util.Log.i("GroupChatRepo", "sendMessage groupId=$groupId msgId=${message.id} content='${message.content.take(20)}' dir=${message.direction} total=${flow.value.size}")
        markDirty(groupId)
    }

    override suspend fun addMessage(groupId: String, message: GroupMessage) {
        sendMessage(groupId, message)
    }

    override suspend fun updateMessageText(groupId: String, messageId: String, newText: String) {
        val flow = getOrCreateFlow(groupId)
        flow.value = flow.value.map { msg ->
            if (msg.id == messageId) msg.copy(content = newText) else msg
        }
        markDirty(groupId)
    }
}
