package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.*

private const val TAG = "AudioRecorder"

/**
 * 统一录音管理器：单 AudioRecord 管线。
 *
 * 职责：
 * - 唤醒检测（FSMN VAD → KWS CNN 唤醒词识别）
 * - AEC 回声消除（防止 TTS 自激）
 * - 创建/销毁 OpusEncoder
 * - 启动/停止 AudioService 录音（带 VAD）
 * - 8 秒录音超时（群聊/单聊一致）
 * - 回调隔离（generation 机制防陈旧回调）
 *
 * 架构（对应 ESP32 AFE 流水线）：
 *   PCM → [AudioAEC 回声消除] → [RMS预检] → [FSMN VAD] → [环形缓冲 2s]
 *                                                          ↓ 检测到语音
 *                                                     [MfccExtractor: 提取40×63 MFCC]
 *                                                          ↓
 *                                                     [KwsEngine: DSCNN 推理]
 *                                                          ↓ 唤醒词
 *                                                     Opus编码 → WebSocket发送
 *
 * 唤醒词验证由 KwsEngine 本地完成（DSCNN ONNX 模型），
 * 无需依赖服务器 ASR，和 ESP32 的 WakeNet 方案一致。
 */
class AudioRecorder(
    private val audioService: AudioService,
    /** KWS 唤醒词引擎（替代 MFCC 余弦匹配）*/
    val kwsEngine: KwsEngine = KwsEngine(),
    /** AEC 回声消除器 */
    val aec: AudioAEC = AudioAEC()
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
    // ESP32 方案：VAD 触发后直接通知服务器，不依赖本地模板匹配
    // 唤醒词验证由服务器 ASR + wakeup_words 配置完成
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
    /** 最大连续低能帧数后跳过 VAD 检查（从5提到20，ESP32无此限制） */
    private val MAX_LOW_ENERGY_FRAMES = 20
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
        // 与 ESP32 的 ~300ms 唤醒延迟对齐
        private const val WAKE_HOLD_MS = 200L
        // RMS 阈值（0-32767），与 AudioService.SILENCE_RMS_THRESHOLD 保持一致
        private const val WAKE_RMS_THRESHOLD = 800
        // 唤醒模式下 PCM 前置增益（×1.5），ESP32 AFE 无额外增益
        private const val WAKE_GAIN = 1.5f
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
        onWakeDetected: (String) -> Unit
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

        // 初始化 KWS 引擎
        if (!kwsEngine.initialized) {
            kwsEngine.init()
            if (!kwsEngine.initialized) {
                Logger.w(TAG, "KWS 引擎未就绪，将降级到 VAD 直接触发")
            }
        }
        // 重置 KWS 状态
        kwsEngine.reset()
        wakeMfccReady = false
        mfccExtractor.reset()

        // 重置 AEC
        aec.reset()

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
            handleWakePcm(pcmData)
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

    // ── KWS 唤醒词识别（仅用于区分联系人，不拦截唤醒） ──
    private val mfccExtractor = MfccExtractor()
    private var wakeMfccReady = false

    /**
     * 唤醒模式 PCM 处理：AEC → FSMN VAD + 环形缓冲 → VAD触发 → KWS识别联系人
     */
    private fun handleWakePcm(pcmData: ByteArray) {
        if (!wakeModeActive) {
            // 已过渡到实时模式，直接编码
            nativeOpusEncoder?.encode(pcmData) ?: opusEncoder?.encode(pcmData)
            return
        }

        // ===== 1. AEC 回声消除（对应 ESP32 AFE） =====
        val echoCanceled = if (aec.enabled) aec.process(pcmData) else pcmData

        // ===== 2. 前置增益 1.5x =====
        val amplified = applyGain(echoCanceled, WAKE_GAIN)

        val now = System.currentTimeMillis()

        // 写入环形缓冲
        synchronized(wakeRingLock) {
            val len = amplified.size
            if (len >= wakeBufferMaxBytes) {
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

        // ===== 3. 同时提取 MFCC 特征（喂 MfccExtractor） =====
        // 将 PCM byte 转 float 后喂入
        val pcmFloats = pcmBytesToFloats(amplified)
        mfccExtractor.feed(pcmFloats)
        if (mfccExtractor.isReady()) {
            wakeMfccReady = true
        }

        // 每帧更新 SNR 估计
        val currentRms = calcRms(amplified)
        if (currentRms < noiseFloorRms * 1.5 || noiseFloorRms < 1) {
            noiseFloorRms = noiseFloorRms * 0.95 + currentRms * 0.05
        }
        if (currentRms > recentPeakRms) {
            recentPeakRms = currentRms
        } else {
            recentPeakRms = recentPeakRms * 0.995 + currentRms * 0.005
        }
        if (noiseFloorRms > 1.0 && recentPeakRms > noiseFloorRms) {
            val snrLinear = (recentPeakRms / noiseFloorRms).toFloat()
            val snrDb = 20f * kotlin.math.log10(snrLinear).coerceIn(0f, 40f)
            smoothedSnrDb = smoothedSnrDb * 0.9f + snrDb * 0.1f
        }

        // ── 三级预检 Level 1: RMS 能量预检查 ──
        frameCounter++
        val energyOk = currentRms > WAKE_RMS_THRESHOLD * ENERGY_PRE_CHECK_THRESHOLD
        if (!energyOk) {
            consecutiveLowEnergy++
            if (consecutiveLowEnergy > MAX_LOW_ENERGY_FRAMES) {
                lastVadCheckMs = now
                return
            }
        } else {
            consecutiveLowEnergy = 0
        }

        if (now - lastVadCheckMs < VAD_CHECK_INTERVAL_MS) return
        lastVadCheckMs = now

        // ── 三级预检 Level 2: RMS 能量检测（唤醒模式不走 FSMN VAD，避免误过滤）
        val hasVoice = calcRms(amplified) > WAKE_RMS_THRESHOLD

        if (hasVoice) {
            silenceStartTime = 0L
            if (wakeVoiceStartTime == 0L) {
                wakeVoiceStartTime = now
            }

            // ── 三级预检 Level 3: VAD 持续语音 200ms → 触发唤醒（ESP32 方案）
            if (!wakeTriggered && now - wakeVoiceStartTime >= WAKE_HOLD_MS) {
                var matchedContact = ""
                // MFCC + DTW 匹配联系人（保留时间序列，字序敏感）
                if (wakeMfccReady && kwsEngine.hasTemplates()) {
                    val mfccData = mfccExtractor.getInputData()
                    val matched = kwsEngine.match(mfccData)
                    if (matched != null) {
                        matchedContact = matched
                        Logger.w(TAG, "🎯 DTW 识别到联系人: $matched (dist=${"%.2f".format(kwsEngine.lastScore)})")
                    } else {
                        Logger.w(TAG, "DTW 未匹配, dist=${"%.2f".format(kwsEngine.lastScore)}")
                    }
                }
                triggerWake(matchedContact)
            }
        } else {
            if (silenceStartTime == 0L) silenceStartTime = now

            // 静音容错期内检查
            if (!wakeTriggered &&
                wakeVoiceStartTime > 0 &&
                now - silenceStartTime <= SILENCE_GRACE_MS &&
                now - wakeVoiceStartTime >= WAKE_HOLD_MS) {
                var matchedContact = ""
                if (wakeMfccReady && kwsEngine.hasTemplates()) {
                    val mfccData = mfccExtractor.getInputData()
                    val matched = kwsEngine.match(mfccData)
                    if (matched != null) matchedContact = matched
                }
                triggerWake(matchedContact)
            }

            if (now - silenceStartTime > SILENCE_GRACE_MS) {
                wakeVoiceStartTime = 0L
                // 重置 KWS 状态（一段话结束）
                if (wakeMfccReady) {
                    wakeMfccReady = false
                    mfccExtractor.reset()
                }
            }
        }
    }

    /** PCM 字节转 float 数组 [-1, 1] */
    private fun pcmBytesToFloats(pcmBytes: ByteArray): FloatArray {
        val n = pcmBytes.size / 2
        val floats = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt() and 0xFF
            floats[i] = ((hi shl 8 or lo).toShort()).toFloat() / 32768f
        }
        return floats
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
        // 重置 KWS 和 AEC 状态
        wakeMfccReady = false
        mfccExtractor.reset()
        aec.reset()
        Logger.i(TAG, "录音已停止")
    }

    /** 完整释放 */
    fun release() {
        stop()
        scope = null
        wakeFsmnVad?.reset()
        wakeFsmnVad = null
        kwsEngine.release()
        mfccExtractor.reset()
        aec.reset()
    }

    /**
     * 触发唤醒：创建编码器、发送缓冲 PCM、切为实时模式、回调上层
     * 将 KWS 匹配到的联系人 ID 传给上层，自动切换到对应智能体
     *
     * @param contactId KWS 匹配到的联系人 ID，空字符串表示无模板降级触发
     */
    private fun triggerWake(contactId: String = "") {
        if (wakeTriggered) return
        wakeTriggered = true
        Logger.w(TAG, "🎤 唤醒触发: contactId=$contactId")

        // 清理环形缓冲
        synchronized(wakeRingLock) {
            wakeRingWritePos = 0
            wakeRingSize = 0
        }

        wakeModeActive = false
        // 传 contactId 给上层（ConnectionRepositoryImpl 处理断线重连 + 启动编码）
        onWakeDetected?.invoke(contactId)
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
