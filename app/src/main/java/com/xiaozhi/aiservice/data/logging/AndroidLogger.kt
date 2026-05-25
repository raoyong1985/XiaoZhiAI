package com.xiaozhi.aiservice.data.logging

import android.util.Log
import com.xiaozhi.aiservice.BuildConfig
import com.xiaozhi.aiservice.domain.PlatformLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidLogger @Inject constructor() : PlatformLogger {

    override fun log(level: PlatformLogger.Level, tag: String, message: String) {
        if (level == PlatformLogger.Level.DEBUG || level == PlatformLogger.Level.INFO) {
            if (!BuildConfig.ENABLE_DEBUG_LOG) return
        }
        when (level) {
            PlatformLogger.Level.DEBUG -> Log.d(tag, message)
            PlatformLogger.Level.INFO -> Log.i(tag, message)
            PlatformLogger.Level.WARN -> Log.w(tag, message)
            PlatformLogger.Level.ERROR -> Log.e(tag, message)
        }
    }
}
