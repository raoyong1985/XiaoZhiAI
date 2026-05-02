package com.xiaozhi.aiservice.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.xiaozhi.aiservice.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.abs

/**
 * 条件日志：仅在 debug 版本输出
 */
private fun debugLog(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
}

private fun debugLogE(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.e(tag, msg)
}

private fun debugLogW(tag: String, msg: String) {
    if (BuildConfig.ENABLE_DEBUG_LOG) Log.w(tag, msg)
}

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
        private const val MAX_QUEUE_SIZE = 200      // 增大队列缓冲网络抖动
        private const val BUFFER_MULTIPLIER = 16    // 16倍 minBuffer ≈ 960ms
    }

    private var decoderPtr: Long = 0  // native decoder pointer
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 帧大小
    private val serverFrameSamples = serverSampleRate * 60 / 1000  // 60ms @ 24kHz = 1440

    init {
        System.loadLibrary("native_opus_decoder")
    }

    fun init() {
        try {
            decoderPtr = nativeCreateDecoder(serverSampleRate, channels)
            if (decoderPtr == 0L) {
                debugLogE(TAG, "Failed to create native Opus decoder")
                return
            }
            debugLog(TAG, "Native Opus decoder created: ${serverSampleRate}Hz, $channels ch")

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
            debugLog(TAG, "minBuffer=$minBuffer, using=$bufferSize")

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                clientSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                debugLogE(TAG, "AudioTrack 初始化失败")
                audioTrack?.release()
                audioTrack = null
                return
            }

            audioTrack?.play()
            isPlaying.set(true)

            warmUpPlayer()

            debugLog(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                debugLog(TAG, "Play thread started")
                while (isPlaying.get()) {
                    try {
                        // poll 带超时，队列空时不阻塞音频线程
                        val data = audioQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        if (data.isNotEmpty()) {
                            val written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written < 0) {
                                debugLogE(TAG, "AudioTrack write error: $written")
                            }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        debugLogE(TAG, "play err: ${e.message}")
                    }
                }
                debugLog(TAG, "Play thread ended")
            }.apply { start() }

        } catch (e: Exception) {
            debugLogE(TAG, "init failed: ${e.message}")
        }
    }

    private fun warmUpPlayer() {
        try {
            // 预填充 3帧静音（180ms），让 AudioTrack 有足够数据开始播放不卡顿
            val warmupFrames = 3
            val warmupSamplesPerFrame = clientSampleRate * 60 / 1000  // 960 @ 16kHz
            val warmupData = ByteArray(warmupSamplesPerFrame * warmupFrames * 2)
            audioTrack?.write(warmupData, 0, warmupData.size)
            debugLog(TAG, "warmup ${warmupFrames} frames ok (${warmupData.size} bytes)")
        } catch (e: Exception) {
            debugLogE(TAG, "warmup failed: ${e.message}")
        }
    }

    /**
     * 接收 Opus 数据包，解码并播放
     * 协议头已在 XiaoZhiBackendService.handleBinaryMessage 中剥离，
     * 这里收到的 data 是纯 Opus payload，直接送 native 解码器
     */
    fun decodePacket(opusData: ByteArray) {
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
                     debugLogW(TAG, "resample 慢: ${resampleUs}us (samples=$samples)")
                 }

                // ===== AGC 处理（与 Flutter 一致） =====
                val pcmLimiter = applyLimiter(pcmResampled)

                // 转换为字节
                val byteBuffer = ByteBuffer.allocate(pcmLimiter.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(pcmLimiter)
                val pcmBytes = byteBuffer.array()

                if (audioQueue.remainingCapacity() > 0) {
                    audioQueue.offer(pcmBytes)
                } else {
                    debugLogW(TAG, "queue full")
                }
            }
        } catch (e: Exception) {
            debugLogE(TAG, "decode err: ${e.message}")
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
            debugLogE(TAG, "release err: ${e.message}")
        }
    }

    // ===== JNI 原生 Opus 接口 =====
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(decoderPtr: Long, input: ByteArray, output: ShortArray, maxFrameSize: Int): Int
    private external fun nativeDestroyDecoder(decoderPtr: Long)
}
