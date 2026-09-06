package dev.melo.gptmobile.improved.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.ChatRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository
}
