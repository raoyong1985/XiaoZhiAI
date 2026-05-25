package com.xiaozhi.aiservice.data.settings

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context, private val ns: String, readWrite: Boolean = true) {

    private val prefs: SharedPreferences = context.getSharedPreferences(ns, Context.MODE_PRIVATE)

    private var dirty = false

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        dirty = true
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        dirty = true
    }

    fun getBool(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        dirty = true
    }

    fun eraseKey(key: String) {
        prefs.edit().remove(key).apply()
        dirty = true
    }

    fun eraseAll() {
        prefs.edit().clear().apply()
        dirty = true
    }

    fun isDirty(): Boolean = dirty

    companion object {
        const val NS_DEVICE = "device"
        const val NS_AUDIO = "audio"
        const val NS_USER = "user"
    }
}
