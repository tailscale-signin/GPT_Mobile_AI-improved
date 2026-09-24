package dev.chungjungsoo.gptmobile.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterCreditsRepository
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OpenRouterSettingsViewModel @Inject constructor(
    private val repository: OpenRouterSettingsRepository,
    private val creditsRepository: OpenRouterCreditsRepository? = null
) : ViewModel() {

    private val _settings = MutableLiveData<OpenRouterSettings>()
    val settings: LiveData<OpenRouterSettings> = _settings

    private val _uiState = MutableStateFlow(OpenRouterSettings(apiKey = ""))
    val uiState: StateFlow<OpenRouterSettings> = _uiState.asStateFlow()

    private val _providerStatus = MutableStateFlow(OpenRouterProviderStatus())
    val providerStatus: StateFlow<OpenRouterProviderStatus> = _providerStatus.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val s = repository.loadSettings()
            _settings.value = s
            _uiState.value = s
            if (s.apiKey.isNotBlank()) refreshCredits()
        }
    }

    fun updateSettings(newSettings: OpenRouterSettings) {
        val validated = newSettings.copy(
            batchSize = newSettings.batchSize.coerceIn(1, 100),
            maxRetries = newSettings.maxRetries.coerceIn(0, 10),
            cacheTtlSeconds = newSettings.cacheTtlSeconds.coerceAtLeast(0)
        )
        viewModelScope.launch {
            repository.saveSettings(validated)
            _settings.value = validated
            _uiState.value = validated
        }
    }

    fun refreshCredits() {
        val repo = creditsRepository ?: return
        val key = _uiState.value.apiKey.trim()
        if (key.isBlank()) {
            _providerStatus.value = OpenRouterProviderStatus()
            return
        }
        viewModelScope.launch {
            _providerStatus.value = _providerStatus.value.copy(isLoadingCredits = true, creditsError = null)
            repo.fetchCredits(key, forceRefresh = true)
                .onSuccess { credits ->
                    _providerStatus.value = OpenRouterProviderStatus(
                        credits = credits,
                        isLoadingCredits = false,
                        creditsError = null,
                        lastUpdatedAt = System.currentTimeMillis()
                    )
                }
                .onFailure { error ->
                    _providerStatus.value = _providerStatus.value.copy(
                        isLoadingCredits = false,
                        creditsError = error.message ?: "Credits unavailable"
                    )
                }
        }
    }

    fun validateApiKey(key: String): Boolean {
        return key.isNotBlank() && key.length >= 32
    }
}

data class OpenRouterProviderStatus(
    val credits: OpenRouterCreditsData? = null,
    val isLoadingCredits: Boolean = false,
    val creditsError: String? = null,
    val lastUpdatedAt: Long? = null
)
