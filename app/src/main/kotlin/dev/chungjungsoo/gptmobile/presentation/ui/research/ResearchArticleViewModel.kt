package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator
import dev.chungjungsoo.gptmobile.data.research.feature.EvidenceAppraisal
import dev.chungjungsoo.gptmobile.data.research.feature.EvidencePassage
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWork
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWorkVersion
import dev.chungjungsoo.gptmobile.data.research.feature.StudyField
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArticleDetailState(val work: ResearchWork? = null, val version: ResearchWorkVersion? = null, val fields: List<StudyField> = emptyList(), val passages: List<EvidencePassage> = emptyList(), val appraisals: List<EvidenceAppraisal> = emptyList(), val bodyAvailable: Boolean = false, val loading: Boolean = false, val acceptedJobId: String? = null, val error: String? = null, val reviews: List<dev.chungjungsoo.gptmobile.data.research.feature.ResearchStudyReview> = emptyList(), val validations: List<dev.chungjungsoo.gptmobile.data.research.feature.ResearchSourceValidation> = emptyList(), val relationships: List<dev.chungjungsoo.gptmobile.data.research.feature.ResearchIdentityResolution> = emptyList())

@HiltViewModel
class ResearchArticleViewModel @Inject constructor(private val archive: ResearchArchiveRepository, private val coordinator: ArticleExaminationCoordinator, private val settings: SettingRepository) : ViewModel() {
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    val jobs = coordinator.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutable = MutableStateFlow(ArticleDetailState())
    val state = mutable.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val focusedJob = state.map { it.acceptedJobId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf<dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob?>(null) else archive.dao.observeJob(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private var loader: Job? = null
    private data class Selection(val workId: String, val versionId: String?, val authority: String)
    private var openedSelection: Selection? = null
    private var selectionGeneration = 0L
    private val admitting = MutableStateFlow(false)
    val admissionInProgress = admitting.asStateFlow()
    init {
        viewModelScope.launch { coordinator.recover() }
    }
    fun open(workId: String, versionId: String?, authority: String) {
        loader?.cancel()
        val selection = Selection(workId, versionId, authority)
        val sameSelection = openedSelection == selection
        openedSelection = selection
        if (!sameSelection) selectionGeneration++
        val generation = selectionGeneration
        mutable.value = if (sameSelection) mutable.value.copy(loading = true) else ArticleDetailState(loading = true)
        loader = viewModelScope.launch {
            try {
                val work = requireNotNull(archive.dao.work(workId)) { "Saved article is unavailable" }
                val version = requireNotNull(versionId?.let { archive.dao.version(it) } ?: archive.dao.latestVersion(workId)) { "Saved version is missing" }
                require(version.workId == workId && (version.publicSource || version.authority == authority)) { "Article access is no longer authorized" }
                val available = archive.text(version, authority) != null
                val details = ArticleDetailState(work, archive.dao.version(version.versionId), archive.dao.fields(version.versionId), archive.dao.passages(version.versionId), archive.dao.appraisals(version.versionId), available, validations = archive.dao.sourceValidations(version.versionId), relationships = archive.dao.identityResolutions(workId), reviews = archive.dao.studyReviews(version.versionId))
                currentCoroutineContext().ensureActive()
                if (selectionGeneration == generation) mutable.update { details.copy(acceptedJobId = it.acceptedJobId, error = it.error) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (selectionGeneration == generation) mutable.value = ArticleDetailState(error = error.message ?: "Article details unavailable")
            }
        }
    }
    fun saveCrawlerForChat(chatId: Int, profileUid: String, tools: ChatMcpToolConfig) = action {
        require(chatId > 0 && profiles.value.any { it.uid == profileUid && it.enabled })
        settings.mutateFeatureSettings { current ->
            val selection = current.conversationResearch[chatId] ?: tools.research ?: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection()
            current.copy(conversationResearch = current.conversationResearch + (chatId to selection.copy(crawlerProfileUid = profileUid)))
        }
    }
    fun examine(request: ExaminationRequest, tools: () -> ChatMcpToolConfig) {
        if (!admitting.compareAndSet(false, true)) return
        val generation = selectionGeneration
        viewModelScope.launch {
            try {
                val admission = coordinator.submitUserAction(request, tools)
                if (selectionGeneration == generation) mutable.update { it.copy(acceptedJobId = admission.jobId, error = admission.message?.let { message -> "${admission.blocker}: $message" }) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (selectionGeneration == generation) mutable.update { it.copy(error = error.message ?: "Examination could not start") }
            } finally {
                admitting.value = false
            }
        }
    }
    fun pause(id: String) = action { coordinator.pause(id) }
    fun cancel(id: String) = action { coordinator.cancel(id) }
    private fun action(block: suspend () -> Unit) {
        val generation = selectionGeneration
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (selectionGeneration == generation) mutable.update { it.copy(error = error.message) }
            }
        }
    }
}
