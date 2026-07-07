package com.xiaozhi.aiservice.data.remote.mcp

import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.data.settings.DeviceSettings
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry

fun registerXiaozhiTools(registry: MCPToolRegistry) {
    registry.registerTool(
        name = "xiaozhi_volume",
        description = "读取(不传level)或设置(传level 0-100)小智AI语音输出音量，持久化保存",
        inputSchema = jsonSchema(
            "level" to jsonNumberProp("设置音量 0-100，不传则只读取当前音量"),
        )
    ) { args ->
        val level = args["level"] as? Number
        if (level != null) {
            val v = level.toInt().coerceIn(0, 100)
            AudioSettings.setOutputVolume(registry.context, v)
            registry.connectionRepository.get().let { repo ->
                if (repo is com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl) {
                    repo.setVolume(v)
                }
            }
            JsonObject().apply {
                addProperty("success", true)
                addProperty("level", v)
                addProperty("message", "音量已设置为 $v%")
            }
        } else {
            JsonObject().apply {
                addProperty("level", AudioSettings.getOutputVolume(registry.context))
            }
        }
    }

    registry.registerTool(
        name = "set_aec_mode",
        description = "设置AEC回声消除模式：off（关闭）/ device（设备端AEC）",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("AEC模式: off/device，默认 off")
        )
    ) { args ->
        val mode = (args["mode"] as? String)?.lowercase() ?: "off"
        val validModes = setOf("off", "device")
        if (mode !in validModes) throw Exception("mode 必须为 off/device")
        val aecMode = DeviceSettings.AecMode.fromValue(mode)
        DeviceSettings.setAecMode(registry.context, aecMode)
        registry.audioService.setAecEnabled(aecMode != DeviceSettings.AecMode.OFF)
        val isSupported = registry.audioService.isAecSupported()
        JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", aecMode.value)
            addProperty("device_support", isSupported)
            addProperty("message", when {
                !isSupported -> "AEC 已设置但设备不支持 AcousticEchoCanceler"
                aecMode == DeviceSettings.AecMode.OFF -> "AEC 已关闭"
                else -> "AEC 已启用（device 模式）"
            })
        }
    }

    registry.registerTool(
        name = "get_device_state",
        description = "获取小智 AI 设备的当前状态",
        inputSchema = jsonSchema()
    ) { _ ->
        val connRepo = registry.connectionRepository.get()
        val aiState = connRepo.aiState.value
        val deviceState = connRepo.deviceState.value
        JsonObject().apply {
            addProperty("device_state", deviceState.name)
            addProperty("device_state_display", deviceState.displayName)
            addProperty("ai_state", aiState.name)
        }
    }
}
