package com.xiaozhi.aiservice.presentation.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题管理器 — 单例，MCP 和 Compose 共享
 * mode: "system" | "light" | "dark"
 */
object ThemeManager {
    private val _mode = MutableStateFlow("system")
    val mode: StateFlow<String> = _mode.asStateFlow()

    fun setMode(newMode: String) {
        val normalized = when (newMode.lowercase()) {
            "light", "亮色", "白天" -> "light"
            "dark", "暗色", "夜晚", "深色" -> "dark"
            else -> "system"
        }
        _mode.value = normalized
    }

    fun getMode(): String = _mode.value
}
