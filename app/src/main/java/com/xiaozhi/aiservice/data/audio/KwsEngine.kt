package com.xiaozhi.aiservice.data.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.xiaozhi.aiservice.utils.Logger
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * KWS（Keyword Spotting）唤醒词引擎。
 *
 * 使用 DSCNN embedding 模型，支持两种模式：
 *
 * 模式 A - Embedding 匹配（默认）：
 *   1. 用 Edge TTS 合成唤醒词 → 提取 128 维 DSCNN embedding
 *   2. 存储为模板（联系人 → embedding）
 *   3. 运行时提取音频 embedding，和模板做余弦相似度
 *   4. 匹配度 > 阈值 → 触发唤醒
 *
 *   优势：支持任意唤醒词，自动训练，效果好于 MFCC 余弦匹配
 *         DSCNN embedding 是神经网络学习的语音特征，比 MFCC 更有判别力
 *
 * 模式 B - 直接分类（需预训练分类模型）：
 *   ONNX 模型输出 [唤醒词置信度, 非唤醒词置信度]
 *   嵌入式 KWS 模式，无需模板匹配
 *
 * 对应 ESP32 的 WakeNet + 自定义唤醒词训练流程。
 *
 * 自动训练流程（和旧代码 TtsWakeTrainer 类似，但使用 DSCNN embedding）：
 *   Edge TTS 合成 → [MfccExtractor 提取 40×63 MFCC] → [KwsEngine 提取 128D embedding]
 *   → 多种语速变体 → 平均 → 存为模板
 */
class KwsEngine(
    /** 模板匹配阈值 [0-1]，余弦相似度 */
    private var matchThreshold: Float = 0.75f,
    /** Embedding 模型文件名（assets） */
    private val modelAssetPath: String = "kws_embedding.onnx"
) {
    companion object {
        private const val TAG = "KwsEngine"
        private const val N_MFCC = 40
        private const val CONTEXT_FRAMES = 63
        private val INPUT_SHAPE = longArrayOf(1L, 1L, N_MFCC.toLong(), CONTEXT_FRAMES.toLong())
        private const val EMBEDDING_DIM = 128
        /** 模板数量自动平均的最小样本数 */
        private const val MIN_TEMPLATE_SAMPLES = 2
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var initialized = false
        private set

    // ===== 模板存储（联系人ID → 128维 embedding） =====
    private val templates = mutableMapOf<String, TemplateData>()
    data class TemplateData(
        val embedding: FloatArray,
        val wakeWord: String
    )

    /** 最后推理的相似度 */
    @Volatile
    var lastScore = 0f
        private set

    // ===== 初始化 =====

    fun init(context: Context) {
        try {
            System.loadLibrary("onnxruntime4j_jni")
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)

            val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
            Logger.i(TAG, "Embedding 模型加载: ${modelAssetPath} (${modelBytes.size} bytes)")

            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelBytes, opts)
            initialized = true
            Logger.i(TAG, "KWS 引擎初始化完成 (threshold=$matchThreshold)")
        } catch (e: Exception) {
            Logger.e(TAG, "KWS 初始化失败: ${e.message}")
            initialized = false
        }
    }

    fun reset() {
        lastScore = 0f
    }

    fun release() {
        try {
            session?.close(); session = null
            env?.close(); env = null
            initialized = false
        } catch (_: Exception) {}
    }

    // ===== Embedding 提取（核心推理） =====

    /**
     * 从 MFCC 特征图提取 128 维 embedding 向量。
     *
     * @param mfccFeatureMap FloatArray 长度 2520（40×63），来自 MfccExtractor.getInputData()
     * @return 128 维 L2 归一化 embedding，失败时返回 null
     */
    fun extractEmbedding(mfccFeatureMap: FloatArray): FloatArray? {
        if (!initialized || session == null || env == null) return null
        if (mfccFeatureMap.size != N_MFCC * CONTEXT_FRAMES) return null

        try {
            val tensor = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(mfccFeatureMap), INPUT_SHAPE)
            val result = session!!.run(mapOf("input" to tensor))
            val outputTensor = result.get("output") as OnnxTensor
            val embedding = FloatArray(EMBEDDING_DIM)
            outputTensor.floatBuffer.get(embedding)
            tensor.close(); result.close()
            return embedding
        } catch (e: Exception) {
            Logger.w(TAG, "Embedding 提取异常: ${e.message}")
            return null
        }
    }

    // ===== 模板管理（自动训练核心） =====

    /**
     * 从 PCM 音频提取 embedding 并添加为模板。
     * 由训练流程调用（Edge TTS 合成音频 → MfccExtractor → 此方法）。
     *
     * @param contactId 联系人 ID
     * @param wakeWord 唤醒词文本
     * @param mfccFeatures 已提取的 MFCC 特征图（MfccExtractor.getInputData()）
     * @return true 如果添加成功
     */
    fun addTemplate(contactId: String, wakeWord: String, mfccFeatures: FloatArray): Boolean {
        val embedding = extractEmbedding(mfccFeatures) ?: return false
        templates[contactId] = TemplateData(embedding, wakeWord)
        Logger.i(TAG, "✅ 唤醒模板已添加: contact=$contactId 「$wakeWord」")
        return true
    }

    /**
     * 从 PCM 字节数据直接提取 embedding 并添加模板。
     * 使用内置的 MfccExtractor 完成特征提取。
     */
    fun addTemplateFromPcm(contactId: String, wakeWord: String, pcmBytes: ByteArray): Boolean {
        val extractor = MfccExtractor()
        val floats = pcmBytesToFloats(pcmBytes)
        extractor.feed(floats)
        if (!extractor.isReady()) return false
        return addTemplate(contactId, wakeWord, extractor.getInputData())
    }

    /**
     * 从多段 PCM 音频提取 embedding 并平均后添加为模板。
     * 比单段更鲁棒。
     */
    fun addTemplateFromMultiplePcm(
        contactId: String,
        wakeWord: String,
        pcmSamples: List<ByteArray>
    ): Boolean {
        if (pcmSamples.isEmpty()) return false
        var sumEmb: FloatArray? = null
        var count = 0

        for (pcm in pcmSamples) {
            val extractor = MfccExtractor()
            var pcmFloats = pcmBytesToFloats(pcm)
            // 短音频循环填充至至少 8000 samples (500ms)，确保 MFCC 帧数足够
            val minSamples = 8000
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
                val emb = extractEmbedding(extractor.getInputData())
                if (emb != null) {
                    if (sumEmb == null) sumEmb = FloatArray(EMBEDDING_DIM)
                    for (i in 0 until EMBEDDING_DIM) sumEmb!![i] += emb[i]
                    count++
                }
            }
        }

        if (sumEmb == null || count < MIN_TEMPLATE_SAMPLES) return false

        // 平均并 L2 归一化
        var norm = 0f
        for (i in 0 until EMBEDDING_DIM) {
            sumEmb[i] /= count.toFloat()
            norm += sumEmb[i] * sumEmb[i]
        }
        norm = sqrt(norm.coerceAtLeast(1e-10f))
        for (i in 0 until EMBEDDING_DIM) sumEmb[i] /= norm

        templates[contactId] = TemplateData(sumEmb!!, wakeWord)
        Logger.i(TAG, "✅ 多段平均模板已添加: contact=$contactId 「$wakeWord」 ($count samples)")
        return true
    }

    fun removeTemplate(contactId: String) { templates.remove(contactId) }
    fun clearTemplates() { templates.clear() }
    fun hasTemplates(): Boolean = templates.isNotEmpty()
    fun templateCount(): Int = templates.size

    // ===== 唤醒检测（运行时） =====

    /**
     * 对实时音频 MFCC 特征执行唤醒检测。
     * 提取 embedding 并与所有已注册模板做余弦相似度。
     *
     * @param mfccFeatureMap 40×63 MFCC 特征
     * @return 匹配到的联系人 ID，无匹配返回 null
     */
    fun match(mfccFeatureMap: FloatArray): String? {
        if (templates.isEmpty()) return null

        val queryEmb = extractEmbedding(mfccFeatureMap) ?: return null

        var bestContact: String? = null
        var bestScore = 0f

        for ((contactId, template) in templates) {
            val score = cosineSimilarity(queryEmb, template.embedding)
            if (score > bestScore) {
                bestScore = score
                bestContact = contactId
            }
        }

        lastScore = bestScore

        if (bestContact != null && bestScore >= matchThreshold) {
            val word = templates[bestContact]?.wakeWord ?: ""
            Logger.i(TAG, "🎯 唤醒匹配成功: $bestContact 「$word」 score=${"%.3f".format(bestScore)}")
            return bestContact
        }

        if (bestScore > 0.3f) {
            Logger.d(TAG, "唤醒匹配未通过: 最佳=$bestContact score=${"%.3f".format(bestScore)}")
        }
        return null
    }

    // ===== 工具 =====

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot  // 因为都是 L2 归一化的，点积 = 余弦相似度
    }

    fun setThreshold(threshold: Float) { matchThreshold = threshold }

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
