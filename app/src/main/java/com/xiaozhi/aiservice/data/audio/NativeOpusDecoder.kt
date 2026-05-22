package com.xiaozhi.aiservice.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import com.xiaozhi.aiservice.utils.Logger
import com.xiaozhi.aiservice.BuildConfig
import kotlinx.coroutines.delay
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
        // BUFFER_MULTIPLIER: 参考 ESP32 tiny I2S 缓冲设计
        // ESP32 I2S 缓冲仅 128-512 样本（4-16ms）。安卓需稍大缓冲防 OPPO/MTK underrun，
        // 但 32x 的 3.84s 缓冲引入了巨大播放延迟。用 8x ≈ 1s 平衡延迟与稳定性。
        // 群聊 bot 切换间隙可能长达数秒无新音频，AudioFlinger 在 6s 超时后会移除
        // 因 underrun 被禁用的 track；8x 缓冲 + take() 阻塞写入可保证数据流畅。
        private const val BUFFER_MULTIPLIER = 8
        private const val MAX_QUEUE_SIZE = 200
        // 播放线程: poll(500ms) 等待音频数据，超时写少量静音帧保活
        // 参考 ESP32 AudioOutputTask: condition_variable::wait() 直到音频队列非空
        // 但 Android AudioFlinger 约 3-4s 后会将空闲 track 因 underrun 超时移除，
        // 需要空闲静音防 AudioTrack 被禁用。每 1.5s 写 1KB 静音 ≈ 32ms @16kHz，
        // 远少于原 500ms/2KB 的激进写入（30s 空闲 ≈ 160ms vs 原 120KB 静音）

        private var audioManager: AudioManager? = null
        private var focusRequest: AudioFocusRequest? = null

        fun initAudioManager(context: Context) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            // 预创建 AudioFocusRequest（复用同一个，避免自引用编译问题）
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        Logger.w(TAG, "AudioFocus lost($focusChange)，重新申请")
                        // 焦点丢失时重新申请，防止 OPPO 系统路由切换后无声
                        focusRequest?.let {
                            audioManager?.abandonAudioFocusRequest(it)
                            audioManager?.requestAudioFocus(it)
                        }
                    }
                }
                .build()
        }

        /**
         * 请求音频焦点，OPPO/MTK 设备上必须显式申请焦点才有声音
         * 使用 AUDIOFOCUS_GAIN（完全获取焦点，其他音频暂停）
         */
        private fun requestAudioFocus() {
            try {
                focusRequest?.let { audioManager?.requestAudioFocus(it) }
                Logger.i(TAG, "AudioFocus requested (AUDIOFOCUS_GAIN)")
            } catch (e: Exception) {
                Logger.w(TAG, "requestAudioFocus failed: ${e.message}")
            }
        }
    }

    private var decoderPtr: Long = 0
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    val isReleased: Boolean get() = decoderPtr == 0L && audioTrack == null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    private val totalBytesWritten = AtomicLong(0L)

    private val serverFrameSamples = serverSampleRate * 60 / 1000

    private val pendingDecodePackets = AtomicInteger(0)
    /** 最后一次成功的音频播放时间戳，用于空闲超时检测 */
    private val lastAudioActivityMs = AtomicLong(System.currentTimeMillis())
    // 每次 restartPlayback 后重置，用于调试首次解码
    private val packetsSinceRestart = AtomicInteger(0)

    /**
     * 创建 AudioTrack（使用 Builder 模式，消除 deprecated 警告）
     */
    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(clientSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 强制排空播放缓冲：清空队列 + 暂停+flush+恢复
     * 不要完全停止 AudioTrack（OPPO/MTK 上 AudioTrack 重建可能导致无声）
     * 参考 ESP32 AudioService::ResetDecoder() — 清空所有队列并重置解码器
     * 用于 SPEAKING→LISTENING 切换时，立即停止残留音频播放
     */
    fun flushAudio() {
        Logger.i(TAG, "flushAudio: 强制排空播放缓冲")
        // 清空 Opus 解码队列（参考 ESP32 ResetDecoder: audio_decode_queue_.clear()）
        audioQueue.clear()
        pendingDecodePackets.set(0)

        // flush AudioTrack 硬件缓冲（仅暂停+清空，不释放 AudioTrack）
        // 注意：不写入预填静音——写入静音会排在实际音频前输出，引入额外延迟
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
        lastAudioActivityMs.set(System.currentTimeMillis())
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

    /** 获取解码器使用的服务器采样率 */
    fun getServerSampleRate(): Int = serverSampleRate

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

            // 先请求音频焦点（OPPO/MTK 必须显式申请，否则第二个 AudioTrack 无声）
            requestAudioFocus()

            audioTrack = createAudioTrack(bufferSize)

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioTrack init failed")
                audioTrack?.release()
                audioTrack = null
                isPlaying.set(false)
                return
            }

            Logger.i(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            // 先写预填静音再 play()，防止空缓冲启动导致立即 underrun（华为等设备不恢复）
            try { audioTrack?.write(ByteArray(3200), 0, 3200) } catch (_: Exception) {}
            audioTrack?.play()
            isPlaying.set(true)

            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Play thread started")
                // 保活静音缓冲，每 3s 写 500B ≈ 15ms @16kHz，占空比 0.5%，
                // 防止 AudioFlinger 因 underrun 超时移除 AudioTrack
                val keepAliveSilence = ByteArray(500)
                var keepAliveTimer = 0L
                while (isPlaying.get()) {
                    try {
                        val data = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            audioTrack?.play()
                            lastAudioActivityMs.set(System.currentTimeMillis())
                            keepAliveTimer = 0L
                            pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                            var written = audioTrack?.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                totalBytesWritten.updateAndGet { it + written }
                            }
                            while (isPlaying.get()) {
                                val next = audioQueue.poll()
                                if (next == null) break
                                audioTrack?.play()
                                written = audioTrack?.write(next, 0, next.size) ?: -1
                                if (written > 0) {
                                    totalBytesWritten.updateAndGet { it + written }
                                }
                                pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                                lastAudioActivityMs.set(System.currentTimeMillis())
                            }
                        } else {
                            // poll 超时：空闲时每 3s 写 500B 保活，防 AudioFlinger 杀 track
                            val idleMs = System.currentTimeMillis() - lastAudioActivityMs.get()
                            if (idleMs > keepAliveTimer + 3000) {
                                keepAliveTimer = idleMs
                                try { audioTrack?.write(keepAliveSilence, 0, keepAliveSilence.size) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Logger.e(TAG, "play err: ${e.message}")
                    }
                }
                isPlaying.set(false)
                Logger.i(TAG, "Play thread ended")
            }.apply { start() }

        } catch (e: Exception) {
            Logger.e(TAG, "init failed: ${e.message}")
        }
    }

    /**
     * 惰性重启播放（省电优化）
     *
     * 当播放线程因空闲超时自动停止后，新音频到达时重建 AudioTrack 和播放线程。
     * 不重新创建 native Opus 解码器（decoderPtr 复用），避免不必要的 JNI 开销。
     *
     * 对应 ESP32 ResumeVoicePlayback() 语义
     */
    private fun restartPlayback() {
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                clientSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer * BUFFER_MULTIPLIER, 32768)

            // 先请求音频焦点（OPPO/MTK 必须显式申请，否则第二个 AudioTrack 无声）
            requestAudioFocus()

            val track = createAudioTrack(bufferSize)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Logger.e(TAG, "restartPlayback: AudioTrack 初始化失败")
                track.release()
                return
            }

            // 释放旧的 AudioTrack（如果有）
            try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
            // 中断旧播放线程（take() 阻塞在 LinkedBlockingQueue，需要 interrupt 唤醒）
            playThread?.interrupt()
            playThread?.join(500)
            audioTrack = track
            audioQueue.clear()
            pendingDecodePackets.set(0)
            packetsSinceRestart.set(0)
            totalBytesWritten.set(0L)
            lastAudioActivityMs.set(System.currentTimeMillis())

            // 先写预填静音再 play()，与 init() 保持一致
            try { track.write(ByteArray(3200), 0, 3200) } catch (_: Exception) {}
            track.play()
            isPlaying.set(true)

            // 重建播放线程
            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Play thread restarted")
                val keepAliveSilence = ByteArray(500)
                var keepAliveTimer = 0L
                while (isPlaying.get()) {
                    try {
                        val data = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            audioTrack?.play()
                            lastAudioActivityMs.set(System.currentTimeMillis())
                            keepAliveTimer = 0L
                            pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                            var written = track.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                totalBytesWritten.updateAndGet { it + written }
                            }
                            while (isPlaying.get()) {
                                val next = audioQueue.poll()
                                if (next == null) break
                                audioTrack?.play()
                                written = track.write(next, 0, next.size) ?: -1
                                if (written > 0) {
                                    totalBytesWritten.updateAndGet { it + written }
                                }
                                pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                                lastAudioActivityMs.set(System.currentTimeMillis())
                            }
                        } else {
                            val idleMs = System.currentTimeMillis() - lastAudioActivityMs.get()
                            if (idleMs > keepAliveTimer + 3000) {
                                keepAliveTimer = idleMs
                                try { audioTrack?.write(keepAliveSilence, 0, keepAliveSilence.size) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Logger.e(TAG, "play err: ${e.message}")
                    }
                }
                isPlaying.set(false)
                Logger.i(TAG, "Play thread restarted ended")
            }.apply { start() }

            Logger.i(TAG, "restartPlayback 完成（${clientSampleRate}Hz mono）")
        } catch (e: Exception) {
            Logger.e(TAG, "restartPlayback 失败: ${e.message}")
            isPlaying.set(false)
        }
    }

    fun decodePacket(opusData: ByteArray, onPcm: ((ByteArray) -> Unit)? = null) {
        if (decoderPtr == 0L) return

        // 惰性重启：播放线程因空闲超时停止后，有新音频到达时自动恢复
        // 使用 compareAndSet 确保只重启一次，避免并发竞态（多个音频包同时触发 restartPlayback）
        if (isPlaying.compareAndSet(false, true)) {
            Logger.i(TAG, "播放线程已停止，新音频到达，自动重启播放")
            restartPlayback()
            if (!isPlaying.get()) return  // 重启失败
        } else if (!isPlaying.get()) {
            return  // 有其他线程正在重启，跳过本包
        }

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
                // 记录解码次数（用于调试：每次重启后第一个包打日志）
                if (packetsSinceRestart.incrementAndGet() <= 1) {
                    Logger.i(TAG, "decodePacket 解码成功 (samples=$samples, resample=${resampleUs}us, queueSize=${audioQueue.size})")
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

        // Catmull-Rom Hermite 立方插值，比线性插值更平滑、频谱保持更好
        // 参考: https://en.wikipedia.org/wiki/Cubic_Hermite_spline
        for (i in 0 until outputLength) {
            val srcIndex = i * inputRate.toDouble() / outputRate
            val srcIndexInt = srcIndex.toInt()
            val frac = srcIndex - srcIndexInt

            if (srcIndexInt <= 0) {
                // 左边界：线性插值
                val s0 = input[0].toInt()
                val s1 = if (srcIndexInt + 1 < inputSamples) input[1].toInt() else s0
                output[i] = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
            } else if (srcIndexInt >= inputSamples - 2) {
                // 右边界：线性插值
                val s0 = input[inputSamples - 2].toInt()
                val s1 = input[inputSamples - 1].toInt()
                output[i] = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
            } else {
                // Hermite 立方插值
                val m1 = input[srcIndexInt - 1].toInt()
                val a = input[srcIndexInt].toInt()
                val b = input[srcIndexInt + 1].toInt()
                val m2 = input[srcIndexInt + 2].toInt()
                val c0 = a
                val c1 = 0.5f * (b - m1)
                val c2 = m1 - 2.5f * a + 2f * b - 0.5f * m2
                val c3 = 0.5f * (m2 - m1) + 1.5f * (a - b)
                val resampled = ((c3 * frac + c2) * frac + c1) * frac + c0
                output[i] = resampled.toInt().coerceIn(-32768, 32767).toShort()
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

        // 无削峰时直接返回，避免不必要的内存分配
        if (peak <= HARD_CLIP) return pcm

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
     *
     * 注意：此方法为 suspend 函数，应在协程中调用。
     * 内部使用 delay() 避免阻塞调用线程。
     */
    suspend fun waitForQueueEmpty(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (audioQueue.isEmpty() && pendingDecodePackets.get() == 0) return true
            delay(20)
        }
        return false
    }

    /**
     * 等待 AudioTrack 硬件缓冲自然排空。
     * 不强制 flush，让已写入的音频播放完。
     * 用于 drainAndFlushPlayback，在不切音尾的前提下阻止回声。
     */
    suspend fun waitForHardwareDrain(timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = getRemainingBytes()
            if (remaining <= 0) return true
            val estMs = remaining * 1000L / (clientSampleRate * 2 * channels)
            // 剩很少了直接等完，省一次轮询
            if (estMs < 30) {
                delay(estMs + 20)
                return getRemainingBytes() <= 0
            }
            delay(50)
        }
        return getRemainingBytes() <= 0
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
