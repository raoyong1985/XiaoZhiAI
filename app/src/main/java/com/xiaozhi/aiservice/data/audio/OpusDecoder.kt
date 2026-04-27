package com.xiaozhi.aiservice.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.concentus.OpusDecoder
import org.concentus.OpusException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Concentus Opus 解码播放
 * 服务器 16000Hz PCM → AudioTrack 16000Hz → Android AudioFlinger 上采样到原生 48000Hz
 * PCM 固定增益 ×2：原始峰值约 ±2000-8000，×2 后约 ±4000-16000，无爆音风险
 */
class OpusDecoder(
    private val serverSampleRate: Int = 16000,
    private val serverChannels: Int = 1
) {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MAX_QUEUE_SIZE = 50
        // 固定增益 ×2：原始峰值约 ±2000-8000，×2 后约 ±4000-16000，无爆音风险
        private const val FIXED_GAIN = 2
    }

    private var decoder: OpusDecoder? = null
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 服务器帧参数：60ms 帧
    private val serverMaxFrameSize = serverSampleRate * 60 / 1000  // 960 samples @ 16kHz, 1440 @ 24kHz
    private val serverPcmBufferSamples = serverMaxFrameSize * serverChannels

    fun init() {
        try {
            decoder = OpusDecoder(serverSampleRate, serverChannels)
            Log.i(TAG, "Decoder: ${serverSampleRate}Hz ${serverChannels}ch maxFrame=$serverMaxFrameSize gain=$FIXED_GAIN")

            val minBuffer = AudioTrack.getMinBufferSize(
                serverSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 60ms @ 16kHz = 960 samples * 2 bytes * 4 倍缓冲
            val frameSize = serverMaxFrameSize * 2
            val actualBufferSize = max(minBuffer, frameSize * 4)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(serverSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(actualBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying.set(true)
            Log.i(TAG, "AudioTrack: ${serverSampleRate}Hz mono buffer=$actualBufferSize")

            playThread = Thread {
                Log.i(TAG, "Play thread started")
                while (isPlaying.get()) {
                    try {
                        val data = audioQueue.take()
                        if (data.isNotEmpty()) {
                            val written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                Log.v(TAG, "write ${data.size}B ok q=${audioQueue.size}")
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

    fun decodePacket(opusData: ByteArray) {
        if (decoder == null || !isPlaying.get()) return

        try {
            val pcmShorts = ShortArray(serverPcmBufferSamples)
            val samples = decoder!!.decode(opusData, 0, opusData.size, pcmShorts, 0, serverMaxFrameSize, false)

            if (samples > 0) {
                // 使用 ByteBuffer 正确处理 16-bit signed PCM little-endian 转换
                val byteBuffer = java.nio.ByteBuffer.allocate(samples * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var minS: Short = 0; var maxS: Short = 0
                for (i in 0 until samples) {
                    val raw = pcmShorts[i].toInt()
                    val boosted = (raw * FIXED_GAIN).coerceIn(-32768, 32767).toShort()
                    if (boosted < minS) minS = boosted
                    if (boosted > maxS) maxS = boosted
                    byteBuffer.putShort(boosted)
                }
                val pcmBytes = byteBuffer.array()
                Log.d(TAG, "decode ${opusData.size}B → ${samples}ss(${serverSampleRate}Hz) gain=$FIXED_GAIN min=${minS.toInt()} max=${maxS.toInt()}")

                if (audioQueue.remainingCapacity() > 0) {
                    audioQueue.offer(pcmBytes)
                } else {
                    Log.w(TAG, "queue full")
                }
            }
        } catch (e: OpusException) {
            Log.e(TAG, "opus err: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}")
        }
    }

    fun release() {
        isPlaying.set(false)
        playThread?.interrupt()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            decoder = null
        } catch (e: Exception) {
            Log.e(TAG, "release err: ${e.message}")
        }
    }
}
