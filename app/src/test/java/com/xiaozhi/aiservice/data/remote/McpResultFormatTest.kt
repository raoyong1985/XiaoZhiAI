package com.xiaozhi.aiservice.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpResultFormatTest {

    // ── null / empty ──

    @Test
    fun `null returns null`() {
        assertNull(formatToolResult(null))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(formatToolResult("  "))
    }

    // ── String ──

    @Test
    fun `non-empty string returns trimmed`() {
        assertEquals("成功", formatToolResult("  成功  "))
    }

    // ── JsonObject: error fields ──

    @Test
    fun `error field returns prefixed message`() {
        val obj = JsonObject().apply { addProperty("error", "权限不足") }
        assertEquals("失败: 权限不足", formatToolResult(obj))
    }

    @Test
    fun `error_message field works`() {
        val obj = JsonObject().apply { addProperty("error_message", "超时") }
        assertEquals("失败: 超时", formatToolResult(obj))
    }

    @Test
    fun `exception field works`() {
        val obj = JsonObject().apply { addProperty("exception", "空指针") }
        assertEquals("失败: 空指针", formatToolResult(obj))
    }

    @Test
    fun `error boolean true returns fail text`() {
        val obj = JsonObject().apply { addProperty("error", true) }
        assertEquals("执行失败", formatToolResult(obj))
    }

    // ── JsonObject: success=false ──

    @Test
    fun `success false returns null`() {
        val obj = JsonObject().apply { addProperty("success", false) }
        assertNull(formatToolResult(obj))
    }

    // ── JsonObject: preferred keys ──

    @Test
    fun `message field extracted`() {
        val obj = JsonObject().apply { addProperty("message", "已复制到剪贴板") }
        assertEquals("已复制到剪贴板", formatToolResult(obj))
    }

    @Test
    fun `text field extracted`() {
        val obj = JsonObject().apply { addProperty("text", "结果输出") }
        assertEquals("结果输出", formatToolResult(obj))
    }

    @Test
    fun `content field extracted`() {
        val obj = JsonObject().apply { addProperty("content", "操作成功") }
        assertEquals("操作成功", formatToolResult(obj))
    }

    @Test
    fun `result field extracted`() {
        val obj = JsonObject().apply { addProperty("result", "已保存") }
        assertEquals("已保存", formatToolResult(obj))
    }

    @Test
    fun `description extracted over info`() {
        val obj = JsonObject().apply {
            addProperty("description", "优先的描述")
            addProperty("info", "次选的信息")
        }
        assertEquals("优先的描述", formatToolResult(obj))
    }

    @Test
    fun `error takes precedence over message`() {
        val obj = JsonObject().apply {
            addProperty("error", "致命错误")
            addProperty("message", "正常消息")
        }
        assertEquals("失败: 致命错误", formatToolResult(obj)) // error wins
    }

    // ── JsonObject: single primitive field ──

    @Test
    fun `single success true returns 已执行`() {
        val obj = JsonObject().apply { addProperty("success", true) }
        assertEquals("已执行", formatToolResult(obj))
    }

    @Test
    fun `single number field returns number string`() {
        val obj = JsonObject().apply { addProperty("id", 42) }
        assertEquals("42", formatToolResult(obj))
    }

    @Test
    fun `single string field returns string value`() {
        val obj = JsonObject().apply { addProperty("status", "ok") }
        assertEquals("ok", formatToolResult(obj))
    }

    // ── JsonObject: multiple primitive fields ──

    @Test
    fun `multiple string primitives join with comma`() {
        val obj = JsonObject().apply {
            addProperty("a", "X")
            addProperty("b", "Y")
        }
        assertEquals("X，Y", formatToolResult(obj))
    }

    // ── JsonObject: no match → null ──

    @Test
    fun `unrecognized object returns null`() {
        val obj = JsonObject().apply {
            add("data", JsonObject()) // nested object, not primitive
        }
        assertNull(formatToolResult(obj))
    }

    // ── JsonArray ──

    @Test
    fun `json array with strings joins with newline`() {
        val arr = JsonArray().apply {
            add(JsonObject().apply { addProperty("message", "item 1") })
            add(JsonObject().apply { addProperty("message", "item 2") })
        }
        assertEquals("item 1\nitem 2", formatToolResult(arr))
    }

    @Test
    fun `json array empty returns null`() {
        assertEquals(null, formatToolResult(JsonArray()))
    }

    // ── Other types ──

    @Test
    fun `number returns string`() {
        assertEquals("42", formatToolResult(42))
    }

    @Test
    fun `boolean true returns string`() {
        assertEquals("true", formatToolResult(true))
    }

    @Test
    fun `long string over 100 chars returned as-is via string branch`() {
        val longStr = "a".repeat(101)
        // String branch returns trimmed string regardless of length
        assertEquals(longStr, formatToolResult(longStr))
    }
}
