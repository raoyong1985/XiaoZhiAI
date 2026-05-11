package com.xiaozhi.aiservice.presentation.viewmodel

import com.xiaozhi.aiservice.domain.model.ChatMessage

/**
 * 群聊 bot 事件（来自 WebSocket 的单 bot 消息流）
 * 将 ttsText/chatMessages/aiState 三个控制流合并为一个事件通道，
 * audioData（音频数据）独立处理，避免 decodePacket 阻塞控制事件
 */
sealed class BotEvent {
    data class TtsText(val text: String) : BotEvent()
    data class Message(val msg: ChatMessage) : BotEvent()
    data class AiState(val state: String) : BotEvent()
}
