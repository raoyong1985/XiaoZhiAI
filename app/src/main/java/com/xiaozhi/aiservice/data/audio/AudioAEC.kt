package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlin.math.min

/**
 * 简易 NLMS 自适应回声消除器。
 *
 * 对应 ESP32 AFE（Audio Front-End）中的 AEC 模块。
 * 使用归一化最小均方（NLMS）自适应滤波器，
 * 以扬声器参考信号为输入，估计并消除麦克风中的回声分量。
 *
 * 算法：
 *   1. 延迟对齐：扬声器输出 → 麦克风采集有固定延迟（~30-50ms）
 *   2. NLMS 自适应滤波：估计回声路径的冲击响应
 *   3. 从麦克风信号中减去估计的回声
 *
 * 使用场景：
 *   - 设备播放 TTS 时，麦克风拾取的扬声器声音会被消除
 *   - 防止 TTS 播放过程中自己说话触发唤醒
 *
 * 参考：
 *   ESP-DSP 库中的 AEC 实现
 *   https://docs.espressif.com/projects/esp-dsp/en/latest/
 */
class AudioAEC(
    /** 自适应滤波器阶数（对应回声路径长度 @16kHz）*/
    private val filterLength: Int = 128,  // 8ms @16kHz
    /** NLMS 步长（μ），0.1~0.5，越大收敛越快但稳定性越差 */
    private val mu: Float = 0.3f,
    /** 正则化因子（防止除以 0）*/
    private val delta: Float = 1e-6f
) {
    companion object {
        private const val TAG = "AudioAEC"

        /** 固定延迟补偿（帧数 @16kHz，每帧 10ms = 160 samples）*/
        private const val DELAY_FRAMES = 3  // 30ms 延迟
        /** 播放参考环形缓冲大小（保存最近 500ms 的参考信号）*/
        private const val REF_BUF_SIZE = 8000  // 500ms @16kHz
    }

    /** 自适应滤波器系数 */
    private val w = FloatArray(filterLength)

    /** 播放参考信号环形缓冲 */
    private val refBuffer = FloatArray(REF_BUF_SIZE)
    private var refWritePos = 0

    /** 是否启用 */
    @Volatile
    var enabled = true

    /** 统计 */
    @Volatile
    var echoAttenuationDb = 0f  // 回声衰减量（dB）
        private set

    init {
        Logger.i(TAG, "AEC 初始化: filterLength=$filterLength mu=$mu")
    }

    /**
     * 将扬声器输出帧写入参考缓冲。
     * 由 AudioPlaybackManager 在播放音频时调用。
     *
     * @param pcmSamples 16-bit PCM 样本（float [-1, 1]）
     */
    fun feedReference(pcmSamples: FloatArray) {
        synchronized(refBuffer) {
            for (s in pcmSamples) {
                refBuffer[refWritePos] = s
                refWritePos = (refWritePos + 1) % REF_BUF_SIZE
            }
        }
    }

    /**
     * 对麦克风输入执行回声消除。
     *
     * @param micPcm 麦克风 PCM 数据（float [-1, 1]）
     * @return 回声消除后的 PCM 数据（长度和输入一致）
     */
    fun process(micPcm: FloatArray): FloatArray {
        if (!enabled || micPcm.isEmpty()) return micPcm

        val output = FloatArray(micPcm.size)
        var echoPower = 0f
        var micPower = 0f

        for (i in micPcm.indices) {
            // 从参考缓冲中获取延迟对齐的参考样本
            // 固定延迟补偿：寻找参考信号中对应位置
            val refIdx = (refWritePos - DELAY_FRAMES * 160 + i) % REF_BUF_SIZE

            // NLMS 自适应滤波
            // 1. 计算估计回声
            var estimatedEcho = 0f
            var normFactor = 0f
            var j = 0
            while (j < filterLength) {
                val refSample = if (refIdx - j >= 0) {
                    synchronized(refBuffer) { refBuffer[(refIdx - j) % REF_BUF_SIZE] }
                } else 0f
                estimatedEcho += w[j] * refSample
                normFactor += refSample * refSample
                j++
            }

            // 2. 误差信号 = 麦克风 - 估计回声
            val error = micPcm[i] - estimatedEcho
            output[i] = error

            // 3. 更新滤波器系数（NLMS）
            if (normFactor > delta) {
                val step = mu / normFactor
                var k = 0
                while (k < filterLength) {
                    val refSample = if (refIdx - k >= 0) {
                        synchronized(refBuffer) { refBuffer[(refIdx - k) % REF_BUF_SIZE] }
                    } else 0f
                    w[k] += step * error * refSample
                    k++
                }
            }

            // 统计
            echoPower += estimatedEcho * estimatedEcho
            micPower += micPcm[i] * micPcm[i]
        }

        // 计算回声衰减量（dB）
        if (micPower > 1e-10f && echoPower > 1e-10f) {
            val ratio = (micPower - echoPower) / micPower
            if (ratio > 0.01f) {
                echoAttenuationDb = 10f * kotlin.math.log10(ratio.coerceAtLeast(0.01f))
            }
        }

        return output
    }

    /** 处理 PCM 字节数据（16-bit 有符号 LE）*/
    fun process(pcmBytes: ByteArray): ByteArray {
        if (!enabled || pcmBytes.isEmpty()) return pcmBytes

        val floats = pcmBytesToFloats(pcmBytes)
        val processed = process(floats)
        return floatsToPcmBytes(processed)
    }

    /** 重置滤波器状态 */
    fun reset() {
        w.fill(0f)
        synchronized(refBuffer) {
            refBuffer.fill(0f)
            refWritePos = 0
        }
        echoAttenuationDb = 0f
    }

    // ===== 工具方法 =====

    private fun pcmBytesToFloats(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        val floats = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            floats[i] = ((hi shl 8 or lo).toShort()).toFloat() / 32768f
        }
        return floats
    }

    private fun floatsToPcmBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val sample = (floats[i] * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
