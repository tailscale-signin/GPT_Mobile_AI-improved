package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DeepResearchSettingsUiState(val draft: DeepResearchSettings? = null, val saving: Boolean = false, val error: String? = null, val storage: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore.StorageStats? = null)

@HiltViewModel
class DeepResearchSettingsViewModel @Inject constructor(private val repository: SettingRepository, private val savedState: SavedStateHandle, private val sessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(DeepResearchSettingsUiState())
    val state = mutable.asStateFlow()

    fun open() {
        viewModelScope.launch {
            try {
                val draft = savedState.get<String>("researchDraft")?.let { Json.decodeFromString<DeepResearchSettings>(it) }
                    ?: repository.getFeatureSettings().delegation.deepResearch
                mutable.value = DeepResearchSettingsUiState(draft.migrated())
                refreshStorage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = DeepResearchSettingsUiState(error = "Research settings could not be loaded. Close and retry; no settings were changed.")
            }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            try {
                val storage = sessions.storageStats()
                mutable.update { it.copy(storage = storage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Research storage diagnostics are unavailable; the editor remains usable.") }
            }
        }
    }

    fun migrate(session: dev.chungjungsoo.gptmobile.data.research.ResearchSession) {
        viewModelScope.launch {
            try {
                val settings = repository.getFeatureSettings().delegation.deepResearch
                sessions.migrateLegacyRun(session, dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(settings))
                refreshStorage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "This run could not be migrated. Its original evidence and counters are kept.") }
            }
        }
    }

    fun edit(value: DeepResearchSettings) {
        savedState["researchDraft"] = Json.encodeToString(value)
        mutable.update { it.copy(draft = value, error = null) }
    }

    fun save(onSuccess: () -> Unit) {
        val value = mutable.value.draft ?: return
        if (mutable.value.saving) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.updateResearchSettings(value)
                discard()
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not save research settings. Your draft is kept; retry Save.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun discard() {
        savedState.remove<String>("researchDraft")
        mutable.value = DeepResearchSettingsUiState()
    }
}
