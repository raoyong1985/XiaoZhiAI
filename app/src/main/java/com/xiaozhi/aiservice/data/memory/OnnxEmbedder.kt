package com.xiaozhi.aiservice.data.memory

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.xiaozhi.aiservice.utils.Logger
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * ONNX 嵌入模型包装器
 *
 * 加载 assets 中的 ONNX 模型，将文本转为向量。
 * 模型文件（如 gte-small.onnx）需放在 app/src/main/assets/ 下。
 *
 * 支持的模型格式：
 * - SentenceTransformers 导出的 ONNX（input_ids + attention_mask → last_hidden_state）
 * - 任何输入为 token_ids(int32)，输出为 float32 的嵌入模型
 *
 * 如果模型文件不存在或加载失败，isAvailable() 返回 false，
 * RAGMemoryImpl 会自动降级为关键词搜索。
 */
class OnnxEmbedder(private val context: Context) {

    companion object {
        private const val TAG = "OnnxEmbedder"
        private const val MODEL_FILE = "embedding_model.onnx"
        private const val MAX_SEQ_LEN = 128  // 最大 token 数
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    @Volatile
    private var available = false

    fun isAvailable(): Boolean = available

    /**
     * 尝试加载 ONNX 模型 + WordPiece tokenizer
     * 静默失败——模型文件不存在就返回 false
     */
    fun init(): Boolean {
        try {
            val modelBytes: ByteArray
            try {
                modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
                Logger.i(TAG, "嵌入模型加载: ${modelBytes.size} bytes")
            } catch (e: Exception) {
                Logger.w(TAG, "嵌入模型文件不存在 (${MODEL_FILE})，使用关键词搜索降级")
                return false
            }

            // 加载 WordPiece tokenizer（先加载 vocab，不影响模型初始化）
            tokenizer = WordPieceTokenizer(context).apply { load() }

            System.loadLibrary("onnxruntime4j_jni")
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)

            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelBytes, opts)

            available = true
            Logger.i(TAG, "ONNX 嵌入模型初始化成功")
            return true
        } catch (e: Exception) {
            Logger.w(TAG, "ONNX 嵌入模型加载失败: ${e.message}，使用关键词搜索降级")
            available = false
            return false
        }
    }

    /**
     * 将文本转为嵌入向量
     * @param text 输入文本
     * @return 向量 (FloatArray)，失败返回 null
     */
    fun embed(text: String): FloatArray? {
        if (!available || session == null || env == null) return null

        try {
            // 1. WordPiece tokenizer
            val tok = tokenizer ?: return null
            val (inputIdsArr, attentionMaskArr) = tok.encode(text)

            // 2. 创建 ONNX 张量
            val ortEnv = env!!
            val ortSession = session!!

            val inputIds = IntArray(MAX_SEQ_LEN) { inputIdsArr[it] }
            val attentionMask = IntArray(MAX_SEQ_LEN) { attentionMaskArr[it] }

            val inputTensor = OnnxTensor.createTensor(
                ortEnv, IntBuffer.wrap(inputIds),
                longArrayOf(1L, MAX_SEQ_LEN.toLong())
            )
            val maskTensor = OnnxTensor.createTensor(
                ortEnv, IntBuffer.wrap(attentionMask),
                longArrayOf(1L, MAX_SEQ_LEN.toLong())
            )

            // 3. 推理
            val inputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor
            )
            val result = ortSession.run(inputs)

            // 4. 取最后一个输出（通常是 pooled/embedding 输出）
            val outputIter = result.iterator()
            // 尝试取命名输出，否则取第一个
            var embedding: FloatArray? = null
            while (outputIter.hasNext()) {
                val entry = outputIter.next()
                val tensor = entry.value as? OnnxTensor ?: continue
                val shape = tensor.info.shape
                // 找 1D 或 2D 输出（[1, dim] 或 [dim]）
                if (shape != null && shape.size >= 2 && shape.last() != null) {
                    val dim = shape.last().toInt()
                    val arr = FloatArray(dim.coerceAtMost(1024))
                    tensor.floatBuffer.get(arr)
                    embedding = arr
                    break
                }
            }

            // 5. 释放资源
            inputTensor.close()
            maskTensor.close()
            result.close()

            if (embedding == null) {
                Logger.w(TAG, "嵌入推理成功但无法找到合适的输出张量")
            }
            return embedding
        } catch (e: Exception) {
            Logger.w(TAG, "嵌入推理失败: ${e.message}")
            return null
        }
    }

    fun close() {
        try {
            session?.close()
            // OrtEnvironment 是全局单例，不关闭
        } catch (_: Exception) {}
        available = false
    }
}
