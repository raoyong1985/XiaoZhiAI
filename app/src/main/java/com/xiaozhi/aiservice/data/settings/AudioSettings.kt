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

    @JvmStatic
    fun getOutputVolume(ctx: Context): Int =
        Settings(ctx, NS).getInt(KEY_OUTPUT_VOLUME, 80).coerceIn(0, 100)

    @JvmStatic
    fun setOutputVolume(ctx: Context, value: Int) =
        Settings(ctx, NS).setInt(KEY_OUTPUT_VOLUME, value.coerceIn(0, 100))
}
