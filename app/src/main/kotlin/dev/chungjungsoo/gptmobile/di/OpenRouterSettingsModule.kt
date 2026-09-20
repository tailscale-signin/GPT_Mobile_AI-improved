package dev.chungjungsoo.gptmobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OpenRouterSettingsModule {

    @Binds
    @Singleton
    abstract fun bindOpenRouterSettingsRepository(
        impl: OpenRouterSettingsRepositoryImpl
    ): OpenRouterSettingsRepository
}
