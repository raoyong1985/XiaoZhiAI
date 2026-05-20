package com.xiaozhi.aiservice.data.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import com.xiaozhi.aiservice.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Porcupine 唤醒词检测器
 *
 * 参考 ESP32 esp_wake_word.cc / afe_wake_word.cc 唤醒词引擎实现
 * 使用 Picovoice Porcupine 引擎，纯本地离线运行，低延迟
 *
 * 架构对比：
 * ESP32: AudioCodec(MIC) → wake_word_->Feed(data) → esp_wn_iface->detect()
 * Android: AudioRecord → WakeWordDetector.feed() → Porcupine.process()
 */
class WakeWordDetector {

    companion object {
        private const val TAG = "WakeWordDetector"

        /** 默认唤醒词（内置关键词 "jarvis"） */
        const val DEFAULT_KEYWORD = "jarvis"

        /**
         * Picovoice AccessKey - 免费获取: https://console.picovoice.ai/
         * Free tier 支持 2 个自定义唤醒词 + 内置关键词无限制
         * 替换成你自己的 AccessKey 后生效
         */
        const val ACCESS_KEY = "YOUR_PICOVOICE_ACCESS_KEY_HERE"

        /**
         * 预分配最大样本数（2048 = 4096 字节 PCM）
         * WAKE_WORD_BUFFER_MS=100ms, 16kHz → 1600 样本，2048 充裕覆盖
         */
        private const val MAX_SAMPLES = 2048
    }

    private var porcupine: Porcupine? = null
    private var isInitialized = false
    private var detectionCallback: ((String) -> Unit)? = null
    private var androidContext: Context? = null

    /** 预分配 ShortArray，避免每 50ms 创建新数组的 GC 风暴 */
    private val samples = ShortArray(MAX_SAMPLES)

    /** 预分配帧缓冲，避免循环内每帧 copyOfRange 分配 */
    private var frameBuffer: ShortArray? = null

    /**
     * 当前使用的唤醒词名称
     */
    var currentKeyword: String = DEFAULT_KEYWORD
        private set

    /**
     * Porcupine 所需帧长度（每帧样本数）
     * 默认 512 (16kHz 下约 32ms)
     */
    val frameLength: Int
        get() = porcupine?.frameLength ?: 512

    /**
     * 将关键词字符串转为 BuiltInKeyword 枚举
     */
    private fun parseBuiltInKeyword(keyword: String): Porcupine.BuiltInKeyword? {
        // 将 "jarvis" → "JARVIS", "hey google" → "HEY_GOOGLE", "ok google" → "OK_GOOGLE"
        val enumName = keyword.uppercase().replace(' ', '_')
        return try {
            Porcupine.BuiltInKeyword.valueOf(enumName)
        } catch (_: IllegalArgumentException) {
            Logger.w(TAG, "'$keyword' 不是内置关键词")
            null
        }
    }

    /**
     * 初始化 Porcupine 引擎
     * @param accessKey Picovoice AccessKey
     * @param keyword 内置关键词名称（如 "jarvis"）或 .ppn 文件路径
     * @return true 初始化成功
     */
    fun initialize(
        accessKey: String = ACCESS_KEY,
        keyword: String = DEFAULT_KEYWORD,
        context: Context? = null
    ): Boolean {
        if (isInitialized) {
            Logger.w(TAG, "唤醒词引擎已初始化，跳过")
            return true
        }

        if (accessKey == "YOUR_PICOVOICE_ACCESS_KEY_HERE") {
            Logger.w(TAG, "未配置 Picovoice AccessKey，唤醒词不生效。请到 https://console.picovoice.ai/ 获取免费 Key")
            return false
        }

        if (context == null) {
            Logger.w(TAG, "需要 Android Context 才能初始化 Porcupine")
            return false
        }
        androidContext = context

        return try {
            val kw = parseBuiltInKeyword(keyword)
            if (kw == null) {
                Logger.e(TAG, "不支持的关键词: $keyword（仅支持内置关键词）")
                return false
            }

            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(kw)
                .build(androidContext!!)

            currentKeyword = keyword
            isInitialized = true
            Logger.i(TAG, "Porcupine 唤醒词引擎初始化成功（关键词: $keyword, 帧长: ${porcupine!!.frameLength}）")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "唤醒词引擎初始化失败: ${e.message}")
            isInitialized = false
            false
        }
    }

    /**
     * 设置唤醒词检测回调
     * 对应 ESP32 WakeWord::OnWakeWordDetected()
     */
    fun setOnWakeWordDetected(callback: (String) -> Unit) {
        detectionCallback = callback
    }

    /**
     * 喂 PCM 数据进行唤醒词检测
     * 对应 ESP32 EspWakeWord::Feed()
     *
     * @param pcmData PCM16 字节数组 (little-endian, 16kHz, mono)
     * @return 检测到的唤醒词名称，无检测则返回 null
     */
    fun feed(pcmData: ByteArray): String? {
        val pp = porcupine ?: return null
        if (!isInitialized) return null

        try {
            val sampleCount = pcmData.size / 2
            if (sampleCount > MAX_SAMPLES) {
                Logger.w(TAG, "PCM 样本数 ($sampleCount) 超出预分配缓冲 ($MAX_SAMPLES)，跳过检测")
                return null
            }

            // 复用预分配 samples 数组，将 PCM 字节直接解码为 short
            ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples, 0, sampleCount)

            val frameLen = pp.frameLength
            // 惰性初始化帧缓冲（Porcupine 初始化后才知道 frameLength）
            var fb = frameBuffer
            if (fb == null || fb.size != frameLen) {
                fb = ShortArray(frameLen)
                frameBuffer = fb
            }

            var offset = 0
            while (offset + frameLen <= sampleCount) {
                // System.arraycopy → 零分配，替代 copyOfRange 造成的 GC 压力
                System.arraycopy(samples, offset, fb, 0, frameLen)
                val keywordIndex = pp.process(fb)
                if (keywordIndex >= 0) {
                    Logger.i(TAG, "检测到唤醒词: $currentKeyword (index=$keywordIndex)")
                    detectionCallback?.invoke(currentKeyword)
                    return currentKeyword
                }
                offset += frameLen
            }
        } catch (e: Exception) {
            Logger.e(TAG, "唤醒词检测异常: ${e.message}")
        }
        return null
    }

    /**
     * 释放 Porcupine 引擎资源
     * 对应 ESP32 EspWakeWord::~EspWakeWord()
     */
    fun release() {
        Logger.i(TAG, "释放唤醒词引擎")
        porcupine?.delete()
        porcupine = null
        isInitialized = false
        detectionCallback = null
        frameBuffer = null
    }

    fun isReady(): Boolean = isInitialized && porcupine != null
}
