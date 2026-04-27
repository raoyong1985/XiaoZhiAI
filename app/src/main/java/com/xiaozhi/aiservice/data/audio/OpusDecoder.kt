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
 * 服务器 24000Hz → AudioTrack 24000Hz → Android AudioFlinger 2:1 上采样到原生 48000Hz
 * PCM 固定增益 ×8：原始峰值约 ±500-2000，×8 后约 ±4000-16000（12-50% 满幅）
 */
class OpusDecoder(
    private val serverSampleRate: Int = 24000,
    private val serverChannels: Int = 1
) {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MAX_QUEUE_SIZE = 50
        // 固定增益 ×8：原始峰值约 ±500-2000，×8 后约 ±4000-16000，无爆音风险
        private const val FIXED_GAIN = 8
    }

    private var decoder: OpusDecoder? = null
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 服务器帧参数：60ms 帧
    private val serverMaxFrameSize = serverSampleRate * 60 / 1000  // 1440 samples @ 24kHz
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
            // 60ms @ 24kHz = 1440 samples * 2 bytes * 4 倍缓冲
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
                val pcmBytes = ByteArray(samples * 2)
                var minS: Short = 0; var maxS: Short = 0
                for (i in 0 until samples) {
                    val raw = pcmShorts[i].toInt()
                    val boosted = (raw * FIXED_GAIN).coerceIn(-32768, 32767)
                    if (boosted < minS) minS = boosted.toShort()
                    if (boosted > maxS) maxS = boosted.toShort()
                    pcmBytes[i * 2] = (boosted and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((boosted shr 8) and 0xFF).toByte()
                }
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
