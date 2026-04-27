package com.xiaozhi.aiservice.domain.model

/**
 * 消息类型
 */
enum class MessageType {
    TEXT,
    VOICE,
    IMAGE
}

/**
 * 消息方向
 */
enum class MessageDirection {
    SENT,      // 用户发送
    RECEIVED   // 收到
}

/**
 * 消息状态
 */
enum class MessageStatus {
    SENDING,
    SENT,
    FAILED
}

/**
 * 设备状态
 */
enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    BUSY
}

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val direction: MessageDirection,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Int? = null,
    val audioPath: String? = null
)

/**
 * 设备
 */
data class Device(
    val id: String,
    val name: String,
    val ipAddress: String,
    val status: DeviceStatus = DeviceStatus.OFFLINE,
    val isConnected: Boolean = false
)

/**
 * 连接配置
 */
data class ConnectionConfig(
    val serverUrl: String,
    val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/",
    val deviceId: String = "",   // 设备ID (MAC格式)
    val clientId: String = "",    // 客户端ID (UUID格式)
    val accessToken: String = ""
)

/**
 * AI对话状态
 */
enum class AiState {
    IDLE,          // 空闲，等待唤醒
    LISTENING,     // 正在听用户说话
    SPEAKING,      // 正在回复
    PROCESSING     // 处理中
}

/**
 * 输入模式
 */
enum class InputMode {
    VOICE,   // 语音输入
    TEXT     // 文字输入
}

/**
 * 智能体联系人
 */
data class BotContact(
    val id: String = "",                       // 唯一标识（UUID）
    val name: String = "",                     // 联系人名称
    val serverUrl: String = "wss://api.tenclass.net/xiaozhi/v1/",
    val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/", // OTA注册地址
    val deviceId: String = "",                 // 设备ID (MAC格式)
    val clientId: String = "",                 // 客户端ID (UUID格式)
    val inputMode: InputMode = InputMode.VOICE
)
