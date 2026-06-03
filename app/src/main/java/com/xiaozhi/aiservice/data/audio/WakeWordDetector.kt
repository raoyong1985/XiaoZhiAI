package com.xiaozhi.aiservice.data.audio

import android.content.Context
import com.xiaozhi.aiservice.utils.Logger
import java.io.ByteArrayOutputStream

/**
 * SileroVAD 语音激活检测器
 *
 * 使用 Silero VAD ONNX 模型做纯本地语音活动检测，
 * 连续说话超过 VOICE_HOLD_MS 即触发唤醒。
 *
 * 对比 RMS 能量阈值：
 * - SileroVAD 是神经网络，能区分人声和噪声（风扇/空调/马路不会误触发）
 * - 准确率高得多，特别适合做唤醒触发
 *
 * 模型文件：assets/silero_vad.onnx（与录音管线的 VAD 共用）
 *
 * 架构：
 *   AudioRecord → WakeWordDetector.feed() → SileroVAD.isVoice() → 连续语音计时 → 回调
 *
 * 降级：若 SileroVAD ONNX 初始化失败（部分 OPPO/MTK JNI 问题），自动降级到 RMS 能量
 */
class WakeWordDetector {

    companion object {
        private const val TAG = "WakeWordDetector"

        /** 连续语音持续多久才触发唤醒（毫秒）——中文唤醒词"悠悠你好"约1-1.2秒，降误触发 */
        private const val VOICE_HOLD_MS = 800L

        /** 每帧最大采样数（100ms @ 16kHz） */
        private const val FRAME_SAMPLES = 1600

        /** RMS 能量降级阈值 */
        private const val RMS_THRESHOLD = 600

        /** 唤醒后音频缓冲区上限（保存最近的 PCM） */
        private const val MAX_BUFFER_MS = 2000L
        private const val MAX_BUFFER_BYTES = 64000  // 2秒 PCM: 2000*16*2/1000

        const val DEFAULT_MODEL = "silero_vad"
    }

    // ===== SileroVAD 引擎 =====
    private var sileroVad: SileroVAD? = null
    private var useSilero = false

    // ===== 状态 =====
    private var isInitialized = false
    private var detectionCallback: ((String) -> Unit)? = null
    private var voiceStartTime = 0L
    private var isPendingWake = false
    private var androidContext: Context? = null

    // ===== 音频缓冲区（唤醒后用于服务器验证） =====
    private val audioBuffer = ByteArrayOutputStream()

    fun initialize(
        context: Context? = null,
        modelName: String = DEFAULT_MODEL
    ): Boolean {
        if (isInitialized) return true
        androidContext = context

        // 尝试初始化 SileroVAD（ONNX 模型）
        if (context != null) {
            try {
                val vad = SileroVAD()
                vad.init(context)
                // 直接检查 initialized（不能调 isInitialized()，它硬编码返回 false）
                val initField = SileroVAD::class.java.getDeclaredField("initialized")
                initField.isAccessible = true
                useSilero = initField.getBoolean(vad)
                if (useSilero) {
                    sileroVad = vad
                    Logger.i(TAG, "SileroVAD 引擎就绪")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "SileroVAD 初始化失败: ${e.message}，降级到 RMS 能量")
                useSilero = false
            }
        }

        if (!useSilero) {
            Logger.i(TAG, "RMS 能量阈值模式（阈值=$RMS_THRESHOLD）")
        }

        isInitialized = true
        reset()
        Logger.i(TAG, "语音激活检测器已启动（保持=${VOICE_HOLD_MS}ms）")
        return true
    }

    fun setOnWakeWordDetected(callback: (String) -> Unit) {
        detectionCallback = callback
    }

    /**
     * 喂入 PCM 数据，检测语音激活
     */
    fun feed(pcmData: ByteArray): String? {
        if (!isInitialized) return null

        val now = System.currentTimeMillis()

        // 写入循环音频缓冲区
        writeToBuffer(pcmData)

        // 语音检测：优先 SileroVAD，降级 RMS
        val hasVoice = if (useSilero && sileroVad != null) {
            try {
                sileroVad!!.isVoice(pcmData)
            } catch (_: Exception) {
                calculateRms(pcmData) > RMS_THRESHOLD
            }
        } else {
            calculateRms(pcmData) > RMS_THRESHOLD
        }

        if (hasVoice) {
            if (voiceStartTime == 0L) {
                voiceStartTime = now
            }

            val voiceDuration = now - voiceStartTime

            // 连续语音超过阈值 → 唤醒
            if (!isPendingWake && voiceDuration >= VOICE_HOLD_MS) {
                isPendingWake = true
                Logger.i(TAG, "🎤 SileroVAD 检测到连续语音 ${voiceDuration}ms，触发唤醒")
                detectionCallback?.invoke(DEFAULT_MODEL)
                return DEFAULT_MODEL
            }
        } else {
            voiceStartTime = 0L
        }

        return null
    }

    fun getBufferedAudio(): ByteArray = audioBuffer.toByteArray()
    fun clearBuffer() { synchronized(audioBuffer) { audioBuffer.reset() } }

    private fun writeToBuffer(data: ByteArray) {
        synchronized(audioBuffer) {
            audioBuffer.write(data)
            if (audioBuffer.size() > MAX_BUFFER_BYTES) {
                val excess = audioBuffer.size() - MAX_BUFFER_BYTES
                val current = audioBuffer.toByteArray()
                audioBuffer.reset()
                audioBuffer.write(current, excess, current.size - excess)
            }
        }
    }

    private fun calculateRms(pcmData: ByteArray): Double {
        var sumSq = 0.0
        val n = pcmData.size / 2
        for (i in 0 until n) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8 or lo).toShort().toInt()
            sumSq += (s * s).toDouble()
        }
        return Math.sqrt(sumSq / n)
    }

    fun reset() {
        voiceStartTime = 0L
        isPendingWake = false
        sileroVad?.reset()
    }

    fun release() {
        reset()
        detectionCallback = null
        clearBuffer()
        sileroVad = null
        isInitialized = false
        Logger.i(TAG, "语音激活检测器已释放")
    }

    fun isReady(): Boolean = isInitialized
    val frameLength: Int get() = FRAME_SAMPLES
}
