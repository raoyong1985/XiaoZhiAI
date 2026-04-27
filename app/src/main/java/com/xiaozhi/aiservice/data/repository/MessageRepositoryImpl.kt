package com.xiaozhi.aiservice.data.repository

import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class MessageRepositoryImpl : MessageRepository {

    // 每个联系人独立的 MutableStateFlow
    private val messagesByContact = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    private fun getOrCreateFlow(contactId: String): MutableStateFlow<List<ChatMessage>> {
        return messagesByContact.getOrPut(contactId) {
            MutableStateFlow(emptyList())
        }
    }

    override fun getMessages(contactId: String): StateFlow<List<ChatMessage>> {
        return getOrCreateFlow(contactId).asStateFlow()
    }

    private fun updateMessages(contactId: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        getOrCreateFlow(contactId).value = transform(getOrCreateFlow(contactId).value)
    }

    override suspend fun sendTextMessage(contactId: String, text: String): Result<Unit> {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT,
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        updateMessages(contactId) { it + message }
        return Result.success(Unit)
    }

    override suspend fun sendVoiceMessage(contactId: String, filePath: String, duration: Int): Result<Unit> {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = filePath,
            type = MessageType.VOICE,
            direction = MessageDirection.SENT,
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis(),
            duration = duration,
            audioPath = filePath
        )
        updateMessages(contactId) { it + message }
        return Result.success(Unit)
    }

    override suspend fun addReceivedMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
    }

    override suspend fun addSentMessage(contactId: String, message: ChatMessage) {
        updateMessages(contactId) { it + message }
    }

    override suspend fun updateMessageStatus(contactId: String, messageId: String, status: MessageStatus) {
        updateMessages(contactId) { msgs ->
            msgs.map { msg -> if (msg.id == messageId) msg.copy(status = status) else msg }
        }
    }

    override suspend fun clearMessages(contactId: String) {
        updateMessages(contactId) { emptyList() }
    }
}
