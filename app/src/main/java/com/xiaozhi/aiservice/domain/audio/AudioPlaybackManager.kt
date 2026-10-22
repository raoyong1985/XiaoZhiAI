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
    /** 按 sourceId 前缀批量排空（群聊里 key 带采样率后缀时用） */
    fun flushByPrefix(prefix: String)

    fun flushSource(sourceId: String)
    fun releaseAll()
    fun contains(sourceId: String): Boolean

    fun getServerSampleRate(sourceId: String): Int

    /** 当前排队等待播放的音频时长（毫秒），用于文字与语音对齐 */
    fun queuedMillis(sourceId: String): Long

    /** 在下一个音频包前插入指定时长的静音（句间停顿），避免句子粘连 */
    fun insertSentenceGap(sourceId: String, gapMs: Int)

    suspend fun waitForQueueEmpty(sourceId: String, timeoutMs: Long = 2000): Boolean
    suspend fun waitForHardwareDrain(sourceId: String, timeoutMs: Long = 5000): Boolean
    fun setVolume(level: Int)
    val size: Int
}
