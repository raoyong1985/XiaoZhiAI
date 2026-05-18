package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContainer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun registerExtraTools() {

    // ──────────────── clipboard ────────────────
    MCPToolRegistry.registerTool(
        name = "clipboard",
        description = "读取或写入系统剪贴板。读取时返回剪贴板文本内容，写入时设置新文本到剪贴板",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: read（读取）/ write（写入），默认 read"),
            "text" to jsonStringProp("写入模式下的文本内容"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "read"
        val ctx = AppContainer.context
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

    // ──────────────── set_ringer_mode ────────────────
    MCPToolRegistry.registerTool(
        name = "set_ringer_mode",
        description = "设置手机响铃模式：normal（正常响铃）/ silent（静音）/ vibrate（仅震动）",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("模式: normal/silent/vibrate"),
            required = listOf("mode")
        )
    ) { args ->
        val mode = (args["mode"] as? String)?.lowercase() ?: throw Exception("mode 不能为空")
        val ctx = AppContainer.context
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: throw Exception("无法获取 AudioManager")

        when (mode) {
            "normal" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            "silent" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
            "vibrate" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
            else -> throw Exception("不支持的 mode: $mode，支持 normal/silent/vibrate")
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", mode)
            addProperty("message", when (mode) {
                "normal" -> "已切换到响铃模式"
                "silent" -> "已切换到静音模式"
                "vibrate" -> "已切换到震动模式"
                else -> ""
            })
        }
    }

    // ──────────────── ring_phone ────────────────
    MCPToolRegistry.registerTool(
        name = "ring_phone",
        description = "让手机持续响铃+震动（找手机功能），即使当前是静音/震动模式也会强制响铃。调用后 30 秒自动停止",
        inputSchema = jsonSchema(
            "duration_seconds" to jsonNumberProp("响铃持续时间（秒），默认 15，最大 60"),
        )
    ) { args ->
        val ctx = AppContainer.context
        val duration = (args["duration_seconds"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 15
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: throw Exception("无法获取 AudioManager")

        // 保存原始模式
        val originalMode = audioManager.ringerMode

        // 强制响铃+震动
        audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL

        // 获取最大铃声音量并播放
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVol, 0)

        // 震动
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 500, 500), 0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 500, 500), 0)
            }
        }

        // 自动恢复
        GlobalScope.launch {
            delay(duration * 1000L)
            try {
                audioManager.ringerMode = originalMode
                vibrator?.cancel()
                audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_RING,
                    audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING) / 3,
                    0
                )
            } catch (_: Exception) {}
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("duration_seconds", duration)
            addProperty("message", "手机正在响铃中，$duration 秒后自动停止")
        }
    }

    // ──────────────── wifi_scan ────────────────
    MCPToolRegistry.registerTool(
        name = "wifi_scan",
        description = "扫描附近的 WiFi 网络，返回 SSID、信号强度、加密方式等信息",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 20"),
        )
    ) { args ->
        val ctx = AppContainer.context
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 20
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw Exception("此设备不支持 WiFi")

        if (!wifiManager.isWifiEnabled) throw Exception("WiFi 已关闭，请先打开 WiFi")

        // 触发扫描
        val scanSuccess = wifiManager.startScan()
        if (!scanSuccess) {
            // 可能扫描太频繁，返回上次结果
        }

        // 等待短时间收集扫描结果
        delay(1500)

        val results = wifiManager.scanResults
            .sortedByDescending { it.level }
            .take(maxResults)

        val arr = JsonArray()
        results.forEach { scanResult ->
            @Suppress("DEPRECATION")
            val sr = scanResult as android.net.wifi.ScanResult
            arr.add(JsonObject().apply {
                addProperty("rssi", sr.level)
                addProperty("frequency", sr.frequency)
                addProperty("channel", if (sr.frequency in 2412..2484) "2.4GHz"
                    else if (sr.frequency in 5170..5825) "5GHz" else "6GHz")
                addProperty("capabilities", sr.capabilities)
                addProperty("signal_quality", when {
                    sr.level > -50 -> "Excellent"
                    sr.level > -70 -> "Good"
                    sr.level > -80 -> "Fair"
                    else -> "Poor"
                })
            })
        }
        JsonObject().apply {
            add("networks", arr)
            addProperty("count", arr.size())
            addProperty("scan_initiated", scanSuccess)
        }
    }

    // ──────────────── bluetooth_scan ────────────────
    MCPToolRegistry.registerTool(
        name = "bluetooth_scan",
        description = "扫描附近的蓝牙设备，返回设备名称、地址、类型等信息",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 20"),
        )
    ) { args ->
        val ctx = AppContainer.context

        // 检查权限
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) openAppPermissionSettings("蓝牙扫描权限不足（BLUETOOTH_SCAN）")
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) openAppPermissionSettings("蓝牙连接权限不足（BLUETOOTH_CONNECT）")
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw Exception("此设备不支持蓝牙")

        if (!adapter.isEnabled) throw Exception("蓝牙已关闭，请先开启蓝牙")

        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 20

        // 开始扫描（使用旧 API 兼容）
        val foundDevices = mutableListOf<Pair<String, String>>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) return
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val name = device.name ?: "(未知)"
                    if (foundDevices.none { it.second == device.address }) {
                        foundDevices.add(name to device.address)
                    }
                }
            }
        }

        ctx.registerReceiver(receiver, android.content.IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
        ctx.registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND))
        ctx.registerReceiver(receiver, android.content.IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))

        adapter.startDiscovery()
        delay(5000) // 扫描 5 秒
        adapter.cancelDiscovery()

        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}

        // 也包含已配对设备
        val bonded = adapter.bondedDevices?.map { (it.name ?: "(未知)") to it.address } ?: emptyList()
        val allDevices = (bonded + foundDevices).distinctBy { it.second }.take(maxResults)

        val arr = JsonArray()
        allDevices.forEach { (name, address) ->
            arr.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("address", address)
                addProperty("paired", bonded.any { it.second == address })
                addProperty("nearby", foundDevices.any { it.second == address })
            })
        }

        JsonObject().apply {
            add("devices", arr)
            addProperty("count", arr.size())
            addProperty("bluetooth_enabled", adapter.isEnabled)
        }
    }

    // ──────────────── sensor_data ────────────────
    MCPToolRegistry.registerTool(
        name = "sensor_data",
        description = "读取设备传感器数据，支持光感(light)、距离(proximity)、加速度(accelerometer)、陀螺仪(gyroscope)等",
        inputSchema = jsonSchema(
            "type" to jsonStringProp("传感器类型: light/proximity/accelerometer/gyroscope/all，默认 all"),
        )
    ) { args ->
        val sensorType = (args["type"] as? String)?.lowercase() ?: "all"
        val ctx = AppContainer.context
        val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: throw Exception("此设备不支持传感器")

        data class SensorReading(val name: String, val values: List<Float>, val accuracy: Int)

        suspend fun readSensor(type: Int, sensorName: String): SensorReading? {
            val sensor = sensorManager.getDefaultSensor(type) ?: return null
            return suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        cont.resume(SensorReading(sensorName, event.values.toList(), event.accuracy)) {}
                    }
                    override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        }

        val sensorTypes = when (sensorType) {
            "light" -> listOf(Sensor.TYPE_LIGHT to "光照")
            "proximity" -> listOf(Sensor.TYPE_PROXIMITY to "距离")
            "accelerometer" -> listOf(Sensor.TYPE_ACCELEROMETER to "加速度")
            "gyroscope" -> listOf(Sensor.TYPE_GYROSCOPE to "陀螺仪")
            "all" -> listOf(
                Sensor.TYPE_LIGHT to "光照",
                Sensor.TYPE_PROXIMITY to "距离",
                Sensor.TYPE_ACCELEROMETER to "加速度",
                Sensor.TYPE_GYROSCOPE to "陀螺仪",
                Sensor.TYPE_PRESSURE to "气压",
                Sensor.TYPE_AMBIENT_TEMPERATURE to "温度",
                Sensor.TYPE_MAGNETIC_FIELD to "磁场"
            )
            else -> throw Exception("不支持的传感器类型: $sensorType")
        }

        val results = sensorTypes.mapNotNull { (type, name) ->
            try { readSensor(type, name) } catch (_: Exception) { null }
        }

        val arr = JsonArray()
        results.forEach { reading ->
            arr.add(JsonObject().apply {
                addProperty("sensor", reading.name)
                addProperty("accuracy", reading.accuracy)
                val vals = JsonArray()
                reading.values.forEach { vals.add(it) }
                add("values", vals)
            })
        }

        JsonObject().apply {
            add("sensors", arr)
            addProperty("count", arr.size())
            addProperty("message", if (arr.size() == 0) "未找到请求的传感器" else "")
        }
    }

    // ──────────────── vpn_status ────────────────
    MCPToolRegistry.registerTool(
        name = "vpn_status",
        description = "检测设备是否正在使用 VPN 连接",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = AppContainer.context
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var vpnActive = false
        var vpnName = ""

        // 方法1：NetworkCapabilities
        if (Build.VERSION.SDK_INT >= 21) {
            val networks = cm?.allNetworks ?: emptyArray()
            for (network in networks) {
                val caps = cm?.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnActive = true
                    break
                }
            }
        }

        // 方法2：检查 NetworkInterface (兼容旧版本)
        if (!vpnActive) {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isUp && (iface.name.contains("tun") || iface.name.contains("ppp")
                            || iface.name.contains("tap") || iface.name.contains("ipsec"))) {
                        vpnActive = true
                        vpnName = iface.name
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        JsonObject().apply {
            addProperty("vpn_active", vpnActive)
            addProperty("interface", vpnName)
            addProperty("status", if (vpnActive) "VPN 已连接" else "VPN 未连接")
        }
    }
}
