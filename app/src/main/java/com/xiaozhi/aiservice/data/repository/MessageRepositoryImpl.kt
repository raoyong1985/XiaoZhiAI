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
import com.xiaozhi.aiservice.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

@Singleton
class MessageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : MessageRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val messagesByContact = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val dirtyContacts = LinkedHashSet<String>()
    private var flushJob: Job? = null
    private val loadedFromDisk = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TAG = "MessageRepo"
        private fun messagesKey(contactId: String) = stringPreferencesKey("messages_$contactId")
    }

    private fun getOrCreateFlow(contactId: String): MutableStateFlow<List<ChatMessage>> {
        return messagesByContact.getOrPut(contactId) {
            MutableStateFlow(emptyList())
        }
    }

    override fun getMessages(contactId: String): StateFlow<List<ChatMessage>> {
        val flow = getOrCreateFlow(contactId)
        if (loadedFromDisk.putIfAbsent(contactId, true) == null) {
            scope.launch {
                val stored = scopeLoadFromDisk(contactId)
                if (stored.isNotEmpty() && flow.value.isEmpty()) {
                    flow.value = stored
                }
            }
        }
        return flow.asStateFlow()
    }

    private suspend fun scopeLoadFromDisk(contactId: String): List<ChatMessage> {
        return try {
            val preferences = context.messagesDataStore.data.first()
            val json = preferences[messagesKey(contactId)] ?: "[]"
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun markDirty(contactId: String) {
        synchronized(dirtyContacts) {
            dirtyContacts.add(contactId)
            if (flushJob == null) {
                flushJob = scope.launch {
                    delay(500)
                    flushDirty()
                }
            }
        }
    }

    private suspend fun flushDirty() {
        val ids: List<String>
        synchronized(dirtyContacts) {
            ids = dirtyContacts.toList()
            dirtyContacts.clear()
            flushJob = null
        }
        for (contactId in ids) {
            writeToDisk(contactId, getOrCreateFlow(contactId).value)
        }
    }

    private suspend fun writeToDisk(contactId: String, messages: List<ChatMessage>) {
        try {
            context.messagesDataStore.edit { preferences ->
                preferences[messagesKey(contactId)] = gson.toJson(messages)
            }
        } catch (e: Exception) { Logger.w(TAG, "writeToDisk failed: ${e.message}") }
    }

    private fun updateMessages(contactId: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val flow = getOrCreateFlow(contactId)
        synchronized(flow) {
            flow.value = transform(flow.value)
        }
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
        markDirty(contactId)
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
        markDirty(contactId)
        return Result.success(Unit)
    }

    override suspend fun addReceivedMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
        markDirty(contactId)
    }

    override suspend fun addSentMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
        markDirty(contactId)
    }

    override suspend fun updateMessageStatus(contactId: String, messageId: String, status: com.xiaozhi.aiservice.domain.model.MessageStatus) {
        updateMessages(contactId) { msgs ->
            msgs.map { msg -> if (msg.id == messageId) msg.copy(status = status) else msg }
        }
        markDirty(contactId)
    }

    override suspend fun finalizeStreamingMessage(contactId: String, messageId: String) {
        updateMessages(contactId) { msgs ->
            msgs.map { msg -> if (msg.id == messageId) msg.copy(isStreaming = false) else msg }
        }
        markDirty(contactId)
    }

    override suspend fun clearMessages(contactId: String) {
        updateMessages(contactId) { emptyList() }
        writeToDisk(contactId, emptyList())
    }
}
