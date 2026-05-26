package com.xiaozhi.aiservice.data.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.xiaozhi.aiservice.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Arrays

/**
 * Silero VAD（语音活动检测）Android 端实现
 * 移植自 snakers4/silero-vad VADIterator
 *
 * 使用 Silero VAD v6 ONNX 模型检测语音，比 RMS 能量阈值准确得多
 * 核心逻辑：
 *   1. 接收 PCM 16bit 音频（16000Hz）
 *   2. 按 512 样本（32ms）分帧
 *   3. 每帧做 ONNX 推理 → 得到语音概率
 *   4. 双阈值判断 + temp_end 机制（官方 VADIterator 算法）
 *      - speech_prob >= 0.5 → 语音，清除 temp_end
 *      - speech_prob <= 0.35 → 非语音，记录 temp_end
 *      - 中间区间保持前一个状态（hangover）
 *   5. temp_end：语音中短暂停顿（<100ms）不切断
 *   6. triggered 状态：非语音段不做去抖动，减少噪声误报
 */
class SileroVAD(
    /** 语音概率高阈值：超过此值判定为语音 */
    private val threshold: Float = 0.5f,
    /** 语音概率低阈值：低于此值（且触发后）判定为静音 */
    private val thresholdLow: Float = 0.35f,
    /** 连续语音帧数（滑动窗口大小），用于去抖动 */
    private val frameWindowThreshold: Int = 3,
    /** 语音中容忍的静音样本数（100ms @ 16kHz = 1600 样本） */
    private val minSilenceSamples: Int = 1600
) {
    companion object {
        private const val TAG = "SileroVAD"
        private const val SAMPLE_RATE: Long = 16000
        /** 每帧样本数（32ms @ 16kHz） */
        private const val FRAME_SIZE = 512
        /** 上下文窗口大小：保持最后 64 个样本用于下一帧推理 */
        private const val CONTEXT_SIZE = 64
        /** 模型总输入大小 = context(64) + audio(512) */
        private const val INPUT_SIZE = CONTEXT_SIZE + FRAME_SIZE  // 576
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var initialized = false

    // ONNX state（每帧推理需要保持）
    private val stateH = FloatArray(2 * 128)    // shape (2, 1, 128) flattened
    private val context = FloatArray(CONTEXT_SIZE)   // 最后 128 个样本

    // VADIterator 状态
    private var triggered = false          // 当前是否在语音段中
    private var tempEnd = -1L              // 静音开始的样本位置（-1 = 不在静音中）
    private var currentSample = 0L         // 当前处理的样本总数
    private var lastFrameIsVoice = false   // 上一帧的语音状态（hangover 用）

    // 滑动窗口去抖动（仅 triggered 时有效）
    private val voiceWindow = java.util.ArrayDeque<Boolean>()

    fun isInitialized(): Boolean = initialized

    /**
     * 初始化 ONNX 会话
     * @param context Android Context（用于读取 assets 中的模型文件）
     */
    fun init(context: Context) {
        try {
            // 显式加载 ONNX Runtime JNI 库（部分设备 auto-load 不生效）
            System.loadLibrary("onnxruntime4j_jni")
            Logger.i(TAG, "onnxruntime4j_jni loaded")

            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)

            val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
            Logger.i(TAG, "模型加载: ${modelBytes.size} bytes")

            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelBytes, opts)
            initialized = true
            Logger.i(TAG, "SileroVAD 初始化完成")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "SileroVAD JNI 库加载失败: ${e.message}")
            initialized = false
        } catch (e: Exception) {
            Logger.e(TAG, "SileroVAD 初始化失败: ${e.message}")
            initialized = false
        }
    }

    /**
     * 重置 VAD 内部状态
     * 在每次开始新录音前调用
     */
    fun reset() {
        Arrays.fill(stateH, 0f)
        Arrays.fill(context, 0f)
        triggered = false
        tempEnd = -1L
        currentSample = 0L
        lastFrameIsVoice = false
        voiceWindow.clear()
    }

    /**
     * 对 PCM 数据执行 VAD 检测
     * @param pcmData PCM 16bit 音频（小端序，16000Hz）
     * @return true = 检测到语音，false = 静音
     *
     * 算法（移植自官方 VADIterator）：
     * 1. 按 512 样本分帧推理
     * 2. 双阈值带 hangover 判断
     * 3. temp_end：语音中短暂停顿不切断
     * 4. triggered 状态：仅语音段填入滑动窗口
     *
     * 线程安全：@Synchronized 防止多线程并发 ONNX 推理（OrtSession 非线程安全）
     */
    @Synchronized
    fun isVoice(pcmData: ByteArray): Boolean {
        if (!initialized || session == null || env == null) {
            return false
        }

        try {
            val shortCount = pcmData.size / 2
            val audioFloat = FloatArray(shortCount)
            val byteBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortCount) {
                // int16 → float32 / 32768.0 归一化
                audioFloat[i] = byteBuf.getShort(i * 2).toFloat() / 32768.0f
            }

            // 按 512 样本分帧处理
            var offset = 0
            while (offset + FRAME_SIZE <= shortCount) {
                // 拼接 [context(64), audio(512)] → input(576)
                val inputData = FloatArray(INPUT_SIZE)
                System.arraycopy(context, 0, inputData, 0, CONTEXT_SIZE)
                System.arraycopy(audioFloat, offset, inputData, CONTEXT_SIZE, FRAME_SIZE)

                // 创建 ONNX 输入张量
                val inputTensor = OnnxTensor.createTensor(
                    env!!, FloatBuffer.wrap(inputData), longArrayOf(1L, INPUT_SIZE.toLong())
                )
                val stateTensor = OnnxTensor.createTensor(
                    env!!, FloatBuffer.wrap(stateH), longArrayOf(2L, 1L, 128L)
                )
                val srTensor = OnnxTensor.createTensor(
                    env!!, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1L)
                )

                // 推理
                val inputs = mapOf(
                    "input" to inputTensor,
                    "state" to stateTensor,
                    "sr" to srTensor
                )
                val result = session!!.run(inputs)

                // 解析输出：output[0] = 概率，output[1] = 新 state
                val outputIter = result.iterator()
                val probTensor = outputIter.next().value as OnnxTensor
                val newStateTensor = outputIter.next().value as OnnxTensor

                val speechProb = probTensor.floatBuffer.get(0)
                val newStateBuf = newStateTensor.floatBuffer
                newStateBuf.get(stateH)

                // 更新上下文：取本次输入的最后 CONTEXT_SIZE 个样本
                System.arraycopy(inputData, INPUT_SIZE - CONTEXT_SIZE, context, 0, CONTEXT_SIZE)

                // 关闭释放
                inputTensor.close()
                stateTensor.close()
                srTensor.close()
                result.close()

                // === VADIterator 核心算法 ===

                currentSample += FRAME_SIZE

                // 1. 双阈值 hangover：中间区间保持前一帧状态
                val frameIsVoice = when {
                    speechProb >= threshold -> true
                    speechProb <= thresholdLow -> false
                    else -> lastFrameIsVoice
                }
                lastFrameIsVoice = frameIsVoice

                // 2. 语音帧 → 清除 temp_end（结束静音计时）
                if (frameIsVoice && tempEnd >= 0) {
                    tempEnd = -1
                }

                // 3. 开始语音
                if (frameIsVoice && !triggered) {
                    triggered = true
                    voiceWindow.clear()
                }

                // 4. 非语音且处于语音中 → 记录或检查 temp_end
                if (!frameIsVoice && triggered) {
                    if (tempEnd < 0) {
                        tempEnd = currentSample  // 开始静音计时
                    }

                    val silenceDuration = currentSample - tempEnd
                    if (silenceDuration >= minSilenceSamples) {
                        // 静音太长了，结束语音段
                        triggered = false
                        tempEnd = -1
                        voiceWindow.clear()
                    }
                }

                // 5. 仅 triggered 时填入滑动窗口（非语音段不污染窗口）
                if (triggered) {
                    voiceWindow.addLast(true)
                    if (voiceWindow.size > frameWindowThreshold) {
                        voiceWindow.removeFirst()
                    }
                } else if (frameIsVoice) {
                    // 边缘情况：未 triggered 但有语音（第一帧）
                    voiceWindow.addLast(true)
                    if (voiceWindow.size > frameWindowThreshold) {
                        voiceWindow.removeFirst()
                    }
                } else {
                    // 非 triggered 且非语音 → 不填窗口
                    // 让窗口保持之前的决策或变空
                    if (voiceWindow.isNotEmpty()) {
                        voiceWindow.removeFirst()
                    }
                }

                offset += FRAME_SIZE
            }

            // 窗口非空且足够多 true → 语音
            return voiceWindow.size >= frameWindowThreshold &&
                   voiceWindow.count { it } >= frameWindowThreshold

        } catch (e: Exception) {
            Logger.w(TAG, "VAD 推理异常: ${e.message}")
            return false
        }
    }
}
