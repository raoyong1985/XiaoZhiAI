package com.xiaozhi.aiservice.data.remote.mcp

import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.presentation.theme.ThemeManager

fun registerThemeTools(registry: MCPToolRegistry) {

    registry.registerTool(
        name = "set_theme",
        description = "切换App主题模式：亮色/暗色/跟随系统",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("主题模式: light(亮色) / dark(暗色) / system(跟随系统)，默认 system")
        )
    ) { args ->
        val mode = (args["mode"] as? String) ?: "system"
        ThemeManager.setMode(mode)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", ThemeManager.getMode())
            addProperty("message", when (ThemeManager.getMode()) {
                "light" -> "已切换到亮色主题"
                "dark" -> "已切换到暗色主题"
                else -> "已切换到跟随系统主题"
            })
        }
    }

    registry.registerTool(
        name = "get_theme",
        description = "获取当前App主题模式",
        inputSchema = jsonSchema()
    ) { _ ->
        JsonObject().apply {
            addProperty("mode", ThemeManager.getMode())
            addProperty("description", when (ThemeManager.getMode()) {
                "light" -> "亮色主题"
                "dark" -> "暗色主题"
                else -> "跟随系统"
            })
        }
    }
}
