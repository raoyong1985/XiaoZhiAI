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

    // ──────────────── clipboard ────────────────
    registry.registerTool(
        name = "clipboard",
        description = "读取或写入系统剪贴板。读取时返回剪贴板文本内容，写入时设置新文本到剪贴板",
        inputSchema = jsonSchema(
            "action" to jsonStringProp("操作: read（读取）/ write（写入），默认 read"),
            "text" to jsonStringProp("写入模式下的文本内容"),
            required = listOf("action")
        )
    ) { args ->
        val action = (args["action"] as? String)?.lowercase() ?: "read"
        val ctx = registry.context
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

    // ──────────────── schedule_note_reminder (内部函数，用于笔记+闹钟联动) ────────────────
    fun scheduleNoteReminder(ctx: Context, note: Note) {
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra("note_id", note.id)
            putExtra("note_title", note.title)
            putExtra("note_content", note.content)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx,
            (note.id.hashCode() and Int.MAX_VALUE) % 100000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            note.reminderAt ?: return,
            pendingIntent
        )
    }

    // ──────────────── save_note ────────────────
    registry.registerTool(
        name = "save_note",
        description = "保存一条笔记到手机本地。可以记录常用信息、备忘、提醒事项等，供之后查询。支持标签和时间提醒。当用户说「记住…」「帮我记一下」「记个笔记」「记备忘录」时调用此工具",
        inputSchema = jsonSchema(
            "title" to jsonStringProp("笔记的标题/关键词，用于后续查找，如「我的地址」「WiFi密码」"),
            "content" to jsonStringProp("笔记内容"),
            "tags" to jsonStringProp("可选的标签，用逗号分隔，如「生活,备忘」"),
            "reminder_at" to jsonNumberProp("可选，提醒时间戳（毫秒），设置了会在此时间提醒"),
            required = listOf("title", "content")
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val title = (args["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("title 不能为空")
        val content = (args["content"] as? String)?.takeIf { it != null }
            ?: throw Exception("content 不能为空")
        val tags = (args["tags"] as? String)?.takeIf { it.isNotBlank() }
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val reminderAt = (args["reminder_at"] as? Number)?.toLong()

        val note = Note(
            title = title,
            content = content,
            tags = tags,
            reminderAt = reminderAt
        )
        storage.save(note)

        // 有提醒时间 → 注册系统闹钟
        if (reminderAt != null) {
            scheduleNoteReminder(registry.context, note)
        }

        val result = JsonObject().apply {
            addProperty("success", true)
            addProperty("title", title)
            addProperty("content_length", content.length)
            addProperty("tags", tags.joinToString(","))
            addProperty("created_at", note.createdAt)
            addProperty("message", "已保存笔记「${title}」${if (tags.isNotEmpty()) "（标签：${tags.joinToString("，")}）" else ""}")
            if (reminderAt != null) {
                addProperty("reminder_at", reminderAt)
                addProperty("reminder_note", "将在指定时间提醒")
            }
        }
        result
    }

    // ──────────────── read_note ────────────────
    registry.registerTool(
        name = "read_note",
        description = "读取之前保存的笔记内容。支持按标题、关键词或标签查找。当用户说「我之前记的…」「查一下笔记」「我的备忘录」时调用此工具",
        inputSchema = jsonSchema(
            "query" to jsonStringProp("搜索关键词，匹配标题或内容"),
            "tag" to jsonStringProp("可选，按标签筛选"),
            required = listOf()
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val notes = when {
            tag != null -> storage.getByTag(tag)
            query != null -> storage.search(query)
            else -> storage.getAll()
        }

        if (notes.isEmpty()) {
            throw Exception(if (query != null) "未找到匹配「$query」的笔记" else "还没有保存过笔记")
        }

        val results = JsonArray()
        notes.forEach { note ->
            results.add(JsonObject().apply {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("content", note.content)
                addProperty("tags", note.tags.joinToString(","))
                addProperty("created_at", note.createdAt)
                addProperty("updated_at", note.updatedAt)
                if (note.reminderAt != null) {
                    addProperty("reminder_at", note.reminderAt)
                }
            })
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("count", notes.size)
            add("results", results)
            if (query != null) addProperty("query", query)
            if (tag != null) addProperty("tag", tag)
        }
    }

    // ──────────────── list_notes ────────────────
    registry.registerTool(
        name = "list_notes",
        description = "列出所有已保存的笔记概要（标题、标签、创建时间）。支持按标签筛选",
        inputSchema = jsonSchema(
            "tag" to jsonStringProp("可选，只列出特定标签的笔记"),
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val notes = if (tag != null) storage.getByTag(tag) else storage.getAll()

        val arr = JsonArray()
        notes.forEach { note ->
            arr.add(JsonObject().apply {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("content_length", note.content.length)
                addProperty("tags", note.tags.joinToString(","))
                addProperty("created_at", note.createdAt)
                if (note.reminderAt != null) {
                    addProperty("has_reminder", true)
                }
            })
        }

        JsonObject().apply {
            add("notes", arr)
            addProperty("count", arr.size())
            if (tag != null) addProperty("tag", tag)
            addProperty("sort", "按创建时间降序")
        }
    }

    // ──────────────── delete_note ────────────────
    registry.registerTool(
        name = "delete_note",
        description = "删除一条已保存的笔记。支持按标题或ID删除。当用户说「删除笔记」「删掉那条记录」时调用此工具",
        inputSchema = jsonSchema(
            "title" to jsonStringProp("要删除的笔记标题（精确匹配优先，失败则模糊匹配）"),
            "id" to jsonStringProp("要删除的笔记ID（如果知道的话）"),
            required = listOf()
        )
    ) { args ->
        val storage = NoteStorage(registry.context)
        val id = (args["id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val title = (args["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (id != null) {
            if (storage.delete(id)) {
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("message", "已删除笔记")
                }
            }
            throw Exception("未找到该笔记")
        }

        if (title != null) {
            // 先精确匹配
            val exact = storage.search(title).filter { it.title.equals(title, ignoreCase = true) }
            if (exact.size == 1) {
                storage.delete(exact[0].id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("title", title)
                    addProperty("message", "已删除笔记「$title」")
                }
            }
            // 模糊删除
            val deleted = storage.deleteByTitle(title)
            if (deleted > 0) {
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("query", title)
                    addProperty("deleted_count", deleted)
                    addProperty("message", "已删除 $deleted 条匹配笔记")
                }
            }
            throw Exception("未找到标题包含「$title」的笔记")
        }

        throw Exception("请提供要删除的笔记 title 或 id")
    }

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
        description = "扫描附近的蓝牙设备，返回设备名称、地址、类型等信息",
        inputSchema = jsonSchema(
            "max_results" to jsonNumberProp("最多返回数量，默认 20"),
        )
    ) { args ->
        val ctx = registry.context

        // 检查权限
        if (Build.VERSION.SDK_INT >= 31) {
            val btPerm = checkPermissionWithGuide(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                permissionName = "蓝牙"
            )
            if (btPerm != null) return@registerTool btPerm
        }

        val adapter = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(android.bluetooth.BluetoothManager::class.java))?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } ?: throw Exception("此设备不支持蓝牙")

        if (!adapter.isEnabled) throw Exception("蓝牙已关闭，请先开启蓝牙")

        val maxResults = (args["max_results"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 20

        // 开始扫描（使用旧 API 兼容）
        val foundDevices = mutableListOf<Pair<String, String>>()
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
                    if (foundDevices.none { it.second == device.address }) {
                        foundDevices.add(name to device.address)
                    }
                }
            }
        }

        ctx.registerReceiver(receiver, android.content.IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED), Context.RECEIVER_NOT_EXPORTED)
        ctx.registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND), Context.RECEIVER_NOT_EXPORTED)
        ctx.registerReceiver(receiver, android.content.IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED), Context.RECEIVER_NOT_EXPORTED)

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

    // ──────────────── set_reminder ────────────────
    registry.registerTool(
        name = "set_reminder",
        description = "在手机上设置一个定时提醒，到时间会通过通知提醒你。可以设置延迟时间或具体时间。当用户说「提醒我…」「定时提醒」「X分钟后提醒我」「X点提醒我」时调用此工具",
        inputSchema = jsonSchema(
            "message" to jsonStringProp("提醒内容，如「喝水」「吃药」「开会」"),
            "minutes" to jsonNumberProp("多少分钟后提醒（与 hour/minute 二选一，优先用此参数）"),
            "hour" to jsonNumberProp("提醒的小时 (0-23)，用于指定具体时间"),
            "minute" to jsonNumberProp("提醒的分钟 (0-59)，用于指定具体时间"),
            required = listOf("message")
        )
    ) { args ->
        val ctx = registry.context
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw Exception("message 不能为空")

        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance()

        // 优先用 minutes 参数（相对时间）
        val delayMinutes = args["minutes"] as? Number
        if (delayMinutes != null) {
            val mins = delayMinutes.toInt().coerceIn(1, 10080) // 最多7天
            calendar.timeInMillis = now.timeInMillis + mins * 60_000L
        } else {
            // 用绝对时间
            val hour = (args["hour"] as? Number)?.toInt() ?: throw Exception("hour 或 minutes 必须提供一个")
            val minute = (args["minute"] as? Number)?.toInt() ?: 0
            require(hour in 0..23) { "hour 必须在 0-23 之间" }
            require(minute in 0..59) { "minute 必须在 0-59 之间" }
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            if (calendar.before(now)) calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 创建提醒通知频道
        try {
            val channel = android.app.NotificationChannel(
                "reminder_channel", "定时提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI设置的定时提醒"
                enableVibration(true)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {}

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw Exception("系统闹钟服务不可用")

        val requestCode = (System.nanoTime().toInt() and 0x7FFFFFFF)
        val reminderId = "reminder_${requestCode}"

        val pendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = "com.xiaozhi.aiservice.REMINDER_$requestCode"
                putExtra("message", message)
                putExtra("reminder_id", reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 检查精确闹钟权限
        val hasExactPerm = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (hasExactPerm) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                pendingIntent
            )
        } else {
            // setExactAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM，在 Doze 模式下也能触发
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            sendPermissionGuideNotification("精确闹钟")
        }

        // 持久化
        ReminderPersistence.save(ctx, reminderId, message, calendar.timeInMillis)

        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", message)
            addProperty("trigger_time", df.format(calendar.time))
            addProperty("is_today", calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR))
            addProperty("reminder_id", reminderId)
            if (!hasExactPerm) {
                addProperty("permission_note", "需要手动开启「精确闹钟」权限→设置→应用管理→小智→其他权限，否则提醒可能延迟或不响")
            }
        }
    }

    // ──────────────── list_reminders ────────────────
    registry.registerTool(
        name = "list_reminders",
        description = "列出所有尚未触发的定时提醒。当用户说「有哪些提醒」「查看提醒」「我的提醒」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val records = ReminderPersistence.getAll(ctx)
        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val arr = JsonArray()
        records.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("message", r.message)
                addProperty("trigger_time", df.format(Date(r.triggerAt)))
            })
        }
        JsonObject().apply {
            add("reminders", arr)
            addProperty("count", arr.size())
        }
    }

    // ──────────────── cancel_reminder ────────────────
    registry.registerTool(
        name = "cancel_reminder",
        description = "取消一个尚未触发的定时提醒。通过提醒ID或内容查找取消。当用户说「取消提醒」「删掉提醒」「取消XX提醒」时调用此工具",
        inputSchema = jsonSchema(
            "reminder_id" to jsonStringProp("要取消的提醒ID（从 list_reminders 获取）"),
            "message" to jsonStringProp("要取消的提醒内容关键词，匹配后取消第一个匹配项"),
            required = listOf()
        )
    ) { args ->
        val ctx = registry.context
        val reminderId = (args["reminder_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val msg = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (reminderId != null) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ReminderPersistence.remove(ctx, reminderId)
            // 取消已注册的 PendingIntent
            val requestCode = reminderId.removePrefix("reminder_").toIntOrNull()
            if (requestCode != null && alarmManager != null) {
                val pi = PendingIntent.getBroadcast(
                    ctx, requestCode,
                    Intent(ctx, ReminderReceiver::class.java).apply { action = "com.xiaozhi.aiservice.REMINDER_$requestCode" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                pi?.cancel()
            }
            return@registerTool JsonObject().apply {
                addProperty("success", true)
                addProperty("message", "已取消提醒")
            }
        }

        if (msg != null) {
            val records = ReminderPersistence.getAll(ctx)
            val match = records.find { it.message.contains(msg, ignoreCase = true) }
            if (match != null) {
                ReminderPersistence.remove(ctx, match.id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("cancelled_message", match.message)
                    addProperty("message", "已取消提醒「${match.message}」")
                }
            }
            throw Exception("未找到包含「$msg」的提醒")
        }

        throw Exception("请提供 reminder_id 或 message")
    }

    // ──────────────── list_alarms ────────────────
    registry.registerTool(
        name = "list_alarms",
        description = "列出所有已设置的闹钟。当用户说「有哪些闹钟」「查看闹钟」「我的闹钟」时调用此工具",
        inputSchema = jsonSchema()
    ) { _ ->
        val ctx = registry.context
        val records = AlarmPersistence.getAll(ctx)
        val arr = JsonArray()
        records.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("time", String.format(Locale.getDefault(), "%02d:%02d", r.hour, r.minute))
                addProperty("message", r.message)
            })
        }
        JsonObject().apply {
            add("alarms", arr)
            addProperty("count", arr.size())
        }
    }

    // ──────────────── cancel_alarm ────────────────
    registry.registerTool(
        name = "cancel_alarm",
        description = "取消一个已设置的闹钟。通过闹钟ID或时间取消。当用户说「取消闹钟」「删掉闹钟」「取消XX点的闹钟」时调用此工具",
        inputSchema = jsonSchema(
            "alarm_id" to jsonStringProp("要取消的闹钟ID（从 list_alarms 获取）"),
            "hour" to jsonNumberProp("要取消的闹钟小时 (0-23)，与 minute 配合使用"),
            "minute" to jsonNumberProp("要取消的闹钟分钟 (0-59)，与 hour 配合使用"),
            required = listOf()
        )
    ) { args ->
        val ctx = registry.context
        val alarmId = (args["alarm_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val hour = (args["hour"] as? Number)?.toInt()
        val minute = (args["minute"] as? Number)?.toInt()

        if (alarmId != null) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            AlarmPersistence.remove(ctx, alarmId)
            // 取消 PendingIntent
            val requestCode = alarmId.substringAfterLast('_').toIntOrNull()
            if (requestCode != null && alarmManager != null) {
                val pi = PendingIntent.getBroadcast(
                    ctx, requestCode,
                    Intent(ctx, AlarmReceiver::class.java).apply { action = "com.xiaozhi.aiservice.ALARM_$requestCode" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                pi?.cancel()
            }
            return@registerTool JsonObject().apply {
                addProperty("success", true)
                addProperty("message", "已取消闹钟")
            }
        }

        if (hour != null && minute != null) {
            val records = AlarmPersistence.getAll(ctx)
            val match = records.find { it.hour == hour && it.minute == minute }
                ?: records.filter { it.hour == hour }.firstOrNull()
            if (match != null) {
                AlarmPersistence.remove(ctx, match.id)
                return@registerTool JsonObject().apply {
                    addProperty("success", true)
                    addProperty("message", "已取消 ${match.hour}:${String.format(Locale.getDefault(), "%02d", match.minute)} 的闹钟")
                }
            }
            throw Exception("未找到 %02d:%02d 的闹钟")
        }

        throw Exception("请提供 alarm_id 或 hour+minute")
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
}
