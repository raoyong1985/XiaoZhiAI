package com.xiaozhi.aiservice.utils

import android.util.Log
import com.xiaozhi.aiservice.BuildConfig

object Logger {
    /** i/d/v 只在 debug 版输出，减少 release 版日志量 */
    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.d(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.v(tag, msg)
    }

    /** e/w 始终输出，release 版也需要看到错误 */
    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }
}
