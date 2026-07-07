package com.xiaozhi.aiservice.data.remote

import com.google.gson.JsonObject

/**
 * 格式化 MCP 工具返回结果，提取用户友好文本
 */
internal fun formatToolResult(raw: Any?, maxLen: Int = 200): String? {
    return when (raw) {
        null -> null
        is String -> {
            val t = raw.trim()
            if (t.length > maxLen) null else t
        }
        is JsonObject -> {
            raw.get("message")?.asString
                ?: raw.get("text")?.asString
                ?: raw.get("content")?.asString
                ?: raw.get("result")?.asString
                ?: raw.get("description")?.asString
                ?: raw.get("msg")?.asString
                ?: "已执行"
        }
        else -> "已执行"
    }
}
