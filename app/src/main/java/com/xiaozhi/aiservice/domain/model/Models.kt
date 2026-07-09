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
 *
 * id、content、type、direction 是必填的核心字段。
 * groupId/botId/botName 仅群聊消息使用，单聊时为空字符串。
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
    val isStreaming: Boolean = false,  // AI正在流式回答中
    // 群聊字段（非群聊消息为空串）
    val groupId: String = "",
    val botId: String = "",
    val botName: String = ""
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
    val voiceMode: VoiceMode = VoiceMode.REALTIME,  // 语音模式
    val wakeWord: String = ""                  // 本地唤醒词（如"悠悠悠悠"），空=不启用语音唤醒
)

/**
 * 群聊
 *
 * 代表一个多智能体群聊会话。核心字段：
 * - id: UUID，唯一标识
 * - botIds: 群内智能体列表（按顺序排列，决定接龙流转方向）
 * - inputMode: 当前输入模式（0=语音录音, 1=键盘文字），群聊级别独立设置
 *
 * 群聊本身不存储消息，消息由 ChatMessage 通过 groupId 关联。
 * 接龙流转信息由 RelayState（运行时状态，不持久化）管理。
 */
data class GroupChat(
    val id: String = "",                       // 群聊唯一标识（UUID）
    val name: String = "",                     // 群聊名称（可编辑）
    val botIds: List<String> = emptyList(),    // 群内智能体 ID 列表（顺序决定接龙顺序）
    val inputMode: Int = 0,                    // 输入模式：0=语音，1=文字
    val isStreaming: Boolean = false           // AI 正在流式回答中的标记（预留）
)
