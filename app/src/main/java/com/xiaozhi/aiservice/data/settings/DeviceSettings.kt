package com.xiaozhi.aiservice.data.settings

import android.content.Context

/**
 * 设备持久化配置 Key-Value 定义
 * 参考 ESP32 settings.cc 的 namespace+key 模式
 * 与 ESP32 配置对照:
 *   - ESP32 "device" namespace → Android NS_DEVICE
 *   - ESP32 "audio" namespace → Android NS_AUDIO
 *   - ESP32 "user" namespace → Android NS_USER
 */
object DeviceSettings {

    private const val NS = Settings.NS_DEVICE

    // ===== 设备标识 =====
    /** 设备ID (MAC格式), 对应 ESP32 wifi_config.device_id */
    const val KEY_DEVICE_ID = "device_id"
    /** 客户端ID (UUID格式), 对应 ESP32 wifi_config.client_id */
    const val KEY_CLIENT_ID = "client_id"
    /** 激活Token, 对应 ESP32 activation.access_token */
    const val KEY_ACCESS_TOKEN = "access_token"

    // ===== 服务器配置 =====
    /** 服务器URL, 对应 ESP32 server_config.server_url */
    const val KEY_SERVER_URL = "server_url"
    /** OTA服务器URL, 对应 ESP32 server_config.ota_url */
    const val KEY_OTA_URL = "ota_url"

    // ===== 设备状态 =====
    /** 是否已激活, 对应 ESP32 activation.is_activated */
    const val KEY_IS_ACTIVATED = "is_activated"
    /** 激活码, 对应 ESP32 activation.activation_code */
    const val KEY_ACTIVATION_CODE = "activation_code"
    /** 固件版本, 对应 ESP32 settings.firmware_version */
    const val KEY_FIRMWARE_VERSION = "firmware_version"

    // ===== 设备能力标记 =====
    /** AEC模式: off/device/server, 对应 ESP32 aec_mode */
    const val KEY_AEC_MODE = "aec_mode"
    /** 唤醒词类型, 对应 ESP32 wake_word_type */
    const val KEY_WAKE_WORD_TYPE = "wake_word_type"

    // ===== API密钥 =====
    /** NewsAPI密钥 */
    const val KEY_NEWS_API_KEY = "news_api_key"

    /**
     * AEC模式枚举 (与 ESP32 AecMode 对应)
     */
    enum class AecMode(val value: String) {
        OFF("off"),
        DEVICE_SIDE("device"),
        SERVER_SIDE("server");

        companion object {
            fun fromValue(value: String): AecMode = entries.find { it.value == value } ?: OFF
        }
    }

    // ===== 读取接口 =====

    @JvmStatic
    fun getDeviceId(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_DEVICE_ID, "")

    @JvmStatic
    fun setDeviceId(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_DEVICE_ID, value)

    @JvmStatic
    fun getClientId(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_CLIENT_ID, "")

    @JvmStatic
    fun setClientId(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_CLIENT_ID, value)

    @JvmStatic
    fun getAccessToken(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_ACCESS_TOKEN, "")

    @JvmStatic
    fun setAccessToken(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_ACCESS_TOKEN, value)

    @JvmStatic
    fun getServerUrl(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_SERVER_URL, "wss://api.tenclass.net/xiaozhi/v1/")

    @JvmStatic
    fun setServerUrl(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_SERVER_URL, value)

    @JvmStatic
    fun getOtaUrl(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_OTA_URL, "https://api.tenclass.net/xiaozhi/ota/")

    @JvmStatic
    fun setOtaUrl(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_OTA_URL, value)

    @JvmStatic
    fun isActivated(ctx: Context): Boolean =
        Settings(ctx, NS).getBool(KEY_IS_ACTIVATED, false)

    @JvmStatic
    fun setActivated(ctx: Context, value: Boolean) =
        Settings(ctx, NS).setBool(KEY_IS_ACTIVATED, value)

    @JvmStatic
    fun getActivationCode(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_ACTIVATION_CODE, "")

    @JvmStatic
    fun setActivationCode(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_ACTIVATION_CODE, value)

    @JvmStatic
    fun getAecMode(ctx: Context): AecMode =
        AecMode.fromValue(Settings(ctx, NS).getString(KEY_AEC_MODE, AecMode.OFF.value))

    @JvmStatic
    fun setAecMode(ctx: Context, mode: AecMode) =
        Settings(ctx, NS).setString(KEY_AEC_MODE, mode.value)

    @JvmStatic
    fun getNewsApiKey(ctx: Context): String =
        Settings(ctx, NS).getString(KEY_NEWS_API_KEY, "")

    @JvmStatic
    fun setNewsApiKey(ctx: Context, value: String) =
        Settings(ctx, NS).setString(KEY_NEWS_API_KEY, value)

    /**
     * 清除所有设备配置 (用于重置/切换账号)
     */
    @JvmStatic
    fun clearAll(ctx: Context) =
        Settings(ctx, NS).eraseAll()
}

