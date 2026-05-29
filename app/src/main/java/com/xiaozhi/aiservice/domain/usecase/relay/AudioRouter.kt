package com.xiaozhi.aiservice.domain.usecase.relay

import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.data.settings.AudioSettings
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import android.util.Log

/**
 * 音频路由 —— 管理 shared decoder 和 speaker 过滤
 *
 * 职责：
 * 1. 所有 bot 的音频写入同一个 shared decoder（OPPO/MTK 多 AudioTrack 会无声）
 * 2. speaker 过滤：只播放预期 bot 的音频
 * 3. decoder 生命周期管理
 */
class AudioRouter(
    private val playbackManager: AudioPlaybackManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRouter"
    }

    /** 按采样率缓存的 sourceId */
    private val decoderCache = ConcurrentHashMap<Int, String>()

    /** 上次 speaker 切换的时间戳（ms） */
    @Volatile
    var lastSpeakerChangeMs: Long = 0L

    fun getSharedSourceId(groupId: String, serverRate: Int): String {
        return "${groupId}_shared_${serverRate}"
    }

    /**
     * 路由音频到 shared decoder
     *
     * @return true=音频已播放, false=被 speaker 过滤丢弃
     */
    fun routeAudio(
        groupId: String,
        botId: String,
        serverRate: Int,
        serverCh: Int,
        audioData: ByteArray,
        expectedSpeakerId: String
    ): Boolean {
        // speaker 过滤
        if (expectedSpeakerId.isNotEmpty() && botId != expectedSpeakerId) {
            if (System.currentTimeMillis() - lastSpeakerChangeMs > RelayConfig.SPEAKER_GRACE_MS) {
                Log.w(TAG, "音频被过滤: $botId != expected=$expectedSpeakerId")
                return false
            } else {
                Log.d(TAG, "音频grace通过: $botId (grace中)")
            }
        }

        val sourceId = getSharedSourceId(groupId, serverRate)
        val volume = AudioSettings.getOutputVolume(context)

        if (!playbackManager.contains(sourceId)) {
            decoderCache[serverRate] = sourceId
            playbackManager.getOrCreate(sourceId, serverRate, serverCh, volume)
            Log.i(TAG, "创建 shared decoder: $sourceId (${serverRate}Hz)")
        }

        playbackManager.decodeAndPlay(sourceId, serverRate, serverCh, audioData, volume)
        return true
    }

    fun onSpeakerChanged() {
        lastSpeakerChangeMs = System.currentTimeMillis()
    }

    fun flush(groupId: String, serverRate: Int) {
        val sourceId = getSharedSourceId(groupId, serverRate)
        if (playbackManager.contains(sourceId)) {
            playbackManager.flushSource(sourceId)
        }
    }

    fun releaseAll() {
        playbackManager.releaseAll()
        decoderCache.clear()
    }
}
