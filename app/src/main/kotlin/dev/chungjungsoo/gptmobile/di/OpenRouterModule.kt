package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsService
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OpenRouterModule {

    @Provides
    @Singleton
    fun provideOpenRouterCreditsService(
        @ApplicationContext context: Context,
        secretRepository: SecretRepository
    ): OpenRouterCreditsService = OpenRouterCreditsService(context, secretRepository)
}
