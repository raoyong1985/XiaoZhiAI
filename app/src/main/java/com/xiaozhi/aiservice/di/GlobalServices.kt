package com.xiaozhi.aiservice.di

import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository

/**
 * 全局服务引用，给 MCP tools 等无法用构造函数注入的组件使用。
 * 在 XiaoZhiApplication.onCreate() 中通过 Hilt EntryPoint 获取实例。
 */
object GlobalServices {
    lateinit var audioService: AudioService
        internal set
    lateinit var connectionRepository: ConnectionRepository
        internal set
    lateinit var messageRepository: MessageRepository
        internal set
    lateinit var xiaozhiBackend: XiaoZhiBackendService
        internal set
}
