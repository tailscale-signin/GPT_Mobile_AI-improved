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
class DeepResearchSettingsViewModel @Inject constructor(private val repository: SettingRepository, private val savedState: SavedStateHandle, private val sessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore, private val archive: dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository, private val academic: dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient) : ViewModel() {
    private val mutable = MutableStateFlow(DeepResearchSettingsUiState())
    val state = mutable.asStateFlow()
    val settings = repository.observeFeatureSettings().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    val archiveCount = archive.dao.observeCount().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)
    val categories = archive.dao.categories().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val topics = archive.dao.topics().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val topicCounts = archive.dao.topicCounts().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val outcomes = archive.dao.observeOutcomes().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableDiscovery = MutableStateFlow("")
    val discovery = mutableDiscovery.asStateFlow()
    private var discoveryJob: kotlinx.coroutines.Job? = null
    private var archiveJob: kotlinx.coroutines.Job? = null
    private val mutablePreview = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.research.engines.CitationPreview>>(emptyList())
    internal val citationPreview = mutablePreview.asStateFlow()
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
        archiveJob?.cancel()
        archiveJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(250)
                archive.initialize()
                archive.observePage(query, topic, settings.value.researchFeature.peerReviewedOnly, offset).collect { rows.value = it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Archive unavailable; retained research history remains accessible") }
            }
        }
    }
    fun excludeRecall(workId: String) = archiveAction { archive.dao.excludeRecall(dev.chungjungsoo.gptmobile.data.research.feature.ResearchRecallExclusion(workId, System.currentTimeMillis())) }
    fun forgetOutcome(id: String) = archiveAction { archive.dao.forgetOutcome(id) }

    private suspend fun discoveryAllowed(): Boolean {
        val current = repository.getFeatureSettings()
        return current.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH) && current.researchFeature.enabled && current.delegation.deepResearch.enabled && current.delegation.deepResearch.academicSources
    }
    fun stopDiscovery() {
        discoveryJob?.cancel()
    }
    fun discover(query: String) {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mutableDiscovery.value = "Searching scholarly engines…"
            try {
                require(query.trim().length in 1..500)
                check(discoveryAllowed()) { "Enable Research and Papers and studies consent in Settings" }
                val admitted = repository.getFeatureSettings()
                val selected = dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient.CORE.toSet() - admitted.delegation.deepResearch.disabledEngineIds
                check(selected.isNotEmpty()) { "Select at least one native engine" }
                val owner = "research-session:" + java.util.UUID.randomUUID()
                val epoch = archive.dao.sequence()?.archiveEpoch ?: 0L
                var saved = 0
                kotlinx.coroutines.withContext(
                    dev.chungjungsoo.gptmobile.data.research.AcademicDiscoveryObserver { records ->
                        if (admitted.researchFeature.archiveEnabled) {
                            records.forEach { row ->
                                val paper = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper>(row.toString())
                                archive.ingestPaper(paper, owner, epoch, admitted.researchFeature.automaticOrganization) {
                                    discoveryAllowed() && repository.getFeatureSettings().researchFeature.archiveEnabled
                                }
                                saved++
                            }
                        }
                    }
                ) {
                    val result = academic.search(
                        dev.chungjungsoo.gptmobile.data.research.academic.AcademicSearchRequest(query.trim(), 50, maxResults = 40),
                        dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(true, 50, enabledProviders = selected),
                        ::discoveryAllowed
                    )
                    mutableDiscovery.value = "${result.papers.size} results · $saved admissions · " + result.providers.joinToString { "${it.id}: ${it.state}" }
                }
            } catch (cancel: CancellationException) {
                mutableDiscovery.value = "Stopped. Saved studies remain in Archive."
                throw cancel
            } catch (error: Exception) {
                mutableDiscovery.value = error.message ?: "Discovery unavailable; saved studies are retained"
            }
        }
    }
    fun openScholar(query: String, open: (String) -> Unit) = archiveAction {
        check(discoveryAllowed()) { "Enable Research and Papers and studies consent in Settings" }
        val url = dev.chungjungsoo.gptmobile.data.research.engines.ScholarCitationImport.scholarUrl(query)
        val id = java.util.UUID.randomUUID().toString()
        archive.dao.handoff(dev.chungjungsoo.gptmobile.data.research.feature.ResearchHandoff(id, query.trim(), "AWAITING_IMPORT", System.currentTimeMillis()))
        savedState["scholarHandoff"] = id
        savedState["scholarQuery"] = query.trim()
        open(url)
    }
    fun previewCitationFile(uri: android.net.Uri, context: android.content.Context) = archiveAction {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readNBytes(262145)
                require(bytes.size <= 262144) { "Citation files must be at most 256 KiB" }
                bytes.decodeToString(throwOnInvalidSequence = true)
            } ?: error("Citation file is unavailable")
        }
        mutablePreview.value = dev.chungjungsoo.gptmobile.data.research.engines.ScholarCitationImport.parse(text)
    }
    fun previewCitations(text: String) = archiveAction { mutablePreview.value = dev.chungjungsoo.gptmobile.data.research.engines.ScholarCitationImport.parse(text) }
    fun cancelImport() = archiveAction {
        mutablePreview.value = emptyList()
        savedState.get<String>("scholarHandoff")?.let { id -> archive.dao.handoff(id)?.let { archive.dao.handoff(it.copy(status = "CANCELED")) } }
    }
    fun importCitations() = archiveAction {
        check(discoveryAllowed()) { "Enable Research and Papers and studies consent in Settings" }
        val settings = repository.getFeatureSettings().researchFeature
        check(settings.archiveEnabled) { "Enable automatic archive entries before importing" }
        val epoch = archive.dao.sequence()?.archiveEpoch ?: 0L
        val owner = savedState.get<String>("scholarHandoff") ?: "import:" + java.util.UUID.randomUUID()
        val allowed: suspend () -> Boolean = { discoveryAllowed() && repository.getFeatureSettings().researchFeature.archiveEnabled }
        for (preview in mutablePreview.value) {
            val resolved = try {
                if (preview.identifier.isNotBlank()) academic.getPaper(preview.identifier, allowed) else null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                check(allowed()) { "Research permission changed" }
                null
            }
            val paper = resolved?.copy(sourceProviders = (resolved.sourceProviders + "google_scholar_user_import").distinct())
                ?: dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper(
                    paperId = "import:" + dev.chungjungsoo.gptmobile.data.research.researchHash(preview.toString()),
                    title = preview.title,
                    canonicalUrl = preview.url.ifBlank { dev.chungjungsoo.gptmobile.data.research.engines.ScholarCitationImport.scholarUrl(preview.title.take(500)) },
                    sourceProviders = listOf("google_scholar_user_import"),
                    publicationType = "unverified_bibliographic_entry"
                )
            archive.ingestPaper(paper, owner, epoch, settings.automaticOrganization, allowed)
        }
        archive.dao.handoff(owner)?.let { archive.dao.handoff(it.copy(status = "IMPORTED")) }
        mutablePreview.value = emptyList()
        mutableDiscovery.value = "Selected citations imported. Unresolved bibliographic entries are labelled unverified."
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
