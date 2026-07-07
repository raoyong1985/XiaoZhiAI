package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry

fun registerNoteTools(registry: MCPToolRegistry) {
    // ──────────────── clipboard ────────────────
    registry.registerTool(
        name = "clipboard",
        description = "读取或写入系统剪贴板。读取时返回剪贴板文本内容，写入时设置新文本到剪贴板",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: read（读取）/ write（写入），默认 read"),
            "text" to jsonStringProp("写入模式下的文本内容"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "read"
        val ctx = registry.context
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: throw Exception("此设备不支持剪贴板")

        when (action) {
            "read" -> {
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() ?: "" else ""
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", "read")
                    addProperty("has_text", text.isNotEmpty())
                    addProperty("text", text)
                }
            }
            "write" -> {
                val text = (args["text"] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw Exception("写入模式必须提供 text 参数")
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xiaozhi", text))
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", "write")
                    addProperty("text_length", text.length)
                    addProperty("message", "已复制到剪贴板")
                }
            }
            else -> throw Exception("不支持的 action: $action，支持 read/write")
        }
    }
}
