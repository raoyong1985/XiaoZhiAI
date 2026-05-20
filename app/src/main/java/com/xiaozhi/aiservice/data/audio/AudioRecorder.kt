package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AudioRecorder"

/**
 * 统一录音管理器：抽离单聊/群聊共用的 OpusEncoder + AudioService 录音管线。
 *
 * 职责：
 * - 创建/销毁 OpusEncoder
 * - 启动/停止 AudioService 录音（带 VAD）
 * - 8 秒录音超时（群聊/单聊一致）
 * - 回调隔离（generation 机制防陈旧回调）
 *
 * ViewModel 负责：sendListenStart/sendAbort、状态机、唤醒词切换。
 * AudioRecorder 只负责：录音→编码→回调。
 */
class AudioRecorder(
    private val audioService: AudioService
) {
    private var opusEncoder: OpusEncoder? = null
    private var timeoutJob: Job? = null
    private var scope: CoroutineScope? = null

    /** 单调递增 generation，每次 start 自增，stop 回调时比对防旧回调 */
    @Volatile private var generation = 0

    /** 是否正在录音 */
    @Volatile var isRecording: Boolean = false
        private set

    /**
     * 启动录音。
     *
     * @param scope        协程作用域（viewModelScope）
     * @param enableVAD    是否启用静音自动停止
     * @param maxRecordMs  录音超时（默认 8s）
     * @param onOpusPacket Opus 编码后的音频包回调（发送给 WebSocket）
     * @param onSilenceDetected 静音检测回调（VAD 自然结束）
     * @return 是否成功启动
     */
    fun start(
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

        // 停止上一次残留
        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null

        val sampleRate = audioService.getSampleRate()
        val encoder = OpusEncoder(sampleRate = sampleRate)
        if (!encoder.init { packet -> onOpusPacket(packet) }) {
            Logger.e(TAG, "Opus 编码器初始化失败")
            return false
        }
        opusEncoder = encoder

        // 绑定静音检测回调
        if (onSilenceDetected != null) {
            audioService.setOnSilenceDetected {
                if (generation == currentGen) {
                    stop()
                    onSilenceDetected()
                }
            }
        }

        isRecording = true

        if (!audioService.startRecording({ pcmData -> encoder.encode(pcmData) }, enableVAD)) {
            Logger.e(TAG, "AudioService 录音启动失败")
            isRecording = false
            opusEncoder?.stop()
            opusEncoder = null
            return false
        }

        // 录音超时兜底
        timeoutJob = scope.launch {
            delay(maxRecordMs)
            if (isRecording && generation == currentGen) {
                Logger.w(TAG, "录音超时 ${maxRecordMs}ms，自动停止")
                stop()
                onSilenceDetected?.invoke()
            }
        }

        Logger.i(TAG, "录音启动成功 (VAD=$enableVAD, 超时=${maxRecordMs}ms)")
        return true
    }

    /** 停止录音和编码器，不清除 state flag（由调用方决定） */
    fun stop() {
        if (!isRecording) return
        isRecording = false
        timeoutJob?.cancel()
        timeoutJob = null
        audioService.stopRecording()
        opusEncoder?.stop()
        opusEncoder = null
        Logger.i(TAG, "录音已停止")
    }

    /** 完整释放 */
    fun release() {
        stop()
        scope = null
    }
}
