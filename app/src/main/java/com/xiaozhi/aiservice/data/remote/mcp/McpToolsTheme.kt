package com.xiaozhi.aiservice.data.remote.mcp

import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.presentation.theme.ThemeManager

fun registerThemeTools(registry: MCPToolRegistry) {

    registry.registerTool(
        name = "set_theme",
        description = "切换App主题模式：亮色/暗色/跟随系统。不传mode则读取当前主题",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("主题: light/dark/system，不传则读取"),
        )
    ) { args ->
        val mode = args["mode"] as? String
        if (mode != null) {
            ThemeManager.setMode(mode)
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", ThemeManager.getMode())
            addProperty("description", when (ThemeManager.getMode()) {
                "light" -> "亮色主题"
                "dark" -> "暗色主题"
                else -> "跟随系统"
            })
            if (mode != null) {
                addProperty("message", when (ThemeManager.getMode()) {
                    "light" -> "已切换到亮色主题"
                    "dark" -> "已切换到暗色主题"
                    else -> "已切换到跟随系统主题"
                })
            }
        }
    }
}
