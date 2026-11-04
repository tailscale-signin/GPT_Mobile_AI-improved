package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal data class GitHubWorkflowEvidence(
    val verifications: Map<String, JsonObject> = emptyMap(),
    val unreadableRuns: Set<String> = emptySet(),
    val limited: Boolean = false
)

/** Observe bounded metadata; decrypt only each run's latest retained verification. */
@HiltViewModel
class GitHubWorkflowViewModel @Inject constructor(private val workspace: WorkspaceRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GitHubWorkflowEvidence())
    internal val evidence = mutable.asStateFlow()
    private var chatId: Int? = null
    private var observation: Job? = null

    fun bind(value: Int?) {
        if (chatId == value) return
        chatId = value
        observation?.cancel()
        mutable.value = GitHubWorkflowEvidence()
        if (value == null) return
        observation = viewModelScope.launch(Dispatchers.IO) {
            workspace.dao.observeGitHubEvidence(value).collect { records ->
                val latest = records.filter { it.runId != null && ":verification_" in it.id }
                    .sortedWith(compareByDescending<dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord> { it.updatedAt }.thenByDescending { it.id })
                    .distinctBy { it.runId }
                val verifications = mutableMapOf<String, JsonObject>()
                val unreadable = mutableSetOf<String>()
                latest.forEach { record ->
                    val runId = requireNotNull(record.runId)
                    val verification = try {
                        workspace.protectedSynthesisCheckpoint(record.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (verification != null) verifications[runId] = verification else unreadable += runId
                }
                if (chatId == value) mutable.value = GitHubWorkflowEvidence(verifications, unreadable, records.size >= 80)
            }
        }
    }
}
