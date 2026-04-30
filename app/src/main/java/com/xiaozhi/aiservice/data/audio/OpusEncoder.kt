package com.xiaozhi.aiservice.data.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.xiaozhi.aiservice.BuildConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 条件日志：仅在 debug 版本输出
 */
private fun debugLogEnc(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
}

private fun debugLogEncE(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.e(tag, msg)
}

/**
 * Opus 音频编码器
 * 使用 Android 原生 MediaCodec 将 PCM 编码为 Opus
 */
class OpusEncoder(
    private val sampleRate: Int = 16000,  // 必须与 AudioService 录音采样率一致（16kHz）
    private val channels: Int = 1,
    private val bitRate: Int = 32000  // 32kbps（原来 16kbps 太低，语音细节丢失）
) {
    companion object {
        private const val TAG = "OpusEncoder"
        private const val TIMEOUT_US = 10000L
    }

    private var encoder: MediaCodec? = null
    @Volatile private var isEncoding = false
    private var encodingThread: Thread? = null
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    private var onOpusData: ((ByteArray) -> Unit)? = null
    private var presentationTimeUs = 0L

    fun init(onOpusPacket: (ByteArray) -> Unit): Boolean {
        this.onOpusData = onOpusPacket
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)  // 16KB（增大防止丢帧）

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()

            isEncoding = true
            startEncodeLoop()

            debugLogEnc(TAG, "Opus 编码器初始化成功: sampleRate=$sampleRate, bitRate=$bitRate")
            return true
        } catch (e: Exception) {
            debugLogEncE(TAG, "Opus 编码器初始化失败: ${e.message}")
            return false
        }
    }

    fun encode(pcmData: ByteArray) {
        if (encoder != null) {
            pcmQueue.offer(pcmData)
        }
    }

    private fun startEncodeLoop() {
        encodingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val codec = encoder ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()
            presentationTimeUs = 0L

            while (isEncoding) {
                val pcm = try { pcmQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (pcm == null) continue

                try {
                    // 送 PCM 到编码器
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcm)
                            codec.queueInputBuffer(inputIndex, 0, pcm.size, presentationTimeUs, 0)
                            presentationTimeUs += (pcm.size / 2) * 1000000L / sampleRate
                        }
                    }

                    // 取编码后的 Opus 数据
                    var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    while (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val opusData = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(opusData, 0, bufferInfo.size)
                            // 跳过 csd 数据（配置帧）
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                onOpusData?.invoke(opusData)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    }
                    } catch (e: Exception) {
                        if (isEncoding) debugLogEncE(TAG, "编码错误: ${e.message}")
                    }
            }
        }, "opus-encoder").apply { start() }
    }

    fun stop() {
        isEncoding = false
        // 发送 EOS 让编码器 flush 最后几帧
        encoder?.let { codec ->
            try {
                val inputIndex = codec.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (_: Exception) {}
        }
        encodingThread?.interrupt()
        encodingThread = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        pcmQueue.clear()
        onOpusData = null
        debugLogEnc(TAG, "Opus 编码器已停止")
    }
}
