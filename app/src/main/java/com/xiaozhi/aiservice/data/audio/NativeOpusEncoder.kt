package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * native Opus 编码器（libopus JNI）
 *
 * 用于 TCL/部分 MTK 设备上 MediaCodec Opus 编码器缺失的替代方案。
 * 共享 NativeOpusDecoder 加载的 native_opus_decoder so（内建编码函数）。
 */
class NativeOpusEncoder(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitRate: Int = 32000
) {
    companion object {
        private const val TAG = "NativeOpusEncoder"

        init {
            System.loadLibrary("native_opus_decoder")
        }
    }

    private var encoderPtr: Long = 0
    private var isEncoding = false
    private var encodingThread: Thread? = null
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    private var onOpusData: ((ByteArray) -> Unit)? = null
    private var presentationTimeUs = 0L

    /** 每帧 PCM 样本数（20ms @ 16kHz = 320 样本） */
    private val frameSamples = sampleRate * 20 / 1000

    fun init(onOpusPacket: (ByteArray) -> Unit): Boolean {
        this.onOpusData = onOpusPacket
        try {
            encoderPtr = nativeCreateEncoder(sampleRate, channels, bitRate)
            if (encoderPtr == 0L) {
                Logger.e(TAG, "Native Opus 编码器创建失败")
                return false
            }

            isEncoding = true
            startEncodeLoop()

            Logger.i(TAG, "Native Opus 编码器初始化成功: ${sampleRate}Hz, ${bitRate}bps")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Native Opus 编码器初始化失败: ${e.message}")
            return false
        }
    }

    fun encode(pcmData: ByteArray) {
        if (encoderPtr != 0L) {
            pcmQueue.offer(pcmData)
        }
    }

    private fun startEncodeLoop() {
        encodingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val codecPtr = encoderPtr
            var pcmAccumulator = ShortArray(frameSamples * 4)  // 预分配 ~4 帧容量，避免频繁扩容
            var accumulatedSamples = 0

            while (isEncoding) {
                val pcm = try { pcmQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (pcm == null) {
                    // 积攒的 PCM 够一帧时编码
                    if (accumulatedSamples >= frameSamples) {
                        encodeFrame(codecPtr, pcmAccumulator, accumulatedSamples)
                        pcmAccumulator = ShortArray(frameSamples * 4)
                        accumulatedSamples = 0
                    }
                    continue
                }

                // byte[] → short[]
                val shortBuf = ShortArray(pcm.size / 2)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuf)

                // 确保有足够空间
                if (accumulatedSamples + shortBuf.size > pcmAccumulator.size) {
                    pcmAccumulator = pcmAccumulator.copyOf(pcmAccumulator.size * 2)
                }
                System.arraycopy(shortBuf, 0, pcmAccumulator, accumulatedSamples, shortBuf.size)
                accumulatedSamples += shortBuf.size

                // 积攒够一帧就编码
                while (accumulatedSamples >= frameSamples) {
                    encodeFrame(codecPtr, pcmAccumulator, frameSamples)
                    // 移动剩余样本到数组头部
                    val remaining = accumulatedSamples - frameSamples
                    System.arraycopy(pcmAccumulator, frameSamples, pcmAccumulator, 0, remaining)
                    accumulatedSamples = remaining
                }
            }

            // 编码剩余数据
            if (accumulatedSamples > 0) {
                encodeFrame(codecPtr, pcmAccumulator, accumulatedSamples)
            }

            Logger.i(TAG, "编码线程结束")
        }, "native-opus-encoder").apply { start() }
    }

    private fun encodeFrame(ptr: Long, samples: ShortArray, count: Int) {
        try {
            val input = if (count == samples.size) samples else samples.copyOf(count)

            // 输出缓冲：Opus 最大帧约 1275 字节（@ 48kHz），16kHz 更少
            val maxOutputSize = 1500
            val output = ByteArray(maxOutputSize)

            val encodedBytes = nativeEncode(ptr, input, count, output, maxOutputSize)
            if (encodedBytes > 0) {
                val opusData = output.copyOf(encodedBytes)
                onOpusData?.invoke(opusData)
            }
        } catch (e: Exception) {
            if (isEncoding) Logger.e(TAG, "编码帧错误: ${e.message}")
        }
    }

    fun stop() {
        isEncoding = false
        val thread = encodingThread
        encodingThread = null
        thread?.interrupt()
        if (encoderPtr != 0L) {
            nativeDestroyEncoder(encoderPtr)
            encoderPtr = 0L
        }
        pcmQueue.clear()
        onOpusData = null
        Logger.i(TAG, "Native Opus 编码器已停止")
    }

    private external fun nativeCreateEncoder(sampleRate: Int, channels: Int, bitRate: Int): Long
    private external fun nativeEncode(encoderPtr: Long, input: ShortArray, inputSamples: Int, output: ByteArray, maxOutputSize: Int): Int
    private external fun nativeDestroyEncoder(encoderPtr: Long)
}
