package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AudioPlaybackManager"

/**
 * 统一音频播放管理器。
 *
 * 职责：
 * - 创建/缓存 NativeOpusDecoder（统一音量注入、init）
 * - 释放解码器（单源/全量）
 * - 编解码器数量（群聊多 bot 场景）
 *
 * 不负责：接龙排空逻辑、播放源切换仲裁 —— 这些由调用方（GroupChatViewModel）管理。
 */
class AudioPlaybackManager {

    private val decoders = ConcurrentHashMap<String, NativeOpusDecoder>()

    /**
     * 获取或创建解码器，自动注入音量设置。
     */
    fun getOrCreate(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        volume: Int = 100
    ): NativeOpusDecoder {
        return decoders.getOrPut(sourceId) {
            NativeOpusDecoder(serverRate, 16000, channels, volume).also {
                it.init()
                if (volume < 100) it.setVolume(volume)
            }
        }
    }

    /**
     * 解码播放一包音频（懒创建解码器）。
     */
    fun decodeAndPlay(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        audioData: ByteArray,
        volume: Int = 100
    ) {
        val decoder = getOrCreate(sourceId, serverRate, channels, volume)
        decoder.decodePacket(audioData)
    }

    /**
     * 释放指定源。
     */
    fun release(sourceId: String) {
        decoders.remove(sourceId)?.let {
            if (!it.isReleased) it.release()
        }
    }

    /**
     * 强制清空指定源的播放缓冲（丢弃未播放的音频）。
     */
    fun flushSource(sourceId: String) {
        decoders[sourceId]?.flushAudio()
    }

    /**
     * 释放所有解码器。
     */
    fun releaseAll() {
        decoders.values.forEach {
            if (!it.isReleased) it.release()
        }
        decoders.clear()
    }

    /**
     * 判断源是否存在。
     */
    fun contains(sourceId: String): Boolean = decoders.containsKey(sourceId)

    /**
     * 等待指定源的音频队列排空。
     */
    suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long = 2000): Boolean {
        val decoder = decoders[sourceId] ?: return true
        return decoder.waitForQueueEmpty(timeoutMs)
    }

    /**
     * 淘汰超出上限的解码器（移除最旧的，保留最新的）。
     * @param protectKey 受保护的 key，不会被淘汰
     */
    fun releaseExcess(maxKeep: Int, protectKey: String? = null) {
        if (decoders.size <= maxKeep) return
        val keys = decoders.keys.toList()
        val candidates = if (protectKey != null) keys.filter { it != protectKey } else keys
        val toRemove = candidates.take(candidates.size - maxKeep + (if (protectKey != null && decoders.containsKey(protectKey)) 1 else 0))
        if (toRemove.isEmpty()) return
        Logger.i(TAG, "淘汰 ${toRemove.size} 个解码器 (当前=${decoders.size}, 上限=$maxKeep)")
        toRemove.forEach { release(it) }
    }

    /**
     * 排空除 protectKey 外所有解码器的播放队列。
     */
    suspend fun drainAllExcept(protectKey: String, timeoutMs: Long = 2000) {
        for ((key, decoder) in decoders) {
            if (key == protectKey || decoder.isReleased) continue
            decoder.waitForQueueEmpty(timeoutMs)
        }
    }

    /** 当前解码器数量 */
    val size: Int get() = decoders.size

    /** 更新所有活跃解码器的音量 */
    fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, 100)
        decoders.values.forEach { decoder ->
            if (!decoder.isReleased) decoder.setVolume(clamped)
        }
        Logger.i(TAG, "音量已同步到所有解码器: $clamped%")
    }
}
