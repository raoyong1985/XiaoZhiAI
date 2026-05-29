package com.xiaozhi.aiservice.presentation.viewmodel

import com.xiaozhi.aiservice.domain.model.ChatMessage

/**
 * 群聊 bot 事件
 *
 * 将每个 bot WebSocket 连接的三个独立控制流合并为一个事件通道：
 * 1. ttsText — AI 正在说的文本（流式，逐句推送）
 * 2. chatMessages — 完整的消息对象（含元数据）
 * 3. aiState — AI 状态变化（idle/listening/speaking/processing）
 *
 * 为什么合并？
 * Bot 事件处理需要严格的顺序（先收到 ttsText，再收到 idle 状态，
 * 最后决定是否转发）。三个独立 collect 会导致竞态顺序混乱。
 * BUFFERED Channel 保证顺序，不丢事件。
 *
 * 音频数据（audioData）独立处理，不走事件通道 ——
 * 避免 decodePacket（CPU 密集操作）阻塞控制事件流程。
 */
sealed class BotEvent {
    /** AI 正在流式输出的一句话文本 */
    data class TtsText(val text: String) : BotEvent()

    /** 一条完整的聊天消息（含 direction/type 等元数据） */
    data class Message(val msg: ChatMessage) : BotEvent()

    /** AI 状态变化：idle（空闲）/listening（监听）/speaking（说话）/processing（处理中） */
    data class AiState(val state: String) : BotEvent()

    /** 服务器发送 tts stop（仅此触发 relay，不受 sentence_end 超时干扰） */
    data object TtsStopped : BotEvent()
}
