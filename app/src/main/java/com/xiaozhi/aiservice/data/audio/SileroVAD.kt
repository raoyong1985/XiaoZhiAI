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
import java.util.ArrayDeque
import java.util.Arrays

/**
 * Silero VAD（语音活动检测）Android 端实现
 * 移植自 xiaozhi-esp32-server 的 core/providers/vad/silero.py
 *
 * 使用 Silero VAD ONNX 模型检测语音，比 RMS 能量阈值准确得多
 * 核心逻辑：
 *   1. 接收 PCM 16bit 音频（16000Hz）
 *   2. 按 512 样本（32ms）分帧
 *   3. 每帧做 ONNX 推理 → 得到语音概率
 *   4. 双阈值判断（speech_prob >= 0.5 = 有声音，<= 0.2 = 无声音，之间保持之前状态）
 *   5. 3 帧滑动窗口去抖动
 */
class SileroVAD(
    /** 语音概率高阈值：超过此值判定为语音，值越高越不灵敏 */
    private val threshold: Float = 0.7f,
    /** 语音概率低阈值：低于此值判定为静音，值越高越不易保持语音 */
    private val thresholdLow: Float = 0.4f,
    /** 连续语音帧数（滑动窗口大小）用于去抖动，值越大越稳定但响应越慢 */
    private val frameWindowThreshold: Int = 4
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

    // 每连接独立状态（服务器用 conn._vad_state，这里用成员变量）
    private val stateH = FloatArray(2 * 128)    // shape (2, 1, 128) flattened
    private val context = FloatArray(CONTEXT_SIZE)   // 最后 64 个样本
    private var lastIsVoice = false
    private val voiceWindow = ArrayDeque<Boolean>()

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
        lastIsVoice = false
        voiceWindow.clear()
    }

    /**
     * 对 PCM 数据执行 VAD 检测
     * @param pcmData PCM 16bit 音频（小端序，16000Hz）
     * @return true = 检测到语音，false = 静音
     */
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

                // 双阈值判断（参考 silero.py 双阈值逻辑）
                val isVoice = when {
                    speechProb >= threshold -> true
                    speechProb <= thresholdLow -> false
                    else -> lastIsVoice  // 中间区间保持前一个状态
                }
                lastIsVoice = isVoice

                // 滑动窗口去抖动（3帧中有 frameWindowThreshold 帧为声音才判定为声音）
                voiceWindow.addLast(isVoice)
                if (voiceWindow.size > frameWindowThreshold) {
                    voiceWindow.removeFirst()
                }

                offset += FRAME_SIZE
            }

            return voiceWindow.count { it } >= frameWindowThreshold

        } catch (e: Exception) {
            Logger.w(TAG, "VAD 推理异常: ${e.message}")
            return false
        }
    }
}
