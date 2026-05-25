package com.xiaozhi.aiservice.di

import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService

/**
 * 全局服务引用，当前仅保留 xiaozhiBackend 用于 MCP mute 工具。
 * 其他服务已迁移至 MCPToolRegistry（通过 Hilt 构造注入）。
 *
 * 在 XiaoZhiApplication.onCreate() 中通过 Hilt EntryPoint 获取实例。
 */
object GlobalServices {
    lateinit var xiaozhiBackend: XiaoZhiBackendService
        internal set
}
