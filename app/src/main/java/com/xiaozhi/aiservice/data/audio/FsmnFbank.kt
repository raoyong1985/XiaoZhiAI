package com.xiaozhi.aiservice.data.audio

import kotlin.math.*

/**
 * FSMN-VAD 所需的 FBank 特征提取（16kHz, 25ms窗, 10ms移, 80mel, 5帧叠LFR）
 *
 * 管线（对齐 FunASR WavFrontendOnline + Kaldi FBank 标准）：
 *   PCM(16kHz int16) → [预加重 0.97] → [STFT 512FFT] → [功率谱/FFT_SIZE] → [80 Mel滤波器组]
 *   → [Log] → [5帧叠LFR] → 输出 [frames, 400]
 *
 * 修复：
 * - 加预加重（FunASR 标准）
 * - 功率谱归一化（÷FFT_SIZE，对齐 Kaldi）
 * - Mel 滤波器单位面积归一化
 *
 * 每10ms输出一帧（第一步进160样本），模型输入 400 维 = 80mel × 5帧叠
 */
class FsmnFbank {

    companion object {
        const val SAMPLE_RATE = 16000
        /** 帧长 25ms */
        const val WINDOW_SIZE = 400
        /** 帧移 10ms */
        const val FRAME_SHIFT = 160
        /** FFT 点数 */
        private const val FFT_SIZE = 512
        /** Mel 滤波器数量 */
        private const val N_MELS = 80
        /** LFR 叠帧数 */
        private const val LFR_M = 5
        /** 最终特征维度 */
        const val FEAT_DIM = N_MELS * LFR_M  // 400
        /** 预加重系数（Kaldi 标准） */
        private const val PRE_EMPHASIS = 0.97f
    }

    // Hamming 窗
    private val window = FloatArray(WINDOW_SIZE).apply {
        for (i in indices) {
            this[i] = (0.54 - 0.46 * cos(2.0 * PI * i / (WINDOW_SIZE - 1))).toFloat()
        }
    }

    // Mel 滤波器组 [N_MELS][FFT_SIZE/2+1]，面积归一化
    private val melBanks = createMelFilterbank()

    // 输入 PCM 缓冲（跨帧保持连续性）
    private val pcmBuffer = FloatArray(FFT_SIZE)
    private var pcmOffset = 0
    /** 上一帧最后一个样本（预加重延迟线） */
    private var prevSample = 0f

    // LFR 环形缓冲：存最近 LFR_M 帧的 mel 特征
    private val lfrBuffer = Array(LFR_M) { FloatArray(N_MELS) }
    private var lfrIndex = 0
    private var lfrFilled = false

    // 已输出的帧数（起始帧丢弃）
    private var frameCount = 0
    private val dropFrames = 3

    // FFT 工作区
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)

    /** 重置状态 */
    fun reset() {
        pcmBuffer.fill(0f)
        pcmOffset = 0
        prevSample = 0f
        lfrBuffer.forEach { it.fill(0f) }
        lfrIndex = 0
        lfrFilled = false
        frameCount = 0
        fftRe.fill(0f)
        fftIm.fill(0f)
    }

    /**
     * 输入 PCM 数据，返回完整 FBank 帧列表。
     * @param pcmData PCM 16bit 小端序
     * @return 每帧 [FEAT_DIM] float，形状 [frames, FEAT_DIM]
     */
    fun process(pcmData: ByteArray): List<FloatArray> {
        val shortCount = pcmData.size / 2
        if (shortCount == 0) return emptyList()

        // 转 int16 → float32 + 预加重
        val audioFloat = FloatArray(shortCount)
        for (i in 0 until shortCount) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt() and 0xFF
            val sample = (hi shl 8 or lo).toShort().toFloat() / 32768.0f
            // 预加重：y(t) = x(t) - 0.97 * x(t-1)
            audioFloat[i] = sample - PRE_EMPHASIS * prevSample
            prevSample = sample
        }

        val frames = mutableListOf<FloatArray>()

        // 批量写入 PCM 缓冲，避免逐样本循环
        var writePos = 0
        while (writePos < shortCount) {
            val space = FFT_SIZE - pcmOffset
            val copyLen = minOf(space, shortCount - writePos)
            System.arraycopy(audioFloat, writePos, pcmBuffer, pcmOffset, copyLen)
            pcmOffset += copyLen
            writePos += copyLen

            // 够一帧就处理
            while (pcmOffset >= WINDOW_SIZE) {
                val frame = pcmBuffer.copyOfRange(0, WINDOW_SIZE)

                val melFeat = computeMel(frame)
                frameCount++

                if (frameCount > dropFrames) {
                    lfrBuffer[lfrIndex] = melFeat
                    lfrIndex = (lfrIndex + 1) % LFR_M
                    if (!lfrFilled && lfrIndex == 0) lfrFilled = true

                    if (lfrFilled) {
                        val feat = FloatArray(FEAT_DIM)
                        for (j in 0 until LFR_M) {
                            val srcIdx = (lfrIndex + j) % LFR_M
                            System.arraycopy(lfrBuffer[srcIdx], 0, feat, j * N_MELS, N_MELS)
                        }
                        frames.add(feat)
                    }
                }

                // 滑动 PCM 缓冲
                System.arraycopy(pcmBuffer, FRAME_SHIFT, pcmBuffer, 0, FFT_SIZE - FRAME_SHIFT)
                pcmOffset = FFT_SIZE - FRAME_SHIFT
            }
        }

        return frames
    }

    /**
     * 计算一帧的 Mel 特征（80维）
     * Kaldi 兼容：功率谱归一化 → Mel滤波 → Log
     */
    private fun computeMel(frame: FloatArray): FloatArray {
        // 1. 加 Hamming 窗
        for (i in frame.indices) {
            fftRe[i] = frame[i] * window[i]
            fftIm[i] = 0f
        }
        for (i in WINDOW_SIZE until FFT_SIZE) {
            fftRe[i] = 0f
            fftIm[i] = 0f
        }

        // 2. RFFT
        rfft(fftRe, fftIm)

        // 3. 功率谱归一化（÷FFT_SIZE，对齐 Kaldi）
        val invN = 1.0f / FFT_SIZE
        for (i in 0..FFT_SIZE / 2) {
            powerSpectrum[i] = (fftRe[i] * fftRe[i] + fftIm[i] * fftIm[i]) * invN
        }

        // 4. Mel 滤波器组
        val melEnergies = FloatArray(N_MELS)
        for (m in 0 until N_MELS) {
            var energy = 0f
            for (k in 0..FFT_SIZE / 2) {
                energy += powerSpectrum[k] * melBanks[m][k]
            }
            melEnergies[m] = max(energy, 1e-10f)
        }

        // 5. Log
        for (m in 0 until N_MELS) {
            melEnergies[m] = ln(melEnergies[m])
        }

        return melEnergies
    }

    /**
     * 实数 FFT（Cooley-Tukey 基2，就地修改 re/im）
     */
    private fun rfft(re: FloatArray, im: FloatArray) {
        val n = re.size
        val bits = Integer.numberOfLeadingZeros(n) xor 31
        require(1 shl bits == n) { "FFT size must be power of 2" }

        // 位反转重排
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
            }
            var k = n / 2
            while (k <= j) { j -= k; k /= 2 }
            j += k
        }

        // 蝶形运算
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val wRe = cos(-2.0 * PI / len).toFloat()
            val wIm = sin(-2.0 * PI / len).toFloat()
            for (i in 0 until n step len) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until halfLen) {
                    val idx1 = i + k
                    val idx2 = i + k + halfLen
                    val tr = wr * re[idx2] - wi * im[idx2]
                    val ti = wr * im[idx2] + wi * re[idx2]
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    val newWr = wr * wRe - wi * wIm
                    wi = wr * wIm + wi * wRe
                    wr = newWr
                }
            }
            len *= 2
        }
    }

    /**
     * 创建 Mel 滤波器组（80个三角滤波器，面积归一化）
     * 覆盖 0~8000Hz，映射到 512-bin FFT
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(SAMPLE_RATE / 2.0)
        val melPoints = DoubleArray(N_MELS + 2)
        val hzPoints = DoubleArray(N_MELS + 2)
        for (i in melPoints.indices) {
            melPoints[i] = lowMel + i * (highMel - lowMel) / (N_MELS + 1)
            hzPoints[i] = melToHz(melPoints[i])
        }

        val binPoints = IntArray(N_MELS + 2) { i ->
            (hzPoints[i] * (FFT_SIZE / 2) / (SAMPLE_RATE / 2)).toInt()
                .coerceIn(0, FFT_SIZE / 2)
        }

        val filterbank = Array(N_MELS) { FloatArray(FFT_SIZE / 2 + 1) }
        for (m in 0 until N_MELS) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            // 计算面积归一化因子
            val area = (right - left).toFloat() / 2.0f
            val invArea = if (area > 0f) 1.0f / area else 1.0f
            // 左斜坡
            for (k in left until center) {
                filterbank[m][k] = ((k - left).toFloat() / (center - left)) * invArea
            }
            // 右斜坡
            for (k in center..right) {
                filterbank[m][k] = ((right - k).toFloat() / (right - center)) * invArea
            }
        }
        return filterbank
    }

    private fun hzToMel(hz: Double): Double {
        return 2595.0 * log10(1.0 + hz / 700.0)
    }

    private fun melToHz(mel: Double): Double {
        return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }
}
