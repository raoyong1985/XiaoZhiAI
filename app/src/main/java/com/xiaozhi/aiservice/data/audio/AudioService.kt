package com.xiaozhi.aiservice.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.xiaozhi.aiservice.BuildConfig
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 条件日志：仅在 debug 版本输出
 */
private fun debugLogAudio(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
}

private fun debugLogAudioE(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.e(tag, msg)
}

private fun debugLogAudioW(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.w(tag, msg)
}

class AudioService {

    companion object {
        private const val TAG = "AudioService"
        const val DEFAULT_SAMPLE_RATE = 16000  // 16kHz 语音采样率（与 ESP32 一致）
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // VAD 参数（参考 ESP32 afe_audio_processor.cc: VAD_MODE_0, vad_min_noise_ms=100）
        // 能量阈值：RMS 能量，700 ≈ 安静房间正常说话（VOICE_COMMUNICATION AEC 处理后动态范围压缩）
        // 静音持续 3 秒则触发自动停止（ESP32 用 AFE VAD，Android 用 RMS 能量阈值）
        // 注意：阈值不能太低（<500），否则噪声/呼吸声也会被误判为语音，导致永远不触发停止
        private const val SILENCE_RMS_THRESHOLD = 700
        private const val SILENCE_TIMEOUT_MS = 3000L  // 静音 3 秒自动停止
        // 置信度：连续 2 个 buffer 都低于阈值才认定是静音（加快响应，约 512ms）
        private const val SILENCE_CONSECUTIVE_BUFFERS = 2
        // 语音确认：连续 2 个 buffer 都高于阈值才算有语音（防止瞬时噪声误触发）
        private const val SPEECH_CONSECUTIVE_BUFFERS = 2
        // 最短语音时长：语音必须持续至少 300ms 才能算作有效语音
        // 防止短促的呼吸声/噪声被误认为语音，导致 VAD 在正常停顿时反而无法触发
        private const val MIN_SPEECH_DURATION_MS = 300L
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var onSilenceDetected: (() -> Unit)? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * 请求音频焦点（播放时使用）
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK：临时获取焦点，其他媒体音量降低
     */
    fun requestAudioFocus(): Boolean {
        if (audioFocusRequest != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val builder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
                debugLogAudio(TAG, "AudioFocus granted")
                true
            } else {
                debugLogAudioW(TAG, "AudioFocus request failed")
                false
            }
        } catch (e: Exception) {
            debugLogAudioE(TAG, "AudioFocus error: ${e.message}")
            false
        }
    }

    fun abandonAudioFocus() {
        audioFocusRequest?.let {
            try {
                audioManager?.abandonAudioFocusRequest(it)
                debugLogAudio(TAG, "AudioFocus abandoned")
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
        debugLogAudio(TAG, "设置采样率: $rate")
    }

    fun getSampleRate(): Int = sampleRate

    /**
     * 设置静音检测回调
     */
    fun setOnSilenceDetected(callback: () -> Unit) {
        onSilenceDetected = callback
    }

    /**
     * 启动录音，带 VAD 静音检测
     * @param onAudioData PCM数据回调
     * @param enableVAD 是否启用静音检测（自动停止）
     */
    fun startRecording(onAudioData: (ByteArray) -> Unit, enableVAD: Boolean = true): Boolean {
        if (isRecording) return false

        // 使用设置的采样率（与 ESP32 AFE 音频处理器一致，语音识别最优化）
        val recordSampleRate = sampleRate
        val minBuffer = AudioRecord.getMinBufferSize(
            recordSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)  // 8KB 缓冲

        try {
            // VOICE_COMMUNICATION 启用系统 AEC/NS + AGC，参考 ESP32 AEC_MODE_VOIP_HIGH_PERF
            // 回声消除防止扬声器播放的 AI 声音被麦克风采集，VAD 才能准确检测语音结束
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                recordSampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                debugLogAudioE(TAG, "AudioRecord初始化失败，尝试降级到MIC")
                // 降级到 MIC（部分设备不支持 VOICE_COMMUNICATION）
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    recordSampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    debugLogAudioE(TAG, "AudioRecord降级初始化也失败")
                    return false
                }
            }

            isRecording = true
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
                while (isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readSize <= 0) continue

                    val pcmData = buffer.copyOf(readSize)
                    onAudioData(pcmData)

                    // VAD 检测（参考 ESP32 afe_audio_processor VAD_MODE_0）
                    if (enableVAD) {
                        val rmsEnergy = calculateRmsEnergy(pcmData)
                        val now = System.currentTimeMillis()

                        if (rmsEnergy > SILENCE_RMS_THRESHOLD) {
                            // 有语音（需连续 2 个 buffer 确认，防止瞬时噪声误触发）
                            consecutiveSpeechBuffers++
                            consecutiveSilenceBuffers = 0
                            if (consecutiveSpeechBuffers >= SPEECH_CONSECUTIVE_BUFFERS) {
                                // 第一次确认语音时记录时间（用于 MIN_SPEECH_DURATION_MS 抑制）
                                if (firstSpeechTime == 0L) {
                                    firstSpeechTime = now
                                }
                                silenceStartTime = 0
                                hadSpeech = true
                            }
                        } else {
                            // 静音
                            consecutiveSpeechBuffers = 0
                            if (hadSpeech && firstSpeechTime > 0) {
                                // 之前有过语音，现在静音
                                if (silenceStartTime == 0L) {
                                    silenceStartTime = now
                                }
                                // 连续 2 个 buffer 都静音才认定是语音结束（防噪声误触）
                                consecutiveSilenceBuffers++
                                // MIN_SPEECH_DURATION_MS：语音必须持续至少 300ms 才有效
                                // 防止短促呼吸声/噪声被误认为语音，导致 VAD 在正常停顿时反而无法触发
                                val speechDuration = silenceStartTime - firstSpeechTime
                                if (consecutiveSilenceBuffers >= SILENCE_CONSECUTIVE_BUFFERS &&
                                    now - silenceStartTime >= SILENCE_TIMEOUT_MS &&
                                    speechDuration >= MIN_SPEECH_DURATION_MS) {
                                    debugLogAudio(TAG, "VAD检测到${SILENCE_TIMEOUT_MS}ms静音，触发自动停止（RMS=$rmsEnergy, 语音持续${speechDuration}ms）")
                                    onSilenceDetected?.invoke()
                                    break
                                }
                            }
                        }
                    }
                }
            }
            recordingThread?.start()

            debugLogAudio(TAG, "录音开始, 采样率: $recordSampleRate, VAD: $enableVAD, 降级阈值: $SILENCE_RMS_THRESHOLD")
            return true
        } catch (e: SecurityException) {
            debugLogAudioE(TAG, "录音权限被拒绝: ${e.message}")
            return false
        } catch (e: Exception) {
            debugLogAudioE(TAG, "录音启动失败: ${e.message}")
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
        val sampleCount = pcmData.size / 2
        for (i in 0 until sampleCount) {
            val sample = (pcmData[i * 2].toInt() and 0xFF) or
                        ((pcmData[i * 2 + 1].toInt() and 0xFF) shl 8)
            sumSquares += (sample * sample).toDouble()
        }
        return kotlin.math.sqrt(sumSquares / sampleCount)
    }

    fun stopRecording() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        debugLogAudio(TAG, "录音停止")
    }

    fun release() {
        stopRecording()
    }

    fun isRecording(): Boolean = isRecording
}
