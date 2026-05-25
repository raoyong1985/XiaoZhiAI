package com.xiaozhi.aiservice.domain

fun interface PlatformLogger {
    fun log(level: Level, tag: String, message: String)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    companion object {
        val EMPTY: PlatformLogger = PlatformLogger { _, _, _ -> }
    }
}
