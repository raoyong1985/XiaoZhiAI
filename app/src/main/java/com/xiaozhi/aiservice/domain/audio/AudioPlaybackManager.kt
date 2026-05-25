package com.xiaozhi.aiservice.domain.audio

interface AudioPlaybackManager {
    fun getOrCreate(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        volume: Int = 100
    )

    fun decodeAndPlay(
        sourceId: String,
        serverRate: Int,
        channels: Int,
        audioData: ByteArray,
        volume: Int = 100
    )

    fun release(sourceId: String)
    fun flushSource(sourceId: String)
    fun releaseAll()
    fun contains(sourceId: String): Boolean

    fun getServerSampleRate(sourceId: String): Int

    suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long = 2000): Boolean
    suspend fun waitForHardwareDrain(sourceId: String, timeoutMs: Long = 5000): Boolean
    fun releaseAllExcept(protectKey: String)
    fun setVolume(level: Int)
    val size: Int
}
