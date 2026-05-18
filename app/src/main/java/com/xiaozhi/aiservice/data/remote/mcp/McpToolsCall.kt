package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder
import com.xiaozhi.aiservice.data.audio.OpusEncoder
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.di.AppContainer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

fun registerCallTools() {

    MCPToolRegistry.registerTool(
        name = "ai_make_call",
        description = "AI智能回电话。自动使用第一个智能体拨打指定号码，接通后AI自动替你和对方通话。需CALL_PHONE和READ_CONTACTS权限",
        inputSchema = jsonSchema(
            "phone_number" to jsonStringProp("电话号码（可选，提供name时自动查找联系人号码）"),
            "name" to jsonStringProp("联系人姓名（可选，提供后自动从通讯录查找号码）"),
            required = emptyList()
        )
    ) { args ->
        val ctx = AppContainer.context
        var number = (args["phone_number"] as? String)?.takeIf { it.isNotBlank() } ?: ""
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: ""

        // 获取号码
        if (number.isEmpty() && name.isNotEmpty()) {
            val contactPerm = checkPermissionWithGuide(
                Manifest.permission.READ_CONTACTS, permissionName = "通讯录"
            )
            if (contactPerm != null) return@registerTool contactPerm
            number = lookupContactNumber(ctx, name)
            if (number.isEmpty()) throw Exception("通讯录中未找到联系人「$name」，试试直接提供电话号码")
        }
        if (number.isEmpty()) throw Exception("phone_number 或 name 至少提供一个")
        number = number.replace(Regex("[\\s\\-()（）]"), "")
        val displayName = if (name.isNotEmpty()) name else number

        // 通话权限
        val callPerm = checkPermissionWithGuide(
            Manifest.permission.CALL_PHONE, permissionName = "通话"
        )
        if (callPerm != null) return@registerTool callPerm

        // 获取第一个智能体
        val contacts = AppContainer.contactsRepository.getContacts().take(1).first()
        val bot = contacts.firstOrNull()
            ?: throw Exception("没有可用的智能体（Bot），请先在联系人中添加智能体")

        android.util.Log.i("AiCall", "使用智能体: ${bot.name}(${bot.id})，拨号: $number")

        // 连接智能体
        val botService = XiaoZhiBackendService(AppContainer.gson)
        botService.connect(bot.serverUrl, bot.otaUrl, bot.deviceId, bot.clientId)

        var connected = false
        for (i in 0 until 100) {
            if (botService.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) {
                connected = true
                break
            }
            kotlinx.coroutines.delay(100)
        }
        if (!connected) {
            botService.disconnect()
            throw Exception("智能体「${bot.name}」连接超时，请检查网络")
        }

        // 建立完整的音频通路（让AI能听能说）
        val audioService = AppContainer.audioService
        val encoder = OpusEncoder(sampleRate = audioService.getSampleRate())
        var sessionActive = true

        // ── 启动 Opus 编码器 → 发到 WebSocket ──
        if (encoder.init { opusPacket -> botService.sendAudio(opusPacket) }) {
            audioService.startRecording({ pcmData -> encoder.encode(pcmData) }, enableVAD = false)
        } else {
            botService.disconnect()
            throw Exception("音频编码器初始化失败")
        }

        // ── 接收 AI 语音 → 解码播放 ──
        val decoder = NativeOpusDecoder(
            botService.getServerSampleRate(), 16000, botService.getServerChannels()
        ).also { it.init() }

        var audioJob: Job? = null
        audioJob = GlobalScope.launch {
            botService.audioData.collect { data ->
                if (data.isNotEmpty() && sessionActive) {
                    decoder.decodePacket(data)
                }
            }
        }

        // ── 通知 AI 开始监听 ──
        botService.sendListenStart("realtime")
        android.util.Log.i("AiCall", "AI音频通路已建立，开始拨号")

        // ── 拨号 ──
        val dialIntent = Intent(Intent.ACTION_CALL, android.net.Uri.fromParts("tel", number, null)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(dialIntent)

        // ── 监听通话状态 ──
        val telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasPhoneStatePerm = Build.VERSION.SDK_INT < 31 ||
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        var callConnected = false

        val cleanup = {
            if (sessionActive) {
                sessionActive = false
                try { audioService.stopRecording() } catch (_: Exception) {}
                try { encoder.stop() } catch (_: Exception) {}
                try { if (!decoder.isReleased) decoder.release() } catch (_: Exception) {}
                try { audioJob?.cancel() } catch (_: Exception) {}
                try { botService.sendListenStop() } catch (_: Exception) {}
                try { botService.disconnect() } catch (_: Exception) {}
                android.util.Log.i("AiCall", "AI通话资源已清理")
            }
        }

        if (telephonyManager != null && hasPhoneStatePerm) {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        when (state) {
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                if (!callConnected) {
                                    callConnected = true
                                    ctx.getSystemService(Context.AUDIO_SERVICE)?.let {
                                        (it as? android.media.AudioManager)?.isSpeakerphoneOn = true
                                    }
                                    android.util.Log.i("AiCall", "通话已接通（扬声器模式）")
                                }
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (callConnected) {
                                    android.util.Log.i("AiCall", "通话已挂断，清理资源")
                                    cleanup()
                                }
                            }
                        }
                    }
                }
                telephonyManager.registerTelephonyCallback(ctx.mainExecutor, callback)
                // 3分钟后自动断开（防止长时间通话后监听泄漏）
                kotlinx.coroutines.delay(180_000)
                try { telephonyManager.unregisterTelephonyCallback(callback) } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        when (state) {
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                if (!callConnected) {
                                    callConnected = true
                                    ctx.getSystemService(Context.AUDIO_SERVICE)?.let {
                                        (it as? android.media.AudioManager)?.isSpeakerphoneOn = true
                                    }
                                    android.util.Log.i("AiCall", "通话已接通（扬声器模式）")
                                }
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (callConnected) {
                                    android.util.Log.i("AiCall", "通话已挂断，清理资源")
                                    cleanup()
                                }
                            }
                        }
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                kotlinx.coroutines.delay(180_000)
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }

        // 如果未接通，30秒后自动清理
        kotlinx.coroutines.delay(30_000)
        if (!callConnected && sessionActive) {
            android.util.Log.w("AiCall", "30秒未接通，清理资源")
            cleanup()
        }

        android.util.Log.i("AiCall", "AI已拨号: $displayName 使用智能体: ${bot.name}")

        JsonObject().apply {
            addProperty("success", true)
            addProperty("phone_number", number)
            addProperty("name", name)
            addProperty("bot_name", bot.name)
            addProperty("message", "已使用智能体「${bot.name}」拨打 $displayName，接通后AI将自动对话")
        }
    }
}
