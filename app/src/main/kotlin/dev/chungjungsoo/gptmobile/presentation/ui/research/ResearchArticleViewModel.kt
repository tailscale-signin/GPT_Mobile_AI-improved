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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArticleDetailState(val work: ResearchWork? = null, val version: ResearchWorkVersion? = null, val fields: List<StudyField> = emptyList(), val passages: List<EvidencePassage> = emptyList(), val appraisals: List<EvidenceAppraisal> = emptyList(), val bodyAvailable: Boolean = false, val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class ResearchArticleViewModel @Inject constructor(private val archive: ResearchArchiveRepository, private val coordinator: ArticleExaminationCoordinator, settings: SettingRepository) : ViewModel() {
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    val jobs = coordinator.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutable = MutableStateFlow(ArticleDetailState())
    val state = mutable.asStateFlow()
    private var loader: Job? = null
    init {
        viewModelScope.launch { coordinator.recover() }
    }
    fun open(workId: String, versionId: String?, authority: String) {
        loader?.cancel()
        mutable.value = ArticleDetailState(loading = true)
        loader = viewModelScope.launch {
            try {
                val work = requireNotNull(archive.dao.work(workId)) { "Saved article is unavailable" }
                val version = requireNotNull(versionId?.let { archive.dao.version(it) } ?: archive.dao.latestVersion(workId)) { "Saved version is missing" }
                require(version.workId == workId && (version.publicSource || version.authority == authority)) { "Article access is no longer authorized" }
                mutable.value = ArticleDetailState(work, version, archive.dao.fields(version.versionId), archive.dao.passages(version.versionId), archive.dao.appraisals(version.versionId), archive.text(version, authority) != null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.value = ArticleDetailState(error = error.message ?: "Article details unavailable")
            }
        }
    }
    fun examine(request: ExaminationRequest, tools: () -> ChatMcpToolConfig) = action { coordinator.userExamine(request, tools) }
    fun pause(id: String) = action { coordinator.pause(id) }
    fun cancel(id: String) = action { coordinator.cancel(id) }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.update { it.copy(error = error.message) }
            }
        }
    }
}
