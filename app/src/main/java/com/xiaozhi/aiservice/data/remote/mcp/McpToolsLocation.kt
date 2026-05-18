package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContainer

fun registerLocationTools() {
    MCPToolRegistry.registerTool(
        name = "get_location",
        description = "获取设备当前地理位置（经纬度），需要定位权限",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = AppContainer.context
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            openAppPermissionSettings("定位权限不足")
        }
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
    }

    MCPToolRegistry.registerTool(
        name = "get_weather",
        description = "获取指定城市的天气信息，包括温度、湿度、风速、天气状况等",
        inputSchema = jsonSchema(
            "city" to jsonStringProp("城市名称，默认为设备当前位置")
        )
    ) { args ->
        val city = args["city"] as? String ?: ""
        try {
            val (lat, lon) = if (city.isNotEmpty()) {
                geoCodeCity(city)
            } else {
                val loc = getCurrentLocation() ?: throw Exception("无法获取当前位置，请授予定位权限或提供城市名称")
                Pair(loc.first, loc.second)
            }

            val cacheKey = "${lat},${lon}"
            evictExpiredWeatherCache()  // 清理过期条目后再查缓存
            val cached = weatherCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < 300_000) {
                return@registerTool cached.data
            }

            val weatherJson = httpGetWeather(lat, lon)
            val displayCity = if (city.isNotEmpty()) city else reverseGeoCode(lat, lon)

            val current = weatherJson.getAsJsonObject("current")
            val temp = current.get("temperature_2m").asDouble
            val weatherCode = current.get("weather_code").asInt

            val result = JsonObject().apply {
                addProperty("city", displayCity)
                addProperty("latitude", lat)
                addProperty("longitude", lon)
                addProperty("temperature", temp)
                addProperty("feels_like", current.get("apparent_temperature").asDouble)
                addProperty("humidity", current.get("relative_humidity_2m").asInt)
                addProperty("precipitation", current.get("precipitation").asDouble)
                addProperty("wind_speed", current.get("wind_speed_10m").asDouble)
                addProperty("weather_code", weatherCode)
                addProperty("weather", WEATHER_CODES[weatherCode] ?: "未知($weatherCode)")
                addProperty("timezone", weatherJson.get("timezone").asString)
            }
            weatherCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
            result
        } catch (e: Exception) {
            throw Exception("获取天气失败: ${e.message}")
        }
    }
}
