package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.*

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
    /** 唤醒模式环形缓冲（固定 byte 数组，零分配零扩容） */
    private val wakeRingBuffer = ByteArray(64000)  // 2秒 @ 16kHz 16bit
    private var wakeRingWritePos = 0
    @Volatile private var wakeRingSize = 0
    private val wakeRingLock = Any()
    /** 环形缓冲容量 */
    private val wakeBufferMaxBytes = 64000
    /** applyGain 复用缓冲，避免每帧分配 ByteArray */
    private var gainBuffer: ByteArray? = null
    private var onWakeDetected: ((String) -> Unit)? = null
    private var wakeModeActive = false
    /** 唤醒模板匹配器（可选，设置后 VAD 触发后需本地匹配成功才发 listen_start） */
    var wakeTemplateMatcher: WakeTemplateMatcher? = null
    /** VAD 降频计时：每 100ms 跑一次 ONNX 推理，中间帧跳过 */
    private var lastVadCheckMs = 0L
    private val VAD_CHECK_INTERVAL_MS = 100L
    /** 连续静音时长（词语间隙容错） */
    private var silenceStartTime = 0L
    private val SILENCE_GRACE_MS = 300L  // 词语间停顿不超过300ms不重置timer

    // ── 三级预检（节能）──
    /** 三级预检帧计数器：每隔多少帧执行一次完整 VAD */
    private var frameCounter = 0
    /** 连续低能量帧数，用于拉长 VAD 间隔 */
    private var consecutiveLowEnergy = 0
    /** 最大连续低能帧数后跳过 VAD 检查 */
    private val MAX_LOW_ENERGY_FRAMES = 5
    /** 三级预检的低能量阈值（低于此值直接跳过 VAD，省 ONNX 推理） */
    private val ENERGY_PRE_CHECK_THRESHOLD = 0.4f  // WAKE_RMS_THRESHOLD 的 40%

    // ── SNR 估计 ──
    /** 噪声底噪 RMS 指数移动平均 */
    private var noiseFloorRms = 200.0
    /** 最近 2s 的最大 RMS（用于 SNR 计算） */
    private var recentPeakRms = 200.0
    /** SNR 平滑值，避免每帧抖动 */
    private var smoothedSnrDb = 20f

    companion object {
        // "悠悠"约400-500ms，400ms可让用户说到一半时就触发（缩短响应延迟）
        private const val WAKE_HOLD_MS = 400L
        // RMS 阈值（0-32767），与 AudioService.SILENCE_RMS_THRESHOLD 保持一致
        private const val WAKE_RMS_THRESHOLD = 800
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
        onOpusPacket: (ByteArray) -> Unit
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
        // 重置三级预检和 SNR 状态
        frameCounter = 0
        consecutiveLowEnergy = 0
        noiseFloorRms = 200.0
        recentPeakRms = 200.0
        smoothedSnrDb = 20f
        // 重置环形缓冲
        synchronized(wakeRingLock) {
            wakeRingWritePos = 0
            wakeRingSize = 0
        }

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
     * @param startMuteMs 录音启动后丢弃前多少毫秒的 PCM（防扬声器回声），
     *                    用户主动按键时传小值(0-100)，自动重听传大值(800)
     */
    fun startLive(
        owner: String,
        scope: CoroutineScope,
        enableVAD: Boolean = true,
        maxRecordMs: Long = 8_000L,
        startMuteMs: Long = 200L,
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

        if (!initEncoder(onOpusPacket)) {
            Logger.e(TAG, "实时录音启动失败: Opus编码器初始化失败")
            return false
        }

        if (onSilenceDetected != null) {
            audioService.setOnSilenceDetected(owner) {
                if (generation == currentGen) {
                    stop(owner)
                    onSilenceDetected()
                }
            }
        }

        isRecording = true
        // 录音启动后丢弃前 N ms 的 PCM（防扬声器回声混入 STT）
        val recordingStartTime = System.currentTimeMillis()
        val muteUntilMs = startMuteMs

        if (!audioService.startRecording(owner, { pcmData ->
            // 静默期内丢弃音频，不编码不发送
            if (System.currentTimeMillis() - recordingStartTime < muteUntilMs) return@startRecording
            if (nativeOpusEncoder != null) nativeOpusEncoder?.encode(pcmData)
            else opusEncoder?.encode(pcmData)
        }, enableVAD)) {
            val audioErr = audioService.lastRecordError
            Logger.e(TAG, "实时录音启动失败: $audioErr")
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
            nativeOpusEncoder?.encode(pcmData) ?: opusEncoder?.encode(pcmData)
            return
        }

        // ——— 前置增益 2x：让 ASR 收到更清晰的音频，提高唤醒词识别率 ———
        val amplified = if (WAKE_GAIN != 1.0f) applyGain(pcmData, WAKE_GAIN) else pcmData

        val now = System.currentTimeMillis()

        // 写入环形缓冲（固定 circular array，零分配零扩容）
        synchronized(wakeRingLock) {
            val len = amplified.size
            if (len >= wakeBufferMaxBytes) {
                // 数据比缓冲还大，直接保留末尾
                System.arraycopy(amplified, len - wakeBufferMaxBytes, wakeRingBuffer, 0, wakeBufferMaxBytes)
                wakeRingWritePos = 0
                wakeRingSize = wakeBufferMaxBytes
            } else {
                val space = wakeBufferMaxBytes - wakeRingWritePos
                if (len <= space) {
                    System.arraycopy(amplified, 0, wakeRingBuffer, wakeRingWritePos, len)
                } else {
                    System.arraycopy(amplified, 0, wakeRingBuffer, wakeRingWritePos, space)
                    System.arraycopy(amplified, space, wakeRingBuffer, 0, len - space)
                }
                wakeRingWritePos = (wakeRingWritePos + len) % wakeBufferMaxBytes
                if (wakeRingSize < wakeBufferMaxBytes) {
                    wakeRingSize = minOf(wakeRingSize + len, wakeBufferMaxBytes)
                }
            }
        }

        // 每帧更新 SNR 估计（即便不跑 VAD 也跟踪环境噪声）
        val currentRms = calcRms(amplified)
        // 噪声底噪指数移动平均（取静音段的 RMS）
        if (currentRms < noiseFloorRms * 1.5 || noiseFloorRms < 1) {
            noiseFloorRms = noiseFloorRms * 0.95 + currentRms * 0.05
        }
        // 跟踪峰值
        if (currentRms > recentPeakRms) {
            recentPeakRms = currentRms
        } else {
            recentPeakRms = recentPeakRms * 0.995 + currentRms * 0.005
        }
        // 计算平滑 SNR
        if (noiseFloorRms > 1.0 && recentPeakRms > noiseFloorRms) {
            val snrLinear = (recentPeakRms / noiseFloorRms).toFloat()
            val snrDb = 20f * kotlin.math.log10(snrLinear).coerceIn(0f, 40f)
            smoothedSnrDb = smoothedSnrDb * 0.9f + snrDb * 0.1f
        }

        // ── 三级预检 Level 1: RMS 能量预检查（每秒约 100 次，0.01ms/次）──
        // 能量太低 → 跳过 FSMN VAD，省 ONNX 推理
        frameCounter++
        val energyOk = currentRms > WAKE_RMS_THRESHOLD * ENERGY_PRE_CHECK_THRESHOLD
        if (!energyOk) {
            consecutiveLowEnergy++
            // 连续低能量 → 拉长 VAD 检查间隔（省电）
            if (consecutiveLowEnergy > MAX_LOW_ENERGY_FRAMES) {
                // 跳过本轮 VAD，重置 VAD 计时让 ONNX 更晚执行
                lastVadCheckMs = now
                return
            }
        } else {
            consecutiveLowEnergy = 0
        }

        // ✅ 降频：每 200ms 才跑 VAD（ONNX 推理），中间帧直接跳过
        if (now - lastVadCheckMs < VAD_CHECK_INTERVAL_MS) return
        lastVadCheckMs = now

        // ── 三级预检 Level 2: FSMN VAD（ONNX）或 RMS 降级 ──
        val hasVoice = if (wakeUseFsmn && wakeFsmnVad != null) {
            try { wakeFsmnVad!!.isVoice(amplified) }
            catch (_: Exception) { calcRms(amplified) > WAKE_RMS_THRESHOLD }
        } else {
            calcRms(amplified) > WAKE_RMS_THRESHOLD
        }

        if (hasVoice) {
            silenceStartTime = 0L  // 有人声，清除静音计时
            if (wakeVoiceStartTime == 0L) wakeVoiceStartTime = now
            if (!wakeTriggered && now - wakeVoiceStartTime >= WAKE_HOLD_MS) {
                triggerWake(onOpusPacket)
            }
        } else {
            // 静音：不超过容错期不重置，防止词语间隙打断唤醒
            if (silenceStartTime == 0L) silenceStartTime = now

            // 静音容错期内检查：如果说话总时长已到 WAKE_HOLD_MS，触发
            if (!wakeTriggered && wakeVoiceStartTime > 0 &&
                now - silenceStartTime <= SILENCE_GRACE_MS &&
                now - wakeVoiceStartTime >= WAKE_HOLD_MS) {
                Logger.i(TAG, "🎤 静音容错期内触发唤醒（累计语音 ${now - wakeVoiceStartTime}ms）")
                triggerWake(onOpusPacket)
            }

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

    /**
     * 触发唤醒：创建编码器、发送缓冲 PCM、切为实时模式、回调上层
     * 从 hasVoice 路径和静音容错路径都能调用，消除代码重复
     */
    private fun triggerWake(onOpusPacket: (ByteArray) -> Unit) {
        if (wakeTriggered) return
        wakeTriggered = true
        Logger.i(TAG, "🎤 检测到连续语音，过渡到实时编码")

        // ——— 本地唤醒模板匹配 ———
        // 如果有模板，先匹配再决定是否触发
        val matcher = wakeTemplateMatcher
        if (matcher != null && matcher.hasTemplates()) {
            val ringData = getWakeRingData()
            val matchedContactId = matcher.match(ringData, smoothedSnrDb)
            if (matchedContactId == null) {
                // 不匹配 → 静默重置，继续监听
                Logger.d(TAG, "🔇 本地匹配未通过，静默重置唤醒")
                wakeTriggered = false
                wakeVoiceStartTime = 0L
                silenceStartTime = 0L
                synchronized(wakeRingLock) {
                    wakeRingWritePos = 0
                    wakeRingSize = 0
                }
                return  // 不触发，不发送任何数据到服务器
            }
            Logger.i(TAG, "🎯 本地唤醒匹配: contact=$matchedContactId，发送 listen_start")
        }

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

        val buffered = getWakeRingData()
        if (buffered.isNotEmpty()) {
            Logger.i(TAG, "发送唤醒缓冲 ${buffered.size}B")
            nativeOpusEncoder?.encode(buffered) ?: opusEncoder?.encode(buffered)
        }
        // 重置环形缓冲
        synchronized(wakeRingLock) {
            wakeRingWritePos = 0
            wakeRingSize = 0
        }

        wakeModeActive = false
        onWakeDetected?.invoke("")
    }

    /**
     * 从环形缓冲读取唤醒前的 PCM 数据。
     * 仅在 triggerWake 中调用，一次调用一次分配。
     */
    private fun getWakeRingData(): ByteArray {
        synchronized(wakeRingLock) {
            val size = wakeRingSize
            if (size == 0) return ByteArray(0)
            val result = ByteArray(size)
            if (size < wakeBufferMaxBytes) {
                System.arraycopy(wakeRingBuffer, 0, result, 0, size)
            } else {
                val tailLen = wakeBufferMaxBytes - wakeRingWritePos
                System.arraycopy(wakeRingBuffer, wakeRingWritePos, result, 0, tailLen)
                System.arraycopy(wakeRingBuffer, 0, result, tailLen, wakeRingWritePos)
            }
            return result
        }
    }

    /** 初始化唤醒用的 FSMN VAD（创建独立实例，不与 AudioService 共享避免状态竞争） */
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
                    Logger.i(TAG, "唤醒 FSMN VAD 就绪（独立实例）")
                    return
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "唤醒 FSMN VAD 初始化失败: ${e.message}")
        }
        // 降级到全局共享实例
        val sharedVad = audioService.fsmnVad
        if (sharedVad.initialized) {
            sharedVad.reset()
            wakeFsmnVad = sharedVad
            wakeUseFsmn = true
            Logger.i(TAG, "唤醒 FSMN VAD 就绪（降级到全局共享实例）")
        } else {
            Logger.w(TAG, "全局 FSMN VAD 也未就绪，降级到 RMS 能量检测")
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
        // 每 4 样本取 1，降低功耗（与 AudioService.calculateRmsEnergy 一致）
        var i = 0
        while (i < n) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8 or lo).toShort().toInt()
            sumSq += (s * s).toDouble()
            i += 4
        }
        return Math.sqrt(sumSq / ((n + 3) / 4))
    }

    /** 前置增益放大 PCM（16-bit 有符号 LE），复用缓冲避免每帧分配 */
    private fun applyGain(pcmData: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return pcmData
        val size = pcmData.size
        // 复用或扩容缓冲
        val buf = gainBuffer
        if (buf == null || buf.size < size) {
            gainBuffer = ByteArray(size)
        }
        val result = gainBuffer!!
        for (i in 0 until size / 2) {
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
