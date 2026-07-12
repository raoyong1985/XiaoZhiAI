package com.xiaozhi.aiservice.data.audio

import android.util.Base64
import com.xiaozhi.aiservice.utils.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 本地唤醒词模板匹配器。
 *
 * v1.5.68+ 支持自适应阈值：根据环境 SNR 和唤醒词音节数动态调整匹配阈值。
 */
class WakeTemplateMatcher {

    companion object {
        private const val TAG = "WakeTemplateMatcher"

        /** 基础阈值：4 音节词（"悠悠悠悠"） */
        private const val BASE_THRESHOLD_4SYL = 0.75f
        /** 基础阈值：2-3 音节词（"小智小智"） */
        private const val BASE_THRESHOLD_2SYL = 0.68f
        /** 基础阈值：1 音节词（极短） */
        private const val BASE_THRESHOLD_1SYL = 0.62f

        /** 安静环境（SNR>20dB）加严 */
        private const val SNR_STRICT_ADJ = 0.03f
        /** 嘈杂环境（SNR 5-10dB）放宽 */
        private const val SNR_LOOSE_ADJ = -0.03f
        /** 极吵环境（SNR<5dB）大幅放宽 */
        private const val SNR_VERY_LOOSE_ADJ = -0.06f

        private const val N_MFCC = 40

        fun floatsToBase64(features: FloatArray): String {
            val baos = ByteArrayOutputStream(N_MFCC * 4)
            val dos = DataOutputStream(baos)
            for (f in features) dos.writeFloat(f)
            dos.close()
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        fun base64ToFloats(base64: String): FloatArray {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val features = FloatArray(bytes.size / 4)
            for (i in features.indices) features[i] = dis.readFloat()
            dis.close()
            return features
        }

        /**
         * 估算普通话/中文唤醒词音节数。
         * 中文每个汉字≈1 音节，英文按空格分词。
         */
        fun estimateSyllables(text: String): Int {
            if (text.isBlank()) return 2
            val cleaned = text.filter { it.isLetter() }
            // 全英文
            if (cleaned.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                return cleaned.split(Regex("\\s+")).size
            }
            // 中英文混合或纯中文
            var syllables = 0
            for (ch in cleaned) {
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    // 英文部分按空格算，但我们已经过滤了空格，直接累加
                    syllables++
                } else {
                    // 中文字符每字≈1 音节
                    syllables++
                }
            }
            return syllables.coerceAtLeast(1)
        }

        /**
         * 根据 SNR 和音节数计算动态阈值。
         *
         * @param syllableCount 唤醒词音节数
         * @param snrDb         环境信噪比估计（dB），默认 20（安静室内）
         * @return 计算后的阈值 [0.50, 0.90]
         */
        fun computeThreshold(syllableCount: Int, snrDb: Float = 20f): Float {
            val base = when {
                syllableCount >= 4 -> BASE_THRESHOLD_4SYL
                syllableCount >= 2 -> BASE_THRESHOLD_2SYL
                else -> BASE_THRESHOLD_1SYL
            }
            val snrAdj = when {
                snrDb > 20f -> SNR_STRICT_ADJ       // 安静 → 加严 0.03
                snrDb > 10f -> 0f                    // 正常 → 不变
                snrDb > 5f  -> SNR_LOOSE_ADJ         // 吵 → 放宽 0.03
                else -> SNR_VERY_LOOSE_ADJ            // 极吵 → 放宽 0.06
            }
            return (base + snrAdj).coerceIn(0.50f, 0.90f)
        }
    }

    private val mfccExtractor = MfccExtractor()

    data class TemplateData(
        val features: FloatArray,
        val wakeWord: String
    )

    // <contactId, TemplateData>
    private val templates = mutableMapOf<String, TemplateData>()

    fun addTemplate(contactId: String, templateBase64: String, wakeWord: String) {
        val features = base64ToFloats(templateBase64)
        if (features.size != N_MFCC) {
            Logger.w(TAG, "唤醒模板维度错误: 期望 $N_MFCC, 实际 ${features.size}")
            return
        }
        templates[contactId] = TemplateData(features, wakeWord)
        Logger.i(TAG, "唤醒模板已添加: contact=$contactId wakeWord=「$wakeWord」")
    }

    fun removeTemplate(contactId: String) { templates.remove(contactId) }
    fun clear() { templates.clear() }
    fun hasTemplates(): Boolean = templates.isNotEmpty()
    fun templateCount(): Int = templates.size

    fun extractTemplateFromPcm(pcmBytes: ByteArray): String {
        val features = mfccExtractor.extractTemplate(pcmBytes)
        return floatsToBase64(features)
    }

    /**
     * 匹配 PCM 音频与所有唤醒模板（使用自适应阈值）。
     *
     * @param pcmBytes 唤醒触发时的 PCM 音频（~2s）
     * @param snrDb    环境信噪比估计（dB），用于动态调整阈值
     * @return 匹配到的联系人ID，无匹配返回 null
     */
    fun match(pcmBytes: ByteArray, snrDb: Float = 20f): String? {
        if (templates.isEmpty()) return null
        if (pcmBytes.size < 1600) return null  // <100ms 音频太短

        val queryFeatures = mfccExtractor.extractTemplate(pcmBytes)

        var bestMatch: String? = null
        var bestScore = 0f

        for ((contactId, template) in templates) {
            val score = mfccExtractor.cosineSimilarity(queryFeatures, template.features)
            if (score > bestScore) {
                bestScore = score
                bestMatch = contactId
            }
        }

        // 自适应阈值
        val threshold = if (bestMatch != null) {
            val syllables = estimateSyllables(templates[bestMatch]?.wakeWord ?: "")
            computeThreshold(syllables, snrDb)
        } else {
            0.75f
        }

        if (bestMatch != null && bestScore >= threshold) {
            val word = templates[bestMatch]?.wakeWord ?: ""
            Logger.i(TAG, "✅ 唤醒匹配成功: contact=$bestMatch 「$word」 score=${
                "%.3f".format(bestScore)} threshold=${
                "%.3f".format(threshold)} SNR=${snrDb.toInt()}dB")
            return bestMatch
        }

        if (bestMatch != null) {
            val word = templates[bestMatch]?.wakeWord ?: ""
            Logger.d(TAG, "❌ 唤醒匹配未通过: 最佳=$bestMatch 「$word」 score=${
                "%.3f".format(bestScore)} threshold=${
                "%.3f".format(threshold)} SNR=${snrDb.toInt()}dB")
        }
        return null
    }
}
