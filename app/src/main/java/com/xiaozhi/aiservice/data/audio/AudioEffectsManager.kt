package com.xiaozhi.aiservice.data.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import com.xiaozhi.aiservice.utils.Logger

private const val TAG = "AudioEffectsManager"

/**
 * 音频效果管理器 — 低音增强 + 等响度补偿
 *
 * DSP 浮点域处理（电子管/headroom）在 NativeOpusDecoder 中完成，
 * 此管理器仅负责系统级硬件效果（BassBoost + LoudnessEnhancer）
 */
class AudioEffectsManager(
    private val audioSessionId: Int
) {
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var _enabled = true
    val isEnabled: Boolean get() = _enabled

    /** 初始化 DSP 效果链 */
    fun init(): Boolean {
        if (audioSessionId <= 0) {
            Logger.w(TAG, "无效 audioSessionId: $audioSessionId")
            return false
        }

        try {
            // ── 1. 低音增强（硬件级 BassBoost）──
            val bb = BassBoost(0, audioSessionId)
            bb.enabled = false
            bb.setStrength(400.toShort())
            bassBoost = bb
            bb.enabled = true
            Logger.i(TAG, "✅ 低音增强已启用 (400/1000)")

            // ── 2. 等响度补偿（小音量下保留低频/高频感知）──
            val le = LoudnessEnhancer(audioSessionId)
            le.enabled = false
            le.setTargetGain(300)
            loudness = le
            le.enabled = true
            Logger.i(TAG, "✅ 等响度补偿已启用 (300/1000)")

            return true
        } catch (e: Exception) {
            Logger.w(TAG, "音频效果初始化失败: ${e.message}")
            return false
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled = enabled
        try {
            bassBoost?.enabled = enabled
            loudness?.enabled = enabled
        } catch (e: Exception) {
            Logger.w(TAG, "切换效果状态失败: ${e.message}")
        }
    }

    fun release() {
        try {
            bassBoost?.let { it.enabled = false; it.release() }
            loudness?.let { it.enabled = false; it.release() }
        } catch (e: Exception) {
            Logger.w(TAG, "释放效果失败: ${e.message}")
        }
        bassBoost = null
        loudness = null
    }
}
