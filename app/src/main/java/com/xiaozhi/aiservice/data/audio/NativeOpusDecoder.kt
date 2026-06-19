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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.abs
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val isPlaying = AtomicBoolean(false)
    val isReleased: Boolean get() = decoderPtr == 0L && audioTrackRef.get() == null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private var playThread: Thread? = null

    @Volatile
    private var released = false
    private val isRestarting = AtomicBoolean(false)

    private val totalBytesWritten = AtomicLong(0L)

    private val serverFrameSamples = serverSampleRate * 60 / 1000

    private val pendingDecodePackets = AtomicInteger(0)
    /** 解码器线程安全锁：保护 nativeDecode、restartPlayback、flushAudio、release 互斥 */
    // Opus C 库解码器非线程安全。群聊中多个 bot 的音频协程并发调用 decodePacket，
    // 必须串行化防止 nativeDecode 内部状态错乱导致输出全零无声
    private val decodeLock = ReentrantLock()
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
        decodeLock.withLock {
            Logger.i(TAG, "flushAudio: 强制排空播放缓冲")
            audioQueue.clear()
            pendingDecodePackets.set(0)

            // flush AudioTrack 硬件缓冲（仅暂停+清空，不释放 AudioTrack）
            // 注意：不写入预填静音——写入静音会排在实际音频前输出，引入额外延迟
            try {
                audioTrackRef.get()?.let { track: AudioTrack ->
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
    }

    /**
     * 在播放线程中重建 AudioTrack。不创建新线程，不操作队列。
     * 用于 AudioTrack 被 AudioFlinger 因 persistent underrun 杀死后的恢复。
     * OPPO/MTK 上 underrun 超时约 1-2s，keepAlive 无法完全阻止，
     * write() 静默失败后通过此方法重建。
     */
    private fun recreateAudioTrack() {
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                clientSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer * BUFFER_MULTIPLIER, 32768)
            val track = createAudioTrack(bufferSize)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Logger.e(TAG, "recreateAudioTrack: AudioTrack 初始化失败")
                track.release()
                return
            }
            try { track.write(ByteArray(3200), 0, 3200) } catch (_: Exception) {}
            track.play()
            // 原子替换：旧的 AudioTrack 被替换后，后续 write() 写入新 track
            val old = audioTrackRef.getAndSet(track)
            try { old?.stop(); old?.release() } catch (_: Exception) {}
            totalBytesWritten.set(0L)
            // 清空队列需持 decodeLock（防止与 decodePacket 竞态）
            decodeLock.withLock { audioQueue.clear() }
            lastAudioActivityMs.set(System.currentTimeMillis())
            Logger.i(TAG, "recreateAudioTrack 完成")
        } catch (e: Exception) {
            Logger.e(TAG, "recreateAudioTrack 失败: ${e.message}")
        }
    }

    // 音量缩放因子：volumeScale / 100，范围 0-100
    // 参考 ESP32 audio_codec.cc SetOutputVolume()
    @Volatile private var volumeScale: Int = initialVolume.coerceIn(0, 100)

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

            audioTrackRef.set(createAudioTrack(bufferSize))

            if (audioTrackRef.get()?.state != AudioTrack.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioTrack init failed")
                audioTrackRef.get()?.release()
                audioTrackRef.set(null)
                isPlaying.set(false)
                return
            }

            Logger.i(TAG, "AudioTrack: ${clientSampleRate}Hz mono STREAM_MUSIC buffer=$minBuffer")

            // 先写预填静音再 play()，防止空缓冲启动导致立即 underrun（华为等设备不恢复）
            try { audioTrackRef.get()?.write(ByteArray(3200), 0, 3200) } catch (_: Exception) {}
            audioTrackRef.get()?.play()
            isPlaying.set(true)

            playThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Play thread started")
                // 保活静音缓冲，每 400ms 写 500B ≈ 15ms @16kHz，占空比 ~3.75%，
                // OPPO/MTK underrun 超时约 1-2s，400ms 间隔确保 track 始终不被超时移除
                val keepAliveSilence = ByteArray(500)
                var keepAliveTimer = System.currentTimeMillis()
                /** 连续写入失败计数：AudioTrack 被系统杀死后重建 */
                var consecutiveWriteFails = 0
                val MAX_WRITE_FAILS = 3
                while (isPlaying.get()) {
                    try {
                        // 检查是否需要在播放前重建 AudioTrack（上次写入失败太多）
                        if (consecutiveWriteFails >= MAX_WRITE_FAILS) {
                            Logger.w(TAG, "AudioTrack 写入失败 $consecutiveWriteFails 次，重建 AudioTrack")
                            recreateAudioTrack()
                            consecutiveWriteFails = 0
                        }
                        val data = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            audioTrackRef.get()?.play()
                            lastAudioActivityMs.set(System.currentTimeMillis())
                            keepAliveTimer = System.currentTimeMillis()
                            pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                            var written = audioTrackRef.get()?.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                totalBytesWritten.updateAndGet { it + written }
                                consecutiveWriteFails = 0
                            } else {
                                consecutiveWriteFails++
                            }
                            while (isPlaying.get()) {
                                val next = audioQueue.poll()
                                if (next == null) break
                                audioTrackRef.get()?.play()
                                written = audioTrackRef.get()?.write(next, 0, next.size) ?: -1
                                if (written > 0) {
                                    totalBytesWritten.updateAndGet { it + written }
                                    consecutiveWriteFails = 0
                                } else {
                                    consecutiveWriteFails++
                                }
                                pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                                lastAudioActivityMs.set(System.currentTimeMillis())
                            }
                        } else {
                            // poll 超时：空闲时每 3s 写 500B 保活，防 AudioFlinger 杀 track
                            val now = System.currentTimeMillis()
                            if (now - keepAliveTimer >= 400) {
                                keepAliveTimer = System.currentTimeMillis()
                                try {
                                    audioTrackRef.get()?.write(keepAliveSilence, 0, keepAliveSilence.size)
                                    consecutiveWriteFails = 0
                                } catch (_: Exception) {
                                    consecutiveWriteFails++
                                }
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
        if (released) return
        if (!isRestarting.compareAndSet(false, true)) {
            Logger.w(TAG, "restartPlayback 已在执行中，跳过")
            return
        }
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
            try { audioTrackRef.get()?.stop(); audioTrackRef.get()?.release() } catch (_: Exception) {}
            // 中断旧播放线程（take() 阻塞在 LinkedBlockingQueue，需要 interrupt 唤醒）
            // 先设 isPlaying=false 确保旧线程退出，join 更可靠
            isPlaying.set(false)
            playThread?.interrupt()
            playThread?.join(1000)
            // join 超时后旧线程可能还活着，但 isPlaying=false 且 AudioTrack 已释放，
            // 旧线程的下一次 write() 会抛异常由 catch 处理退出
            audioTrackRef.set(track)
            // 清空队列需持 decodeLock（防止与 decodePacket 竞态）
            decodeLock.withLock { audioQueue.clear() }
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
                var keepAliveTimer = System.currentTimeMillis()
                /** 连续写入失败计数：AudioTrack 被系统杀死后重建 */
                var consecutiveWriteFails = 0
                val MAX_WRITE_FAILS = 5
                while (isPlaying.get()) {
                    try {
                        // 检查是否需要在播放前重建 AudioTrack（上次写入失败太多）
                        if (consecutiveWriteFails >= MAX_WRITE_FAILS) {
                            Logger.w(TAG, "AudioTrack 写入失败 $consecutiveWriteFails 次，重建 AudioTrack")
                            recreateAudioTrack()
                            consecutiveWriteFails = 0
                        }
                        val data = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null && data.isNotEmpty()) {
                            audioTrackRef.get()?.play()
                            lastAudioActivityMs.set(System.currentTimeMillis())
                            keepAliveTimer = System.currentTimeMillis()
                            pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                            var written = track.write(data, 0, data.size) ?: -1
                            if (written > 0) {
                                totalBytesWritten.updateAndGet { it + written }
                            }
                            while (isPlaying.get()) {
                                val next = audioQueue.poll()
                                if (next == null) break
                                audioTrackRef.get()?.play()
                                written = track.write(next, 0, next.size) ?: -1
                                if (written > 0) {
                                    totalBytesWritten.updateAndGet { it + written }
                                }
                                pendingDecodePackets.updateAndGet { maxOf(it - 1, 0) }
                                lastAudioActivityMs.set(System.currentTimeMillis())
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - keepAliveTimer >= 400) {
                                keepAliveTimer = System.currentTimeMillis()
                                try {
                                    audioTrackRef.get()?.write(keepAliveSilence, 0, keepAliveSilence.size)
                                    consecutiveWriteFails = 0
                                } catch (_: Exception) {
                                    consecutiveWriteFails++
                                }
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
        } finally {
            isRestarting.set(false)
        }
    }

    fun decodePacket(opusData: ByteArray, onPcm: ((ByteArray) -> Unit)? = null) {
        if (released) return
        if (decoderPtr == 0L) return

        // 惰性重启：在 decodeLock 外执行。restartPlayback 调 stop()+release()
        // 可能阻塞等待 play thread 退出，持有锁会导致死锁
        if (isPlaying.compareAndSet(false, true)) {
            Logger.i(TAG, "播放线程已停止，新音频到达，自动重启播放")
            restartPlayback()
            if (!isPlaying.get()) return  // 重启失败
        } else if (!isPlaying.get()) {
            return  // 有其他线程正在重启，跳过本包
        }

        decodeLock.withLock {
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
                    if (packetsSinceRestart.incrementAndGet() <= 1) {
                        Logger.i(TAG, "decodePacket 解码成功 (samples=$samples, resample=${resampleUs}us, queueSize=${audioQueue.size})")
                    }

                    val pcmLimiter = applyLimiter(pcmResampled)
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
    }

    private fun resamplePcm(input: ShortArray, inputSamples: Int, inputRate: Int, outputRate: Int): ShortArray {
        if (inputRate == outputRate) {
            return input.copyOf(inputSamples)
        }

        val outputLength = (inputSamples.toDouble() * outputRate / inputRate).toInt()
        val output = ShortArray(outputLength)

        // 线性插值：简单安全，不会像 Catmull-Rom 那样过冲产生可听失真
        // Catmull-Rom 在 24k→16k 的 1.5x 采样步进下，遇到语音爆破音/摩擦音
        // 会产生远超原始范围的样本值，soft limiter 硬切后听起来像胡言乱语
        for (i in 0 until outputLength) {
            val srcIndex = i * inputRate.toDouble() / outputRate
            val srcIndexInt = srcIndex.toInt()
            val frac = srcIndex - srcIndexInt

            if (srcIndexInt >= inputSamples - 1) {
                output[i] = input[inputSamples - 1]
            } else {
                val s0 = input[srcIndexInt].toInt()
                val s1 = input[srcIndexInt + 1].toInt()
                output[i] = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
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
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            val compressed = if (s >= 0) {
                ((s * ratio) + (s - s * ratio) * kotlin.math.sqrt(s.toDouble() / peak)).toInt()
            } else {
                -((abs(s) * ratio) + (abs(s) - abs(s) * ratio) * kotlin.math.sqrt(abs(s).toDouble() / peak)).toInt()
            }
            pcm[i] = compressed.coerceIn(-HARD_CLIP, HARD_CLIP).toShort()
        }
        return pcm
    }

    /**
     * 应用音量缩放
     * 参考 ESP32 audio_codec.cc 音量处理
     * @param pcm PCM样本数组（16-bit，就地修改）
     * @param volume 音量 0-100
     */
    private fun applyVolume(pcm: ShortArray, volume: Int): ShortArray {
        if (volume <= 0) {
            pcm.fill(0)
            return pcm
        }
        if (volume >= 100) return pcm
        val scale = volume.toFloat() / 100f
        for (i in pcm.indices) {
            pcm[i] = (pcm[i].toInt() * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /**
     * 获取AudioTrack缓冲中尚未播放的总字节数（warmup + 音频 + 静音）
     * 全部计入，因为 playbackHeadPosition 包含所有已播放字节
     */
    fun getRemainingBytes(): Long {
        try {
            val track = audioTrackRef.get() ?: return 0L
            val playedFrames = track.playbackHeadPosition.toLong()
            val playedBytes = playedFrames * 2L * channels
            return (totalBytesWritten.get() - playedBytes).coerceAtLeast(0L)
        } catch (e: Exception) {
            return 0L
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
        decodeLock.withLock {
            released = true
            isPlaying.set(false)
            playThread?.interrupt()
            try {
                audioTrackRef.get()?.stop()
                audioTrackRef.get()?.release()
                if (decoderPtr != 0L) {
                    nativeDestroyDecoder(decoderPtr)
                    decoderPtr = 0L
                }
            } catch (e: Exception) {
                Logger.e(TAG, "release err: ${e.message}")
            }
        }
    }

    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(decoderPtr: Long, input: ByteArray, output: ShortArray, maxFrameSize: Int): Int
    private external fun nativeDestroyDecoder(decoderPtr: Long)
}
