package com.xiaozhi.aiservice.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * NVS风格持久化设置存储
 * 参考 ESP32 settings.cc 实现，对标 Android SharedPreferences
 */
class Settings(context: Context, private val ns: String, readWrite: Boolean = true) {

    private val prefs: SharedPreferences = context.getSharedPreferences(ns, Context.MODE_PRIVATE)

    private var dirty = false

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String) {
        if (prefs.edit().putString(key, value).commit()) {
            dirty = true
        }
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun setInt(key: String, value: Int) {
        if (prefs.edit().putInt(key, value).commit()) {
            dirty = true
        }
    }

    fun getBool(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun setBool(key: String, value: Boolean) {
        if (prefs.edit().putBoolean(key, value).commit()) {
            dirty = true
        }
    }

    fun eraseKey(key: String) {
        prefs.edit().remove(key).commit()
        dirty = true
    }

    fun eraseAll() {
        prefs.edit().clear().commit()
        dirty = true
    }

    fun isDirty(): Boolean = dirty

    companion object {
        // Namespace: device - 设备级配置
        const val NS_DEVICE = "device"
        // Namespace: audio - 音频配置
        const val NS_AUDIO = "audio"
        // Namespace: user - 用户配置
        const val NS_USER = "user"
    }
}
