package com.xiaozhi.aiservice.data.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.xiaozhi.aiservice.utils.Logger
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * FSMN VAD（阿里达摩院 speech_fsmn_vad_zh-cn-16k-common-onnx）
 *
 * 比 SileroVAD 的优势：
 * - 专为中文语音优化，静音边界更准
 * - 模型更小（~500KB vs 1.3MB）
 * - FSMN 结构延迟更低（10ms帧移）
 *
 * 接口与 SileroVAD 兼容：isVoice(pcmData) → Boolean
 *
 * 内部管线：
 *   PCM(16kHz) → FsmnFbank → ONNX推理(4个cache状态) → Softmax → threshold判决
 */
class FsmnVAD(
    /**
     * 语音/静音判决阈值（logits softmax后，1-静音概率 > 此值判定为语音）。
     *
     * 调优说明：
     * - 原始模型 speech_noise_thres=0.6（后端离线VAD用）
     * - 唤醒场景降低到 0.5，对轻声/远场说话更敏感
     * - FSMN 模型中文精度高，0.5 不会增加噪声误报
     */
    private val speechThreshold: Float = 0.5f,
    /**
     * 连续语音帧数判决窗口（10ms/帧）。
     *
     * 调优说明：
     * - 5帧=50ms：需要连续5帧判定为语音才输出 true
     * - 比SileroVAD的3帧(96ms)更短，但FSMN的10ms帧移更细，
     *   5帧已足够去除噪声毛刺
     * - 模型自身有去抖动（cache状态），窗口小些不影响稳定性
     */
    private val frameThreshold: Int = 5
) {
    companion object {
        private const val TAG = "FsmnVAD"
        /** 模型输入维度和格式常量 */
        private const val FEAT_DIM = 400
        private const val CACHE_DIM1 = 128
        private const val CACHE_DIM2 = 19
        private const val CACHE_DIM3 = 1
        /** 帧移 10ms 对应的检测帧率 */
        private val FRAME_MS = 10
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    @Volatile
    var initialized = false
        private set

    // FBank 特征提取器
    private val fbank = FsmnFbank()

    // 4 个 FSMN cache 状态 [1, 128, 19, 1]
    private val cacheStates = Array(4) { FloatArray(CACHE_DIM1 * CACHE_DIM2 * CACHE_DIM3) }

    // 语音判决滑动窗口（去抖动）
    private val voiceWindow = ArrayDeque<Boolean>()
    private var triggered = false
    private var tempSilenceFrames = 0
    /** 静音容忍帧数（20帧 = 200ms），语音中短暂停顿不切断 */
    private val maxSilenceFrames = 20

    fun isInitialized(): Boolean = initialized

    /**
     * 初始化 ONNX 会话并加载 FSMN VAD 模型
     */
    fun init(context: Context) {
        try {
            System.loadLibrary("onnxruntime4j_jni")
            Logger.i(TAG, "onnxruntime4j_jni loaded")

            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)

            val modelBytes = context.assets.open("fsmn_vad.onnx").use { it.readBytes() }
            Logger.i(TAG, "FSMN VAD 模型加载: ${modelBytes.size} bytes")

            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelBytes, opts)

            // 验证初始化成功
            initialized = true
            Logger.i(TAG, "FSMN VAD 初始化完成")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "FSMN VAD JNI 库加载失败: ${e.message}")
            initialized = false
        } catch (e: Exception) {
            Logger.e(TAG, "FSMN VAD 初始化失败: ${e.message}")
            initialized = false
        }
    }

    /**
     * 重置 VAD 内部状态（新语音段开始时调用）
     */
    fun reset() {
        fbank.reset()
        for (i in 0 until 4) cacheStates[i].fill(0f)
        voiceWindow.clear()
        triggered = false
        tempSilenceFrames = 0
    }

    /**
     * 对 PCM 数据执行 VAD 检测
     * @param pcmData PCM 16bit 小端序音频（16000Hz）
     * @return true = 检测到语音，false = 静音
     */
    @Synchronized
    fun isVoice(pcmData: ByteArray): Boolean {
        if (!initialized || session == null || env == null) return false

        try {
            // 1. 提取 FBank 特征
            val features = fbank.process(pcmData)
            if (features.isEmpty()) return false

            // 2. 逐帧 ONNX 推理
            for (feat in features) {
                val speechProb = inferFrame(feat)
                updateState(speechProb)
            }

            // 3. 滑动窗口判决
            val result = voiceWindow.size >= frameThreshold &&
                   voiceWindow.count { it } >= frameThreshold
            return result
        } catch (e: Exception) {
            Logger.w(TAG, "FSMN VAD 推理异常: ${e.message}")
            return false
        }
    }

    /**
     * 单帧 ONNX 推理
     * @param feat [FEAT_DIM] float 特征
     * @return speech probability [0, 1]
     */
    private fun inferFrame(feat: FloatArray): Float {
        val ortEnv = env!!
        val ortSession = session!!

        // 创建输入张量
        val speechTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(feat), longArrayOf(1L, 1L, FEAT_DIM.toLong())
        )

        val cacheTensors = Array(4) { i ->
            OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(cacheStates[i]),
                longArrayOf(1L, CACHE_DIM1.toLong(), CACHE_DIM2.toLong(), CACHE_DIM3.toLong())
            )
        }

        // 推理
        val inputs = mapOf(
            "speech" to speechTensor,
            "in_cache0" to cacheTensors[0],
            "in_cache1" to cacheTensors[1],
            "in_cache2" to cacheTensors[2],
            "in_cache3" to cacheTensors[3]
        )
        val result = ortSession.run(inputs)

        // 解析输出
        val outputIter = result.iterator()
        val logitsTensor = outputIter.next().value as OnnxTensor  // [1, 1, 248]
        val logits = FloatArray(248)
        logitsTensor.floatBuffer.get(logits)

        // 更新 cache 状态
        for (i in 0 until 4) {
            val cacheTensor = outputIter.next().value as OnnxTensor
            cacheTensor.floatBuffer.get(cacheStates[i])
        }

        // 释放资源
        speechTensor.close()
        cacheTensors.forEach { it.close() }
        result.close()

        // Softmax: speech_prob = 1 - softmax(class=0)
        // class 0 = 静音
        return softmaxSpeechProb(logits)
    }

    /**
     * 从 logits 计算语音概率
     * logits[0] 是静音类的 logit
     * speech_prob = 1 - exp(logits[0]) / sum(exp(logits))
     */
    private fun softmaxSpeechProb(logits: FloatArray): Float {
        // 数值稳定 softmax
        val maxLogit = logits.max()
        var sumExp = 0f
        for (i in logits.indices) {
            sumExp += exp((logits[i] - maxLogit).toDouble()).toFloat()
        }
        val silenceProb = exp((logits[0] - maxLogit).toDouble()).toFloat() / sumExp
        return 1.0f - silenceProb
    }

    /**
     * 更新 VAD 状态机（含去抖动和短时静音容错）
     */
    private fun updateState(speechProb: Float) {
        val isSpeech = speechProb >= speechThreshold

        if (isSpeech) {
            tempSilenceFrames = 0
            if (!triggered) {
                triggered = true
                voiceWindow.clear()
            }
            voiceWindow.addLast(true)
        } else if (triggered) {
            // 语音中短暂静音
            tempSilenceFrames++
            if (tempSilenceFrames > maxSilenceFrames) {
                // 静音太长，退出语音段
                triggered = false
                tempSilenceFrames = 0
                voiceWindow.clear()
                return
            }
            // 短暂静音：仍然填入 false 让滑动窗口衰减
            voiceWindow.addLast(false)
        }

        // 限制窗口大小
        while (voiceWindow.size > frameThreshold + 2) {
            voiceWindow.removeFirst()
        }
    }
}
