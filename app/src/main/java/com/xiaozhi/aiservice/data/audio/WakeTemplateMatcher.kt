package com.xiaozhi.aiservice.data.audio

import android.util.Base64
import com.xiaozhi.aiservice.utils.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 本地唤醒词模板匹配器
 *
 * 将用户说"悠悠悠悠"时的 PCM 音频提取为 MFCC 特征模板，
 * 后续 FSMN VAD 触发时从环形缓冲取 PCM，算余弦相似度，
 * 匹配成功才触发对话，不匹配则静默。
 *
 * 完全本地运行，零网络请求。
 */
class WakeTemplateMatcher {

    companion object {
        private const val TAG = "WakeTemplateMatcher"
        /** 余弦相似度阈值，≥此值认为匹配成功 */
        private const val SIMILARITY_THRESHOLD = 0.75f
        private const val N_MFCC = 40

        /** 40维 float 数组 → Base64 */
        fun floatsToBase64(features: FloatArray): String {
            val baos = ByteArrayOutputStream(N_MFCC * 4)
            val dos = DataOutputStream(baos)
            for (f in features) {
                dos.writeFloat(f)
            }
            dos.close()
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        /** Base64 → 40维 float 数组 */
        fun base64ToFloats(base64: String): FloatArray {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val features = FloatArray(bytes.size / 4)
            for (i in features.indices) {
                features[i] = dis.readFloat()
            }
            dis.close()
            return features
        }
    }

    private val mfccExtractor = MfccExtractor()

    // <contactId, (Base64 模板, 唤醒词文字)>
    private val templates = mutableMapOf<String, TemplateData>()

    data class TemplateData(
        val features: FloatArray,
        val wakeWord: String
    )

    /**
     * 添加或更新唤醒模板
     */
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

    /**
     * 从 PCM 字节数据中提取 MFCC 模板
     */
    fun extractTemplateFromPcm(pcmBytes: ByteArray): String {
        val features = mfccExtractor.extractTemplate(pcmBytes)
        return floatsToBase64(features)
    }

    /**
     * 匹配 PCM 音频与所有唤醒模板
     *
     * @return 匹配到的联系人ID，无匹配返回 null
     */
    fun match(pcmBytes: ByteArray): String? {
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

        if (bestMatch != null && bestScore >= SIMILARITY_THRESHOLD) {
            val word = templates[bestMatch]?.wakeWord ?: ""
            Logger.i(TAG, "✅ 唤醒匹配成功: contact=$bestMatch wakeWord=「$word」 score=${"%.3f".format(bestScore)}")
            return bestMatch
        }

        if (bestMatch != null) {
            val word = templates[bestMatch]?.wakeWord ?: ""
            Logger.d(TAG, "❌ 唤醒匹配失败: 最佳=$bestMatch 「$word」 score=${"%.3f".format(bestScore)}")
        }
        return null
    }
}
