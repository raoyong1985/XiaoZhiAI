package com.xiaozhi.aiservice.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

class MessageRepositoryImpl(
    private val context: Context,
    private val gson: Gson
) : MessageRepository {

    // 每个联系人独立的 MutableStateFlow（内存缓存）
    private val messagesByContact = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    companion object {
        // 用联系人ID作为key存储消息列表的JSON
        private fun messagesKey(contactId: String) = stringPreferencesKey("messages_$contactId")
    }

    private fun getOrCreateFlow(contactId: String): MutableStateFlow<List<ChatMessage>> {
        return messagesByContact.getOrPut(contactId) {
            MutableStateFlow(emptyList())
        }
    }

    override fun getMessages(contactId: String): StateFlow<List<ChatMessage>> {
        // 首次加载时从持久化存储恢复
        if (getOrCreateFlow(contactId).value.isEmpty()) {
            kotlinx.coroutines.runBlocking {
                val stored = loadFromDisk(contactId)
                getOrCreateFlow(contactId).value = stored
            }
        }
        return getOrCreateFlow(contactId).asStateFlow()
    }

    private suspend fun loadFromDisk(contactId: String): List<ChatMessage> {
        return try {
            val preferences = context.messagesDataStore.data.first()
            val json = preferences[messagesKey(contactId)] ?: "[]"
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveToDisk(contactId: String, messages: List<ChatMessage>) {
        context.messagesDataStore.edit { preferences ->
            preferences[messagesKey(contactId)] = gson.toJson(messages)
        }
    }

    private fun updateMessages(contactId: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        getOrCreateFlow(contactId).value = transform(getOrCreateFlow(contactId).value)
    }

    override suspend fun sendTextMessage(contactId: String, text: String): Result<Unit> {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            type = com.xiaozhi.aiservice.domain.model.MessageType.TEXT,
            direction = com.xiaozhi.aiservice.domain.model.MessageDirection.SENT,
            status = com.xiaozhi.aiservice.domain.model.MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        updateMessages(contactId) { it + message }
        saveToDisk(contactId, getOrCreateFlow(contactId).value)
        return Result.success(Unit)
    }

    override suspend fun sendVoiceMessage(contactId: String, filePath: String, duration: Int): Result<Unit> {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = filePath,
            type = com.xiaozhi.aiservice.domain.model.MessageType.VOICE,
            direction = com.xiaozhi.aiservice.domain.model.MessageDirection.SENT,
            status = com.xiaozhi.aiservice.domain.model.MessageStatus.SENT,
            timestamp = System.currentTimeMillis(),
            duration = duration,
            audioPath = filePath
        )
        updateMessages(contactId) { it + message }
        saveToDisk(contactId, getOrCreateFlow(contactId).value)
        return Result.success(Unit)
    }

    override suspend fun addReceivedMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
        saveToDisk(contactId, getOrCreateFlow(contactId).value)
    }

    override suspend fun addSentMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
        saveToDisk(contactId, getOrCreateFlow(contactId).value)
    }

    override suspend fun updateMessageStatus(contactId: String, messageId: String, status: com.xiaozhi.aiservice.domain.model.MessageStatus) {
        updateMessages(contactId) { msgs ->
            msgs.map { msg -> if (msg.id == messageId) msg.copy(status = status) else msg }
        }
        saveToDisk(contactId, getOrCreateFlow(contactId).value)
    }

    override suspend fun clearMessages(contactId: String) {
        updateMessages(contactId) { emptyList() }
        saveToDisk(contactId, emptyList())
    }
}
