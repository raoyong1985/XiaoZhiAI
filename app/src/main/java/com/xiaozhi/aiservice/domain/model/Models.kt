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
    val audioPath: String? = null,
    val isStreaming: Boolean = false  // AI正在流式回答中
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
/**
 * 语音模式
 */
enum class VoiceMode(val serverValue: String, val displayName: String) {
    AUTO("auto", "自动"),
    MANUAL("manual", "手动"),
    REALTIME("realtime", "实时");

    companion object {
        fun fromServerValue(value: String): VoiceMode =
            entries.find { it.serverValue == value } ?: REALTIME
    }
}

/**
 * AiState 字符串常量：替换 XiaoZhiBackendService 中的魔法字符串
 */
object AiStateStr {
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val SPEAKING = "speaking"
    const val PROCESSING = "processing"
}

data class BotContact(
    val id: String = "",                       // 唯一标识（UUID）
    val name: String = "",                     // 联系人名称
    val serverUrl: String = "wss://api.tenclass.net/xiaozhi/v1/",
    val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/", // OTA注册地址
    val deviceId: String = "",                 // 设备ID (MAC格式)
    val clientId: String = "",                 // 客户端ID (UUID格式)
    val inputMode: InputMode = InputMode.VOICE,
    val voiceMode: VoiceMode = VoiceMode.REALTIME  // 语音模式
)

/**
 * 群聊
 *
 * 代表一个多智能体群聊会话。核心字段：
 * - id: UUID，唯一标识
 * - botIds: 群内智能体列表（按顺序排列，决定接龙流转方向）
 * - inputMode: 当前输入模式（0=语音录音, 1=键盘文字），群聊级别独立设置
 *
 * 群聊本身不存储消息，消息由 GroupMessage 通过 groupId 关联。
 * 接龙流转信息由 RelayState（运行时状态，不持久化）管理。
 */
data class GroupChat(
    val id: String = "",                       // 群聊唯一标识（UUID）
    val name: String = "",                     // 群聊名称（可编辑）
    val botIds: List<String> = emptyList(),    // 群内智能体 ID 列表（顺序决定接龙顺序）
    val inputMode: Int = 0,                    // 输入模式：0=语音，1=文字
    val createdAt: Long = System.currentTimeMillis()  // 创建时间戳
)

/**
 * 群聊消息
 *
 * 与普通 ChatMessage 的区别：
 * - botId/botName：标识消息由哪个智能体发出（空字符串 = 用户消息）
 * - groupId：关联所属群聊
 * - direction：SENT=用户发送，RECEIVED=智能体回复（UI 靠此字段区分气泡左右）
 *
 * 字段设计意图：
 * - botId 用于接龙转发时的去重和 speaker 计分
 * - botName 直接嵌入消息体，不依赖运行时查询联系人列表
 * - content 可能经过流式拼接（多个 ttsText 聚合为一句话）
 */
data class GroupMessage(
    val id: String = "",                       // 消息唯一标识（UUID）
    val groupId: String = "",                  // 所属群聊 ID，用于消息检索和分区持久化
    val botId: String = "",                    // 发送者智能体 ID（空 = 用户发送）
    val botName: String = "",                  // 发送者名称（UI 显示用，免查询）
    val content: String = "",                  // 消息文本内容
    val type: MessageType = MessageType.TEXT,  // 消息类型（目前仅 TEXT）
    val direction: MessageDirection = MessageDirection.RECEIVED,  // 消息方向（决定气泡位置）
    val status: MessageStatus = MessageStatus.SENT,               // 发送状态
    val timestamp: Long = System.currentTimeMillis(),              // 时间戳
    val isStreaming: Boolean = false           // AI 正在流式回答中的标记（预留）
)
