package com.xiaozhi.aiservice.di

import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint 接口，用于在 Application 中获取 @Singleton 实例。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServicesEntryPoint {
    fun audioService(): AudioService
    fun mcpToolRegistry(): MCPToolRegistry
    fun xiaozhiBackend(): XiaoZhiBackendService
}
