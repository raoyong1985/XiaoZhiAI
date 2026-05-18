package com.xiaozhi.aiservice.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator

/**
 * 状态提示音 — 对应 ESP32 PlaySound()
 * 使用 SoundPool 加载内置音色，不依赖外部音频文件
 */
object SoundEffect {

    private const val TAG = "SoundEffect"

    enum class Type {
        LISTEN_START,   // 开始监听 → 短促上升音
        LISTEN_STOP,    // 停止监听 → 短促下降音
        AI_SPEAK,       // AI 开始说话 → 轻柔提示
        WAKE_WORD       // 唤醒成功 → 确认音
    }

    private var soundPool: SoundPool? = null
    private var loaded = false

    /**
     * 播放状态提示音（异步，非阻塞）
     */
    fun play(context: Context, type: Type) {
        // 使用 ToneGenerator 生成短音（不需要音频文件）
        val toneType = when (type) {
            Type.LISTEN_START -> ToneGenerator.TONE_PROP_PROMPT
            Type.LISTEN_STOP -> ToneGenerator.TONE_PROP_NACK
            Type.AI_SPEAK -> ToneGenerator.TONE_PROP_ACK
            Type.WAKE_WORD -> ToneGenerator.TONE_CDMA_PRESSHOLDKEY_LITE
        }

        try {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            generator.startTone(toneType, 150) // 150ms
            // ToneGenerator 会在播放完后自动释放
            // 但为了安全，延迟释放
            Thread {
                try {
                    Thread.sleep(200)
                    generator.release()
                } catch (_: InterruptedException) {}
            }.start()
        } catch (e: Exception) {
            Logger.w(TAG, "提示音播放失败: ${e.message}")
        }
    }
}
