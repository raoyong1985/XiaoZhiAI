package com.xiaozhi.aiservice.utils

import android.util.Log
import com.xiaozhi.aiservice.BuildConfig

object Logger {
    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.e(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.w(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.d(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.v(tag, msg)
    }
}
