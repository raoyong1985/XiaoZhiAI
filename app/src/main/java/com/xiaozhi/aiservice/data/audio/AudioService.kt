package com.xiaozhi.aiservice.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import com.xiaozhi.aiservice.BuildConfig
import com.xiaozhi.aiservice.utils.Logger
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioService {

    companion object {
        private const val TAG = "AudioService"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_RMS_THRESHOLD = 700
        private const val SILENCE_TIMEOUT_MS = 3000L
        private const val SILENCE_CONSECUTIVE_BUFFERS = 2
        private const val SPEECH_CONSECUTIVE_BUFFERS = 2
        private const val MIN_SPEECH_DURATION_MS = 300L
        private const val POOL_CAPACITY = 4

        /** 唤醒词模式录音参数（小缓冲、低延迟） */
        private const val WAKE_WORD_SAMPLE_RATE = 16000
        private const val WAKE_WORD_BUFFER_MS = 50  // 50ms 缓冲
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
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var onSilenceDetected: (() -> Unit)? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var aecEnabled = false
    private var appContext: android.content.Context? = null

    // 唤醒词检测相关（参考 ESP32 AudioService::wake_word_）
    private val wakeWordDetector = WakeWordDetector()
    private var onWakeWordDetected: ((String) -> Unit)? = null
    private val isWakeWordRecording = AtomicBoolean(false)
    private var wakeWordThread: Thread? = null
    private var wakeWordRecord: AudioRecord? = null

    fun init(context: Context) {
        appContext = context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * 设置 AEC（声学回声消除）启用状态
     * 参考 ESP32 AudioService::EnableDeviceAec()
     * 注意：AEC 需要 AudioRecord 初始化后才能应用，会在下次 startRecording() 时生效
     */
    fun setAecEnabled(enabled: Boolean) {
        aecEnabled = enabled
        // 如果已经初始化了 AEC，启用/停用它
        acousticEchoCanceler?.let {
            try {
                it.enabled = enabled
                Logger.i(TAG, "AEC ${if (enabled) "启用" else "停用"}")
            } catch (e: Exception) {
                Logger.e(TAG, "AEC enabled 失败: ${e.message}")
            }
        }
        Logger.i(TAG, "AEC 状态设置为 ${if (enabled) "启用" else "禁用"}（将在下次录音时应用）")
    }

    fun isAecEnabled(): Boolean = aecEnabled

    /**
     * 检查设备是否支持 AEC
     */
    fun isAecSupported(): Boolean = AcousticEchoCanceler.isAvailable()

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
     * 设置静音检测回调
     */
    fun setOnSilenceDetected(callback: () -> Unit) {
        onSilenceDetected = callback
    }

    /**
     * 设置唤醒词检测回调
     * 对应 ESP32 AudioServiceCallbacks.on_wake_word_detected
     */
    fun setOnWakeWordDetected(callback: (String) -> Unit) {
        onWakeWordDetected = callback
    }

    /**
     * 唤醒词引擎是否可用（AccessKey 已配置）
     */
    fun isWakeWordAvailable(): Boolean = wakeWordDetector.isReady()

    /**
     * 唤醒词检测是否正在运行
     */
    fun isWakeWordRunning(): Boolean = isWakeWordRecording.get()

    /**
     * 初始化并启动唤醒词检测
     * 对应 ESP32 AudioService::EnableWakeWordDetection(true)
     *
     * 使用独立的 AudioRecord（小缓冲、VOICE_RECOGNITION），
     * 录音数据仅用于 Porcupine 引擎，不编码不发送
     *
     * @param accessKey Picovoice AccessKey
     * @param keyword 唤醒词
     */
    fun startWakeWordDetection(
        accessKey: String = WakeWordDetector.ACCESS_KEY,
        keyword: String = WakeWordDetector.DEFAULT_KEYWORD
    ): Boolean {
        if (isWakeWordRecording.get()) return true
        if (isRecording.get()) {
            Logger.w(TAG, "语音录音中，暂不启动唤醒词检测")
            return false
        }

        // 初始化引擎（首次调用）
        if (!wakeWordDetector.initialize(accessKey, keyword, appContext)) {
            Logger.w(TAG, "唤醒词引擎初始化失败（AccessKey 未配置？），唤醒词不生效")
            return false
        }

        // 设置回调链：WakeWordDetector → AudioService → App
        wakeWordDetector.setOnWakeWordDetected { wakeWord ->
            Logger.i(TAG, "唤醒词检测命中: $wakeWord")
            onWakeWordDetected?.invoke(wakeWord)
            // 检测到后自动停止唤醒模式
            stopWakeWordDetection()
        }

        return startWakeWordRecording()
    }

    /**
     * 启动唤醒词专用录音
     */
    private fun startWakeWordRecording(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            WAKE_WORD_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val frameBytes = WAKE_WORD_SAMPLE_RATE * 2 * WAKE_WORD_BUFFER_MS / 1000 // 16bit = 2 bytes

        return try {
            wakeWordRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WAKE_WORD_SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(bufferSize, frameBytes)
            )

            if (wakeWordRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "唤醒词 AudioRecord 初始化失败")
                wakeWordRecord?.release()
                wakeWordRecord = null
                return false
            }

            isWakeWordRecording.set(true)
            wakeWordRecord?.startRecording()

            wakeWordThread = Thread {
                val buffer = ByteArray(frameBytes)
                while (isWakeWordRecording.get()) {
                    val readSize = wakeWordRecord?.read(buffer, 0, frameBytes) ?: 0
                    if (readSize > 0) {
                        val actualData = if (readSize < frameBytes) buffer.copyOf(readSize) else buffer
                        wakeWordDetector.feed(actualData)
                    }
                }
            }.also { it.start() }

            Logger.i(TAG, "唤醒词录音已启动（采样率: ${WAKE_WORD_SAMPLE_RATE}Hz, 缓冲: ${WAKE_WORD_BUFFER_MS}ms）")
            true
        } catch (e: SecurityException) {
            Logger.e(TAG, "唤醒词录音权限被拒绝: ${e.message}")
            false
        } catch (e: Exception) {
            Logger.e(TAG, "唤醒词录音启动失败: ${e.message}")
            false
        }
    }

    /**
     * 停止唤醒词检测
     * 对应 ESP32 AudioService::EnableWakeWordDetection(false)
     */
    fun stopWakeWordDetection() {
        isWakeWordRecording.set(false)
        try {
            wakeWordRecord?.stop()
        } catch (_: Exception) {}
        wakeWordThread?.join(300)
        wakeWordThread = null
        try {
            wakeWordRecord?.release()
        } catch (_: Exception) {}
        wakeWordRecord = null
        Logger.i(TAG, "唤醒词检测已停止")
    }

    /**
     * 启动录音，带 VAD 静音检测
     * @param onAudioData PCM数据回调
     * @param enableVAD 是否启用静音检测（自动停止）
     */
    fun startRecording(onAudioData: (ByteArray) -> Unit, enableVAD: Boolean = true): Boolean {
        if (isRecording.get()) return false

        // 使用设置的采样率（与 ESP32 AFE 音频处理器一致，语音识别最优化）
        val recordSampleRate = sampleRate
        val minBuffer = AudioRecord.getMinBufferSize(
            recordSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)  // 8KB 缓冲

        try {
            // VOICE_RECOGNITION 保留原始音频不做 AEC/NS 处理，STT 准确率最高
            // 但如果启用了 AEC，我们用 VOICE_COMMUNICATION 让系统做 AEC
            val audioSource = if (aecEnabled) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            audioRecord = AudioRecord(
                audioSource,
                recordSampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord初始化失败，尝试降级到MIC")
                // 降级到 MIC（部分设备不支持 VOICE_COMMUNICATION）
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    recordSampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Logger.e(TAG, "AudioRecord降级初始化也失败")
                    return false
                }
            }

            // 初始化并启用 AEC（声学回声消除）
            // 参考 ESP32 audio_service.cc EnableDeviceAec()
            if (aecEnabled) {
                initAec()
            }

            isRecording.set(true)
            audioRecord?.startRecording()

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

                    // VAD 检测（参考 ESP32 afe_audio_processor VAD_MODE_0）
                    if (enableVAD) {
                        val rmsEnergy = calculateRmsEnergy(pcmData)
                        val now = System.currentTimeMillis()

                        if (rmsEnergy > SILENCE_RMS_THRESHOLD) {
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
                                    Logger.i(TAG, "VAD检测到${SILENCE_TIMEOUT_MS}ms静音，触发自动停止（RMS=$rmsEnergy, 语音持续${speechDuration}ms）")
                                    onSilenceDetected?.invoke()
                                    break
                                }
                            }
                        }
                    }
                }
            }
            recordingThread?.start()

            Logger.i(TAG, "录音开始, 采样率: $recordSampleRate, VAD: $enableVAD, AEC: $aecEnabled, 音频源: ${if (aecEnabled) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"}")
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
     * 初始化 AEC（声学回声消除）
     * 参考 ESP32 audio_service.cc EnableDeviceAec()
     * 使用 Android AcousticEchoCanceler API 对麦克风采集的音频进行回声消除
     */
    private fun initAec() {
        if (!isAecSupported()) {
            Logger.w(TAG, "设备不支持 AEC（AcousticEchoCanceler）")
            return
        }

        try {
            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId == 0) {
                Logger.w(TAG, "AudioRecord session ID 为 0，无法创建 AEC")
                return
            }

            // 释放旧的 AEC
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null

            acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
            if (acousticEchoCanceler != null) {
                acousticEchoCanceler?.enabled = aecEnabled
                Logger.i(TAG, "AcousticEchoCanceler 初始化成功, sessionId=$sessionId, enabled=$aecEnabled")
            } else {
                Logger.w(TAG, "AcousticEchoCanceler.create() 返回 null")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "AEC 初始化失败: ${e.message}")
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
        }
    }

    /**
     * 计算 RMS 音频能量（与 ESP32 VAD 一致）
     * RMS = sqrt(mean(x^2))，范围 0-32767
     */
    private fun calculateRmsEnergy(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        var sumSquares = 0.0
        val sampleCount = pcmData.size / 2
        for (i in 0 until sampleCount) {
            val sample = (pcmData[i * 2].toInt() and 0xFF) or
                        ((pcmData[i * 2 + 1].toInt() and 0xFF) shl 8)
            sumSquares += (sample * sample).toDouble()
        }
        return kotlin.math.sqrt(sumSquares / sampleCount)
    }

    fun stopRecording() {
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
        // 5. 释放 AEC
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {}
        acousticEchoCanceler = null

        // 6. 停止唤醒词检测（如果正在运行）
        stopWakeWordDetection()

        Logger.i(TAG, "录音停止")
    }

    fun release() {
        stopRecording()
        wakeWordDetector.release()
    }

    fun isRecording(): Boolean = isRecording.get()
}
