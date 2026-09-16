package dev.chungjungsoo.gptmobile.presentation.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterCreditsRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CreditsViewModel @Inject constructor(
    private val creditsRepository: OpenRouterCreditsRepository,
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _creditsState = MutableStateFlow<CreditsState>(CreditsState.Idle)
    val creditsState: StateFlow<CreditsState> = _creditsState.asStateFlow()

    fun fetchCredits(apiKey: String, forceRefresh: Boolean = false) {
        if (apiKey.isBlank()) {
            _creditsState.value = CreditsState.Error("OpenRouter API key is required")
            return
        }

        viewModelScope.launch {
            _creditsState.value = CreditsState.Loading
            val result = creditsRepository.fetchCredits(apiKey, forceRefresh)
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

    sealed class CreditsState {
        object Idle : CreditsState()
        object Loading : CreditsState()
        data class Success(val credits: OpenRouterCreditsData) : CreditsState()
        data class Error(val message: String) : CreditsState()
    }
}
