package dev.chungjungsoo.gptmobile.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsService
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import dev.chungjungsoo.gptmobile.presentation.ui.common.CreditsViewModel

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
