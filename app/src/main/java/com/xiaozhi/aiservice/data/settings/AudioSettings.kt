package com.xiaozhi.aiservice.data.settings

import android.content.Context

/**
 * 音频配置 Key-Value 定义
 */
object AudioSettings {

    private const val NS = Settings.NS_AUDIO

    // ===== 音量 =====
    /** 扬声器音量 0-100, 对应 ESP32 audio.output_volume */
    const val KEY_OUTPUT_VOLUME = "output_volume"
    /** 麦克风增益 0-100, 对应 ESP32 audio.mic_gain */
    const val KEY_MIC_GAIN = "mic_gain"

    // ===== 监听模式 =====
    /** 监听模式: auto/manual/realtime, 对应 ESP32 listening_mode */
    const val KEY_LISTENING_MODE = "listening_mode"

    @JvmStatic
    fun getOutputVolume(ctx: Context): Int =
        Settings(ctx, NS).getInt(KEY_OUTPUT_VOLUME, 80).coerceIn(0, 100)

    @JvmStatic
    fun setOutputVolume(ctx: Context, value: Int) =
        Settings(ctx, NS).setInt(KEY_OUTPUT_VOLUME, value.coerceIn(0, 100))

    @JvmStatic
    fun getMicGain(ctx: Context): Int =
        Settings(ctx, NS).getInt(KEY_MIC_GAIN, 80).coerceIn(0, 100)

    @JvmStatic
    fun setMicGain(ctx: Context, value: Int) =
        Settings(ctx, NS).setInt(KEY_MIC_GAIN, value.coerceIn(0, 100))

    // getListeningMode/setListeningMode 使用 VoiceMode（定义在 domain.model.VoiceMode）
    @JvmStatic
    fun getListeningMode(ctx: Context): com.xiaozhi.aiservice.domain.model.VoiceMode =
        com.xiaozhi.aiservice.domain.model.VoiceMode.fromServerValue(
            Settings(ctx, NS).getString(KEY_LISTENING_MODE, com.xiaozhi.aiservice.domain.model.VoiceMode.REALTIME.serverValue))

    @JvmStatic
    fun setListeningMode(ctx: Context, mode: com.xiaozhi.aiservice.domain.model.VoiceMode) =
        Settings(ctx, NS).setString(KEY_LISTENING_MODE, mode.serverValue)
}
