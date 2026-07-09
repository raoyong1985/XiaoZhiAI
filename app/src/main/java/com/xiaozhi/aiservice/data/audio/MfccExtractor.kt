package com.xiaozhi.aiservice.data.audio

import kotlin.math.*

/**
 * MFCC 特征提取器（40 维）
 *
 * 将 16kHz PCM 音频转为 MFCC 特征向量，用于本地唤醒词模板匹配。
 *
 * 算法步骤：
 *   1. 预加重 (pre-emphasis)
 *   2. 分帧 + 汉宁窗
 *   3. FFT → 功率谱
 *   4. Mel 滤波器组
 *   5. Log
 *   6. DCT → 40 维 MFCC
 */
class MfccExtractor {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SHIFT = 96      // 6ms
        private const val FRAME_LENGTH = 480    // 30ms
        private const val N_MFCC = 40
        private const val N_FFT = 512
        private const val N_MELS = 40
        private const val PRE_EMPHASIS = 0.97f
        private const val CONTEXT_FRAMES = 63
        private const val INPUT_SIZE = N_MFCC * CONTEXT_FRAMES  // 40 * 63 = 2520
    }

    // 循环缓冲：保存最近 CONTEXT_FRAMES 帧的 MFCC
    private val mfccRingBuffer = Array(CONTEXT_FRAMES) { FloatArray(N_MFCC) }
    private var ringIndex = 0
    private var totalFrames = 0

    // 上一帧的最后一个样本（用于预加重）
    private var prevSample = 0f

    // 汉宁窗（预计算）
    private val hanningWindow = FloatArray(FRAME_LENGTH) { i ->
        0.5f * (1f - cos(2f * PI * i / (FRAME_LENGTH - 1))).toFloat()
    }

    // Mel 滤波器组（预计算）
    private val melFilterbank: Array<FloatArray> by lazy { createMelFilterbank() }

    // DCT 矩阵（预计算）
    private val dctMatrix: Array<FloatArray> by lazy { createDctMatrix() }

    // FFT 三角函数查找表
    private val fftSin: FloatArray
    private val fftCos: FloatArray

    init {
        val half = N_FFT / 2
        fftSin = FloatArray(N_FFT) { i -> sin(2f * PI * i / N_FFT).toFloat() }
        fftCos = FloatArray(N_FFT) { i -> cos(2f * PI * i / N_FFT).toFloat() }
    }

    /**
     * 重置内部状态
     */
    fun reset() {
        ringIndex = 0
        totalFrames = 0
        prevSample = 0f
        for (i in 0 until CONTEXT_FRAMES) {
            mfccRingBuffer[i].fill(0f)
        }
    }

    /**
     * 是否已积累足够帧用于推理
     */
    fun isReady(): Boolean = totalFrames >= CONTEXT_FRAMES

    /**
     * 获取输入张量数据（40*63=2520 维）
     * 只有在 isReady() 为 true 时调用有效
     */
    fun getInputData(): FloatArray {
        val data = FloatArray(INPUT_SIZE)
        val oldest = if (totalFrames >= CONTEXT_FRAMES) {
            (ringIndex + 1) % CONTEXT_FRAMES
        } else {
            0
        }
        for (i in 0 until CONTEXT_FRAMES) {
            val bufIdx = (oldest + i) % CONTEXT_FRAMES
            System.arraycopy(mfccRingBuffer[bufIdx], 0, data, i * N_MFCC, N_MFCC)
        }
        return data
    }

    /**
     * 喂入 PCM 数据，逐帧提取 MFCC
     */
    fun feed(pcmData: FloatArray) {
        // 预加重
        val emphasized = FloatArray(pcmData.size)
        for (i in pcmData.indices) {
            emphasized[i] = pcmData[i] - PRE_EMPHASIS * prevSample
            prevSample = pcmData[i]
        }

        // 分帧处理
        var offset = 0
        while (offset + FRAME_LENGTH <= emphasized.size) {
            val frame = FloatArray(FRAME_LENGTH)
            System.arraycopy(emphasized, offset, frame, 0, FRAME_LENGTH)
            for (i in 0 until FRAME_LENGTH) {
                frame[i] *= hanningWindow[i]
            }

            val fftOutput = fft(frame)
            val power = FloatArray(N_FFT / 2 + 1) { i ->
                val idx = i * 2
                val real = fftOutput[idx]
                val imag = if (idx + 1 < fftOutput.size) fftOutput[idx + 1] else 0f
                real * real + imag * imag
            }

            val melEnergy = FloatArray(N_MELS) { m ->
                var energy = 0f
                for (k in 0 until power.size) {
                    energy += power[k] * melFilterbank[m][k]
                }
                energy
            }

            val logMel = FloatArray(N_MELS) { m ->
                ln(maxOf(melEnergy[m], 1e-10f))
            }

            val mfcc = FloatArray(N_MFCC) { c ->
                var sum = 0f
                for (m in 0 until N_MELS) {
                    sum += dctMatrix[c][m] * logMel[m]
                }
                sum
            }

            System.arraycopy(mfcc, 0, mfccRingBuffer[ringIndex], 0, N_MFCC)
            ringIndex = (ringIndex + 1) % CONTEXT_FRAMES
            totalFrames++

            offset += FRAME_SHIFT
        }
    }

    /**
     * 喂入原始 PCM 字节（16-bit little-endian）
     */
    fun feed(pcmBytes: ByteArray) {
        feed(pcmBytesToFloats(pcmBytes))
    }

    /**
     * 从 PCM 字节数据中提取平均 MFCC 特征向量（40维）
     * 适用于唤醒词模板提取和匹配检测
     */
    fun extractTemplate(pcmBytes: ByteArray): FloatArray {
        reset()
        val floats = pcmBytesToFloats(pcmBytes)
        feed(floats)

        val frameCount = minOf(totalFrames, CONTEXT_FRAMES)
        if (frameCount == 0) return FloatArray(N_MFCC)

        val avg = FloatArray(N_MFCC)
        val oldest = if (totalFrames >= CONTEXT_FRAMES) {
            (ringIndex + 1) % CONTEXT_FRAMES
        } else {
            0
        }
        for (i in 0 until frameCount) {
            val bufIdx = (oldest + i) % CONTEXT_FRAMES
            for (j in 0 until N_MFCC) {
                avg[j] += mfccRingBuffer[bufIdx][j]
            }
        }
        for (j in 0 until N_MFCC) {
            avg[j] = avg[j] / frameCount
        }
        return avg
    }

    /**
     * 计算两个 MFCC 向量的余弦相似度
     * 范围：[-1, 1]，值越大越相似，一般 ≥0.85 可认为匹配
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val norm = sqrt(na * nb)
        return if (norm > 1e-10f) dot / norm else 0f
    }

    // === 内部工具 ===

    private fun pcmBytesToFloats(pcmBytes: ByteArray): FloatArray {
        val shortCount = pcmBytes.size / 2
        val floats = FloatArray(shortCount)
        for (i in 0 until shortCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt() and 0xFF
            val sample = (hi shl 8 or lo).toShort()
            floats[i] = sample.toFloat() / 32768f
        }
        return floats
    }

    // === FFT ===
    private fun fft(input: FloatArray): FloatArray {
        val n = nextPow2(input.size)
        val real = FloatArray(n)
        val imag = FloatArray(n)
        System.arraycopy(input, 0, real, 0, input.size)

        for (i in 1 until n) {
            var j = 0
            var m = i
            var bit = n shr 1
            while (bit > 0) {
                if (m and 1 == 1) j += bit
                m = m shr 1
                bit = bit shr 1
            }
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val wStep = N_FFT / len
            for (i in 0 until n step len) {
                for (j in 0 until half) {
                    val wIdx = j * wStep
                    val wr = fftCos[wIdx]
                    val wi = fftSin[wIdx]
                    val tr = real[i + j + half] * wr - imag[i + j + half] * wi
                    val ti = real[i + j + half] * wi + imag[i + j + half] * wr
                    real[i + j + half] = real[i + j] - tr
                    imag[i + j + half] = imag[i + j] - ti
                    real[i + j] += tr
                    imag[i + j] += ti
                }
            }
            len = len shl 1
        }

        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = real[i]
            out[i * 2 + 1] = imag[i]
        }
        return out
    }

    private fun nextPow2(x: Int): Int {
        var n = 1
        while (n < x) n = n shl 1
        return n
    }

    // === Mel 滤波器组 ===
    private fun createMelFilterbank(): Array<FloatArray> {
        val lowHz = 0f
        val highHz = SAMPLE_RATE / 2f
        val minMel = hzToMel(lowHz)
        val maxMel = hzToMel(highHz)
        val melPoints = FloatArray(N_MELS + 2) { i ->
            minMel + (maxMel - minMel) * i / (N_MELS + 1)
        }
        val hzPoints = FloatArray(N_MELS + 2) { melToHertz(melPoints[it]) }
        val nFftBins = N_FFT / 2 + 1

        return Array(N_MELS) { m ->
            val fb = FloatArray(nFftBins)
            for (k in 0 until nFftBins) {
                val fk = k.toFloat() * SAMPLE_RATE / N_FFT
                if (fk < hzPoints[m]) continue
                if (fk <= hzPoints[m + 1]) {
                    fb[k] = (fk - hzPoints[m]) / (hzPoints[m + 1] - hzPoints[m])
                } else if (fk < hzPoints[m + 2]) {
                    fb[k] = (hzPoints[m + 2] - fk) / (hzPoints[m + 2] - hzPoints[m + 1])
                }
            }
            fb
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHertz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    // === DCT 矩阵（Type-II） ===
    private fun createDctMatrix(): Array<FloatArray> {
        return Array(N_MFCC) { c ->
            FloatArray(N_MELS) { m ->
                val cosVal = cos(PI * c * (m + 0.5) / N_MELS).toFloat()
                if (c == 0) {
                    sqrt(1f / N_MELS) * cosVal
                } else {
                    sqrt(2f / N_MELS) * cosVal
                }
            }
        }
    }
}
