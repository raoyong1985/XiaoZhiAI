package com.xiaozhi.aiservice.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.xiaozhi.aiservice.utils.Logger
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioService {

    companion object {
        private const val TAG = "AudioService"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_RMS_THRESHOLD = 800
        private const val SILENCE_TIMEOUT_MS = 3000L
        private const val SILENCE_CONSECUTIVE_BUFFERS = 2
        private const val SPEECH_CONSECUTIVE_BUFFERS = 2
        private const val MIN_SPEECH_DURATION_MS = 300L
        private const val POOL_CAPACITY = 16
    }

    // ByteArray 池：减少录音→编码链路 GC 压力
    private val bufferPool = java.util.ArrayDeque<ByteArray>()
    private val poolLock = Any()

    private fun acquireBuffer(minSize: Int): ByteArray {
        synchronized(poolLock) {
            val pooled = bufferPool.pollLast()
            if (pooled != null && pooled.size >= minSize) return pooled
        }
        return ByteArray(minSize)
    }

    private fun recycleBuffer(buffer: ByteArray) {
        synchronized(poolLock) {
            if (bufferPool.size < POOL_CAPACITY) {
                bufferPool.addLast(buffer)
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private val audioEnhancer = AudioEnhancer()
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE

    /**
     * 当前录音会话的 owner 标识
     * "main" = 私聊，groupId = 群聊，null = 无录音
     * 用于区分两个 ViewModel 的录音请求，避免互锁
     */
    @Volatile private var recorderOwner: String? = null

    /** VAD 回调（按 owner 隔离，支持多个） */
    private val silenceCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var onSilenceDetected: (() -> Unit)? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var appContext: android.content.Context? = null
    private var audioSettingsBackup = mutableMapOf<String, Boolean>()  // 存旧增强配置

    // FSMN VAD（神经网络语音活动检测，替代 SileroVAD，专为中文优化）
    // 单例：AudioRecorder 共享此实例，不再重复加载 ONNX 模型
    val fsmnVad = FsmnVAD()

    fun init(context: Context) {
        appContext = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 提前初始化 FSMN VAD（ONNX 模型加载），使 AudioRecorder 可直接复用
        if (!fsmnVad.initialized) {
            fsmnVad.init(context)
            Logger.i(TAG, "FSMN VAD 全局初始化: ${if (fsmnVad.initialized) "成功" else "失败，将降级到 RMS"}")
        }
    }

    /**
     * 设置 AEC（声学回声消除）启用状态
     * 参考 ESP32 AudioService::EnableDeviceAec()
     */
    fun setAecEnabled(enabled: Boolean) {
        audioEnhancer.setAec(enabled)
        audioEnhancer.reset()
        Logger.i(TAG, "AEC ${if (enabled) "启用" else "禁用"}")
    }

    fun isAecEnabled(): Boolean = audioEnhancer.aecEnabled

    /**
     * 检查设备是否支持 AEC
     */
    fun isAecSupported(): Boolean = audioEnhancer.isAecSupported()

    /**
     * 设置 AGC（自动增益控制）启用状态
     */
    fun setAgcEnabled(enabled: Boolean) {
        audioEnhancer.setAgc(enabled)
        audioEnhancer.reset()
        Logger.i(TAG, "AGC ${if (enabled) "启用" else "禁用"}")
    }

    fun isAgcEnabled(): Boolean = audioEnhancer.agcEnabled
    fun isAgcSupported(): Boolean = audioEnhancer.isAgcSupported()

    /**
     * 设置 NS（降噪）启用状态
     */
    fun setNsEnabled(enabled: Boolean) {
        audioEnhancer.setNs(enabled)
        audioEnhancer.reset()
        Logger.i(TAG, "降噪 ${if (enabled) "启用" else "禁用"}")
    }

    fun isNsEnabled(): Boolean = audioEnhancer.nsEnabled
    fun isNsSupported(): Boolean = audioEnhancer.isNsSupported()

    /**
     * 请求音频焦点（播放时使用）
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK：临时获取焦点，其他媒体音量降低
     */
    fun requestAudioFocus(): Boolean {
        if (audioFocusRequest != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val builder = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
            val focusRequest = builder.build()
            val result = audioManager?.requestAudioFocus(focusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = focusRequest
                Logger.i(TAG, "AudioFocus granted")
                true
            } else {
                Logger.w(TAG, "AudioFocus request failed")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "AudioFocus error: ${e.message}")
            false
        }
    }

    fun abandonAudioFocus() {
        audioFocusRequest?.let {
            try {
                audioManager?.abandonAudioFocusRequest(it)
                Logger.i(TAG, "AudioFocus abandoned")
            } catch (_: Exception) {}
        }
        audioFocusRequest = null
    }

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setSampleRate(rate: Int) {
        sampleRate = rate
        Logger.i(TAG, "设置采样率: $rate")
    }

    fun getSampleRate(): Int = sampleRate

    /**
     * 注册/移除静音检测回调（按 owner 隔离）
     * @param owner 调用方标识
     * @param callback null = 取消注册，非 null = 注册
     */
    fun setOnSilenceDetected(owner: String, callback: (() -> Unit)?) {
        if (callback != null) {
            silenceCallbacks[owner] = callback
        } else {
            silenceCallbacks.remove(owner)
        }
    }

    /** 获取 Application Context（供 AudioRecorder 初始化 SileroVAD 使用） */
    fun getAppContext(): android.content.Context? = appContext

    /**
     * 启动录音，带 VAD 静音检测
     * @param owner 调用方标识（"main"=私聊, groupId=群聊）
     * @param onAudioData PCM数据回调
     * @param enableVAD 是否启用静音检测（自动停止）
     */
    fun startRecording(owner: String, onAudioData: (ByteArray) -> Unit, enableVAD: Boolean = true): Boolean {
        // 如果同一 owner 已在录音，返回 true（已录中）
        if (isRecording.get() && owner == recorderOwner) return true
        // 如果不同 owner 在录音，自动停止前一个
        if (isRecording.get()) {
            Logger.w(TAG, "录音被新请求覆盖：oldOwner=$recorderOwner, newOwner=$owner")
            stopRecordingInternal()
        }

        recorderOwner = owner

        // 使用设置的采样率（与 ESP32 AFE 音频处理器一致，语音识别最优化）
        val recordSampleRate = sampleRate
        val minBuffer = AudioRecord.getMinBufferSize(
            recordSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)  // 8KB 缓冲

        try {
            // 固定 VOICE_RECOGNITION：保留原始音频不做系统级 AEC/NS 预处理
            // 如果 AudioEnhancer 启用了 AEC/AGC/NS，由 AudioEnhancer 统一处理
            // 不要用 VOICE_COMMUNICATION——它自带系统 AEC/NS 会与 AudioEnhancer 冲突（双重处理降质）
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(recordSampleRate)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord初始化失败，尝试降级到MIC")
                // 降级到 MIC（部分设备不支持 VOICE_COMMUNICATION）
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Logger.e(TAG, "AudioRecord降级初始化也失败")
                    return false
                }
            }

            // 初始化 AudioFX 增强器（AEC + AGC + 降噪一体）
            // 参考 ESP32 audio_service.cc EnableDeviceAec() + AFE 配置
            if (audioEnhancer.aecEnabled || audioEnhancer.agcEnabled || audioEnhancer.nsEnabled) {
                val sessionId = audioRecord?.audioSessionId ?: 0
                if (sessionId > 0) {
                    audioEnhancer.init(sessionId)
                } else {
                    Logger.w(TAG, "AudioRecord sessionId=0，AudioFX 增强器无法绑定（音频增强不生效）")
                }
            }

            isRecording.set(true)
            audioRecord?.startRecording()
            // 重置 FSMN VAD 状态
            fsmnVad.reset()

            val buffer = ByteArray(bufferSize)
            var silenceStartTime = 0L
            var consecutiveSilenceBuffers = 0
            var consecutiveSpeechBuffers = 0
            // 记录是否已触发过语音（避免刚开头几个 buffer 就开始算静音）
            var hadSpeech = false
            // 记录第一次检测到语音的时间（用于 MIN_SPEECH_DURATION_MS 抑制）
            var firstSpeechTime = 0L

            recordingThread = Thread {
                while (isRecording.get()) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readSize <= 0) continue

                    val pcmData = acquireBuffer(readSize)
                    System.arraycopy(buffer, 0, pcmData, 0, readSize)
                    try {
                        onAudioData(pcmData)
                    } finally {
                        recycleBuffer(pcmData)
                    }

                    // VAD 检测（优先使用 FSMN VAD，否则降级到 RMS 能量阈值）
                    if (enableVAD) {
                        val now = System.currentTimeMillis()
                        val hasVoice = if (fsmnVad.initialized) {
                            fsmnVad.isVoice(pcmData)
                        } else {
                            calculateRmsEnergy(pcmData) > SILENCE_RMS_THRESHOLD
                        }

                        if (hasVoice) {
                            consecutiveSpeechBuffers++
                            consecutiveSilenceBuffers = 0
                            if (consecutiveSpeechBuffers >= SPEECH_CONSECUTIVE_BUFFERS) {
                                if (firstSpeechTime == 0L) {
                                    firstSpeechTime = now
                                }
                                silenceStartTime = 0
                                hadSpeech = true
                            }
                        } else {
                            consecutiveSpeechBuffers = 0
                            if (hadSpeech && firstSpeechTime > 0) {
                                if (silenceStartTime == 0L) {
                                    silenceStartTime = now
                                }
                                consecutiveSilenceBuffers++
                                val speechDuration = silenceStartTime - firstSpeechTime
                                if (consecutiveSilenceBuffers >= SILENCE_CONSECUTIVE_BUFFERS &&
                                    now - silenceStartTime >= SILENCE_TIMEOUT_MS &&
                                    speechDuration >= MIN_SPEECH_DURATION_MS) {
                                    Logger.i(TAG, "FSMN VAD检测到${SILENCE_TIMEOUT_MS}ms静音，触发自动停止（语音持续${speechDuration}ms）")
                                    // 查找当前 owner 的 VAD 回调
                                    val ownerCallback = silenceCallbacks[recorderOwner]
                                    if (ownerCallback != null) {
                                        ownerCallback.invoke()
                                    } else {
                                        onSilenceDetected?.invoke()
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
            }
            recordingThread?.start()

            Logger.i(TAG, "录音开始, 采样率: $recordSampleRate, VAD: $enableVAD, 增强: ${audioEnhancer.getStatusSummary()}, 音频源: VOICE_RECOGNITION")
            return true
        } catch (e: SecurityException) {
            Logger.e(TAG, "录音权限被拒绝: ${e.message}")
            return false
        } catch (e: Exception) {
            Logger.e(TAG, "录音启动失败: ${e.message}")
            return false
        }
    }

    /**
     * 计算 RMS 音频能量（与 ESP32 VAD 一致）
     * RMS = sqrt(mean(x^2))，范围 0-32767
     */
    private fun calculateRmsEnergy(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        var sumSquares = 0.0
        var count = 0
        val sampleCount = pcmData.size / 2
        // 每 4 个样本取 1 个计算，降低功耗
        var i = 0
        while (i < sampleCount) {
            val sample = (pcmData[i * 2].toInt() and 0xFF) or
                        ((pcmData[i * 2 + 1].toInt() and 0xFF) shl 8)
            sumSquares += (sample * sample).toDouble()
            count++
            i += 4
        }
        return kotlin.math.sqrt(sumSquares / count)
    }

    /**
     * 停止录音
     * @param owner 调用方标识。如果非空且与 recorderOwner 不匹配，不操作（保护其他调用方的录音）
     */
    fun stopRecording(owner: String? = null) {
        // 如果指定了 owner，只停止自己的录音
        if (owner != null && recorderOwner != null && owner != recorderOwner) {
            Logger.w(TAG, "stopRecording 忽略：owner=$owner 不匹配当前 recorderOwner=$recorderOwner")
            return
        }
        stopRecordingInternal()
    }

    /**
     * 内部停止逻辑，不检查 owner
     */
    private fun stopRecordingInternal() {
        // 1. 设标记让录音循环自然退出（不等 interrupt，AudioRecord.read() 不响应 interrupt）
        isRecording.set(false)
        // 2. stop 让阻塞的 read() 抛异常退出（部分设备需要）
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        // 3. 等待线程结束（最多 500ms），避免 release 时 thread 还在跑
        recordingThread?.let { thread ->
            thread.join(500)
        }
        recordingThread = null
        // 4. 释放 AudioRecord
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        // 5. 释放 AudioFX 增强器（AEC+AGC+NS）
        audioEnhancer.release()

        // 6. 清理 owner 记录（不再有活跃录音）
        if (recorderOwner != null) {
            silenceCallbacks.remove(recorderOwner)
            recorderOwner = null
            Logger.i(TAG, "录音停止，清理 owner 状态")
        }

        Logger.i(TAG, "录音停止")
    }

    fun release() {
        stopRecordingInternal()
    }

    fun isRecording(): Boolean = isRecording.get()
}
