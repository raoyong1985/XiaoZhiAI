package com.xiaozhi.aiservice.data.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
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
        private const val MAX_QUEUE_SIZE = 100
    }

    private var decoderPtr: Long = 0  // native decoder pointer
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 帧大小
    private val serverFrameSamples = serverSampleRate * 60 / 1000  // 60ms
    private val clientFrameSamples = clientSampleRate * 60 / 1000  // 60ms @ 16kHz = 960

    init {
        System.loadLibrary("native_opus_decoder")
    }

    fun init() {
        try {
            decoderPtr = nativeCreateDecoder(serverSampleRate, channels)
            if (decoderPtr == 0L) {
                Log.e(TAG, "Failed to create native Opus decoder")
                return
            }
            Log.i(TAG, "Native Opus decoder created: ${serverSampleRate}Hz, $channels ch")

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
            // 4倍缓冲防止卡顿（minBuffer 约 480-960 bytes 太小）
            val bufferSize = maxOf(minBuffer * 4, 4096)
            Log.i(TAG, "minBuffer=$minBuffer, using=$bufferSize")

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                clientSampleRate,  // 16kHz，与 Flutter 一致
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,        // 4倍缓冲防止卡顿
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()
            isPlaying.set(true)

            warmUpPlayer()

            Log.i(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            playThread = Thread {
                Log.i(TAG, "Play thread started")
                while (isPlaying.get()) {
                    try {
                        val data = audioQueue.take()
                        if (data.isNotEmpty()) {
                            val written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                Log.v(TAG, "write ${data.size}B ok")
                            }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "err: ${e.message}")
                    }
                }
                Log.i(TAG, "Play thread ended")
            }.apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun warmUpPlayer() {
        try {
            // 发送静音帧预热（10ms）
            val warmupSamples = clientSampleRate / 100
            val silentFrame = ShortArray(warmupSamples)
            val byteBuffer = ByteBuffer.allocate(silentFrame.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (s in silentFrame) byteBuffer.putShort(s)
            audioTrack?.write(byteBuffer.array(), 0, byteBuffer.array().size)
            Log.i(TAG, "warmup ok")
        } catch (e: Exception) {
            Log.e(TAG, "warmup failed: ${e.message}")
        }
    }

    /**
     * 接收 Opus 数据包，解码并播放
     */
    fun decodePacket(opusData: ByteArray) {
        if (decoderPtr == 0L || !isPlaying.get()) return

        try {
            // 分配 PCM 缓冲区
            val pcmShorts = ShortArray(serverFrameSamples)

            // 调用 native Opus 解码
            val samples = nativeDecode(decoderPtr, opusData, pcmShorts, serverFrameSamples)

            if (samples > 0) {
                // ===== 重采样 24kHz → 16kHz（线性插值，与 Flutter 一致） =====
                val pcmResampled = resamplePcm(pcmShorts, samples, serverSampleRate, clientSampleRate)

                // ===== AGC 处理（与 Flutter 一致） =====
                val pcmAgced = applyAgc(pcmResampled)

                // 转换为字节
                val byteBuffer = ByteBuffer.allocate(pcmAgced.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcmAgced) byteBuffer.putShort(s)
                val pcmBytes = byteBuffer.array()

                if (audioQueue.remainingCapacity() > 0) {
                    audioQueue.offer(pcmBytes)
                } else {
                    Log.w(TAG, "queue full")
                }

                // 调试：打印峰值
                var peak = 0
                for (v in pcmAgced) {
                    val absv = abs(v.toInt())
                    if (absv > peak) peak = absv
                }
                Log.v(TAG, "PCM out ${pcmAgced.size} peak=$peak")
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode err: ${e.message}")
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
     * AGC 处理（与 Flutter audio_util.dart 完全一致）
     */
    private fun applyAgc(pcm: ShortArray): ShortArray {
        val TARGET_PEAK = 20000
        val SOFT_CLIP_THRESHOLD = 26000
        val SOFT_CLIPCEIL = 30000
        val QUIET_THRESHOLD = 5000

        var framePeak = 0
        for (v in pcm) {
            val abs = abs(v.toInt())
            if (abs > framePeak) framePeak = abs
        }

        val result = ShortArray(pcm.size)

        if (framePeak > SOFT_CLIP_THRESHOLD) {
            // 峰值过高 → 软压缩
            for (i in pcm.indices) {
                val raw = pcm[i].toInt()
                val rawAbs = abs(raw)
                val limited = if (rawAbs <= SOFT_CLIP_THRESHOLD) {
                    rawAbs.toDouble()
                } else {
                    val over = (rawAbs - SOFT_CLIP_THRESHOLD).toDouble()
                    val overRange = (framePeak - SOFT_CLIP_THRESHOLD).toDouble()
                    val ratio = (over / overRange).coerceIn(0.0, 1.0)
                    SOFT_CLIP_THRESHOLD + (TARGET_PEAK - SOFT_CLIP_THRESHOLD) * kotlin.math.sqrt(ratio)
                }
                val clamped = (kotlin.math.sign(raw.toDouble()) * limited).toInt().coerceIn(-30000, 30000)
                result[i] = clamped.toShort()
            }
        } else if (framePeak < QUIET_THRESHOLD && framePeak > 0) {
            // 峰值过低 → 归一化
            val gain = (TARGET_PEAK.toDouble() / framePeak).coerceIn(1.0, 4.0)
            for (i in pcm.indices) {
                val boosted = (pcm[i].toInt() * gain).toInt().coerceIn(-30000, 30000)
                result[i] = boosted.toShort()
            }
        } else {
            // 正常帧 → 保持原样，clamp 到 [-30000, 30000]
            for (i in pcm.indices) {
                result[i] = pcm[i].toInt().coerceIn(-30000, 30000).toShort()
            }
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
            Log.e(TAG, "release err: ${e.message}")
        }
    }

    // ===== JNI 原生 Opus 接口 =====
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(decoderPtr: Long, input: ByteArray, output: ShortArray, maxFrameSize: Int): Int
    private external fun nativeDestroyDecoder(decoderPtr: Long)
}
