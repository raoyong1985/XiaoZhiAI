package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager as AudioPlaybackManagerInterface
import com.xiaozhi.aiservice.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlaybackManager"

/**
 * 统一音频播放管理器实现。
 */
@Singleton
class AudioPlaybackManagerImpl @Inject constructor() : AudioPlaybackManagerInterface {

    companion object {
        private const val MAX_DECODERS = 8
    }

    private val decoders = ConcurrentHashMap<String, NativeOpusDecoder>()

    /**
     * 获取或创建解码器，自动注入音量设置。
     */
    override fun getOrCreate(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        volume: Int
    ) {
        // 超限时淘汰最旧解码器（按插入顺序排）
        if (decoders.size >= MAX_DECODERS && !decoders.containsKey(sourceId)) {
            val toEvict = decoders.keys.firstOrNull()
            if (toEvict != null) {
                decoders.remove(toEvict)?.let {
                    if (!it.isReleased) it.release()
                }
                Logger.w(TAG, "解码器超限($MAX_DECODERS)，淘汰: $toEvict")
            }
        }
        decoders.getOrPut(sourceId) {
            NativeOpusDecoder(serverRate, serverRate, channels, volume).also {
                it.init()
                if (volume < 100) it.setVolume(volume)
            }
        }
    }

    /**
     * 解码播放一包音频（懒创建解码器）。
     */
    override fun decodeAndPlay(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        audioData: ByteArray,
        volume: Int
    ) {
        getOrCreate(sourceId, serverRate, channels, volume)
        decoders[sourceId]?.decodePacket(audioData)
    }

    /**
     * 释放指定源。
     */
    override fun release(sourceId: String) {
        decoders.remove(sourceId)?.let {
            if (!it.isReleased) it.release()
        }
    }

    /**
     * 强制清空指定源的播放缓冲（丢弃未播放的音频）。
     */
    override fun flushSource(sourceId: String) {
        decoders[sourceId]?.flushAudio()
    }

    /**
     * 释放所有解码器。
     */
    override fun releaseAll() {
        decoders.values.forEach {
            if (!it.isReleased) it.release()
        }
        decoders.clear()
    }

    /** 判断源是否存在。 */
    override fun contains(sourceId: String): Boolean = decoders.containsKey(sourceId)

    /**
     * 获取指定源的解码器采样率，不存在返回 0
     */
    override fun getServerSampleRate(sourceId: String): Int {
        return decoders[sourceId]?.getServerSampleRate() ?: 0
    }

    /**
     * 等待指定源的音频队列排空。
     */
    override suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long): Boolean {
        val decoder = decoders[sourceId] ?: return true
        return decoder.waitForQueueEmpty(timeoutMs)
    }

    /**
     * 等待指定源 AudioTrack 硬件缓冲自然排空（不 flush，等播完）。
     * 对应 ESP32 WaitForPlaybackQueueEmpty() 语义。
     */
    override suspend fun waitForHardwareDrain(sourceId: String, timeoutMs: Long): Boolean {
        val decoder = decoders[sourceId] ?: return true
        return decoder.waitForHardwareDrain(timeoutMs)
    }

    /** 当前解码器数量 */
    override val size: Int get() = decoders.size

    /**
     * 更新所有活跃解码器的音量
     */
    override fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, 100)
        decoders.entries.toList().forEach { (_, decoder) ->
            if (!decoder.isReleased) decoder.setVolume(clamped)
        }
        Logger.i(TAG, "音量已同步到所有解码器: $clamped%")
    }
}
