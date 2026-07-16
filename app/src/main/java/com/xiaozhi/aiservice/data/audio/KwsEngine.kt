package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlin.math.min
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap

/**
 * KWS（Keyword Spotting）唤醒词引擎。
 *
 * 使用 MFCC + DTW（动态时间规整）匹配：
 *   1. Edge TTS 合成唤醒词 → MfccExtractor 提取 63×40 MFCC 矩阵
 *   2. 存储为模板（联系人 → 完整 MFCC 矩阵）
 *   3. 运行时提取音频 MFCC 矩阵，和模板做 DTW 距离
 *   4. 距离 < 阈值 → 触发唤醒
 *
 * DTW 优势：
 *   - 保留时间序列特征（字序敏感），"悠悠"≠"优游"
 *   - 对语速变化鲁棒
 *   - 不需要训练，无 ONNX 依赖
 */
class KwsEngine(
    /** DTW 距离阈值（越小越严格），默认 2.0 */
    private var matchThreshold: Float = 2.0f
) {
    companion object {
        private const val TAG = "KwsEngine"
        private const val N_MFCC = 40
        private const val CONTEXT_FRAMES = 63
        /** 完整 MFCC 矩阵 = 63 frames × 40 dims */
        private const val EMBEDDING_DIM = N_MFCC * CONTEXT_FRAMES  // 2520
    }

    @Volatile
    var initialized = false
        private set

    // ===== 模板存储（联系人ID → MFCC 矩阵） =====
    private val templates = ConcurrentHashMap<String, TemplateData>()
    data class TemplateData(
        val embedding: FloatArray,  // 2520D = 63×40 MFCC matrix
        val wakeWord: String
    )

    /** 最后匹配的 DTW 距离（越小越匹配） */
    @Volatile
    var lastScore = 0f
        private set

    // ===== 初始化 =====
    fun init() {
        initialized = true
        Logger.w(TAG, "KWS 引擎初始化完成 (DTW mode, threshold=$matchThreshold)")
    }

    fun reset() {
        lastScore = 0f
    }

    fun release() {
        initialized = false
    }

    // ===== MFCC 矩阵提取（不做时间平均，保留完整帧信息） =====
    fun extractEmbedding(mfccFeatureMap: FloatArray): FloatArray? {
        if (mfccFeatureMap.size != EMBEDDING_DIM) {
            Logger.w(TAG, "extractEmbedding: 输入维度错误 ${mfccFeatureMap.size} != $EMBEDDING_DIM")
            return null
        }
        // 直接返回 63×40 MFCC 矩阵（不做均值，DTW 利用时间结构）
        return mfccFeatureMap.copyOf()
    }

    // ===== 模板管理 =====

    fun addTemplate(contactId: String, wakeWord: String, mfccFeatures: FloatArray): Boolean {
        val embedding = extractEmbedding(mfccFeatures) ?: return false
        templates[contactId] = TemplateData(embedding, wakeWord)
        Logger.w(TAG, "✅ 唤醒模板已添加: contact=$contactId 「$wakeWord」 (${EMBEDDING_DIM}D MFCC)")
        return true
    }

    fun addTemplateFromPcm(contactId: String, wakeWord: String, pcmBytes: ByteArray): Boolean {
        val extractor = MfccExtractor()
        val floats = pcmBytesToFloats(pcmBytes)
        extractor.feed(floats)
        if (!extractor.isReady()) return false
        return addTemplate(contactId, wakeWord, extractor.getInputData())
    }

    /**
     * 从多段 PCM 音频中取第一段有效的 MFCC 作为模板。
     * 自动做端点检测裁剪，只保留纯发音部分。
     */
    fun addTemplateFromMultiplePcm(
        contactId: String,
        wakeWord: String,
        pcmSamples: List<ByteArray>
    ): Boolean {
        if (pcmSamples.isEmpty()) {
            Logger.w(TAG, "addTemplateFromMultiplePcm: pcmSamples 为空")
            return false
        }

        for ((idx, pcm) in pcmSamples.withIndex()) {
            val extractor = MfccExtractor()
            var pcmFloats = pcmBytesToFloats(pcm)
            val minSamples = 16000
            if (pcmFloats.size < minSamples) {
                val repeats = (minSamples / pcmFloats.size) + 1
                val padded = FloatArray(pcmFloats.size * repeats)
                for (r in 0 until repeats) {
                    System.arraycopy(pcmFloats, 0, padded, r * pcmFloats.size, pcmFloats.size)
                }
                pcmFloats = padded
            }
            extractor.feed(pcmFloats)
            if (extractor.isReady()) {
                val rawMfcc = extractor.getInputData()
                // 端点检测：去掉前后静音帧，只保留有效发音部分
                val trimmed = trimSilenceFrames(rawMfcc)
                templates[contactId] = TemplateData(trimmed, wakeWord)
                Logger.w(TAG, "✅ DTW 模板已添加: contact=$contactId 「$wakeWord」 ($idx/${pcmSamples.size}) frames=${trimmed.size / N_MFCC}")
                return true
            }
        }
        Logger.w(TAG, "addTemplateFromMultiplePcm: 所有样本 MFCC 帧数不足")
        return false
    }

    /**
     * 端点检测：去掉 MFCC 序列首尾的静音帧。
     * 计算每帧能量（40维平方和），保留能量 > 阈值 1% 的连续段。
     */
    private fun trimSilenceFrames(mfcc: FloatArray): FloatArray {
        val n = mfcc.size / N_MFCC
        if (n <= 2) return mfcc

        // 计算每帧能量
        val energies = FloatArray(n) { i ->
            var e = 0f
            for (j in 0 until N_MFCC) {
                val v = mfcc[i * N_MFCC + j]
                e += v * v
            }
            sqrt(e)
        }

        // 取最大能量的 5% 作为静音阈值
        val maxEnergy = energies.maxOrNull() ?: return mfcc
        val threshold = maxEnergy * 0.05f

        var start = 0
        while (start < n && energies[start] < threshold) start++
        var end = n - 1
        while (end > start && energies[end] < threshold) end--

        if (start >= end) return mfcc  // 保护：全静音则返回原始

        val trimmedLen = (end - start + 1) * N_MFCC
        val trimmed = FloatArray(trimmedLen)
        System.arraycopy(mfcc, start * N_MFCC, trimmed, 0, trimmedLen)
        return trimmed
    }

    fun removeTemplate(contactId: String) { templates.remove(contactId) }
    fun clearTemplates() { templates.clear() }
    fun hasTemplates(): Boolean = templates.isNotEmpty()
    fun templateCount(): Int = templates.size

    // ===== DTW 唤醒检测（运行时） =====

    /**
     * 对实时音频 MFCC 特征执行 DTW 匹配。
     *
     * @param mfccFeatureMap 63×40 MFCC 特征
     * @return 匹配到的联系人 ID，无匹配返回 null
     */
    fun match(mfccFeatureMap: FloatArray): String? {
        if (templates.isEmpty()) return null

        // CMVN 归一化：消除环境底噪漂移
        applyCmvn(mfccFeatureMap)

        var bestContact: String? = null
        var bestDistance = Float.MAX_VALUE
        var secondBestDistance = Float.MAX_VALUE

        for ((contactId, template) in templates) {
            val distance = dtwDistance(mfccFeatureMap, template.embedding)
            if (distance < bestDistance) {
                secondBestDistance = bestDistance
                bestDistance = distance
                bestContact = contactId
            } else if (distance < secondBestDistance) {
                secondBestDistance = distance
            }
        }

        lastScore = bestDistance

        if (bestContact == null) return null

        // ── 自适应判决：只看绝对值不够，还要看相对差距 ──
        // 阈值 1：绝对距离必须 < matchThreshold（越严格）
        if (bestDistance > matchThreshold) {
            Logger.w(TAG, "DTW 距离 ${"%.2f".format(bestDistance)} > 阈值 $matchThreshold")
            return null
        }

        // 阈值 2（动态）：最佳 vs 次佳的距离差距比
        // 如果有多个模板，最佳距离必须明显优于次佳（gap > 30%），否则判为模糊
        val gapRatio = if (secondBestDistance < Float.MAX_VALUE && secondBestDistance > 0) {
            bestDistance / secondBestDistance
        } else {
            0f  // 只有一个模板，无法比较
        }

        if (gapRatio > 0.7f && gapRatio > 0f) {
            // 最佳和次佳太接近（差距 < 30%），拒绝避免误报
            Logger.w(TAG, "DTW 模糊拒绝: best=${"%.2f".format(bestDistance)} 2nd=${"%.2f".format(secondBestDistance)} ratio=${"%.2f".format(gapRatio)}")
            return null
        }

        val word = templates[bestContact]?.wakeWord ?: ""
        Logger.w(TAG, "🎯 DTW 匹配成功: $bestContact 「$word」 dist=${"%.2f".format(bestDistance)} gap=${"%.2f".format(gapRatio)}")
        return bestContact
    }

    // ===== CMVN 归一化状态（实时底噪抑制） =====
    /** 当前环境 MFCC 均值估计（40维），指数移动平均 */
    private val noiseMfccMean = FloatArray(N_MFCC)
    /** 已用于估计噪声的帧数 */
    private var cmvnFrames = 0
    private val CMVN_MAX_FRAMES = 20  // 用前20帧做噪声估计

    /**
     * 对实时 MFCC 做 CMVN 归一化，消除环境底噪漂移。
     * 使用前 CMVN_MAX_FRAMES 帧估计噪声均值，后续帧减去该均值。
     */
    private fun applyCmvn(mfccFeatureMap: FloatArray) {
        val nFrames = CONTEXT_FRAMES
        // 提取当前帧的 MFCC 向量
        val curMfcc = FloatArray(N_MFCC)
        for (j in 0 until N_MFCC) curMfcc[j] = mfccFeatureMap[(nFrames - 1) * N_MFCC + j]

        if (cmvnFrames < CMVN_MAX_FRAMES) {
            // 累积噪声均值
            for (j in 0 until N_MFCC) noiseMfccMean[j] += curMfcc[j]
            cmvnFrames++
            if (cmvnFrames >= CMVN_MAX_FRAMES) {
                for (j in 0 until N_MFCC) noiseMfccMean[j] /= CMVN_MAX_FRAMES.toFloat()
            }
        }

        // 对所有帧减去噪声均值
        if (cmvnFrames >= CMVN_MAX_FRAMES) {
            for (i in 0 until nFrames) {
                for (j in 0 until N_MFCC) {
                    mfccFeatureMap[i * N_MFCC + j] -= noiseMfccMean[j]
                }
            }
        }
    }

    /**
     * 计算两个 MFCC 矩阵的 DTW 距离。支持变长（不等帧数）。
     * 保留时间序列对齐，对语速变化鲁棒。
     */
    private fun dtwDistance(a: FloatArray, b: FloatArray): Float {
        val n = a.size / N_MFCC
        val m = b.size / N_MFCC
        if (n == 0 || m == 0) return Float.MAX_VALUE

        // 成本矩阵（逐帧欧氏距离）
        val cost = Array(n) { FloatArray(m) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                var dist = 0f
                for (k in 0 until N_MFCC) {
                    val d = a[i * N_MFCC + k] - b[j * N_MFCC + k]
                    dist += d * d
                }
                cost[i][j] = sqrt(dist)
            }
        }

        // DTW 累积距离（滚动数组）
        val prev = FloatArray(m) { Float.MAX_VALUE }
        val curr = FloatArray(m)

        prev[0] = cost[0][0]
        for (j in 1 until m) prev[j] = prev[j - 1] + cost[0][j]

        for (i in 1 until n) {
            curr[0] = prev[0] + cost[i][0]
            for (j in 1 until m) {
                curr[j] = cost[i][j] + minOf(prev[j], curr[j - 1], prev[j - 1])
            }
            // 把 curr 复制到 prev 用于下一轮
            System.arraycopy(curr, 0, prev, 0, m)
        }

        return prev[m - 1]  // DTW 累积距离
    }

    // ===== 工具 =====

    fun setThreshold(threshold: Float) { matchThreshold = threshold }

    /**
     * 从持久化的 MFCC 矩阵字节数组恢复模板。
     */
    fun restoreTemplate(contactId: String, wakeWord: String, embeddingBytes: ByteArray): Boolean {
        if (embeddingBytes.size != EMBEDDING_DIM * 4) return false
        val emb = FloatArray(EMBEDDING_DIM)
        val buf = java.nio.ByteBuffer.wrap(embeddingBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().get(emb)
        templates[contactId] = TemplateData(emb, wakeWord)
        Logger.w(TAG, "✅ 已恢复唤醒模板: contact=$contactId 「$wakeWord」 (${EMBEDDING_DIM}D MFCC DTW)")
        return true
    }

    fun getTemplateData(contactId: String): TemplateData? = templates[contactId]
    fun getAllTemplateData(): Map<String, TemplateData> = templates.toMap()

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
}
