package com.xiaozhi.aiservice.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 把 MCP 工具的原始返回格式化为用户可读字符串
 *
 * 背景：MCP 工具的 rawResult 多数是 JsonObject（如 `{"message":"已复制","success":true}`），
 * 少数是 String（工具不存在 / 异常）。直接 `toString()` 会得到 `{"key":"value"}` 原始 JSON，
 * 既难看又像"日志泄漏"到聊天界面。
 *
 * 核心原则：宁可显示不完整（null/兜底文字），也不把原始 JSON 暴露给用户。
 *
 * 策略：
 * 1. null / 空 → null（调用方跳过展示或用兜底文本）
 * 2. String → 原样返回
 * 3. JsonObject → 按优先级提取：
 *    a. error/error_message/exception → "失败: {msg}"
 *    b. message/text/content/result/description/msg → 提取第一个非空
 *    c. 单字段（如 `{"success":true}`）且值为布尔/数字 → 返回 success? "已执行" : "执行失败"
 *    d. 仍取不到 → null（不暴露原始 JSON）
 * 4. JsonArray → 拼接数组元素为文本列表
 * 5. 其他 → toString() 截短，超 100 字符返回 null
 */
internal fun formatToolResult(raw: Any?, maxLen: Int = 200): String? {
    if (raw == null) return null
    if (raw is String) {
        val trimmed = raw.trim()
        return if (trimmed.isBlank()) null else trimmed
    }

    if (raw is JsonObject) {
        // 1. 错误/异常字段优先
        val errorKey = listOf("error", "error_message", "exception", "err_msg")
        for (key in errorKey) {
            val errVal = raw.get(key)
            when {
                errVal is JsonPrimitive && errVal.isString -> {
                    val msg = errVal.asString.trim()
                    if (msg.isNotBlank()) return "失败: $msg"
                }
                errVal is JsonPrimitive && errVal.isBoolean && errVal.asBoolean -> return "执行失败"
            }
        }

        // 2. "success" = false → 告知失败（无详情的简单失败场景）
        val successPrim = raw.get("success")
        if (successPrim is JsonPrimitive && successPrim.isBoolean && !successPrim.asBoolean) {
            return null  // 失败无详细信息 → 让调用方用兜底文本
        }

        // 3. 用户友好字段
        val preferredKeys = listOf("message", "text", "content", "result", "description", "msg", "info", "hint")
        for (key in preferredKeys) {
            val value = raw.get(key)?.asString?.trim()?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }

        // 4. 单 Primitive 字段 → 提取值（避免 {"success":true} 这种仅含布尔/数值的 JSON 泄漏）
        val entries = raw.entrySet().filter { it.value is JsonPrimitive }
        if (entries.size == 1) {
            val prim = entries.single().value as JsonPrimitive
            when {
                prim.isBoolean -> return if (prim.asBoolean) "已执行" else null
                prim.isNumber -> return prim.asString
                prim.isString -> {
                    val str = prim.asString.trim()
                    return if (str.isNotBlank()) str else null
                }
            }
        }

        // 5. 多个 Primitive 字段 → 尝试拼接为可读文本（不暴露 JSON key 名）
        val strValues = entries.mapNotNull {
            val v = it.value
            when {
                v is JsonPrimitive && v.isString -> v.asString.trim().takeIf { s -> s.isNotBlank() }
                v is JsonPrimitive && v.isNumber -> v.asString
                else -> null
            }
        }.filter { it.isNotEmpty() }
        if (strValues.isNotEmpty()) {
            return strValues.joinToString("，")
        }

        // 6. 仍取不到 → 返回 null，不暴露原始 JSON
        return null
    }

    if (raw is JsonArray) {
        val items = raw.mapNotNull { formatToolResult(it, maxLen) }
        return if (items.isEmpty()) null else items.joinToString("\n")
    }

    // 其他类型（Number / Boolean / 异常消息等）
    val str = raw.toString().trim()
    if (str.isBlank() || str == "null") return null
    return if (str.length <= 100) str else null  // 超过 100 字符不可能是友好消息，跳过
}
