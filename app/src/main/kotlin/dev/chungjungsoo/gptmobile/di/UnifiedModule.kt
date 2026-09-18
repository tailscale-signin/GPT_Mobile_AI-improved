package dev.chungjungsoo.gptmobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.unified.AIServiceImpl
import dev.chungjungsoo.gptmobile.data.unified.MessageQueueRepositoryImpl
import dev.chungjungsoo.gptmobile.data.unified.UnifiedModelRepositoryImpl
import dev.chungjungsoo.gptmobile.domain.unified.AIService
import dev.chungjungsoo.gptmobile.domain.unified.MessageQueueRepository
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModelRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UnifiedModule {

    @Binds
    @Singleton
    abstract fun bindAIService(impl: AIServiceImpl): AIService

    @Binds
    @Singleton
    abstract fun bindMessageQueueRepository(impl: MessageQueueRepositoryImpl): MessageQueueRepository

    @Binds
    @Singleton
    abstract fun bindUnifiedModelRepository(impl: UnifiedModelRepositoryImpl): UnifiedModelRepository
}
