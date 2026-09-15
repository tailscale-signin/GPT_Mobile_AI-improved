package dev.chungjungsoo.gptmobile.presentation.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsService
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing OpenRouter credits display
 */
class CreditsViewModel(
    private val creditsService: OpenRouterCreditsService,
    private val secretRepository: SecretRepository
) : ViewModel() {

    private val _creditsState = MutableStateFlow(CreditsState.Loading)
    val creditsState: StateFlow<CreditsState> = _creditsState.asStateFlow()

    init {
        fetchCredits()
    }

    /**
     * Fetch credits from OpenRouter API
     */
    fun fetchCredits(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _creditsState.value = CreditsState.Loading
            val result = creditsService.fetchCredits(forceRefresh)
            result.fold(
                onSuccess = { credits ->
                    _creditsState.value = CreditsState.Success(credits)
                },
                onFailure = { error ->
                    _creditsState.value = CreditsState.Error(error.message ?: "Failed to fetch credits")
                }
            )
        }
    }

    /**
     * Check if OpenRouter management key is configured
     */
    fun isManagementKeyConfigured(): Boolean {
        return secretRepository.hasSecret("openrouter_management_key")
    }

    sealed class CreditsState {
        object Loading : CreditsState()
        data class Success(val credits: OpenRouterCreditsData) : CreditsState()
        data class Error(val message: String) : CreditsState()
    }
}
