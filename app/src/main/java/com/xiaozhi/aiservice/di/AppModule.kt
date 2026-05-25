package com.xiaozhi.aiservice.di

import com.google.gson.Gson
import com.xiaozhi.aiservice.data.audio.AudioPlaybackManagerImpl
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.BotConnectionPoolImpl
import com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService
import com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl
import com.xiaozhi.aiservice.data.repository.ContactsRepositoryImpl
import com.xiaozhi.aiservice.data.repository.DeviceRepositoryImpl
import com.xiaozhi.aiservice.data.repository.GroupChatRepositoryImpl
import com.xiaozhi.aiservice.data.repository.MessageRepositoryImpl
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import com.xiaozhi.aiservice.domain.repository.DeviceRepository
import com.xiaozhi.aiservice.domain.repository.GroupChatRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindBotConnectionPool(
        impl: BotConnectionPoolImpl
    ): BotConnectionPoolRepository

    @Binds
    @Singleton
    abstract fun bindAudioPlaybackManager(
        impl: AudioPlaybackManagerImpl
    ): AudioPlaybackManager

    @Binds
    @Singleton
    abstract fun bindGroupChatRepository(
        impl: GroupChatRepositoryImpl
    ): GroupChatRepository

    @Binds
    @Singleton
    abstract fun bindContactsRepository(
        impl: ContactsRepositoryImpl
    ): ContactsRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MessageRepositoryImpl
    ): MessageRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(
        impl: DeviceRepositoryImpl
    ): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(
        impl: ConnectionRepositoryImpl
    ): ConnectionRepository

    companion object {
        @Provides
        @Singleton
        fun provideGson(): Gson = Gson()

        @Provides
        @Singleton
        fun provideAudioService(): AudioService = AudioService()

        @Provides
        @Singleton
        fun provideXiaoZhiBackendService(
            gson: Gson
        ): XiaoZhiBackendService = XiaoZhiBackendService(gson)
    }
}
