package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitStore
import dev.chungjungsoo.gptmobile.data.agent.withCurrentProviderWaitRows
import dev.chungjungsoo.gptmobile.data.agent.withCurrentProviderWaitSchedules
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal data class GitHubWorkflowEvidence(
    val verifications: Map<String, JsonObject> = emptyMap(),
    val unreadableRuns: Set<String> = emptySet(),
    val limited: Boolean = false,
    val providerWaits: Map<String, ProviderWaitDeadline> = emptyMap(),
    val prerequisiteRunId: String? = null,
    val prerequisiteFence: Long? = null,
    val prerequisiteNoticeRunId: String? = null,
    val prerequisiteNoticeFence: Long? = null,
    val prerequisiteNotice: String? = null
)

/** Observe bounded metadata; decrypt only each run's latest retained verification. */
@HiltViewModel
class GitHubWorkflowViewModel @Inject constructor(
    private val workspace: WorkspaceRepository,
    private val settings: SettingRepository,
    private val providerWaits: ProviderWaitStore,
    private val connections: ToolConnectionRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(GitHubWorkflowEvidence())
    internal val evidence = mutable.asStateFlow()
    private var chatId: Int? = null
    private var observation: Job? = null
    private var prerequisite: Job? = null
    private val prerequisiteOperation = GitHubPrerequisiteOperation()

    fun bind(value: Int?) {
        if (chatId == value) return
        chatId = value
        observation?.cancel()
        prerequisiteOperation.clear()
        prerequisite?.cancel()
        mutable.value = GitHubWorkflowEvidence()
        if (value == null) return
        observation = viewModelScope.launch(Dispatchers.IO) {
            launch {
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
                    if (chatId == value) mutable.update { it.copy(verifications = verifications, unreadableRuns = unreadable, limited = records.size >= 80) }
                }
            }
            launch {
                val dao = workspace.database.agentPersistenceDao()
                dao.observeProviderWaitDeadlines(value)
                    .withCurrentProviderWaitRows(dao::observeProviderWaitRows)
                    .withCurrentProviderWaitSchedules(settings::fetchPlatformV2s)
                    .collect { deadlines ->
                        if (chatId == value) mutable.update { it.copy(providerWaits = deadlines.associateBy(ProviderWaitDeadline::runId)) }
                    }
            }
        }
    }

    internal fun retryAfterProviderDeadline(expected: AgentRun) = prerequisite(expected) { isCurrent ->
        val evidence = GitHubWorkflowRecovery.load(workspace, expected) ?: return@prerequisite null
        val dao = workspace.database.agentPersistenceDao()
        val row = dao.rawProviderWait(expected.runId) ?: return@prerequisite null
        if (evidence.snapshot.evidenceComplete == false || evidence.snapshot.reconciliationRequired || GitHubWorkflowPrerequisites.providerDeadline(expected, ProviderWaitDeadline(row.runId, row.fence, row.retryAt), evidence.snapshot.effectiveRecoveryAction) == null) return@prerequisite null
        val message = dao.recoveryMessage(expected.assistantMessageId) ?: return@prerequisite null
        if (!isCurrent()) return@prerequisite null
        val deadline = providerWaits.reattachOwned(message, expected) ?: return@prerequisite null
        "The saved run remains scheduled for ${java.time.Instant.ofEpochMilli(deadline.effectiveRetryAt)} or later. No new request was created."
    }

    internal fun refreshAuthority(expected: AgentRun, openProfileSettings: (String) -> Unit) = prerequisite(expected) { isCurrent ->
        val evidence = GitHubWorkflowRecovery.load(workspace, expected) ?: return@prerequisite null
        if (!evidence.snapshot.authorityReviewAvailable) return@prerequisite null
        val dao = workspace.database.agentPersistenceDao()
        val review = GitHubWorkflowPrerequisites.authorityReview(
            expected,
            dao::recoveryRun,
            dao::recoveryMessage,
            dao::getToolEventsForRun,
            settings::fetchPlatformV2s,
            connections::getConnection
        ) ?: return@prerequisite null
        if (GitHubWorkflowRecovery.load(workspace, expected)?.snapshot?.authorityReviewAvailable != true || !isCurrent()) return@prerequisite null
        val detail = when {
            review.unavailableConnections > 0 -> "${review.unavailableConnections} saved connection(s) are unavailable. No replacement was selected."
            review.connectionNames.isEmpty() -> "No connection identity was saved; only this profile's settings can be opened."
            else -> "Saved connections: ${review.connectionNames.joinToString()}."
        }
        withContext(Dispatchers.Main.immediate) { if (isCurrent()) openProfileSettings(review.profileUid) }
        "Opened saved profile settings for ${review.profileName}. $detail Review its provider and Tools settings; the saved task remains paused and existing grants are unchanged."
    }

    private fun prerequisite(expected: AgentRun, action: suspend (() -> Boolean) -> String?) {
        if (expected.chatId != chatId || prerequisite?.isActive == true) return
        val token = prerequisiteOperation.begin(expected)
        fun isCurrent() = chatId == expected.chatId && prerequisiteOperation.owns(token)
        mutable.update { it.copy(prerequisiteRunId = expected.runId, prerequisiteFence = expected.ownershipFence, prerequisiteNoticeRunId = expected.runId, prerequisiteNoticeFence = expected.ownershipFence, prerequisiteNotice = null) }
        prerequisite = viewModelScope.launch(Dispatchers.IO) {
            try {
                val notice = action(::isCurrent) ?: "This saved run, checkpoint or configuration is no longer available for this action. Reopen its current task details; no new request was started."
                val current = workspace.database.agentPersistenceDao().recoveryRun(expected.runId)
                if (isCurrent() && current?.let { GitHubWorkflowRecovery.sameAttempt(expected, it) } == true) mutable.update { it.copy(prerequisiteNotice = notice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) mutable.update { it.copy(prerequisiteNotice = "The saved prerequisites could not be refreshed. No new request was started and no access was granted.") }
            } finally {
                if (isCurrent()) {
                    mutable.update { it.copy(prerequisiteRunId = null, prerequisiteFence = null) }
                }
            }
        }
    }
}
