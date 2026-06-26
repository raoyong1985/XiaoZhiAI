package com.xiaozhi.aiservice.data.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.xiaozhi.aiservice.utils.Logger

/**
 * 音频增强器 — AEC + AGC + 降噪 一体模块
 *
 * 对应 ESP32 AFE (Audio Front End) 中的三件套：
 * - AEC: Acoustic Echo Canceler 声学回声消除
 * - AGC: Automatic Gain Control 自动增益控制
 * - NS:  Noise Suppressor 降噪
 *
 * 使用 Android 系统级 AudioFX API，绑定到 AudioRecord 会话。
 * 并非所有设备都支持全部三项，每项通过 isAvailable() 检查。
 */
class AudioEnhancer {

    companion object {
        private const val TAG = "AudioEnhancer"
    }

    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null

    /** 配置参数 */
    var aecEnabled = false
        private set
    var agcEnabled = true
        private set
    var nsEnabled = true
        private set

    /** 开关单个增强 */
    fun setAec(enabled: Boolean) { aecEnabled = enabled }
    fun setAgc(enabled: Boolean) { agcEnabled = enabled }
    fun setNs(enabled: Boolean) { nsEnabled = enabled }

    /** 是否已绑定到 AudioRecord 会话 */
    var isActive: Boolean = false
        private set

    // ── 可用性检查 ──

    fun isAecSupported(): Boolean = AcousticEchoCanceler.isAvailable()
    fun isAgcSupported(): Boolean = AutomaticGainControl.isAvailable()
    fun isNsSupported(): Boolean = NoiseSuppressor.isAvailable()


    // ── 初始化（绑定到 AudioRecord 会话） ──

    /**
     * 绑定到 AudioRecord 会话并创建所有音频效果
     * @param audioSessionId AudioRecord 的 audioSessionId
     */
    fun init(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0) {
            Logger.w(TAG, "无效的 AudioRecord session ID: $audioSessionId")
            return false
        }

        release()
        var anySuccess = false

        // AEC
        if (aecEnabled && AcousticEchoCanceler.isAvailable()) {
            try {
                val localAec = AcousticEchoCanceler.create(audioSessionId)
                aec = localAec
                if (localAec != null) {
                    localAec.enabled = true
                    Logger.i(TAG, "AEC 初始化成功 session=$audioSessionId")
                    anySuccess = true
                } else {
                    Logger.w(TAG, "AEC.create() 返回 null")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "AEC 初始化失败: ${e.message}")
                aec?.release(); aec = null
            }
        }

        // AGC
        if (agcEnabled && AutomaticGainControl.isAvailable()) {
            try {
                val localAgc = AutomaticGainControl.create(audioSessionId)
                agc = localAgc
                if (localAgc != null) {
                    localAgc.enabled = true
                    Logger.i(TAG, "AGC 初始化成功 session=$audioSessionId")
                    anySuccess = true
                } else {
                    Logger.w(TAG, "AGC.create() 返回 null")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "AGC 初始化失败: ${e.message}")
                agc?.release(); agc = null
            }
        }

        // NS
        if (nsEnabled && NoiseSuppressor.isAvailable()) {
            try {
                val localNs = NoiseSuppressor.create(audioSessionId)
                ns = localNs
                if (localNs != null) {
                    localNs.enabled = true
                    Logger.i(TAG, "NS 初始化成功 session=$audioSessionId")
                    anySuccess = true
                } else {
                    Logger.w(TAG, "NS.create() 返回 null")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "NS 初始化失败: ${e.message}")
                ns?.release(); ns = null
            }
        }

        isActive = anySuccess
        if (!anySuccess) {
            Logger.w(TAG, "所有 AudioFX 初始化失败（设备不支持或未启用）")
        }
        return anySuccess
    }

    /**
     * 重置全部效果（不清除配置，重新生效）
     */
    fun reset() {
        aec?.enabled = aecEnabled
        agc?.enabled = agcEnabled
        ns?.enabled = nsEnabled
        Logger.i(TAG, "AudioFX 重置: AEC=${aecEnabled} AGC=${agcEnabled} NS=${nsEnabled}")
    }

    /** 释放所有效果 */
    fun release() {
        aec?.let {
            try { it.release() } catch (_: Exception) {}
        }
        aec = null

        agc?.let {
            try { it.release() } catch (_: Exception) {}
        }
        agc = null

        ns?.let {
            try { it.release() } catch (_: Exception) {}
        }
        ns = null

        isActive = false
    }

    /** 当前状态摘要 */
    fun getStatusSummary(): String {
        val parts = mutableListOf<String>()
        if (aec != null) parts.add("AEC")
        if (agc != null) parts.add("AGC")
        if (ns != null) parts.add("NS")
        if (parts.isEmpty()) return "none"
        return parts.joinToString("+")
    }
}
