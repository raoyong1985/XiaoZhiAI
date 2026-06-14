package com.xiaozhi.aiservice.data.remote

import com.google.gson.JsonObject

/**
 * 把 MCP 工具的原始返回格式化为用户可读字符串
 *
 * 背景：MCP 工具的 rawResult 多数是 JsonObject（如 `{"message":"已复制","success":true}`），
 * 少数是 String（工具不存在 / 异常）。直接 `toString()` 会得到 `{"key":"value"}` 原始 JSON，
 * 既难看又像"日志泄漏"到聊天界面。
 *
 * 策略：
 * 1. null → null（调用方应跳过展示）
 * 2. String → 原样返回
 * 3. JsonObject → 按优先级提取 `message / text / content / result / description` 等
 *    用户友好字段；取不到时 fallback 到 `toString()` 但截短到 maxLen
 * 4. 其他 → toString() 截短
 *
 * @param raw 工具返回的原始结果（JsonObject / String / 异常消息 / 其他）
 * @param maxLen fallback 时 JSON 字符串的最大长度，防止长结果爆框
 * @return 格式化后的用户可读字符串；raw 为 null 时返回 null
 */
internal fun formatToolResult(raw: Any?, maxLen: Int = 200): String? {
    if (raw == null) return null
    if (raw is String) return raw
    if (raw is JsonObject) {
        // 优先提取用户友好字段
        val preferredKeys = listOf("message", "text", "content", "result", "description", "msg")
        for (key in preferredKeys) {
            val value = raw.get(key)?.asString?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        // 取不到友好字段 → 截短 toString()
        val json = raw.toString()
        return if (json.length > maxLen) json.take(maxLen) + "..." else json
    }
    // 其他类型（Number / Boolean 等）
    val str = raw.toString()
    return if (str.length > maxLen) str.take(maxLen) + "..." else str
}
