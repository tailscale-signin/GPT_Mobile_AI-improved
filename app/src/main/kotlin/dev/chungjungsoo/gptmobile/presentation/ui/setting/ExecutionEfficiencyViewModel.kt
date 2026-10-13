package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ExecutionEfficiencyViewModel @Inject constructor(private val repository: SettingRepository, ledger: InvocationLedger) : ViewModel() {
    val settings = repository.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppFeatureSettings())
    val records = ledger.dao.statistics().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun update(change: (ExecutionPolicy) -> ExecutionPolicy) {
        viewModelScope.launch {
            try {
                repository.mutateFeatureSettings { it.copy(executionPolicy = change(it.executionPolicy).validate()) }
                mutableError.value = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Could not save execution policy"
            }
        }
    }
}
