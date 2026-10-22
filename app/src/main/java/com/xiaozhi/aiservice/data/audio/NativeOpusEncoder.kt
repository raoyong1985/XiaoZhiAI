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
    private val bitRate: Int = 64000
) {
    companion object {
        private const val TAG = "NativeOpusEncoder"

        init {
            System.loadLibrary("native_opus_decoder")
        }
    }

    private var encoderPtr: Long = 0
    @Volatile private var isEncoding = false
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
            var pcmAccumulator = ShortArray(frameSamples * 4)
            var accumulatedSamples = 0

            while (isEncoding) {
                val pcm = try { pcmQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (pcm == null) {
                    // 积压可能不止一帧：逐帧编完，剩余样本留在缓冲里，
                    // 否则多出来的音频被下一个 new ShortArray 直接丢掉
                    while (accumulatedSamples >= frameSamples && isEncoding) {
                        encodeFrame(codecPtr, pcmAccumulator, frameSamples)
                        val remaining = accumulatedSamples - frameSamples
                        if (remaining > 0) {
                            System.arraycopy(pcmAccumulator, frameSamples, pcmAccumulator, 0, remaining)
                        }
                        accumulatedSamples = remaining
                    }
                    continue
                }

                // byte[] → short[]
                val shortBuf = ShortArray(pcm.size / 2)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuf)

                // 确保有足够空间（必须在 arraycopy 之前）
                val required = accumulatedSamples + shortBuf.size
                if (required > pcmAccumulator.size) {
                    pcmAccumulator = pcmAccumulator.copyOf(maxOf(required, pcmAccumulator.size * 2))
                }
                System.arraycopy(shortBuf, 0, pcmAccumulator, accumulatedSamples, shortBuf.size)
                accumulatedSamples += shortBuf.size

                // 积攒够一帧就编码
                while (accumulatedSamples >= frameSamples && isEncoding) {
                    encodeFrame(codecPtr, pcmAccumulator, frameSamples)
                    val remaining = accumulatedSamples - frameSamples
                    if (remaining > 0) {
                        System.arraycopy(pcmAccumulator, frameSamples, pcmAccumulator, 0, remaining)
                    }
                    accumulatedSamples = remaining
                }
            }

            if (accumulatedSamples > 0) {
                encodeFrame(codecPtr, pcmAccumulator, accumulatedSamples)
            }

            Logger.i(TAG, "编码线程结束")
        }, "native-opus-encoder").apply { start() }
    }

    private fun encodeFrame(ptr: Long, samples: ShortArray, count: Int) {
        try {
            val n = minOf(count, frameSamples)
            if (n <= 0) return
            // 必须切出**正好一帧**的数组：samples 是累加缓冲（frameSamples*4），
            // 直接整块传过去会让 native 的帧长校验失败，每帧都返回 -1（彻底没声音上行）
            // opus_encode 也要求固定帧长，不足一帧补零，否则同样返回 -1（尾音丢失）
            val input = if (n == frameSamples && samples.size == frameSamples) {
                samples
            } else if (n == frameSamples) {
                samples.copyOf(frameSamples)
            } else {
                ShortArray(frameSamples).also { System.arraycopy(samples, 0, it, 0, n) }
            }

            // 输出缓冲：Opus 最大帧约 1275 字节（@ 48kHz），16kHz 更少
            val maxOutputSize = 1500
            val output = ByteArray(maxOutputSize)

            val encodedBytes = nativeEncode(ptr, input, frameSamples, output, maxOutputSize)
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
        // 等编码线程退出后再销毁 native 编码器，防止 use-after-free
        try {
            var waited = 0
            while (thread?.isAlive == true && waited < 3000) {
                thread.join(200)
                waited += 200
            }
        } catch (_: InterruptedException) {}
        if (thread?.isAlive == true) {
            // 线程还活着就销毁 native 编码器 = use-after-free（native 崩溃，Java 无栈）
            Logger.e(TAG, "编码线程 3s 内未退出，跳过 native 销毁以避免 use-after-free")
        } else if (encoderPtr != 0L) {
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
