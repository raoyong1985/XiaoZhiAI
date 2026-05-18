package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.data.settings.DeviceSettings
import com.xiaozhi.aiservice.di.AppContainer

fun registerNetworkTools() {
    MCPToolRegistry.registerTool(
        name = "get_network_info",
        description = "获取当前设备的网络状态，包括 WiFi/移动网络、IP 地址、信号强度等",
        inputSchema = jsonSchema()
    ) { _ ->
        val context = AppContainer.context
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

    MCPToolRegistry.registerTool(
        name = "get_public_ip",
        description = "获取设备的公网 IP 地址和所在地",
        inputSchema = jsonSchema()
    ) { _ ->
        try {
            val text = httpGet("https://api.ipify.org?format=json", timeoutMs = 5000)
            if (text.isEmpty()) throw Exception("请求失败")
            val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
            val ip = json?.get("ip")?.asString ?: text.trim()
            val locText = httpGet("https://ipapi.co/$ip/json/", timeoutMs = 5000)
            var city = ""; var country = ""; var isp = ""
            try {
                val loc = JsonParser.parseString(locText).asJsonObject
                city = loc.get("city")?.asString ?: ""
                country = loc.get("country_name")?.asString ?: ""
                isp = loc.get("org")?.asString ?: ""
            } catch (_: Exception) {}
            JsonObject().apply {
                addProperty("ip", ip)
                if (city.isNotEmpty()) addProperty("city", city)
                if (country.isNotEmpty()) addProperty("country", country)
                if (isp.isNotEmpty()) addProperty("isp", isp)
            }
        } catch (e: Exception) { throw Exception("获取公网 IP 失败: ${e.message}") }
    }

    MCPToolRegistry.registerTool(
        name = "web_search",
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

    MCPToolRegistry.registerTool(
        name = "get_news",
        description = "获取最新新闻头条，支持指定类别",
        inputSchema = jsonSchema(
            "category" to jsonStringProp("新闻类别：top/general/world/business/technology/science/sports/entertainment，默认 top"),
            "country" to jsonStringProp("国家代码：cn/us/jp/etc，默认 cn"),
            "max_results" to jsonNumberProp("最多返回条数，默认 5"),
        )
    ) { args ->
        val category = (args["category"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: "top"
        val country = (args["country"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: "cn"
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
        val catParam = if (category == "top") "" else "&category=$category"
        val newsApiKey = DeviceSettings.getNewsApiKey(AppContainer.context)
        val url = if (newsApiKey.isNotBlank()) {
            "https://newsapi.org/v2/top-headlines?country=$country$catParam&pageSize=$maxResults&apiKey=$newsApiKey"
        } else ""
        try {
            var text = if (url.isNotEmpty()) httpGet(url, timeoutMs = 6000) else ""
            var json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
            var articles = json?.getAsJsonArray("articles")
            if (articles == null || articles.size() == 0) {
                text = httpGet("https://tenapi.cn/v2/topnews?type=hot", timeoutMs = 5000)
                json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
                articles = json?.getAsJsonArray("data")
                if (articles != null) {
                    val arr = JsonArray()
                    for (i in 0 until minOf(articles.size(), maxResults)) {
                        val a = articles[i].asJsonObject
                        arr.add(JsonObject().apply {
                            addProperty("title", a.get("title")?.asString ?: "")
                            addProperty("desc", a.get("desc")?.asString ?: a.get("content")?.asString ?: "")
                        })
                    }
                    return@registerTool JsonObject().apply { add("articles", arr); addProperty("count", arr.size()) }
                }
            }
            if (articles != null) {
                val arr = JsonArray()
                for (i in 0 until minOf(articles.size(), maxResults)) {
                    val a = articles[i].asJsonObject
                    arr.add(JsonObject().apply {
                        addProperty("title", a.get("title")?.asString ?: "")
                        addProperty("description", a.get("description")?.asString ?: "")
                        addProperty("source", a.getAsJsonObject("source")?.get("name")?.asString ?: "")
                        addProperty("url", a.get("url")?.asString ?: "")
                    })
                }
                JsonObject().apply { add("articles", arr); addProperty("count", arr.size()) }
            } else throw Exception("未获取到新闻")
        } catch (e: Exception) { throw Exception("获取新闻失败: ${e.message}") }
    }
}
