package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.nvidia.NvidiaModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NvidiaModelPickerState(
    val loading: Boolean = false,
    val live: Boolean = false,
    val models: List<ProfileModelOption> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class NvidiaModelPickerViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val repository: NvidiaModelRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(NvidiaModelPickerState())
    val state = mutableState.asStateFlow()
    private var currentJob: Job? = null

    fun load(apiUrl: String, apiKey: String, connectionUid: String?, forceRefresh: Boolean = false) {
        currentJob?.cancel()
        mutableState.value = NvidiaModelPickerState(loading = true)
        currentJob = viewModelScope.launch {
            try {
                // Reusable connections keep their key in SecretVault, not in the UI's connection card.
                val raw = apiKey.ifBlank { connectionUid?.let { settings.getProviderCredentials(it) }.orEmpty() }
                val key = ApiCredentialRotator.parseKeys(raw).firstOrNull()
                require(!key.isNullOrBlank()) { "Enter an API key or select a configured NVIDIA connection to load models." }
                val models = repository.load(apiUrl, key, forceRefresh)
                mutableState.value = NvidiaModelPickerState(live = true, models = models)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = NvidiaModelPickerState(
                    error = error.message?.take(240) ?: "Model discovery unavailable. Enter a model ID manually."
                )
            }
        }
    }
}
