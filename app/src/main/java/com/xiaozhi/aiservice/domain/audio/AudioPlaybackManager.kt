package com.xiaozhi.aiservice.domain.audio

import com.xiaozhi.aiservice.data.audio.NativeOpusDecoder

/**
 * 统一音频播放管理接口
 *
 * 职责：创建/缓存解码器、解码播放、释放资源
 */
interface AudioPlaybackManager {
    /** 获取或创建解码器 */
    fun getOrCreate(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        volume: Int = 100
    ): NativeOpusDecoder

    /** 解码播放一包音频 */
    fun decodeAndPlay(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        audioData: ByteArray,
        volume: Int = 100
    )

    /** 释放指定源 */
    fun release(sourceId: String)

    /** 强制清空指定源的播放缓冲 */
    fun flushSource(sourceId: String)

    /** 释放所有解码器 */
    fun releaseAll()

    /** 判断源是否存在 */
    fun contains(sourceId: String): Boolean

    /** 获取指定源的解码器 */
    fun get(sourceId: String): NativeOpusDecoder?

    /** 等待指定源的音频队列排空 */
    suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long = 2000): Boolean

    /** 等待指定源硬件缓冲自然排空 */
    suspend fun waitForHardwareDrain(sourceId: String, timeoutMs: Long = 5000): Boolean

    /** 释放除 protectKey 外所有解码器 */
    fun releaseAllExcept(protectKey: String)

    /** 更新所有活跃解码器的音量 */
    fun setVolume(level: Int)

    /** 当前解码器数量 */
    val size: Int
}
