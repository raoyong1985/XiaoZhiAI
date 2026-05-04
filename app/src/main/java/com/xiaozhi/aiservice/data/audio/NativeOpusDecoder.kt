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
import kotlin.math.max
import kotlin.math.abs

/**
 * 使用 native libopus 解码 + flutter_pcm_player 风格的 AudioTrack
 * 
 * Flutter 声音正常的架构：
 * 1. opus_dart (libopus 原生) 解码 → 24kHz PCM
 * 2. 线性插值 24kHz→16kHz 重采样
 * 3. AGC 处理
 * 4. flutter_pcm_player (STREAM_MUSIC, 无 AudioAttributes, minBuffer)
 * 
 * 这里复现第4步：用 native AudioTrack 匹配 flutter_pcm_player 的配置
 * 
 * 16kHz 输出，与 Flutter 保持一致
 */
class NativeOpusDecoder(
    private val serverSampleRate: Int = 24000,
    private val clientSampleRate: Int = 16000,
    private val channels: Int = 1
) {
    companion object {
        private const val TAG = "NativeOpusDecoder"
        private const val MAX_QUEUE_SIZE = 400      // 队列缓冲网络抖动
        private const val BUFFER_MULTIPLIER = 32    // 32倍 minBuffer ≈ 2s，吸收网络抖动避免 underrun
    }

    private var decoderPtr: Long = 0  // native decoder pointer
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 帧大小
    private val serverFrameSamples = serverSampleRate * 60 / 1000  // 60ms @ 24kHz = 1440
    // 静音帧 (200ms @ 16kHz = 3200 samples = 6400 bytes)，队列空时填充防 underrun
    private val silenceFrame = ByteArray(6400)

    init {
        System.loadLibrary("native_opus_decoder")
    }

    fun init() {
        try {
            decoderPtr = nativeCreateDecoder(serverSampleRate, channels)
            if (decoderPtr == 0L) {
                Logger.e(TAG, "Failed to create native Opus decoder")
                return
            }
            Logger.i(TAG, "Native Opus decoder created: ${serverSampleRate}Hz, $channels ch")

            // ===== 匹配 flutter_pcm_player 的 AudioTrack 配置 =====
            // PcmPlayer.java uses:
            //   new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, format, mBufferSize, AudioTrack.MODE_STREAM)
            //   mBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, format)
            // NO AudioAttributes, NO special usage
            
            val minBuffer = AudioTrack.getMinBufferSize(
                clientSampleRate,  // 16kHz (与 Flutter 一致)
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 16倍缓冲：约 960ms @ 16kHz，足以掩盖网络抖动和GC暂停
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
                Logger.e(TAG, "AudioTrack 初始化失败")
                audioTrack?.release()
                audioTrack = null
                return
            }

            audioTrack?.play()
            isPlaying.set(true)

            warmUpPlayer()

            Logger.i(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Play thread started")
                while (isPlaying.get()) {
                    try {
                        // poll 带超时，队列空时写静音避免 underrun
                        val data = audioQueue.poll(200, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            val written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written < 0) {
                                Logger.e(TAG, "AudioTrack write error: $written")
                            }
                        } else {
                            // 队列空时写 1 帧静音 (60ms @ 16kHz)，防止 AudioTrack underrun
                            try {
                                audioTrack?.write(silenceFrame, 0, silenceFrame.size)
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

    private fun warmUpPlayer() {
        try {
            // 预填充 6帧静音（360ms），让 AudioTrack 有足够数据开始播放不卡顿
            val warmupFrames = 6
            val warmupSamplesPerFrame = clientSampleRate * 60 / 1000  // 960 @ 16kHz
            val warmupData = ByteArray(warmupSamplesPerFrame * warmupFrames * 2)
            audioTrack?.write(warmupData, 0, warmupData.size)
            Logger.i(TAG, "warmup ${warmupFrames} frames ok (${warmupData.size} bytes)")
        } catch (e: Exception) {
            Logger.e(TAG, "warmup failed: ${e.message}")
        }
    }

    /**
     * 接收 Opus 数据包，解码并播放
     * 协议头已在 XiaoZhiBackendService.handleBinaryMessage 中剥离，
     * 这里收到的 data 是纯 Opus payload，直接送 native 解码器
     */
    fun decodePacket(opusData: ByteArray, onPcm: ((ByteArray) -> Unit)? = null) {
        if (decoderPtr == 0L || !isPlaying.get()) return

        try {
            val audioPayload = opusData  // 数据已干净，直接使用

            // 分配 PCM 缓冲区
            val pcmShorts = ShortArray(serverFrameSamples)

            // 调用 native Opus 解码
            val samples = nativeDecode(decoderPtr, audioPayload, pcmShorts, serverFrameSamples)

            if (samples > 0) {
                // ===== 重采样 24kHz → 16kHz（线性插值，与 Flutter 一致） =====
                val t0 = System.nanoTime()
                val pcmResampled = resamplePcm(pcmShorts, samples, serverSampleRate, clientSampleRate)
                val resampleUs = (System.nanoTime() - t0) / 1000
            if (resampleUs > 5000) {
                     Logger.w(TAG, "resample 慢: ${resampleUs}us (samples=$samples)")
                 }

                // ===== AGC 处理（与 Flutter 一致） =====
                val pcmLimiter = applyLimiter(pcmResampled)

                // 转换为字节
                val byteBuffer = ByteBuffer.allocate(pcmLimiter.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(pcmLimiter)
                val pcmBytes = byteBuffer.array()

                // PCM回调：用于后续重编码提高转发质量
                onPcm?.invoke(pcmBytes)

                if (audioQueue.remainingCapacity() > 0) {
                    audioQueue.offer(pcmBytes)
                } else {
                    Logger.w(TAG, "queue full")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "decode err: ${e.message}")
        }
    }

    /**
     * 线性插值重采样（与 Flutter audio_util.dart 完全一致）
     */
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

    /**
     * 软限幅器（替代原 AGC）——只在峰值超限时才压缩，减少不必要的处理
     * 避免 quiet 帧被放大导致 noise 放大
     */
    private fun applyLimiter(pcm: ShortArray): ShortArray {
        val HARD_CLIP = Short.MAX_VALUE.toInt()

        // 找峰值
        var peak = 0
        for (v in pcm) {
            val abs = abs(v.toInt())
            if (abs > peak) peak = abs
        }

        // 峰值正常 → 直接 clamp 到 30000，不做任何增益调整
        if (peak <= HARD_CLIP) {
            val result = ShortArray(pcm.size)
            for (i in pcm.indices) {
                result[i] = pcm[i].toInt().coerceIn(-HARD_CLIP, HARD_CLIP).toShort()
            }
            return result
        }

        // 峰值过高 → 软压缩（避免硬 clip 产生的谐波）
        val ratio = HARD_CLIP.toDouble() / peak
        val result = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            // 软压缩：对峰值以下的部分做 sqrt 压缩
            val compressed = if (s >= 0) {
                ((s * ratio) + (s - s * ratio) * kotlin.math.sqrt(s.toDouble() / peak)).toInt()
            } else {
                -((abs(s) * ratio) + (abs(s) - abs(s) * ratio) * kotlin.math.sqrt(abs(s).toDouble() / peak)).toInt()
            }
            result[i] = compressed.coerceIn(-HARD_CLIP, HARD_CLIP).toShort()
        }
        return result
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

    // ===== JNI 原生 Opus 接口 =====
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(decoderPtr: Long, input: ByteArray, output: ShortArray, maxFrameSize: Int): Int
    private external fun nativeDestroyDecoder(decoderPtr: Long)
}
