package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import androidx.core.app.ActivityCompat
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
fun registerLocationTools(registry: MCPToolRegistry) {
    registry.registerTool(
        name = "get_location",
        description = "获取设备当前地理位置（经纬度），需要定位权限",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val permCheck = checkPermissionWithGuide(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            permissionName = "位置信息"
        )
        if (permCheck != null) return@registerTool permCheck
        try {
            val loc = getCurrentLocation()
            if (loc != null) {
                JsonObject().apply {
                    addProperty("latitude", loc.first)
                    addProperty("longitude", loc.second)
                    addProperty("provider", "fused")
                    addProperty("status", "ok")
                }
            } else {
                JsonObject().apply {
                    addProperty("status", "unavailable")
                    addProperty("message", "无法获取位置，请检查定位权限或确保定位服务已开启")
                }
            }
        } catch (e: Exception) {
            JsonObject().apply {
                addProperty("status", "unavailable")
                addProperty("message", "获取位置失败，可能设备不支持 Google Play 服务（非 GMS 设备）。请尝试启用 GPS 或连接网络重试")
                addProperty("error", e.message ?: "unknown")
            }
        }
    }

}
