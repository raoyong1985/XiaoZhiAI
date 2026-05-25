package com.xiaozhi.aiservice.di

import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint 接口，用于在 Application 中获取 @Singleton 实例。
 * 仅给 GlobalServices（MCP tools 用）获取实例。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServicesEntryPoint {
    fun audioService(): AudioService
    fun connectionRepository(): ConnectionRepository
    fun messageRepository(): MessageRepository
    fun xiaozhiBackend(): XiaoZhiBackendService
}
