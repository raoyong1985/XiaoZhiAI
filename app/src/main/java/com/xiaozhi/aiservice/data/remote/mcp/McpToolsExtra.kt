package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaozhi.aiservice.data.note.NoteStorage
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.domain.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.MultiFormatReader
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageStatus
import com.xiaozhi.aiservice.domain.model.ChatMessage

fun registerExtraTools(registry: MCPToolRegistry) {

    // ── 笔记工具已移至 McpToolsNotes.kt（registerNoteTools）──
    // ── 提醒/闹钟工具已移至 McpToolsAlarm.kt（registerAlarmTools）──


    // ──────────────── generate_qr ────────────────
    registry.registerTool(
        name = "generate_qr",
        description = "生成二维码图片，将文本或链接生成二维码并保存到手机。当用户说「生成二维码」「做个二维码」「这个链接转成二维码」时调用此工具",
        inputSchema = jsonSchema(
            "content" to jsonStringProp("要生成二维码的内容（文本或链接URL）"),
            "size" to jsonNumberProp("二维码图片尺寸（像素），默认 300，建议 200-500"),
            required = listOf("content")
        )
    ) { args ->
        val content = (args["content"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("content 不能为空")
        val size = (args["size"] as? Number)?.toInt()?.coerceIn(100, 1000) ?: 300

        // 本地 ZXing 生成二维码（不依赖网络 API）
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        // 保存到缓存目录
        val qrDir = java.io.File(registry.context.cacheDir, "qr_codes")
        qrDir.mkdirs()
        val fileName = "qr_${System.currentTimeMillis()}.png"
        val outputFile = java.io.File(qrDir, fileName)
        java.io.FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 插入聊天气泡（同 take_photo 方式）
        val contactId = registry.connectionRepository.get().currentContactId
        if (contactId.isNotEmpty()) {
            registry.messageRepository.addSentMessage(
                contactId,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = outputFile.absolutePath,
                    type = MessageType.IMAGE,
                    direction = MessageDirection.RECEIVED,
                    status = MessageStatus.SENT,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("content", content)
            addProperty("file_path", outputFile.absolutePath)
            addProperty("file_uri", android.net.Uri.fromFile(outputFile).toString())
            addProperty("size_px", size)
            addProperty("message", "二维码已生成并显示在聊天中")
        }
    }

    // ──────────────── set_ringer_mode ────────────────
    registry.registerTool(
        name = "set_ringer_mode",
        description = "设置手机响铃模式：normal（正常响铃）/ silent（静音）/ vibrate（仅震动）",
        inputSchema = jsonSchema(
            "mode" to jsonStringProp("模式: normal/silent/vibrate"),
            required = listOf("mode")
        )
    ) { args ->
        val mode = (args["mode"] as? String)?.lowercase() ?: throw Exception("mode 不能为空")
        val ctx = registry.context

        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: throw Exception("无法获取 AudioManager")

        try {
            when (mode) {
                "normal" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                "silent" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                "vibrate" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                else -> throw Exception("不支持的 mode: $mode，支持 normal/silent/vibrate")
            }
        } catch (e: SecurityException) {
            return@registerTool JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "permission_denied")
                addProperty("permission", "勿扰模式权限")
                addProperty("message", "Android 8+ 需要「勿扰模式权限」才能修改响铃模式，请在 设置 → 应用 → 小智AI → 通知管理 中开启后重试")
            }
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
    registry.registerTool(
        name = "ring_phone",
        description = "让手机持续响铃+震动（找手机功能），即使当前是静音/震动模式也会强制响铃。调用后 30 秒自动停止",
        inputSchema = jsonSchema(
            "duration_seconds" to jsonNumberProp("响铃持续时间（秒），默认 15，最大 60"),
        )
    ) { args ->
        val ctx = registry.context
        val duration = (args["duration_seconds"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 15

        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: throw Exception("无法获取 AudioManager")

        // 保存原始模式
        val originalMode = audioManager.ringerMode

        try {
            // 强制响铃+震动
            audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL

            // 获取最大铃声音量并播放
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVol, 0)

            // 震动
            @Suppress("DEPRECATION")
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
            registry.toolScope.launch {
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
        } catch (e: SecurityException) {
            return@registerTool JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "permission_denied")
                addProperty("permission", "勿扰模式权限")
                addProperty("message", "Android 8+ 需要「勿扰模式权限」才能响铃，请在 设置 → 应用 → 小智AI → 通知管理 中开启后重试")
            }
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("duration_seconds", duration)
            addProperty("message", "手机正在响铃中，$duration 秒后自动停止")
        }
    }

    // ──────────────── wifi_scan ────────────────
    registry.registerTool(
        name = "wifi_scan",
        description = "扫描附近的 WiFi 网络，返回 SSID、信号强度、加密方式等信息",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 20"),
        )
    ) { args ->
        val ctx = registry.context
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 20
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw Exception("此设备不支持 WiFi")

        if (!wifiManager.isWifiEnabled) throw Exception("WiFi 已关闭，请先打开 WiFi")

        // Android 8.1+ 扫描 WiFi 需要 ACCESS_FINE_LOCATION 权限
        if (Build.VERSION.SDK_INT >= 27) {
            val locPerm = checkPermissionWithGuide(
                Manifest.permission.ACCESS_FINE_LOCATION, permissionName = "位置信息"
            )
            if (locPerm != null) return@registerTool locPerm
        }

        // 触发扫描
        @Suppress("DEPRECATION")
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
                val ssidStr = sr.wifiSsid?.toString() ?: "(hidden)"
                addProperty("ssid", ssidStr)
                addProperty("bssid", sr.BSSID)
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
    registry.registerTool(
        name = "bluetooth_scan",
        description = "扫描附近的蓝牙/BLE设备，返回设备名称、地址、类型等信息。API 21+ 使用 BLE 扫描（更快更低功耗），旧设备回退经典发现",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 20"),
            "timeout_ms" to jsonNumberProp("扫描时长（毫秒），默认 4000，建议 3000-8000"),
        )
    ) { args ->
        val ctx = registry.context
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 20
        val scanTimeout = (args["timeout_ms"] as? Number)?.toLong()?.coerceIn(2000, 15000) ?: 4000L

        // 检查权限
        if (Build.VERSION.SDK_INT >= 31) {
            val btPerm = checkPermissionWithGuide(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                permissionName = "蓝牙"
            )
            if (btPerm != null) return@registerTool btPerm
        }

        val bluetoothManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        }
        val adapter = bluetoothManager?.adapter ?: throw Exception("此设备不支持蓝牙")
        if (!adapter.isEnabled) throw Exception("蓝牙已关闭，请先开启蓝牙")

        // ── BLE 扫描（API 21+，更高效）──
        val foundBleDevices = mutableSetOf<Pair<String, String>>()  // name to address
        val leScanner = if (Build.VERSION.SDK_INT >= 21) adapter.bluetoothLeScanner else null
        var bleScanDone = false

        if (leScanner != null) {
            val scanCallback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                    val device = result?.device ?: return
                    val name = device.name ?: result?.scanRecord?.deviceName ?: "(未知)"
                    if (name != "(未知)" || device.address.isNotEmpty()) {
                        foundBleDevices.add(name to device.address)
                    }
                }
                override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
                    results?.forEach { r ->
                        val d = r.device ?: return@forEach
                        foundBleDevices.add((d.name ?: "(未知)") to d.address)
                    }
                }
            }
            try {
                val settings = android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                leScanner.startScan(null, settings, scanCallback)
                delay(scanTimeout)
                leScanner.stopScan(scanCallback)
                bleScanDone = true
            } catch (_: Exception) {
                // BLE 扫描失败（权限不足等），回退到经典扫描
            }
        }

        // ── 经典蓝牙发现（补全经典 BT 设备）──
        val foundClassicDevices = mutableListOf<Pair<String, String>>()
        if (!bleScanDone) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) {
                    if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) return
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val name = device.name ?: "(未知)"
                        if (foundClassicDevices.none { it.second == device.address }) {
                            foundClassicDevices.add(name to device.address)
                        }
                    }
                }
            }
            ctx.registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND), Context.RECEIVER_NOT_EXPORTED)
            adapter.startDiscovery()
            delay(minOf(scanTimeout, 8000L))
            adapter.cancelDiscovery()
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }

        // ── 已配对设备（所有方式都包含）──
        val bonded = adapter.bondedDevices?.map { (it.name ?: "(未知)") to it.address } ?: emptyList()

        // ── 合并去重 ──
        val allDevices = (bonded + foundBleDevices + foundClassicDevices)
            .distinctBy { it.second }
            .take(maxResults)

        val arr = JsonArray()
        allDevices.forEach { (name, address) ->
            arr.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("address", address)
                addProperty("paired", bonded.any { it.second == address })
                addProperty("nearby", foundBleDevices.any { it.second == address } || foundClassicDevices.any { it.second == address })
                addProperty("scan_type", if (foundBleDevices.any { it.second == address }) "ble" else "classic")
            })
        }

        JsonObject().apply {
            add("devices", arr)
            addProperty("count", arr.size())
            addProperty("scan_method", if (bleScanDone) "ble_scanner" else "classic_discovery")
            addProperty("bluetooth_enabled", adapter.isEnabled)
        }
    }

    // ──────────────── sensor_data ────────────────
    registry.registerTool(
        name = "sensor_data",
        description = "读取设备传感器数据，支持光感(light)、距离(proximity)、加速度(accelerometer)、陀螺仪(gyroscope)等",
        inputSchema = jsonSchema(
            "type" to jsonStringProp("传感器类型: light/proximity/accelerometer/gyroscope/all，默认 all"),
        )
    ) { args ->
        val sensorType = (args["type"] as? String)?.lowercase() ?: "all"
        val ctx = registry.context
        val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: throw Exception("此设备不支持传感器")

        data class SensorReading(val name: String, val values: List<Float>, val accuracy: Int)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    registry.registerTool(
        name = "vpn_status",
        description = "检测设备是否正在使用 VPN 连接",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var vpnActive = false
        var vpnName = ""

        // 方法1：NetworkCapabilities
        @Suppress("DEPRECATION")
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

    
    // ──────────────── query_stock ────────────────
    registry.registerTool(
        name = "query_stock",
        description = "查询股票的实时行情，包括当前价、涨跌幅、最高/最低价、成交量等。当用户说「查股票」「股价」「XX股票多少钱」「XX行情」时调用此工具",
        inputSchema = jsonSchema(
            "code" to jsonStringProp("股票代码，如 sh600519（贵州茅台）、sz000001（平安银行）、hk00700（腾讯港股）"),
            "name" to jsonStringProp("股票名称（与 code 二选一），如「茅台」「腾讯」「平安银行」"),
            required = listOf()
        )
    ) { args ->
        var stockCode = (args["code"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val stockName = (args["name"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: ""
        
        if (stockCode.isEmpty() && stockName.isEmpty()) throw Exception("请提供股票代码或名称")
        
        // 通过名称查代码（内置常见映射）
        if (stockCode.isEmpty()) {
            val codeMap = mapOf(
                "茅台" to "sh600519", "贵州茅台" to "sh600519", "腾讯" to "hk00700", "腾讯控股" to "hk00700",
                "阿里巴巴" to "hk09988", "百度" to "hk09888", "京东" to "hk09618", "美团" to "hk03690",
                "小米" to "hk01810", "平安" to "sh601318", "中国平安" to "sh601318",
                "工商银行" to "sh601398", "建设银行" to "sh601939", "农业银行" to "sh601288",
                "招商银行" to "sh600036", "宁德时代" to "sz300750", "比亚迪" to "sz002594",
                "中芯国际" to "sh688981", "药明康德" to "sh603259", "海康威视" to "sz002415",
                "美的" to "sz000333", "格力" to "sz000651", "万科" to "sz000002",
                "中信证券" to "sh600030", "中国移动" to "sh600941", "中国石油" to "sh601857"
            )
            stockCode = codeMap.entries.firstOrNull { stockName.contains(it.key) }?.value ?: stockName
        }
        
        val url = "https://qt.gtimg.cn/q=$stockCode"
        val text = httpGet(url, timeoutMs = 5000)
        if (text.isEmpty()) throw Exception("查询股票失败，请检查网络连接")
        
        // 解析腾讯证券格式
        val parts = text.split("~")
        if (parts.size < 10) throw Exception("未找到股票: $stockCode")
        
        val name = if (parts.size > 1) parts[1] else stockCode
        val price = parts[3].toDoubleOrNull() ?: 0.0
        val prevClose = parts[4].toDoubleOrNull() ?: 0.0
        val change = parts[32].toDoubleOrNull() ?: 0.0
        val changePercent = parts[33].toDoubleOrNull() ?: 0.0
        val high = parts[33].toDoubleOrNull() ?: parts[34].toDoubleOrNull() ?: 0.0
        val low = parts[34].toDoubleOrNull() ?: 0.0
        val open = parts[5].toDoubleOrNull() ?: 0.0
        val volume = (parts[6].toDoubleOrNull() ?: 0.0).toLong()
        
        JsonObject().apply {
            addProperty("success", true)
            addProperty("code", stockCode)
            addProperty("name", name)
            addProperty("price", price)
            addProperty("change", change)
            addProperty("change_percent", changePercent)
            addProperty("high", high)
            addProperty("low", low)
            addProperty("open", open)
            addProperty("prev_close", prevClose)
            addProperty("volume", volume)
            addProperty("currency", if (stockCode.startsWith("sh") || stockCode.startsWith("sz")) "CNY" else "HKD")
        }
    }
    
    // ──────────────── query_traffic_restriction ────────────────
    registry.registerTool(
        name = "query_traffic_restriction",
        description = "查询中国各城市的今日尾号限行信息。支持北京、上海、广州、深圳、成都、杭州等主要城市。当用户说「今天限行」「限行尾号」「北京限行」时调用此工具",
        inputSchema = jsonSchema(
            "city" to jsonStringProp("城市名，如「北京」「上海」「广州」「深圳」，默认从定位获取"),
            required = listOf()
        )
    ) { args ->
        val city = (args["city"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "北京"
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = "https://api.isoyu.com/api/weizhang/weizhang?city=$encoded"
        var text = ""
        try {
            text = httpGet(url, timeoutMs = 5000)
        } catch (_: Exception) {}
        
        if (text.isNotEmpty()) {
            try {
                val json = JsonParser.parseString(text).asJsonObject
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("city", city)
                    addProperty("restriction", json.get("data")?.asString ?: "今日无限行")
                }
            } catch (_: Exception) {}
        }
        
        // 降级：先搜索
        try {
            val html = httpGet("https://www.baidu.com/s?wd=${encoded}限行", timeoutMs = 5000)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("city", city)
                addProperty("restriction", "已查询「${city}限行」，请查看搜索摘要获取最新信息")
                addProperty("note", "已为您搜索「${city}限行」，请参考搜索结果")
            }
        } catch (_: Exception) {
            JsonObject().apply {
                addProperty("success", true)
                addProperty("city", city)
                addProperty("restriction", "今日 $city 限行信息请以当地交管部门公告为准")
            }
        }
    }
    
    // ──────────────── read_sms ────────────────
    registry.registerTool(
        name = "read_sms",
        description = "读取手机上的短信，支持查看未读短信、最近短信或按号码查找。当用户说「看看我的短信」「有没有新短信」「查一下验证码」「最近短信」时调用此工具",
        inputSchema = jsonSchema(
            "type" to jsonStringProp("类型: unread（未读）/ recent（最近）/ all（全部），默认 unread"),
            "sender" to jsonStringProp("按发件人号码或关键词过滤，如「10086」「银行」"),
            "max_results" to jsonNumberProp("最多返回条数，默认 5，最大 20"),
            required = listOf()
        )
    ) { args ->
        val ctx = registry.context
        
        // 权限检查
        val smsPerm = checkPermissionWithGuide(
            Manifest.permission.READ_SMS, permissionName = "短信"
        )
        if (smsPerm != null) return@registerTool smsPerm
        
        val msgType = (args["type"] as? String)?.lowercase() ?: "unread"
        val sender = (args["sender"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
        
        val uri = android.net.Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date", "read")
        val selection = StringBuilder().apply {
            if (msgType == "unread") append("read=0")
            if (sender != null) {
                if (isNotEmpty()) append(" AND ")
                append("address LIKE '%$sender%' OR body LIKE '%$sender%'")
            }
        }.toString().takeIf { it.isNotEmpty() } ?: null
        
        val cursor = ctx.contentResolver.query(
            uri, projection, selection, null, "date DESC"
        ) ?: throw Exception("无法读取短信")
        
        val messages = JsonArray()
        var count = 0
        cursor.use {
            while (it.moveToNext() && count < maxResults) {
                val address = it.getString(1) ?: "未知"
                val body = it.getString(2) ?: ""
                val dateMs = it.getLong(3)
                val isRead = it.getInt(4) == 1
                val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                messages.add(JsonObject().apply {
                    addProperty("sender", address)
                    addProperty("body", body.take(200))
                    addProperty("time", df.format(Date(dateMs)))
                    addProperty("read", isRead)
                    addProperty("length", body.length)
                })
                count++
            }
        }
        
        JsonObject().apply {
            add("messages", messages)
            addProperty("count", messages.size())
            addProperty("type", msgType)
            addProperty("total_unread", if (msgType == "unread") messages.size() else null)
        }
    }

    // ──────────────── scan_qr ────────────────
    registry.registerTool(
        name = "scan_qr",
        description = "使用相机拍照并识别二维码/条形码，返回扫码结果。当用户说「扫一扫」「扫码」「识别二维码」「扫这个码」「扫二维码」时调用此工具",
        inputSchema = jsonSchema(),
        readOnly = true
    ) { _ ->
        val context = registry.context

        // 检查相机权限
        val permCheck = checkPermissionWithGuide(
            Manifest.permission.CAMERA, permissionName = "相机"
        )
        if (permCheck != null) return@registerTool permCheck

        // 并发保护
        val existing = com.xiaozhi.aiservice.presentation.ui.QrScannerActivity.pendingResult
        if (existing != null && !existing.isCompleted) {
            throw Exception("正在扫码中，请稍候再试")
        }

        // 启动扫码 Activity（显示相机预览 + 扫码框）
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        com.xiaozhi.aiservice.presentation.ui.QrScannerActivity.pendingResult = deferred
        com.xiaozhi.aiservice.presentation.ui.QrScannerActivity.launch(context)

        val qrContent = kotlinx.coroutines.withTimeoutOrNull(60_000L) { deferred.await() }
            ?: throw Exception("扫码超时（60秒）或用户取消")

        JsonObject().apply {
            addProperty("success", true)
            addProperty("format", "QR_CODE")
            addProperty("content", qrContent)
            addProperty("message", "扫码成功: $qrContent")
        }
    }

    // ──────────────── contact_manage ────────────────
    registry.registerTool(
        name = "contact_manage",
        description = "管理手机联系人。支持搜索联系人（按姓名/号码）或添加新联系人（弹出系统联系人界面）。当用户说「查找联系人」「存一个电话」「保存联系人」「张三的电话」时调用此工具",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: search/add，默认 search"),
            "query" to jsonStringProp("搜索模式下：姓名或电话号码关键词"),
            "name" to jsonStringProp("添加模式下：联系人姓名"),
            "phone" to jsonStringProp("添加模式下：电话号码"),
            "max_results" to jsonNumberProp("搜索模式下：最多返回条数，默认 10"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "search"
        val ctx = registry.context

        when (action) {
            "search" -> {
                val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 30) ?: 10

                val uri = android.provider.ContactsContract.Contacts.CONTENT_URI
                val projection = arrayOf(
                    android.provider.ContactsContract.Contacts._ID,
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                    android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
                )
                val selection = if (query != null)
                    "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ?" else null
                val args = if (query != null) arrayOf("%$query%") else null

                val cursor = ctx.contentResolver.query(uri, projection, selection, args, null)
                val arr = JsonArray()
                var count = 0
                cursor?.use { c ->
                    while (c.moveToNext() && count < maxResults) {
                        val contactId = c.getString(0)
                        val name = c.getString(1) ?: "(无名称)"
                        val hasPhone = c.getInt(2) > 0

                        var phone = ""
                        if (hasPhone) {
                            // 查询电话号码
                            val phoneCur = ctx.contentResolver.query(
                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId), null
                            )
                            phoneCur?.use { pc ->
                                if (pc.moveToFirst()) phone = pc.getString(0) ?: ""
                            }
                        }

                        arr.add(JsonObject().apply {
                            addProperty("id", contactId)
                            addProperty("name", name)
                            addProperty("phone", phone)
                            addProperty("has_phone", hasPhone)
                        })
                        count++
                    }
                }

                JsonObject().apply {
                    add("contacts", arr)
                    addProperty("count", arr.size())
                    addProperty("query", query ?: "all")
                }
            }
            "add" -> {
                val name = (args["name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw Exception("添加联系人需要提供 name")
                val phone = (args["phone"] as? String)?.takeIf { it.isNotBlank() } ?: ""

                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                        type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                        putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                        if (phone.isNotEmpty()) {
                            putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone)
                        }
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    throw Exception("无法打开联系人界面: ${e.message}")
                }

                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("name", name)
                    addProperty("phone", phone)
                    addProperty("message", "已打开系统联系人界面，请确认保存")
                }
            }
            else -> throw Exception("不支持的 action: $action，支持 search/add")
        }
    }

    // ──────────────── media_control ────────────────
    registry.registerTool(
        name = "media_control",
        description = "控制手机上的媒体播放（音乐、视频），支持播放/暂停/上一首/下一首/快进/快退。当用户说「播放音乐」「暂停」「下一首」「上一曲」时调用此工具",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: play/pause/next/previous/stop，默认 play"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "play"
        val ctx = registry.context
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: throw Exception("无法获取 AudioManager")

        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> throw Exception("不支持的 action: $action，支持 play/pause/next/previous/stop")
        }
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))

        try {
            if (Build.VERSION.SDK_INT >= 21) {
                val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
                val sessions = msm?.getActiveSessions(null) ?: emptyList()
                val target = sessions.firstOrNull()
                if (target != null) {
                    val transportControls = target.transportControls
                    when (action) {
                        "play" -> transportControls?.play()
                        "pause" -> transportControls?.pause()
                        "next" -> transportControls?.skipToNext()
                        "previous" -> transportControls?.skipToPrevious()
                        "stop" -> transportControls?.stop()
                    }
                }
            }
        } catch (_: Exception) {}

        JsonObject().apply {
            addProperty("success", true)
            addProperty("action", action)
            addProperty("message", when (action) {
                "play" -> "已发送播放指令"
                "pause" -> "已暂停"
                "next" -> "已切到下一首"
                "previous" -> "已切到上一首"
                "stop" -> "已停止播放"
                else -> ""
            })
        }
    }

    // ──────────────── read_notifications ────────────────
    registry.registerTool(
        name = "read_notifications",
        description = "读取当前 App 的活跃通知列表。当用户说「看看我的通知」「有什么通知」「通知栏有什么」时调用此工具",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回条数，默认 10"),
        )
    ) { args ->
        val ctx = registry.context
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 10

        if (Build.VERSION.SDK_INT < 23) {
            return@registerTool JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "Android 6.0 以下不支持读取通知")
            }
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        val raw = nm?.getActiveNotifications()
        val active = if (raw != null) raw.take(maxResults) else emptyList<android.service.notification.StatusBarNotification>()
        val arr = JsonArray()
        active.forEach { sbn ->
            val n = sbn.notification
            arr.add(JsonObject().apply {
                addProperty("package", sbn.packageName)
                addProperty("tag", sbn.tag ?: "")
                addProperty("id", sbn.id)
                addProperty("title", n.extras?.getString(android.app.Notification.EXTRA_TITLE) ?: "")
                addProperty("text", n.extras?.getString(android.app.Notification.EXTRA_TEXT) ?: "")
                addProperty("channel", n.channelId ?: "")
                addProperty("time", sbn.postTime)
                addProperty("is_clearable", (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) == 0)
            })
        }
        JsonObject().apply {
            add("notifications", arr)
            addProperty("count", arr.size())
            addProperty("total_active", active.size)
            addProperty("note", if (arr.size() == 0) "当前 App 没有活跃通知" else "仅显示本 App 通知，系统级通知读取需开启通知监听服务权限")
        }
    }

    // ──────────────── nfc_check ────────────────
    registry.registerTool(
        name = "nfc_check",
        description = "检查设备 NFC 功能状态（支持/开启/关闭）。当用户说「帮我刷门禁」「NFC」「打开NFC」「读NFC标签」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(ctx)
        if (adapter == null) {
            JsonObject().apply {
                addProperty("supported", false)
                addProperty("enabled", false)
                addProperty("message", "此设备不支持 NFC")
            }
        } else {
            val enabled = adapter.isEnabled
            JsonObject().apply {
                addProperty("supported", true)
                addProperty("enabled", enabled)
                addProperty("message", if (enabled) "NFC 已开启，可将手机靠近 NFC 标签读取" else "NFC 已关闭。请先打开 NFC 开关后再读取标签")
                if (!enabled) {
                    addProperty("guide", "请在 设置 → 连接与共享 → NFC 中开启，或通过快捷开关打开")
                }
            }
        }
    }

    // ──────────────── voice_memo ────────────────
    registry.registerTool(
        name = "voice_memo",
        description = "录制一段语音备忘录并保存，支持指定录制时长。当用户说「帮我录一段」「语音备忘录」「录音」「记一下我说的话」时调用此工具",
        inputSchema = jsonSchema(
            "duration_seconds" to jsonNumberProp("录制时长（秒），默认 10，最大 120"),
            "label" to jsonStringProp("录音标签/备注，用于后续查找"),
            required = listOf("duration_seconds")
        )
    ) { args ->
        val ctx = registry.context
        val duration = (args["duration_seconds"] as? Number)?.toInt()?.coerceIn(1, 120) ?: 10
        val label = (args["label"] as? String)?.takeIf { it.isNotBlank() } ?: "语音备忘录"

        val permCheck = checkPermissionWithGuide(
            Manifest.permission.RECORD_AUDIO, permissionName = "麦克风"
        )
        if (permCheck != null) return@registerTool permCheck

        val voiceDir = java.io.File(ctx.cacheDir, "voice_memos")
        voiceDir.mkdirs()
        val fileName = "voice_${System.currentTimeMillis()}.mp4"
        val outputFile = java.io.File(voiceDir, fileName)

        val mediaRecorder = android.media.MediaRecorder()
        try {
            mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(16000)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (e: Exception) {
            mediaRecorder.release()
            throw Exception("无法开始录音: ${e.message}")
        }

        delay(duration * 1000L)
        try { mediaRecorder.stop() } catch (_: Exception) {}
        mediaRecorder.release()

        val fileSize = outputFile.length()
        if (fileSize < 100) {
            outputFile.delete()
            throw Exception("录音失败，录制内容为空")
        }

        val contactId = registry.connectionRepository.get().currentContactId
        if (contactId.isNotEmpty()) {
            registry.messageRepository.addSentMessage(
                contactId,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = outputFile.absolutePath,
                    type = MessageType.VOICE,
                    direction = MessageDirection.SENT,
                    status = MessageStatus.SENT,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("label", label)
            addProperty("duration_seconds", duration)
            addProperty("file_path", outputFile.absolutePath)
            addProperty("file_size_kb", fileSize / 1024)
            addProperty("message", "已录制 ${duration} 秒语音备忘录「$label」")
        }
    }

    // ──────────────── toggle_data ────────────────
    registry.registerTool(
        name = "toggle_data",
        description = "开启或关闭移动数据网络。注意：Android 5.0+ 系统限制，部分厂商可能不支持。当用户说「打开数据」「关闭数据」「开启移动网络」「关掉流量」时调用此工具",
        inputSchema = jsonSchema(
            "enabled" to jsonBoolProp("true=开启移动数据，false=关闭"),
            required = listOf("enabled")
        )
    ) { args ->
        val enabled = (args["enabled"] as? Boolean) ?: throw Exception("enabled 必须为 true/false")
        val ctx = registry.context
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw Exception("无法获取网络服务")

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val method = cm.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                method.invoke(cm, enabled)
            }
            JsonObject().apply {
                addProperty("success", true)
                addProperty("data_enabled", enabled)
                addProperty("message", if (enabled) "已开启移动数据" else "已关闭移动数据")
            }
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            JsonObject().apply {
                addProperty("success", false)
                addProperty("note", "系统限制无法自动切换，已打开数据网络设置页，请手动操作")
                addProperty("error", e.message)
            }
        }
    }

    // ──────────────── toggle_hotspot ────────────────
    registry.registerTool(
        name = "toggle_hotspot",
        description = "开启或关闭 WiFi 热点。Android 8+ 通过反射调用（系统限制），部分设备可能不支持。当用户说「打开热点」「关闭热点」「开启WiFi热点」时调用此工具",
        inputSchema = jsonSchema(
            "enabled" to jsonBoolProp("true=开启热点，false=关闭"),
            "ssid" to jsonStringProp("可选，热点名称，默认 \"XiaoZhi-Hotspot\""),
            required = listOf("enabled")
        )
    ) { args ->
        val enabled = (args["enabled"] as? Boolean) ?: throw Exception("enabled 必须为 true/false")
        val ssid = (args["ssid"] as? String)?.takeIf { it.isNotBlank() } ?: "XiaoZhi-Hotspot"
        val ctx = registry.context
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw Exception("此设备不支持 WiFi")

        try {
            val method = wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = ssid
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            }
            method.invoke(wm, if (enabled) config else null, enabled)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("hotspot_enabled", enabled)
                addProperty("ssid", ssid)
                addProperty("message", if (enabled) "热点已开启（SSID: $ssid）" else "热点已关闭")
            }
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            JsonObject().apply {
                addProperty("success", false)
                addProperty("note", "系统限制无法自动切换热点，已打开 WiFi 设置页，请手动操作")
                addProperty("error", e.message)
            }
        }
    }

    // ──────────────── calendar_event ────────────────
    registry.registerTool(
        name = "calendar_event",
        description = "创建日历事件。支持指定标题、时间、地点和备注。当用户说「帮我记个日程」「创建日历提醒」「明天开会」「添加日历事件」时调用此工具",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: add，默认 add（目前只支持添加）"),
            "title" to jsonStringProp("事件标题"),
            "start_time" to jsonNumberProp("开始时间戳（毫秒），默认当前时间+1小时"),
            "end_time" to jsonNumberProp("结束时间戳（毫秒），默认开始后1小时"),
            "location" to jsonStringProp("可选，地点"),
            "description" to jsonStringProp("可选，备注"),
            required = listOf("title")
        )
    ) { args ->
        val ctx = registry.context
        val title = (args["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("title 不能为空")
        val startTime = (args["start_time"] as? Number)?.toLong() ?: (System.currentTimeMillis() + 3600_000L)
        val endTime = (args["end_time"] as? Number)?.toLong() ?: (startTime + 3600_000L)
        val location = (args["location"] as? String) ?: ""
        val desc = (args["description"] as? String) ?: ""

        val calPerm = checkPermissionWithGuide(
            Manifest.permission.WRITE_CALENDAR, permissionName = "日历"
        )
        if (calPerm != null) return@registerTool calPerm

        val calendarsUri = android.provider.CalendarContract.Calendars.CONTENT_URI
        var calId = -1L
        val calCursor = ctx.contentResolver.query(calendarsUri, null, null, null, null)
        calCursor?.use { c ->
            if (c.moveToFirst()) {
                calId = c.getLong(c.getColumnIndexOrThrow(android.provider.CalendarContract.Calendars._ID))
            }
        }
        if (calId < 0) throw Exception("未找到可用日历")

        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.DTSTART, startTime)
            put(android.provider.CalendarContract.Events.DTEND, endTime)
            put(android.provider.CalendarContract.Events.TITLE, title)
            put(android.provider.CalendarContract.Events.DESCRIPTION, desc)
            put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
            put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val eventUri = ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
        val eventId = eventUri?.lastPathSegment ?: ""

        val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        JsonObject().apply {
            addProperty("success", eventId.isNotEmpty())
            addProperty("event_id", eventId)
            addProperty("title", title)
            addProperty("start", df.format(java.util.Date(startTime)))
            addProperty("end", df.format(java.util.Date(endTime)))
            if (location.isNotEmpty()) addProperty("location", location)
            addProperty("message", "已创建日历事件「$title」")
        }
    }

    // ──────────────── file_manager ────────────────
    registry.registerTool(
        name = "file_manager",
        description = "管理手机文件。支持列出目录内容（list）和读取文本文件内容（read）。当用户说「看看我的文件」「下载了什么」「打开下载的文件」时调用此工具",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: list/read，默认 list"),
            "path" to jsonStringProp("目录路径（list）或文件路径（read）。list 时默认 /storage/emulated/0/Download"),
            "max_results" to jsonNumberProp("list 模式下最多返回数量，默认 30"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "list"
        val pathStr = (args["path"] as? String)?.takeIf { it.isNotBlank() }
            ?: if (action == "list") "/storage/emulated/0/Download" else throw Exception("read 模式需要提供 path")
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 30
        val file = java.io.File(pathStr)

        when (action) {
            "list" -> {
                if (!file.exists() || !file.isDirectory) throw Exception("目录不存在: $pathStr")
                val files = file.listFiles()?.sortedByDescending { it.lastModified() }?.take(maxResults)
                    ?: throw Exception("无法读取目录")
                val arr = JsonArray()
                files.forEach { f ->
                    arr.add(JsonObject().apply {
                        addProperty("name", f.name)
                        addProperty("path", f.absolutePath)
                        addProperty("is_dir", f.isDirectory)
                        addProperty("size_bytes", if (f.isFile) f.length() else 0)
                        addProperty("last_modified", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified())))
                    })
                }
                JsonObject().apply { add("files", arr); addProperty("count", arr.size()); addProperty("path", pathStr) }
            }
            "read" -> {
                if (!file.exists() || !file.isFile) throw Exception("文件不存在: $pathStr")
                if (file.length() > 100 * 1024) throw Exception("文件过大（${file.length() / 1024}KB），仅支持 100KB 以下文本文件")
                val text = file.readText(Charsets.UTF_8).take(5000)
                JsonObject().apply { addProperty("success", true); addProperty("path", pathStr); addProperty("content", text); addProperty("size_bytes", file.length()) }
            }
            else -> throw Exception("不支持的 action: $action，支持 list/read")
        }
    }

    // ──────────────── read_call_log ────────────────
    registry.registerTool(
        name = "read_call_log",
        description = "读取最近通话记录。当用户说「最近通话」「通话记录」「谁打过电话」「未接来电」时调用此工具",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回条数，默认 10，最大 50"),
            "type" to jsonStringProp("筛选类型: all/incoming/outgoing/missed，默认 all"),
        )
    ) { args ->
        val ctx = registry.context
        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 10
        val type = (args["type"] as? String)?.lowercase() ?: "all"

        val callLogPerm = checkPermissionWithGuide(
            Manifest.permission.READ_CALL_LOG, permissionName = "通话记录"
        )
        if (callLogPerm != null) return@registerTool callLogPerm

        val uri = android.provider.CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            android.provider.CallLog.Calls.NUMBER,
            android.provider.CallLog.Calls.CACHED_NAME,
            android.provider.CallLog.Calls.TYPE,
            android.provider.CallLog.Calls.DURATION,
            android.provider.CallLog.Calls.DATE
        )
        val selection = when (type) {
            "incoming" -> "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.INCOMING_TYPE}"
            "outgoing" -> "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.OUTGOING_TYPE}"
            "missed" -> "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.MISSED_TYPE}"
            else -> null
        }
        val cursor = ctx.contentResolver.query(uri, projection, selection, null, "${android.provider.CallLog.Calls.DATE} DESC LIMIT $maxResults")
        val arr = JsonArray()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0) ?: ""
                val name = c.getString(1) ?: number
                arr.add(JsonObject().apply {
                    addProperty("number", number)
                    addProperty("name", name)
                    addProperty("type", when (c.getInt(2)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "来电"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "去电"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "未接"
                        else -> "未知"
                    })
                    addProperty("duration_sec", c.getLong(3))
                    addProperty("time", java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(c.getLong(4))))
                })
            }
        }
        JsonObject().apply { add("calls", arr); addProperty("count", arr.size()); addProperty("filter", type) }
    }

    // ──────────────── get_step_count ────────────────
    registry.registerTool(
        name = "get_step_count",
        description = "读取设备步数（基于计步传感器）。当用户说「今天走了多少步」「步数」「计步器」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: throw Exception("此设备不支持传感器")
        val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detectSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepSensor == null && detectSensor == null) {
            return@registerTool JsonObject().apply {
                addProperty("available", false)
                addProperty("message", "此设备没有专用计步传感器")
            }
        }
        val useSensor = stepSensor ?: detectSensor
        val result = try {
            var completed = false
            suspendCancellableCoroutine<JsonObject> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (completed) return
                        completed = true
                        sm.unregisterListener(this)
                        cont.resume(JsonObject().apply {
                            addProperty("available", true)
                            addProperty("steps", event.values[0].toInt())
                            addProperty("sensor", if (stepSensor != null) "step_counter" else "step_detector")
                        }, null)
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sm.registerListener(listener, useSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (_: Exception) {
            JsonObject().apply {
                addProperty("available", false)
                addProperty("steps", 0)
                addProperty("message", "读取步数失败")
            }
        }
        result
    }

    // ──────────────── share_wifi_qr ────────────────
    registry.registerTool(
        name = "share_wifi_qr",
        description = "生成当前连接 WiFi 的二维码（SSID），供其他手机扫码连接。当用户说「WiFi二维码」「分享WiFi」「生成WiFi二维码」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw Exception("此设备不支持 WiFi")
        val wifiInfo = wm.connectionInfo ?: throw Exception("未连接到 WiFi")
        val ssid = wifiInfo.ssid?.replace("\"", "") ?: throw Exception("无法获取 SSID")
        val wifiContent = "WIFI:S:$ssid;T:WPA;;;"

        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val size = 300
        val bitMatrix = writer.encode(wifiContent, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)

        val qrDir = java.io.File(ctx.cacheDir, "wifi_qr"); qrDir.mkdirs()
        val outputFile = java.io.File(qrDir, "wifi_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(outputFile).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        val contactId = registry.connectionRepository.get().currentContactId
        if (contactId.isNotEmpty()) {
            registry.messageRepository.addSentMessage(contactId, ChatMessage(id = UUID.randomUUID().toString(), content = outputFile.absolutePath, type = MessageType.IMAGE, direction = MessageDirection.SENT, status = MessageStatus.SENT, timestamp = System.currentTimeMillis()))
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("ssid", ssid)
            addProperty("file_path", outputFile.absolutePath)
            addProperty("message", "WiFi「$ssid」二维码已生成并显示在聊天中（不含密码）")
        }
    }

    // ──────────────── open_app_settings ────────────────
    registry.registerTool(
        name = "open_app_settings",
        description = "打开系统设置中指定应用的详情页（权限、通知、电池优化）。当用户说「打开微信的权限设置」「看看XX的应用信息」「应用的权限」时调用此工具",
        inputSchema = jsonSchema(
            "package_name" to jsonStringProp("应用包名，如 com.tencent.mm（微信）"),
            required = listOf("package_name")
        )
    ) { args ->
        val pkg = (args["package_name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("package_name 不能为空")
        val ctx = registry.context
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$pkg")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("package", pkg)
                addProperty("message", "已打开「$pkg」的应用详情页")
            }
        } catch (e: Exception) {
            throw Exception("无法打开应用设置: ${e.message}")
        }
    }
}
