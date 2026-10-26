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
import dev.chungjungsoo.gptmobile.data.research.feature.recordConsent
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    data class ConsentPrompt(val id: String, val profileUid: String, val profileName: String)
    private data class PendingConsent(val prompt: ConsentPrompt, val request: ExaminationRequest, val profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2, val tools: () -> ChatMcpToolConfig, val generation: Long)
    private var pendingConsent: PendingConsent? = null
    private var consentEpoch = 0L
    private var checkingConsent = false
    private val consent = MutableStateFlow<ConsentPrompt?>(null)
    val examinationConsent = consent.asStateFlow()
    init {
        viewModelScope.launch { coordinator.recover() }
    }
    fun open(workId: String, versionId: String?, authority: String) {
        loader?.cancel()
        val selection = Selection(workId, versionId, authority)
        val sameSelection = openedSelection == selection
        openedSelection = selection
        if (!sameSelection) {
            selectionGeneration++
            declineExaminationConsent()
        }
        val generation = selectionGeneration
        mutable.value = if (sameSelection) mutable.value.copy(loading = true) else ArticleDetailState(loading = true)
        // Source hashing, body decoding and detail assembly can be large; keep them off the UI thread.
        loader = viewModelScope.launch(Dispatchers.IO) {
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
        require(chatId > 0 && (profileUid.isBlank() || profiles.value.count { it.uid == profileUid && it.enabled } == 1))
        settings.mutateFeatureSettings { current ->
            val selection = current.conversationResearch[chatId] ?: tools.research ?: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection()
            current.copy(conversationResearch = current.conversationResearch + (chatId to selection.copy(crawlerProfileUid = profileUid.takeIf { it.isNotBlank() })))
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
    fun keepWorksSeparate() = action {
        val selection = requireNotNull(openedSelection)
        archive.keepConflictingWorksSeparate(selection.workId, selection.authority)
        open(selection.workId, selection.versionId, selection.authority)
    }

    /** No job exists until the profile-specific consent is accepted. */
    fun requestExamination(request: ExaminationRequest, tools: () -> ChatMcpToolConfig) {
        if (admitting.value || checkingConsent || pendingConsent != null) return
        checkingConsent = true
        val generation = selectionGeneration
        val epoch = consentEpoch
        action {
            try {
                val profiles = settings.fetchPlatformV2s()
                val current = settings.getFeatureSettings()
                if (generation != selectionGeneration || epoch != consentEpoch) return@action
                val profile = dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver.examinerSelection(current, profiles, request.chatId, tools(), request.profileUid).profile
                val selectedRequest = profile?.let { request.copy(profileUid = it.uid) } ?: request
                val blocker = profile?.let { dev.chungjungsoo.gptmobile.data.research.feature.examinationAdmission(current, it, request.copy(origin = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.USER_EXAMINE), tools()) }
                if (profile != null && blocker == dev.chungjungsoo.gptmobile.data.research.feature.EXAMINATION_PAPERS_CONSENT_REQUIRED) {
                    val prompt = ConsentPrompt(java.util.UUID.randomUUID().toString(), profile.uid, profile.name.ifBlank { profile.model })
                    settings.mutateFeatureSettings { current -> current.copy(researchFeature = current.researchFeature.recordConsent(prompt.id, profile.uid, "OFFERED")) }
                    if (generation != selectionGeneration || epoch != consentEpoch) {
                        return@action
                    }
                    pendingConsent = PendingConsent(prompt, selectedRequest, profile, tools, generation)
                    consent.value = prompt
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "CONSENT_PRESENTED · promptId=${prompt.id} · profile=${profile.uid}")
                } else {
                    examine(selectedRequest, tools)
                }
            } finally {
                checkingConsent = false
            }
        }
    }

    fun declineExaminationConsent() {
        pendingConsent?.let { pending ->
            viewModelScope.launch {
                try {
                    settings.mutateFeatureSettings { current -> current.copy(researchFeature = current.researchFeature.recordConsent(pending.prompt.id, pending.profile.uid, "DISMISSED")) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutable.update { it.copy(error = "Consent was dismissed, but its history could not be saved.") }
                }
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "CONSENT_DISMISSED · promptId=${pending.prompt.id} · profile=${pending.profile.uid} · granted=false")
        }
        pendingConsent = null
        consent.value = null
        consentEpoch++
    }

    fun revokePapersConsent(profileUid: String) = action {
        declineExaminationConsent()
        settings.mutateFeatureSettings { current ->
            val behavior = current.profileBehavior[profileUid] ?: dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings()
            current.copy(profileBehavior = current.profileBehavior + (profileUid to behavior.copy(academicSourcesAllowed = false)), researchFeature = current.researchFeature.recordConsent(java.util.UUID.randomUUID().toString(), profileUid, "REVOKED"))
        }
    }

    fun acceptExaminationConsent(id: String) {
        val pending = pendingConsent?.takeIf { it.prompt.id == id } ?: return
        if (checkingConsent || admitting.value) return
        checkingConsent = true
        // Accept clears the prompt without recording a contradictory decline event.
        pendingConsent = null
        consent.value = null
        consentEpoch++
        val epoch = consentEpoch
        action {
            try {
                require(pending.generation == selectionGeneration && epoch == consentEpoch) { "Article selection changed. Choose Examine again." }
                val profile = requireNotNull(settings.fetchPlatformV2s().singleOrNull { it.uid == pending.profile.uid }) { "Examiner is no longer available." }
                require(profile == pending.profile && profile.enabled && !profile.disableAllTools && !profile.disableRemoteTools) { "Examiner configuration changed. Choose Examine again." }
                settings.mutateFeatureSettings { current ->
                    check(pending.generation == selectionGeneration && epoch == consentEpoch) { "Article selection changed. Choose Examine again." }
                    check(current.researchFeature.enabled && current.isToolPluginEnabledForProfile(profile.uid, dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH)) { "Research is disabled for this profile" }
                    val behavior = current.profileBehavior[profile.uid] ?: dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings()
                    val accepted = current.copy(profileBehavior = current.profileBehavior + (profile.uid to behavior.copy(researchEnabledOverride = true, academicSourcesAllowed = true)), researchFeature = current.researchFeature.recordConsent(pending.prompt.id, profile.uid, "ACCEPTED"))
                    dev.chungjungsoo.gptmobile.data.research.feature.examinationAdmission(accepted, profile, pending.request.copy(origin = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.USER_EXAMINE), pending.tools(), checkCapacity = false)?.let { error(it) }
                    accepted
                }
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "CONSENT_ACCEPTED · promptId=${pending.prompt.id} · profile=${profile.uid} · granted=true")
                if (pending.generation == selectionGeneration && epoch == consentEpoch) examine(pending.request, pending.tools)
            } finally {
                checkingConsent = false
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
