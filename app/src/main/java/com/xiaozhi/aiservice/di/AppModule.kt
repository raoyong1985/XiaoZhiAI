package com.xiaozhi.aiservice.di

import com.google.gson.Gson
import com.xiaozhi.aiservice.data.audio.AudioPlaybackManagerImpl
import com.xiaozhi.aiservice.data.remote.BotConnectionPoolImpl
import com.xiaozhi.aiservice.domain.audio.AudioPlaybackManager
import com.xiaozhi.aiservice.domain.repository.BotConnectionPoolRepository
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

    companion object {
        @Provides
        @Singleton
        fun provideGson(): Gson = Gson()
    }
}
