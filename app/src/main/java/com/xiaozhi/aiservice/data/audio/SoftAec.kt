package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.min

/**
 * 软件 NLMS 自适应回声消除器。
 *
 * 使用 TTS 播放 PCM 作为参考信号（far-end），从麦克风输入（near-end）中
 * 自适应地消除扬声器回声分量。参考 ESP32 AFE 的 AEC 模块设计思路。
 *
 * 算法：Normalized Least Mean Squares (NLMS) 自适应滤波器
 *   filter(n) = Σ w(i) * ref(n - i)
 *   e(n) = mic(n) - filter(n)
 *   w(i) += μ * e(n) * ref(n - i) / (||ref||² + δ)
 *
 * 参数设计（16kHz）：
 *   - 滤波器阶数 512 = 32ms（覆盖典型手机扬声器→麦克风回声路径长度）
 *   - 步长 0.3（中等收敛速度，兼顾稳定性和适应性）
 *   - 正则化 1e-6（防止除零）
 */
class SoftAec(
    private val filterOrder: Int = 512,
    private val mu: Float = 0.3f,
    private val regularization: Float = 1e-6f
) {

    companion object {
        private const val TAG = "SoftAec"
        /** 双讲抑制阈值：mic 能量超过参考信号能量 × THRESHOLD 时判为双讲 */
        private const val DOUBLE_TALK_THRESHOLD = 2.0f
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE = 16
    }

    /** 自适应滤波器权重（在 far-end 参考信号上滑动拟合回声路径） */
    private val weights = FloatArray(filterOrder)

    /** 上一次处理的参考信号帧（用于连续帧间的滤波状态保持） */
    private val refHistory = FloatArray(filterOrder)

    /** 是否已训练足够样本（前 500ms 静默学习不做输出） */
    @Volatile
    var isConverged: Boolean = false
        private set

    private var totalSamplesProcessed = 0
    private val CONVERGENCE_SAMPLES = 8000  // 500ms @ 16kHz

    /** 统计值 */
    @Volatile
    var lastErm: Float = 0f   // 残留回声均值
        private set
    @Volatile
    var lastMicLevel: Float = 0f
        private set

    /**
     * 对一帧麦克风 PCM 应用回声消除。
     *
     * @param micPcm  麦克风 PCM（16-bit LE mono，16kHz）
     * @param refPcm  对齐的参考 PCM（TTS 播放音频，16kHz，等长）
     * @return 回声消除后的 PCM（与 micPcm 等长）
     */
    fun process(micPcm: ByteArray, refPcm: ByteArray): ByteArray {
        if (micPcm.size < 2 || refPcm.size < 2) return micPcm
        val sampleCount = min(micPcm.size / BYTES_PER_SAMPLE, refPcm.size / BYTES_PER_SAMPLE)
        if (sampleCount == 0) return micPcm

        val output = ByteArray(sampleCount * BYTES_PER_SAMPLE)
        var echoReturnLoss = 0f
        var micEnergy = 0f

        for (i in 0 until sampleCount) {
            val micSample = bytesToShort(micPcm, i)
            val refSample = bytesToShort(refPcm, i)

            // 更新参考历史
            System.arraycopy(refHistory, 1, refHistory, 0, filterOrder - 1)
            refHistory[filterOrder - 1] = refSample

            // 双讲检测：如果 mic 远强于 ref，可能是用户自己说话，大幅度降低步长
            // （减轻 AEC 在双讲场景下发散失真）
            val isDoubleTalk = abs(micSample) > abs(refSample) * DOUBLE_TALK_THRESHOLD
            val effectiveMu = if (isDoubleTalk) mu * 0.1f else mu

            // NLMS 滤波
            var filterOutput = 0f
            var refPower = 0f
            for (j in 0 until filterOrder) {
                filterOutput += weights[j] * refHistory[j]
                refPower += refHistory[j] * refHistory[j]
            }

            val error = micSample - filterOutput

            // 更新权重
            if (refPower > 1e-8f) {
                val norm = refPower + regularization
                for (j in 0 until filterOrder) {
                    weights[j] += effectiveMu * error * refHistory[j] / norm
                }
            }

            // 输出
            writeShort(output, i, error.toInt().coerceIn(-32768, 32767).toShort())

            echoReturnLoss += abs(error)
            micEnergy += abs(micSample)
            totalSamplesProcessed++
        }

        lastErm = echoReturnLoss / sampleCount
        lastMicLevel = micEnergy / sampleCount

        if (!isConverged && totalSamplesProcessed >= CONVERGENCE_SAMPLES) {
            isConverged = true
            Logger.i(TAG, "NLMS AEC 收敛完成（${totalSamplesProcessed} 样本）")
        }

        return output
    }

    /**
     * 重置滤波器状态。
     * 在对话模式切换（SPEAKING→LISTENING）时调用。
     */
    fun reset() {
        weights.fill(0f)
        refHistory.fill(0f)
        isConverged = false
        totalSamplesProcessed = 0
        lastErm = 0f
        lastMicLevel = 0f
        Logger.i(TAG, "NLMS AEC 已重置")
    }

    // === 工具方法 ===

    private fun bytesToShort(data: ByteArray, index: Int): Float {
        val lo = data[index * 2].toInt() and 0xFF
        val hi = data[index * 2 + 1].toInt() and 0xFF
        return (hi shl 8 or lo).toShort().toFloat()
    }

    private fun writeShort(data: ByteArray, index: Int, value: Short) {
        data[index * 2] = (value.toInt() and 0xFF).toByte()
        data[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}
