package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.*

private const val TAG = "AudioRecorder"

/**
 * 统一录音管理器：单 AudioRecord 管线。
 *
 * 职责：
 * - 创建/销毁 OpusEncoder
 * - 启动/停止 AudioService 录音（带 VAD）
 * - 8 秒录音超时（群聊/单聊一致）
 * - 回调隔离（generation 机制防陈旧回调）
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

    /**
     * 启动实时录音（编码+发送）
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

    /** 停止录音和编码器 */
    fun stop(owner: String? = null) {
        if (!isRecording) return
        isRecording = false
        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording(owner)
        try { Thread.sleep(50) } catch (_: InterruptedException) {}
        nativeOpusEncoder?.stop()
        nativeOpusEncoder = null
        opusEncoder?.stop()
        opusEncoder = null
        Logger.i(TAG, "录音已停止")
    }

    /** 完整释放 */
    fun release() {
        stop()
        scope = null
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
}