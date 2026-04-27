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
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    
    override fun getMessages(): StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    override suspend fun sendTextMessage(text: String): Result<Unit> {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            type = MessageType.TEXT,
            direction = MessageDirection.SENT,
            status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + message
        return Result.success(Unit)
    }
    
    override suspend fun sendVoiceMessage(filePath: String, duration: Int): Result<Unit> {
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
        _messages.value = _messages.value + message
        return Result.success(Unit)
    }
    
    override suspend fun addReceivedMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    override suspend fun addSentMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
    
    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) msg.copy(status = status) else msg
        }
    }
    
    override suspend fun clearMessages() {
        _messages.value = emptyList()
    }
}
