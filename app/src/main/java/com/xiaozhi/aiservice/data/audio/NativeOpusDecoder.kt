package com.xiaozhi.aiservice.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.abs

class NativeOpusDecoder(
    private val serverSampleRate: Int = 24000,
    private val clientSampleRate: Int = 16000,
    private val channels: Int = 1,
    initialVolume: Int = 100  // 0-100, default 100
) {
    companion object {
        private const val TAG = "NativeOpusDecoder"
        private const val MAX_QUEUE_SIZE = 200
        private const val BUFFER_MULTIPLIER = 6
    }

    private var decoderPtr: Long = 0
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    val isReleased: Boolean get() = decoderPtr == 0L && audioTrack == null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    private val totalBytesWritten = AtomicLong(0L)

    private val serverFrameSamples = serverSampleRate * 60 / 1000
    private val silenceFrame = ByteArray(12800)

    private val pendingDecodePackets = AtomicInteger(0)

    /**
     * 强制 flush AudioTrack：清空队列 + 暂停+flush+恢复
     * 参考 ESP32 AudioService::ResetDecoder() — 清空所有队列并重置解码器
     * 用于 SPEAKING→LISTENING 切换时，立即停止残留音频播放
     */
    fun flushAudio() {
        Logger.i(TAG, "flushAudio: 强制排空播放缓冲")
        // 清空 Opus 解码队列（参考 ESP32 ResetDecoder: audio_decode_queue_.clear()）
        audioQueue.clear()
        pendingDecodePackets.set(0)

        // flush AudioTrack 硬件缓冲（参考 ESP32 ResetDecoder 清空 audio_playback_queue_）
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                    Logger.i(TAG, "flushAudio: AudioTrack 已暂停→flush→恢复")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "flushAudio: AudioTrack flush 异常: ${e.message}")
        }

        // 重置写入计数字段（参考 ESP32 ResetDecoder: timestamp_queue_.clear()）
        totalBytesWritten.set(0L)
        Logger.i(TAG, "flushAudio 完成")
    }

    // 音量缩放因子：volumeScale / 100，范围 0-100
    // 参考 ESP32 audio_codec.cc SetOutputVolume()
    private var volumeScale: Int = initialVolume.coerceIn(0, 100)

    init {
        System.loadLibrary("native_opus_decoder")
    }

    /**
     * 设置输出音量
     * 参考 ESP32 audio_codec.cc SetOutputVolume()
     * @param level 音量 0-100
     */
    fun setVolume(level: Int) {
        volumeScale = level.coerceIn(0, 100)
        Logger.i(TAG, "音量设置为 $volumeScale%")
    }

    fun getVolume(): Int = volumeScale

    fun init() {
        try {
            decoderPtr = nativeCreateDecoder(serverSampleRate, channels)
            if (decoderPtr == 0L) {
                Logger.e(TAG, "Failed to create native Opus decoder")
                return
            }
            Logger.i(TAG, "Native Opus decoder created: ${serverSampleRate}Hz, $channels ch")

            val minBuffer = AudioTrack.getMinBufferSize(
                clientSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer * BUFFER_MULTIPLIER, 32768)
            Logger.i(TAG, "minBuffer=$minBuffer, using=$bufferSize")

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                clientSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioTrack init failed")
                audioTrack?.release()
                audioTrack = null
                isPlaying.set(false)
                return
            }

            audioTrack?.play()
            isPlaying.set(true)

            // 写入少量静音预填充 AudioTrack 缓冲，防止部分设备不出声
            try {
                val prime = ByteArray(4096)  // 256ms @16kHz
                audioTrack?.write(prime, 0, prime.size)
            } catch (_: Exception) {}

            Logger.i(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Play thread started")
                while (isPlaying.get()) {
                    try {
                        val data = audioQueue.poll(200, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                            val written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                totalBytesWritten.updateAndGet { it + written }
                            } else if (written < 0) {
                                Logger.e(TAG, "AudioTrack write error: $written")
                            }
                        } else {
                            try {
                                for (i in 0 until 3) {
                                    val ret = audioTrack?.write(silenceFrame, 0, silenceFrame.size)
                                    if (ret != null && ret > 0) {
                                        totalBytesWritten.updateAndGet { it + ret }
                                    } else break
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Logger.e(TAG, "play err: ${e.message}")
                    }
                }
                Logger.i(TAG, "Play thread ended")
            }.apply { start() }

        } catch (e: Exception) {
            Logger.e(TAG, "init failed: ${e.message}")
        }
    }

    fun decodePacket(opusData: ByteArray, onPcm: ((ByteArray) -> Unit)? = null) {
        if (decoderPtr == 0L || !isPlaying.get()) return

        try {
            val audioPayload = opusData
            val pcmShorts = ShortArray(serverFrameSamples)
            val samples = nativeDecode(decoderPtr, audioPayload, pcmShorts, serverFrameSamples)

            if (samples > 0) {
                val t0 = System.nanoTime()
                val pcmResampled = resamplePcm(pcmShorts, samples, serverSampleRate, clientSampleRate)
                val resampleUs = (System.nanoTime() - t0) / 1000
                if (resampleUs > 5000) {
                    Logger.w(TAG, "resample slow: ${resampleUs}us (samples=$samples)")
                }

                val pcmLimiter = applyLimiter(pcmResampled)

                // 应用音量缩放（参考 ESP32 audio_codec.cc SetOutputVolume）
                val pcmWithVolume = if (volumeScale < 100) {
                    applyVolume(pcmLimiter, volumeScale)
                } else {
                    pcmLimiter
                }

                val byteBuffer = ByteBuffer.allocate(pcmWithVolume.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(pcmWithVolume)
                val pcmBytes = byteBuffer.array()

                onPcm?.invoke(pcmBytes)

                if (audioQueue.remainingCapacity() > 0) {
                    audioQueue.offer(pcmBytes)
                    pendingDecodePackets.incrementAndGet()
                } else {
                    Logger.w(TAG, "queue full")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "decode err: ${e.message}")
        }
    }

    private fun resamplePcm(input: ShortArray, inputSamples: Int, inputRate: Int, outputRate: Int): ShortArray {
        if (inputRate == outputRate) {
            return input.copyOf(inputSamples)
        }

        val outputLength = (inputSamples.toDouble() * outputRate / inputRate).toInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = i * inputRate.toDouble() / outputRate
            val srcIndexInt = srcIndex.toInt()
            val frac = srcIndex - srcIndexInt

            if (srcIndexInt >= inputSamples - 1) {
                output[i] = input[inputSamples - 1]
            } else {
                val s0 = input[srcIndexInt].toInt()
                val s1 = input[srcIndexInt + 1].toInt()
                val resampled = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32767, 32767)
                output[i] = resampled.toShort()
            }
        }

        return output
    }

    private fun applyLimiter(pcm: ShortArray): ShortArray {
        val HARD_CLIP = Short.MAX_VALUE.toInt()

        var peak = 0
        for (v in pcm) {
            val abs = abs(v.toInt())
            if (abs > peak) peak = abs
        }

        if (peak <= HARD_CLIP) {
            val result = ShortArray(pcm.size)
            for (i in pcm.indices) {
                result[i] = pcm[i].toInt().coerceIn(-HARD_CLIP, HARD_CLIP).toShort()
            }
            return result
        }

        val ratio = HARD_CLIP.toDouble() / peak
        val result = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            val compressed = if (s >= 0) {
                ((s * ratio) + (s - s * ratio) * kotlin.math.sqrt(s.toDouble() / peak)).toInt()
            } else {
                -((abs(s) * ratio) + (abs(s) - abs(s) * ratio) * kotlin.math.sqrt(abs(s).toDouble() / peak)).toInt()
            }
            result[i] = compressed.coerceIn(-HARD_CLIP, HARD_CLIP).toShort()
        }
        return result
    }

    /**
     * 应用音量缩放
     * 参考 ESP32 audio_codec.cc 音量处理
     * @param pcm PCM样本数组（16-bit）
     * @param volume 音量 0-100
     */
    private fun applyVolume(pcm: ShortArray, volume: Int): ShortArray {
        if (volume <= 0) return ShortArray(pcm.size)  // 静音
        if (volume >= 100) return pcm
        val scale = volume.toFloat() / 100f
        val result = ShortArray(pcm.size)
        for (i in pcm.indices) {
            result[i] = (pcm[i].toInt() * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
        return result
    }

    /**
     * 获取AudioTrack缓冲中尚未播放的总字节数（warmup + 音频 + 静音）
     * 全部计入，因为 playbackHeadPosition 包含所有已播放字节
     */
    fun getRemainingBytes(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val playedFrames = track.playbackHeadPosition.toLong()
            val playedBytes = playedFrames * 2L * channels
            (totalBytesWritten.get() - playedBytes).coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
    }
    /**
     * 等待音频缓冲排空（队列写入 + AudioTrack播放）
     */
    fun drainAudio(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            // 等待队列排空
            while (System.currentTimeMillis() < deadline) {
                if (audioQueue.isEmpty()) break
                Thread.sleep(30)
            }
            // 等待 AudioTrack 播放完剩余数据
            while (System.currentTimeMillis() < deadline) {
                val remaining = getRemainingBytes()
                if (remaining <= 0) break
                Thread.sleep(30)
            }
            return getRemainingBytes() <= 0
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 等待解码队列自然排空（不强制 flush AudioTrack）
     * 用于 SPEAKING→IDLE 自然切换：让已解码的 PCM 数据继续播放完毕
     * 参考 ESP32 WaitForPlaybackQueueEmpty()
     */
    fun waitForQueueEmpty(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (audioQueue.isEmpty() && pendingDecodePackets.get() == 0) return true
            Thread.sleep(20)
        }
        return false
    }

    fun release() {
        isPlaying.set(false)
        playThread?.interrupt()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            if (decoderPtr != 0L) {
                nativeDestroyDecoder(decoderPtr)
                decoderPtr = 0L
            }
        } catch (e: Exception) {
            Logger.e(TAG, "release err: ${e.message}")
        }
    }

    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(decoderPtr: Long, input: ByteArray, output: ShortArray, maxFrameSize: Int): Int
    private external fun nativeDestroyDecoder(decoderPtr: Long)
}
