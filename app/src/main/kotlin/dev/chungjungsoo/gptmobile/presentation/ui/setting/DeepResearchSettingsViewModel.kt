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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DeepResearchSettingsUiState(val draft: DeepResearchSettings? = null, val featureDraft: dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings = dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings(), val dirty: Boolean = false, val saving: Boolean = false, val error: String? = null, val storage: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore.StorageStats? = null)

@HiltViewModel
class DeepResearchSettingsViewModel @Inject constructor(private val repository: SettingRepository, private val savedState: SavedStateHandle, private val sessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore, private val archive: dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository) : ViewModel() {
    private val mutable = MutableStateFlow(DeepResearchSettingsUiState())
    val state = mutable.asStateFlow()
    val settings = repository.observeFeatureSettings().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    val archiveCount = archive.dao.observeCount().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)
    val categories = archive.dao.categories().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = archive.dao.topics().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    private val rows = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.research.feature.ArchiveRow>>(emptyList())
    val archiveRows = rows.asStateFlow()
    private val comparisonRows = MutableStateFlow<List<dev.chungjungsoo.gptmobile.presentation.ui.research.ArticleComparison>>(emptyList())
    val comparison = comparisonRows.asStateFlow()
    fun closeComparison() {
        comparisonRows.value = emptyList()
    }
    fun compareVersions(ids: List<String>) = archiveAction {
        require(ids.distinct().size in 2..mutable.value.featureDraft.comparisonLimit)
        comparisonRows.value = ids.map { id ->
            val version = requireNotNull(archive.dao.version(id))
            require(version.publicSource) { "Private evidence cannot enter the public archive comparison" }
            dev.chungjungsoo.gptmobile.presentation.ui.research.ArticleComparison(requireNotNull(archive.dao.work(version.workId)), version, archive.dao.fields(id), archive.dao.appraisals(id))
        }
    }
    private var original: DeepResearchSettings? = null
    private var originalFeature: dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings? = null
    private var archiveQuery = ""
    private var archiveTopic = ""
    private var archiveOffset = 0
    fun loadArchive(query: String = archiveQuery, topic: String = archiveTopic, offset: Int = 0) {
        archiveQuery = query
        archiveTopic = topic
        archiveOffset = offset
        viewModelScope.launch {
            try {
                rows.value = archive.page(query, topic, mutable.value.featureDraft.peerReviewedOnly, offset)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Archive unavailable; retained research history remains accessible") }
            }
        }
    }
    fun clearArchive() = archiveAction {
        archive.dao.clearArchive(System.currentTimeMillis())
        loadArchive()
    }
    fun removeFromArchive(id: String) = archiveAction {
        archive.remove(id)
        loadArchive(offset = archiveOffset)
    }
    fun renameTopic(id: String, label: String) = archiveAction {
        archive.renameTopic(id, label)
        loadArchive()
    }
    fun renameCategory(id: String, label: String) = archiveAction { archive.renameCategory(id, label) }
    fun createTopic(categoryId: String, label: String) = archiveAction { archive.createTopic(categoryId, label) }
    fun moveWork(workId: String, topicId: String) = archiveAction {
        archive.move(workId, topicId)
        loadArchive(offset = archiveOffset)
    }
    fun mergeTopics(from: String, to: String) = archiveAction {
        archive.mergeTopics(from, to)
        loadArchive(topic = "")
    }
    private fun archiveAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.update { it.copy(error = error.message ?: "Archive change failed") }
            }
        }
    }

    fun editFeature(value: dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings) {
        savedState["researchFeatureDraft"] = Json.encodeToString(value)
        mutable.update { it.copy(featureDraft = value, dirty = it.draft != original || value != originalFeature, error = null) }
    }

    fun open() {
        viewModelScope.launch {
            try {
                val current = repository.getFeatureSettings()
                original = current.delegation.deepResearch.migrated()
                originalFeature = current.researchFeature
                val feature = savedState.get<String>("researchFeatureDraft")?.let { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings>(it) } ?: current.researchFeature
                val draft = savedState.get<String>("researchDraft")?.let { Json.decodeFromString<DeepResearchSettings>(it) }
                    ?: current.delegation.deepResearch
                mutable.value = DeepResearchSettingsUiState(draft.migrated(), feature, draft.migrated() != original || feature != originalFeature)
                loadArchive()
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
        mutable.update { it.copy(draft = value, dirty = value != original || it.featureDraft != originalFeature, error = null) }
    }

    fun save(onSuccess: () -> Unit) {
        val value = mutable.value.draft ?: return
        if (mutable.value.saving) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val feature = mutable.value.featureDraft.validated()
                repository.mutateFeatureSettings { current -> current.copy(delegation = current.delegation.copy(deepResearch = value.migrated()), researchFeature = feature) }
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
        savedState.remove<String>("researchFeatureDraft")
        mutable.value = DeepResearchSettingsUiState()
    }
}
