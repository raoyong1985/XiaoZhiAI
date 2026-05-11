package com.xiaozhi.aiservice.presentation.viewmodel

import com.xiaozhi.aiservice.domain.model.ChatMessage

/**
 * 群聊 bot 事件（来自 WebSocket 的单 bot 消息流）
 * 将 ttsText/chatMessages/aiState/audioData 四个独立流合并为一个事件流，
 * 保证单 bot 内事件顺序处理，消除 AtomicReference 共享状态
 */
sealed class BotEvent {
    data class TtsText(val text: String) : BotEvent()
    data class Message(val msg: ChatMessage) : BotEvent()
    data class AiState(val state: String) : BotEvent()
    data class AudioData(val data: ByteArray) : BotEvent() {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = data.contentHashCode()
    }
}
