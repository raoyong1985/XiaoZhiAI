package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
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
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AiCall"

fun registerCallTools() {

    MCPToolRegistry.registerTool(
        name = "ai_make_call",
        description = "AI智能打电话。AI拨号并和联系人自由对话，接通后AI自动对话（扬声器模式，对方能听到AI声音）",
        inputSchema = jsonSchema(
            "phone_number" to jsonStringProp("电话号码（可选，提供name时自动查找联系人）"),
            "name" to jsonStringProp("联系人姓名（可选，自动从通讯录查找）"),
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
            if (number.isEmpty()) throw Exception("通讯录中未找到联系人「$name」，试试直接说电话号码")
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

        Logger.i(TAG, "使用智能体: ${bot.name}(${bot.id})，拨号: $number")

        // ════════════════════════════════════════
        // 第一步：先拨号
        // ════════════════════════════════════════
        val dialIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(dialIntent)

        // ════════════════════════════════════════
        // 第二步：等待接通
        // ════════════════════════════════════════
        val telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasPhoneStatePerm = Build.VERSION.SDK_INT < 31 ||
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        var callConnected = false
        val callConnectedLock = Object()

        if (telephonyManager != null && hasPhoneStatePerm) {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state == TelephonyManager.CALL_STATE_OFFHOOK && !callConnected) {
                            synchronized(callConnectedLock) {
                                callConnected = true
                                callConnectedLock.notifyAll()
                            }
                            Logger.i(TAG, "通话已接通")
                        }
                    }
                }
                telephonyManager.registerTelephonyCallback(ctx.mainExecutor, callback)

                // 等最多30秒
                synchronized(callConnectedLock) {
                    if (!callConnected) callConnectedLock.wait(30_000)
                }

                // 清理监听
                try { telephonyManager.unregisterTelephonyCallback(callback) } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state == TelephonyManager.CALL_STATE_OFFHOOK && !callConnected) {
                            synchronized(callConnectedLock) {
                                callConnected = true
                                callConnectedLock.notifyAll()
                            }
                            Logger.i(TAG, "通话已接通")
                        }
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)

                synchronized(callConnectedLock) {
                    if (!callConnected) callConnectedLock.wait(30_000)
                }

                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } else {
            // 没有READ_PHONE_STATE权限，盲等几秒让通话接通
            delay(5000)
            callConnected = true
        }

        if (!callConnected) {
            Logger.e(TAG, "通话未接通（30秒超时）")
            throw Exception("通话未接通（对方未接听或号码无效）")
        }

        Logger.i(TAG, "通话已接通，准备建立AI音频通路")

        // ════════════════════════════════════════
        // 第三步：设置音频路由（通信模式+扬声器）
        // ════════════════════════════════════════
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.apply {
            try {
                mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerphoneOn = true
                Logger.i(TAG, "音频路由: MODE_IN_COMMUNICATION + 扬声器")
            } catch (e: Exception) {
                Logger.w(TAG, "设置音频路由失败: ${e.message}")
            }
        }

        // ════════════════════════════════════════
        // 第四步：连接智能体
        // ════════════════════════════════════════
        val botService = XiaoZhiBackendService(AppContainer.gson)
        botService.connect(bot.serverUrl, bot.otaUrl, bot.deviceId, bot.clientId)

        var connected = false
        for (i in 0 until 100) {
            if (botService.connectionState.value is XiaoZhiBackendService.ConnectionState.Connected) {
                connected = true
                break
            }
            delay(100)
        }
        if (!connected) {
            botService.disconnect()
            throw Exception("智能体「${bot.name}」连接超时")
        }

        Logger.i(TAG, "智能体已连接: ${bot.name}")

        // ════════════════════════════════════════
        // 第五步：建立通话专用的音频通路
        // ════════════════════════════════════════
        var sessionActive = true

        // ── 录音：VOICE_COMMUNICATION（通话音频源） ──
        val callSampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            callSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)

        val callRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            callSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (callRecorder.state != AudioRecord.STATE_INITIALIZED) {
            // 降级到 MIC
            Logger.w(TAG, "VOICE_COMMUNICATION 不可用，降级到 MIC")
            callRecorder.apply {
                try { release() } catch (_: Exception) {}
            }
        }

        val finalRecorder = if (callRecorder.state == AudioRecord.STATE_INITIALIZED) {
            callRecorder
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                callSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    it.release()
                    botService.disconnect()
                    throw Exception("通话录音初始化失败（无法捕获通话声音）")
                }
            }
        }

        val encoder = OpusEncoder(sampleRate = callSampleRate)
        val isEncoding = AtomicBoolean(false)

        if (!encoder.init { opusPacket ->
            if (sessionActive) botService.sendAudio(opusPacket)
        }) {
            finalRecorder.release()
            botService.disconnect()
            throw Exception("音频编码器初始化失败")
        }

        isEncoding.set(true)
        finalRecorder.startRecording()
        Logger.i(TAG, "通话录音已启动: ${finalRecorder.audioSource}")

        // 录音线程
        GlobalScope.launch {
            val buf = ByteArray(bufferSize)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isEncoding.get() && sessionActive) {
                try {
                    val read = finalRecorder.read(buf, 0, bufferSize)
                    if (read > 0) {
                        val pcm = buf.copyOf(read)
                        encoder.encode(pcm)
                    }
                } catch (e: Exception) {
                    if (sessionActive) Logger.e(TAG, "录音读取异常: ${e.message}")
                    break
                }
            }
        }

        // ── 播放：AI TTS 通过扬声器播出 ──
        val playSampleRate = botService.getServerSampleRate()
        val playMinBuf = AudioTrack.getMinBufferSize(
            playSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val playBuf = maxOf(playMinBuf * 4, 32768)

        val callPlayer = AudioTrack(
            AudioManager.STREAM_MUSIC,
            playSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            playBuf,
            AudioTrack.MODE_STREAM
        )

        if (callPlayer.state != AudioTrack.STATE_INITIALIZED) {
            isEncoding.set(false)
            try { finalRecorder.stop() } catch (_: Exception) {}
            try { finalRecorder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            botService.disconnect()
            throw Exception("音频播放器初始化失败（AI声音无法播出）")
        }

        callPlayer.play()
        Logger.i(TAG, "通话播放器已启动: ${playSampleRate}Hz STREAM_MUSIC")

        // 接收 AI 音频 → 写入播放器
        var audioJob: Job? = null
        audioJob = GlobalScope.launch {
            botService.audioData.collect { data ->
                if (data.isNotEmpty() && sessionActive) {
                    try {
                        callPlayer.write(data, 0, data.size)
                    } catch (e: Exception) {
                        Logger.w(TAG, "播放写入异常: ${e.message}")
                    }
                }
            }
        }

        // ── 通知 AI 开始监听 ──
        botService.sendListenStart("realtime")
        Logger.i(TAG, "AI通话已启动：扬声器模式下AI与联系人自由对话中")

        // cleanup 函数定义
        val cleanup = {
            if (sessionActive) {
                sessionActive = false
                isEncoding.set(false)
                try { finalRecorder.stop() } catch (_: Exception) {}
                try { finalRecorder.release() } catch (_: Exception) {}
                try { encoder.stop() } catch (_: Exception) {}
                try { callPlayer.stop() } catch (_: Exception) {}
                try { callPlayer.release() } catch (_: Exception) {}
                try { audioJob?.cancel() } catch (_: Exception) {}
                try { botService.sendListenStop() } catch (_: Exception) {}
                try { botService.disconnect() } catch (_: Exception) {}
                try { audioManager?.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
                Logger.i(TAG, "AI通话资源已清理")
            }
        }

        // ════════════════════════════════════════
        // 第六步：后台监控通话挂断（不阻塞返回）
        // ════════════════════════════════════════
        GlobalScope.launch {
            if (telephonyManager != null && hasPhoneStatePerm) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val hangupCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            if (state == TelephonyManager.CALL_STATE_IDLE && callConnected) {
                                Logger.i(TAG, "通话已挂断")
                                cleanup()
                                try { telephonyManager.unregisterTelephonyCallback(this) } catch (_: Exception) {}
                            }
                        }
                    }
                    telephonyManager.registerTelephonyCallback(ctx.mainExecutor, hangupCallback)
                    // 5分钟超时自动清理
                    delay(300_000)
                    try { telephonyManager.unregisterTelephonyCallback(hangupCallback) } catch (_: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    val hangupListener = object : PhoneStateListener() {
                        @Suppress("DEPRECATION")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            if (state == TelephonyManager.CALL_STATE_IDLE && callConnected) {
                                Logger.i(TAG, "通话已挂断")
                                cleanup()
                                @Suppress("DEPRECATION")
                                telephonyManager.listen(this, PhoneStateListener.LISTEN_NONE)
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(hangupListener, PhoneStateListener.LISTEN_CALL_STATE)
                    delay(300_000)
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(hangupListener, PhoneStateListener.LISTEN_NONE)
                }
            }

            // 超时自动清理（5分钟）
            delay(300_000)
            if (sessionActive) {
                Logger.i(TAG, "AI通话超时（5分钟），自动清理")
                cleanup()
            }
        }

        Logger.i(TAG, "AI已拨号: $displayName 使用智能体: ${bot.name}")

        // 立即返回成功，后台继续运行
        JsonObject().apply {
            addProperty("success", true)
            addProperty("phone_number", number)
            addProperty("name", name)
            addProperty("bot_name", bot.name)
            addProperty("message", "已使用智能体「${bot.name}」拨打 $displayName，接通后AI与联系人自由对话中（扬声器模式，请保持手机扬声器开启）")
        }
    }
}
