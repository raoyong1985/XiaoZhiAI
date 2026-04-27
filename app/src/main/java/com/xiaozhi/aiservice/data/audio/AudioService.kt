package com.xiaozhi.aiservice.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

class AudioService {

    companion object {
        private const val TAG = "AudioService"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_TIMEOUT_MS = 3000L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var onSilenceDetected: (() -> Unit)? = null
    private var audioManager: AudioManager? = null
    private var context: Context? = null
    private var isMuted = false
    private var audioFocusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * 请求音频焦点（播放时使用）
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK：临时获取焦点，其他媒体音量降低
     */
    fun requestAudioFocus(): Boolean {
        if (audioFocusRequest != null) return true
        return try {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
            val result = audioManager?.requestAudioFocus(focusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = focusRequest
                Log.i(TAG, "AudioFocus granted")
                true
            } else {
                Log.w(TAG, "AudioFocus request failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioFocus error: ${e.message}")
            false
        }
    }

    /**
     * 放弃音频焦点
     */
    fun abandonAudioFocus() {
        audioFocusRequest?.let {
            try {
                audioManager?.abandonAudioFocusRequest(it)
                Log.i(TAG, "AudioFocus abandoned")
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
        Log.i(TAG, "设置采样率: $rate")
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

        // 使用 16000Hz 录音（与服务器上行一致），缓冲区 4 倍防止丢帧
        val recordSampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            recordSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)  // 至少 8KB 缓冲

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 含 AEC
                recordSampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败")
                return false
            }

            isRecording = true
            audioRecord?.startRecording()

            val buffer = ByteArray(bufferSize)
            var silenceStartTime = 0L

            recordingThread = Thread {
                while (isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readSize <= 0) continue

                    val pcmData = buffer.copyOf(readSize)
                    onAudioData(pcmData)

                    // VAD 检测
                    if (enableVAD) {
                        val energy = calculateEnergy(pcmData)
                        val now = System.currentTimeMillis()

                        if (energy > SILENCE_THRESHOLD) {
                            silenceStartTime = 0
                        } else {
                            if (silenceStartTime == 0L) {
                                silenceStartTime = now
                            } else if (now - silenceStartTime >= SILENCE_TIMEOUT_MS) {
                                Log.i(TAG, "检测到${SILENCE_TIMEOUT_MS}ms静音，自动停止录音")
                                onSilenceDetected?.invoke()
                                break
                            }
                        }
                    }
                }
            }
            recordingThread?.start()

            Log.i(TAG, "录音开始, 采样率: $recordSampleRate, VAD: $enableVAD")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "录音权限被拒绝: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
            return false
        }
    }

    /**
     * 计算音频能量
     */
    private fun calculateEnergy(pcmData: ByteArray): Double {
        if (pcmData.size < 2) return 0.0
        var sum = 0.0
        val sampleCount = pcmData.size / 2
        for (i in 0 until sampleCount) {
            // 16-bit PCM, little-endian
            val sample = (pcmData[i * 2].toInt() and 0xFF) or
                        ((pcmData[i * 2 + 1].toInt() and 0xFF) shl 8)
            sum += abs(sample)
        }
        return sum / sampleCount
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

        Log.i(TAG, "录音停止")
    }

    fun release() {
        stopRecording()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun isRecording(): Boolean = isRecording
}
