package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import dev.chungjungsoo.gptmobile.data.datastore.SecretDataSourceImpl
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsService
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import dev.chungjungsoo.gptmobile.presentation.ui.common.CreditsViewModel
import javax.inject.Singleton

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {

    @Provides
    @ViewModelScoped
    fun provideCreditsViewModel(
        creditsService: OpenRouterCreditsService,
        secretRepository: SecretRepository
    ): CreditsViewModel = CreditsViewModel(creditsService, secretRepository)
}

@Module
@InstallIn(ViewModelComponent::class)
object DataStoreModule {

    @Provides
    @ViewModelScoped
    fun provideSecretRepository(
        @ApplicationContext context: Context
    ): SecretRepository = SecretDataSourceImpl(context)
}
