package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LocalToolsViewModel @Inject constructor(private val settings: SettingRepository) : ViewModel() {
    val delegation = settings.observeFeatureSettings().map { it.delegation.normalized() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelDelegationSettings())
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun update(transform: (ModelDelegationSettings) -> ModelDelegationSettings) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val latest = settings.getFeatureSettings()
                settings.updateFeatureSettings(latest.copy(delegation = transform(latest.delegation).normalized()))
                _error.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _error.value = "Could not save delegation settings. Try again."
            } finally {
                _busy.value = false
            }
        }
    }
}
