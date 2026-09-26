package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FactVaultViewModel @Inject constructor(private val repository: FactVaultRepository) : ViewModel() {
    val vault = repository.state
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = perform { repository.load() }
    fun updateSettings(settings: dev.chungjungsoo.gptmobile.data.rag.FactVaultSettings) = perform { repository.updateSettings(settings) }
    fun setEnabled(enabled: Boolean) = perform { repository.setEnabled(enabled) }
    fun setFactEnabled(id: String, enabled: Boolean) = perform { repository.setFactEnabled(id, enabled) }
    fun delete(id: String) = perform { repository.deleteFact(id) }
    fun clear() = perform { repository.clear() }

    private fun perform(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                _error.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _error.value = "Could not update Fact Vault. Your saved facts were not changed."
            } finally {
                _busy.value = false
            }
        }
    }
}
