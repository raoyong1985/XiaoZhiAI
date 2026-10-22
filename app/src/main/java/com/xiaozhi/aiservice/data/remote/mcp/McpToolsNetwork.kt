package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
@Suppress("DEPRECATION")
fun registerNetworkTools(registry: MCPToolRegistry) {
    registry.registerTool(
        name = "get_network_info",
        description = "获取当前设备的网络状态，包括 WiFi/移动网络、IP 地址、信号强度等",
        inputSchema = jsonSchema()
    ) { _ ->
        val context = registry.context
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val network = cm?.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }

        val networkType = when {
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            network != null -> "Unknown"
            else -> "None"
        }

        val wifiInfo = wifiManager?.connectionInfo
        val ip = wifiInfo?.ipAddress ?: 0
        val rssi = wifiInfo?.rssi ?: -127

        JsonObject().apply {
            addProperty("connected", network != null)
            addProperty("type", networkType)
            addProperty("wifi_ssid", wifiInfo?.ssid?.replace("\"", "") ?: "Unknown")
            addProperty("wifi_bssid", wifiInfo?.bssid ?: "Unknown")
            addProperty("wifi_rssi", rssi)
            addProperty("wifi_link_speed", wifiInfo?.linkSpeed ?: -1)
            addProperty("ip_address", "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}")
            addProperty("network_score", when {
                networkType == "WiFi" && rssi > -50 -> "Excellent"
                networkType == "WiFi" && rssi > -70 -> "Good"
                networkType == "WiFi" && rssi > -80 -> "Fair"
                network != null -> "Poor"
                else -> "No connection"
            })
        }
    }

    registry.registerTool(
        // 设备端必须加前缀：服务端自带一个 web_search，同名会被服务端 MCP 管理器拒绝
        // （客户端弹「Duplicate tool names: web_search」）
        name = "device_web_search",
        description = "搜索网页获取实时信息，返回搜索结果列表",
        inputSchema = jsonSchema(
            "query" to jsonStringProp("搜索关键词"),
            "max_results" to jsonNumberProp("最多返回结果数量，默认 5"),
            required = listOf("query")
        )
    ) { args ->
        val query = (args["query"] as? String).takeIf { !it.isNullOrEmpty() }
            ?: throw Exception("query 不能为空")
        val maxResults = (args["max_results"] as? Number)?.toInt() ?: 5
        val results = webSearch(query, maxResults)
        val resultsArray = JsonArray()
        results.forEach { r ->
            resultsArray.add(JsonObject().apply {
                addProperty("title", r.title)
                addProperty("url", r.url)
                addProperty("snippet", r.snippet)
            })
        }
        JsonObject().apply {
            add("results", resultsArray)
            addProperty("query", query)
            addProperty("count", results.size)
        }
    }
}
