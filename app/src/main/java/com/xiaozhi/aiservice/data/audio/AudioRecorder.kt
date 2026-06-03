package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

private const val TAG = "AudioRecorder"

/**
 * 统一录音管理器：单 AudioRecord 管线。
 *
 * 职责：
 * - 唤醒检测（FSMN VAD + 环形缓冲 + 无缝过渡到编码）
 * - 创建/销毁 OpusEncoder
 * - 启动/停止 AudioService 录音（带 VAD）
 * - 8 秒录音超时（群聊/单聊一致）
 * - 回调隔离（generation 机制防陈旧回调）
 *
 * 核心设计（模仿 ESP32 AFE 单管线）：
 *   单 AudioRecord → PCM → [唤醒模式: FSMN VAD + 环形缓冲] → [触发后: Opus编码 → 发送]
 *                          → [实时模式: Opus编码 → 发送]
 */
class AudioRecorder(
    private val audioService: AudioService
) {
    private var opusEncoder: OpusEncoder? = null
    private var nativeOpusEncoder: NativeOpusEncoder? = null
    private var timeoutJob: Job? = null
    private var scope: CoroutineScope? = null

    /** 单调递增 generation，每次 start 自增，stop 回调时比对防旧回调 */
    @Volatile private var generation = 0

    /** 是否正在录音 */
    @Volatile var isRecording: Boolean = false
        private set

    // ===== 唤醒检测相关 =====
    private var wakeFsmnVad: FsmnVAD? = null
    private var wakeUseFsmn = false
    private var wakeVoiceStartTime = 0L
    private var wakeTriggered = false
    /** 唤醒模式环形缓冲（最近 2 秒 PCM） */
    private val wakeBuffer = ByteArrayOutputStream()
    private val wakeBufferMaxBytes = 64000  // 2秒 @ 16kHz 16bit
    private var onWakeDetected: ((String) -> Unit)? = null
    private var wakeModeActive = false
    private var lastVoiceLogMs = 0L
    /** 连续静音时长（词语间隙容错） */
    private var silenceStartTime = 0L
    private val SILENCE_GRACE_MS = 300L  // 词语间停顿不超过300ms不重置timer

    companion object {
        // "悠悠你好"约0.8-1.2秒，600ms可让用户说到"悠悠你"时就触发，缩短响应延迟
        private const val WAKE_HOLD_MS = 600L
        // RMS 阈值（0-32767），越低越灵敏。350≈中等环境噪声下的轻声说话
        private const val WAKE_RMS_THRESHOLD = 350
        // 唤醒模式下 PCM 前置增益（×2.0），让 ASR 收到更清晰的音频
        private const val WAKE_GAIN = 2.0f
    }

    /**
     * 启动唤醒模式：FSMN VAD + 环形缓冲，检测到连续语音后自动过渡到编码发送。
     *
     * @param owner        调用方标识
     * @param scope        协程作用域
     * @param onWakeDetected 唤醒触发回调（参数=唤醒文本）
     * @param onOpusPacket Opus 编码后的音频包回调
     * @param onSilenceDetected 静音检测回调（VAD 自然结束）
     */
    fun startWake(
        owner: String,
        scope: CoroutineScope,
        onWakeDetected: (String) -> Unit,
        onOpusPacket: (ByteArray) -> Unit,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        if (isRecording) return false
        this.scope = scope
        generation++
        val currentGen = generation

        // 停止残留
        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording(owner)

        // 初始化 FSMN VAD（唤醒用）
        initWakeVad()

        isRecording = true
        wakeModeActive = true
        wakeTriggered = false
        wakeVoiceStartTime = 0L
        silenceStartTime = 0L
        wakeBuffer.reset()

        // 唤醒模式不绑定 VAD 静音停止 —— 唤醒 AudioRecord 常驻，直到被对话接管
        // 不传 onSilenceDetected，AudioService 的 enableVAD=false 确保不会 auto-stop

        // 启动单 AudioRecord，PCM 回调由我们处理
        if (!audioService.startRecording(owner, { pcmData ->
            handleWakePcm(pcmData, onOpusPacket)
        }, false)) {  // enableVAD=false：唤醒模式常驻，不 auto-stop
            Logger.e(TAG, "唤醒模式：AudioService 录音启动失败")
            isRecording = false
            wakeModeActive = false
            return false
        }

        this.onWakeDetected = { text ->
            if (generation == currentGen) {
                onWakeDetected(text)
            }
        }

        Logger.i(TAG, "唤醒模式已启动（等待 ${WAKE_HOLD_MS}ms 连续语音）")
        return true
    }

    /**
     * 启动实时录音（编码+发送，无唤醒检测）
     */
    fun startLive(
        owner: String,
        scope: CoroutineScope,
        enableVAD: Boolean = true,
        maxRecordMs: Long = 8_000L,
        onOpusPacket: (ByteArray) -> Unit,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        if (isRecording) return false
        this.scope = scope
        generation++
        val currentGen = generation

        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording(owner)
        opusEncoder?.stop()
        opusEncoder = null
        nativeOpusEncoder?.stop()
        nativeOpusEncoder = null

        wakeModeActive = false  // 确保唤醒模式关闭

        if (!initEncoder(onOpusPacket)) return false

        if (onSilenceDetected != null) {
            audioService.setOnSilenceDetected(owner) {
                if (generation == currentGen) {
                    stop(owner)
                    onSilenceDetected()
                }
            }
        }

        isRecording = true
        // 录音启动后的静默期：丢弃前 800ms 的 PCM（扬声器回声还未完全消散）
        val recordingStartTime = System.currentTimeMillis()
        val muteUntilMs = 800L

        if (!audioService.startRecording(owner, { pcmData ->
            // 静默期内丢弃音频，不编码不发送
            if (System.currentTimeMillis() - recordingStartTime < muteUntilMs) return@startRecording
            if (nativeOpusEncoder != null) nativeOpusEncoder?.encode(pcmData)
            else opusEncoder?.encode(pcmData)
        }, enableVAD)) {
            Logger.e(TAG, "实时录音启动失败")
            isRecording = false
            nativeOpusEncoder?.stop()
            nativeOpusEncoder = null
            opusEncoder?.stop()
            opusEncoder = null
            return false
        }

        timeoutJob = scope.launch {
            delay(maxRecordMs)
            if (isRecording && generation == currentGen) {
                Logger.w(TAG, "录音超时 ${maxRecordMs}ms，自动停止")
                stop(owner)
                onSilenceDetected?.invoke()
            }
        }

        Logger.i(TAG, "实时录音启动成功 (VAD=$enableVAD, 超时=${maxRecordMs}ms)")
        return true
    }

    /**
     * 唤醒模式 PCM 处理：FSMN VAD + 环形缓冲 + 触发后过渡到编码
     */
    private fun handleWakePcm(pcmData: ByteArray, onOpusPacket: (ByteArray) -> Unit) {
        if (!wakeModeActive) {
            // 已过渡到实时模式，直接编码（唤醒模式增益已由缓冲端完成，实时模式不加增益避免失真）
            if (nativeOpusEncoder != null) nativeOpusEncoder!!.encode(pcmData)
            else opusEncoder?.encode(pcmData)
            return
        }

        // ——— 前置增益 2x：让 ASR 收到更清晰的音频，提高唤醒词识别率 ———
        val amplified = if (WAKE_GAIN != 1.0f) applyGain(pcmData, WAKE_GAIN) else pcmData

        val now = System.currentTimeMillis()

        // 写入环形缓冲（已增益）
        synchronized(wakeBuffer) {
            wakeBuffer.write(amplified)
            if (wakeBuffer.size() > wakeBufferMaxBytes) {
                val excess = wakeBuffer.size() - wakeBufferMaxBytes
                val current = wakeBuffer.toByteArray()
                wakeBuffer.reset()
                wakeBuffer.write(current, excess, current.size - excess)
            }
        }

        // 语音检测（用已增益数据，优先 FSMN VAD）
        val hasVoice = if (wakeUseFsmn && wakeFsmnVad != null) {
            try { wakeFsmnVad!!.isVoice(amplified) }
            catch (_: Exception) { calcRms(amplified) > WAKE_RMS_THRESHOLD }
        } else {
            calcRms(amplified) > WAKE_RMS_THRESHOLD
        }

        // 每秒打印一次语音状态 debug
        if (now - lastVoiceLogMs > 1000) {
            val rms = calcRms(amplified)
            Logger.i(TAG, "唤醒VAD: voice=$hasVoice rms=${"%.0f".format(rms)} fsmn=$wakeUseFsmn hold=${now - wakeVoiceStartTime}ms")
            lastVoiceLogMs = now
        }

        if (hasVoice) {
            silenceStartTime = 0L  // 有人声，清除静音计时
            if (wakeVoiceStartTime == 0L) wakeVoiceStartTime = now
            val duration = now - wakeVoiceStartTime

            if (!wakeTriggered && duration >= WAKE_HOLD_MS) {
                wakeTriggered = true
                Logger.i(TAG, "🎤 检测到连续语音 ${duration}ms，过渡到实时编码")

                // 1. 创建编码器
                val sampleRate = audioService.getSampleRate()
                val nativeEncoder = NativeOpusEncoder(sampleRate = sampleRate)
                if (nativeEncoder.init { packet -> onOpusPacket(packet) }) {
                    nativeOpusEncoder = nativeEncoder
                } else {
                    val encoder = OpusEncoder(sampleRate = sampleRate)
                    if (!encoder.init { packet -> onOpusPacket(packet) }) {
                        Logger.e(TAG, "唤醒后编码器初始化失败")
                        return
                    }
                    opusEncoder = encoder
                }

                // 2. 发送缓冲 PCM（先编码再发送，填补唤醒检测期的音频缺口）
                val buffered = wakeBuffer.toByteArray()
                if (buffered.isNotEmpty()) {
                    Logger.i(TAG, "发送唤醒缓冲 ${buffered.size}B")
                    if (nativeOpusEncoder != null) nativeOpusEncoder!!.encode(buffered)
                    else opusEncoder?.encode(buffered)
                }
                wakeBuffer.reset()

                // 3. 切换为实时模式（后续 PCM 直接编码）
                wakeModeActive = false

                // 4. 回调通知上层发送 listen_start
                onWakeDetected?.invoke("voice")
            }
        } else {
            // 静音：不超过容错期不重置，防止词语间隙打断唤醒
            if (silenceStartTime == 0L) silenceStartTime = now
            if (now - silenceStartTime > SILENCE_GRACE_MS) {
                wakeVoiceStartTime = 0L
            }
        }
    }

    /** 停止录音和编码器 */
    fun stop(owner: String? = null) {
        if (!isRecording) return
        isRecording = false
        wakeModeActive = false
        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording(owner)
        try { Thread.sleep(50) } catch (_: InterruptedException) {}
        nativeOpusEncoder?.stop()
        nativeOpusEncoder = null
        opusEncoder?.stop()
        opusEncoder = null
        wakeFsmnVad?.reset()
        Logger.i(TAG, "录音已停止")
    }

    /** 完整释放 */
    fun release() {
        stop()
        scope = null
        wakeFsmnVad?.reset()
        wakeFsmnVad = null
    }

    /** 初始化唤醒用的 FSMN VAD */
    private fun initWakeVad() {
        if (wakeFsmnVad != null) return
        try {
            val vad = FsmnVAD()
            val ctx = audioService.getAppContext()
            if (ctx != null) {
                vad.init(ctx)
                wakeUseFsmn = vad.initialized
                if (wakeUseFsmn) {
                    wakeFsmnVad = vad
                    Logger.i(TAG, "唤醒 FSMN VAD 就绪")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "唤醒 FSMN VAD 初始化失败: ${e.message}，降级 RMS")
            wakeUseFsmn = false
        }
    }

    /** 初始化编码器 */
    private fun initEncoder(onOpusPacket: (ByteArray) -> Unit): Boolean {
        val sampleRate = audioService.getSampleRate()
        val nativeEncoder = NativeOpusEncoder(sampleRate = sampleRate)
        if (nativeEncoder.init { packet -> onOpusPacket(packet) }) {
            nativeOpusEncoder = nativeEncoder
            Logger.i(TAG, "使用 native Opus 编码器")
            return true
        }
        val encoder = OpusEncoder(sampleRate = sampleRate)
        if (!encoder.init { packet -> onOpusPacket(packet) }) {
            Logger.e(TAG, "Opus 编码器初始化失败（native + MediaCodec 均失败）")
            return false
        }
        opusEncoder = encoder
        Logger.i(TAG, "使用 MediaCodec Opus 编码器")
        return true
    }

    private fun calcRms(pcmData: ByteArray): Double {
        var sumSq = 0.0
        val n = pcmData.size / 2
        for (i in 0 until n) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8 or lo).toShort().toInt()
            sumSq += (s * s).toDouble()
        }
        return Math.sqrt(sumSq / n)
    }

    /** 前置增益放大 PCM（16-bit 有符号 LE），防止削顶 */
    private fun applyGain(pcmData: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return pcmData
        val result = ByteArray(pcmData.size)
        for (i in 0 until pcmData.size / 2) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt() and 0xFF
            var sample = (hi shl 8 or lo).toShort().toInt()
            sample = (sample * gain).toInt().coerceIn(-32767, 32767)
            result[i * 2] = (sample and 0xFF).toByte()
            result[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return result
    }
}
