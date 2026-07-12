package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager as AudioPlaybackManagerInterface
import com.xiaozhi.aiservice.utils.Logger
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlaybackManager"

/**
 * 统一音频播放管理器实现。
 */
@Singleton
class AudioPlaybackManagerImpl @Inject constructor() : AudioPlaybackManagerInterface {

    companion object {
        private const val MAX_DECODERS = 32
    }

    // LinkedHashMap with accessOrder=true: 最久未使用的排前面
    private val decoders = object : LinkedHashMap<String, NativeOpusDecoder>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NativeOpusDecoder>?): Boolean {
            return size > MAX_DECODERS
        }
    }
    private val decoderLock = Any()

    override fun getOrCreate(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        volume: Int
    ) {
        synchronized(decoderLock) {
            if (!decoders.containsKey(sourceId)) {
                decoders[sourceId] = NativeOpusDecoder(serverRate, serverRate, channels, volume).also {
                    it.init()
                    if (volume < 100) it.setVolume(volume)
                }
            }
        }
    }

    override fun decodeAndPlay(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        audioData: ByteArray,
        volume: Int
    ) {
        val decoder: NativeOpusDecoder
        synchronized(decoderLock) {
            decoder = decoders.getOrPut(sourceId) {
                NativeOpusDecoder(serverRate, serverRate, channels, volume).also {
                    it.init()
                    if (volume < 100) it.setVolume(volume)
                }
            }
        }
        decoder.decodePacket(audioData)
    }

    override fun release(sourceId: String) {
        synchronized(decoderLock) {
            decoders.remove(sourceId)?.let {
                if (!it.isReleased) it.release()
            }
        }
    }

    override fun flushSource(sourceId: String) {
        synchronized(decoderLock) {
            decoders[sourceId]?.flushAudio()
        }
    }

    override fun releaseAll() {
        synchronized(decoderLock) {
            decoders.values.forEach {
                if (!it.isReleased) it.release()
            }
            decoders.clear()
        }
    }

    override fun contains(sourceId: String): Boolean = synchronized(decoderLock) { decoders.containsKey(sourceId) }

    override fun getServerSampleRate(sourceId: String): Int {
        synchronized(decoderLock) {
            return decoders[sourceId]?.getServerSampleRate() ?: 0
        }
    }

    override suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long): Boolean {
        val decoder = synchronized(decoderLock) { decoders[sourceId] }
        return decoder?.waitForQueueEmpty(timeoutMs) ?: true
    }

    override suspend fun waitForHardwareDrain(sourceId: String, timeoutMs: Long): Boolean {
        val decoder = synchronized(decoderLock) { decoders[sourceId] }
        return decoder?.waitForHardwareDrain(timeoutMs) ?: true
    }

    override val size: Int get() = synchronized(decoderLock) { decoders.size }

    override fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, 100)
        val snapshot: List<NativeOpusDecoder>
        synchronized(decoderLock) {
            snapshot = decoders.values.toList()
        }
        snapshot.forEach { decoder ->
            if (!decoder.isReleased) decoder.setVolume(clamped)
        }
        Logger.i(TAG, "音量已同步到所有解码器: $clamped%")
    }
}
