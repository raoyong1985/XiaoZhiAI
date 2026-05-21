package com.xiaozhi.aiservice.data.remote.mcp

import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.settings.AudioSettings
import com.xiaozhi.aiservice.data.settings.DeviceSettings
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContainer

fun registerXiaozhiTools() {
    MCPToolRegistry.registerTool(
        name = "set_xiaozhi_volume",
        description = "设置小智 AI 的语音输出音量（0-100），会持久化保存，下次启动自动恢复",
        inputSchema = jsonSchema(
            "level" to jsonNumberProp("音量 0-100，默认 80"),
            required = listOf("level")
        )
    ) { args ->
        val level = (args["level"] as? Number)?.toInt()?.coerceIn(0, 100)
            ?: throw Exception("level 必须为 0-100 的整数")
        AudioSettings.setOutputVolume(AppContainer.context, level)
        // 立即应用到所有活跃解码器（私聊 + 群聊各 bot）
        AppContainer.connectionRepository.let { repo ->
            if (repo is com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl) {
                repo.setVolume(level)
            }
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("level", level)
            addProperty("message", "音量已设置为 $level%，下次启动自动恢复")
        }
    }

    MCPToolRegistry.registerTool(
        name = "get_xiaozhi_volume",
        description = "获取当前小智 AI 的语音输出音量（0-100）",
        inputSchema = jsonSchema()
    ) { _ ->
        val level = AudioSettings.getOutputVolume(AppContainer.context)
        JsonObject().apply {
            addProperty("level", level)
        }
    }

    MCPToolRegistry.registerTool(
        name = "set_listening_mode",
        description = "设置小智 AI 的监听模式：auto（自动停止后重启）/ manual（手动重启）/ realtime（实时持续）。参考 ESP32 ListeningMode",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("监听模式: auto/manual/realtime，默认 realtime"),
            required = listOf("mode")
        )
    ) { args ->
        val mode = (args["mode"] as? String)?.lowercase()
            ?: throw Exception("mode 必须为 auto/manual/realtime")
        val validModes = setOf("auto", "manual", "realtime")
        if (mode !in validModes) throw Exception("mode 必须为 auto/manual/realtime，当前值: $mode")
        AudioSettings.setListeningMode(AppContainer.context,
            com.xiaozhi.aiservice.domain.model.VoiceMode.fromServerValue(mode))
        JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", mode)
            addProperty("message", "监听模式已设置为 $mode")
        }
    }

    MCPToolRegistry.registerTool(
        name = "get_listening_mode",
        description = "获取当前小智 AI 的监听模式",
        inputSchema = jsonSchema()
    ) { _ ->
        val mode = AudioSettings.getListeningMode(AppContainer.context)
        JsonObject().apply {
            addProperty("mode", mode.serverValue)
            addProperty("display_name", mode.displayName)
        }
    }

    MCPToolRegistry.registerTool(
        name = "set_aec_mode",
        description = "设置 AEC（声学回声消除）模式：off（关闭）/ device（设备端 AEC）。AEC 可减少 AI 说话时麦克风采集回声的问题",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("AEC模式: off/device，默认 off")
        )
    ) { args ->
        val mode = (args["mode"] as? String)?.lowercase() ?: "off"
        val validModes = setOf("off", "device")
        if (mode !in validModes) throw Exception("mode 必须为 off/device")
        val aecMode = DeviceSettings.AecMode.fromValue(mode)
        DeviceSettings.setAecMode(AppContainer.context, aecMode)
        // 立即应用 AEC 状态
        AppContainer.audioService.setAecEnabled(aecMode != DeviceSettings.AecMode.OFF)
        val isSupported = AppContainer.audioService.isAecSupported()
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

    MCPToolRegistry.registerTool(
        name = "get_device_state",
        description = "获取小智 AI 设备的当前状态（设备状态机）",
        inputSchema = jsonSchema()
    ) { _ ->
        val connRepo = AppContainer.connectionRepository
        val aiState = connRepo.aiState.value
        val deviceState = connRepo.deviceState.value
        JsonObject().apply {
            addProperty("device_state", deviceState.name)
            addProperty("device_state_display", deviceState.displayName)
            addProperty("ai_state", aiState.name)
        }
    }
}
