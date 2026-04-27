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
import kotlin.math.abs

/**
 * 测试版：24kHz 直通，完全不做任何处理
 * 服务器 24kHz Opus → 解码 → AudioTrack(24kHz)
 * 用于排查声音问题
 */
class OpusDecoder(
    private val serverSampleRate: Int = 24000,
    private val serverChannels: Int = 1
) {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MAX_QUEUE_SIZE = 50
    }

    private var decoder: OpusDecoder? = null
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    // 60ms 帧 @ 24kHz = 1440 samples
    private val serverMaxFrameSize = serverSampleRate * 60 / 1000
    private val serverPcmBufferSamples = serverMaxFrameSize * serverChannels

    fun init() {
        try {
            decoder = OpusDecoder(serverSampleRate, serverChannels)
            Log.i(TAG, "Decoder init: ${serverSampleRate}Hz")

            val minBuffer = AudioTrack.getMinBufferSize(
                serverSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 60ms * 24kHz * 2bytes * 10帧
            val frameSize = serverMaxFrameSize * 2
            val actualBufferSize = max(minBuffer, frameSize * 10)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

            warmUpPlayer()

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

    private fun warmUpPlayer() {
        try {
            // 10ms warmup
            val warmupSamples = serverSampleRate / 100
            val silentFrame = ShortArray(warmupSamples)
            val byteBuffer = java.nio.ByteBuffer.allocate(silentFrame.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in silentFrame) byteBuffer.putShort(s)
            audioTrack?.write(byteBuffer.array(), 0, byteBuffer.array().size)
            Log.i(TAG, "warmup ok")
        } catch (e: Exception) {
            Log.e(TAG, "warmup failed: ${e.message}")
        }
    }

    fun decodePacket(opusData: ByteArray) {
        if (decoder == null || !isPlaying.get()) return

        try {
            val pcmShorts = ShortArray(serverPcmBufferSamples)
            val samples = decoder!!.decode(opusData, 0, opusData.size, pcmShorts, 0, serverMaxFrameSize, false)

            if (samples > 0) {
                // 完全直通：不重采样，不 AGC
                val pcmData = pcmShorts.copyOf(samples)

                var peak = 0
                for (v in pcmData) {
                    val absv = abs(v.toInt())
                    if (absv > peak) peak = absv
                }
                if (peak > 0) {
                    Log.v(TAG, "PCM ${pcmData.size} peak=$peak")
                }

                // 写入 AudioTrack
                val byteBuffer = java.nio.ByteBuffer.allocate(pcmData.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in pcmData) {
                    byteBuffer.putShort(s)
                }
                val pcmBytes = byteBuffer.array()

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
