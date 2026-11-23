package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayAgentWorkspace
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringCheckpoints
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
import dev.chungjungsoo.gptmobile.data.github.GatewayObserverPolling
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceCommandReceipt
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceSource
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceSourcePath
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkspaceTree
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class CandidatePageCursor(val binding: GatewayEngineeringBinding, val checkId: String, val offset: Int, val next: Int?, val left: String? = null)

data class GatewayEngineeringUiState(
    val bindings: List<GatewayEngineeringBinding> = emptyList(),
    val options: GatewayEngineeringSettings = GatewayEngineeringSettings(),
    val busy: Boolean = false,
    val error: String? = null,
    val evidencePreview: String? = null,
    val candidateCursor: CandidatePageCursor? = null,
    val checkpoints: Pair<GatewayEngineeringBinding, dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringCheckpoints>? = null,
    val workspaceRequestId: String? = null,
    val workspace: GatewayAgentWorkspace? = null,
    val workspaceCandidates: GatewayEngineeringCheckpoints? = null,
    val workspaceCandidate: CandidatePageCursor? = null,
    val workspaceDiff: String? = null,
    val workspaceTree: GatewayWorkspaceTree? = null,
    val workspaceTreeQuery: String = "",
    val workspaceSource: GatewayWorkspaceSource? = null,
    val workspaceReceipt: GatewayWorkspaceCommandReceipt? = null,
    val workspaceNotice: String? = null
)

@HiltViewModel
class GatewayEngineeringViewModel @Inject constructor(
    private val repository: GatewayEngineeringRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayEngineeringUiState())
    val state = mutable.asStateFlow()

    @Volatile private var chat: Int? = null
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private var polling: Job? = null
    private var localRead: Job? = null
    private val visibleOwners = mutableMapOf<String, Int>()

    /** Cached jobs remain readable even when their original profile is unavailable. */
    fun bind(chatId: Int) {
        val selectedEpoch = synchronized(epoch) {
            if (chat == chatId) return
            chat = chatId
            epoch.incrementAndGet().also {
                mutable.update { it.copy(bindings = emptyList(), evidencePreview = null, candidateCursor = null, checkpoints = null, error = null, workspaceRequestId = null, workspace = null, workspaceCandidates = null, workspaceCandidate = null, workspaceDiff = null, workspaceTree = null, workspaceTreeQuery = "", workspaceSource = null, workspaceReceipt = null, workspaceNotice = null) }
            }
        }
        polling?.cancel()
        localRead?.cancel()
        localRead = viewModelScope.launch(ioDispatcher) {
            try {
                val saved = repository.bindings(chatId)
                val options = repository.options()
                updateForEpoch(selectedEpoch) { it.copy(bindings = saved, options = options) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateForEpoch(selectedEpoch) { it.copy(error = failure.message) }
            }
        }
    }

    fun setVisible(owner: String, chatId: Int, visible: Boolean) {
        if (visible) {
            visibleOwners[owner] = chatId
            bind(chatId)
            if (polling?.isActive != true) observeVisible(chatId)
        } else {
            visibleOwners.remove(owner)
            if (visibleOwners.values.none { it == chat }) {
                polling?.cancel()
                polling = null
            }
        }
    }

    private fun observeVisible(chatId: Int) {
        val selectedEpoch = epoch.get()
        polling = viewModelScope.launch(ioDispatcher) {
            while (isActive && epoch.get() == selectedEpoch) {
                var nextDelayMillis = 30_000L
                try {
                    val bindings = repository.bindings(chatId)
                    // An unavailable older job must not starve later jobs in this chat.
                    val failure = GatewayObserverPolling.observeAll(bindings.map { it.request.requestId }) {
                        repository.observe(it)
                    }
                    val saved = repository.bindings(chatId)
                    updateForEpoch(selectedEpoch) { it.copy(bindings = saved, error = failure?.message) }
                    refreshVisibleWorkspace(selectedEpoch, saved)
                    val activeStates = setOf("QUEUED", "ACCEPTED", "STARTING", "RUNNING", "PLAN", "READ", "IMPLEMENT", "REVIEW", "TEST", "COMMIT", "OPEN_PR", "CI", "WAIT_CI", "PROMOTE", "MERGE", "VERIFY", "CANCELLING")
                    val active = saved.any { binding -> binding.job == null || binding.job.state.uppercase() in activeStates }
                    nextDelayMillis = if (active) 5_000 else 30_000
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    updateForEpoch(selectedEpoch) { it.copy(error = failure.message) }
                }
                delay(nextDelayMillis)
            }
        }
    }

    private suspend fun refreshVisibleWorkspace(selectedEpoch: Long, bindings: List<GatewayEngineeringBinding>) {
        val requestId = mutable.value.workspaceRequestId ?: return
        if (mutable.value.busy || bindings.none { it.request.requestId == requestId }) return
        val board = repository.workspace(requestId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == requestId) it.copy(workspace = board) else it }
        if (bindings.single { it.request.requestId == requestId }.workspaceCommand?.settled == false) {
            val receipt = repository.reconcileWorkspaceCommand(requestId)
            updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == requestId) it.copy(workspaceReceipt = receipt) else it }
            val refreshed = repository.bindings(chat)
            updateForEpoch(selectedEpoch) { it.copy(bindings = refreshed) }
        }
    }

    fun start(context: GitHubConversationContext, base: String, branch: String, objective: String, options: GatewayEngineeringSettings) {
        val chatId = chat ?: return
        action { repository.start(chatId, context, base, branch, objective, options) }
    }

    fun control(binding: GatewayEngineeringBinding, expected: GatewayEngineeringJob, action: String) = action {
        repository.control(binding.request.requestId, expected, action)
    }

    /** Explicit recovery retains the saved idempotency key and selected execution binding. */
    fun reconnect(binding: GatewayEngineeringBinding) = action { repository.reconnect(binding.request.requestId) }

    fun check(binding: GatewayEngineeringBinding) = action { repository.observe(binding.request.requestId) }

    fun markSeen(binding: GatewayEngineeringBinding) = action { repository.markSeen(binding.request.requestId) }

    fun evidence(binding: GatewayEngineeringBinding, receiptId: String) = action { selectedEpoch ->
        val text = repository.evidence(binding.request.requestId, receiptId)
        updateForEpoch(selectedEpoch) { it.copy(evidencePreview = text, candidateCursor = null) }
    }

    fun exportOutput(binding: GatewayEngineeringBinding, output: kotlinx.serialization.json.JsonObject, context: android.content.Context, uri: android.net.Uri) = action {
        val temporary = java.io.File.createTempFile("gateway-output-", null, context.cacheDir)
        try {
            temporary.outputStream().use { repository.exportOutput(binding.request.requestId, output, it) }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The selected document could not be opened for writing." }.use { destination ->
                temporary.inputStream().use { it.copyTo(destination) }
            }
        } finally {
            temporary.delete()
        }
    }

    fun queuePlanEdit(binding: GatewayEngineeringBinding, text: String, commandId: String) = action {
        repository.queuePlanEdit(binding, text, commandId)
    }

    fun checkpoints(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        val result = repository.checkpoints(binding.request.requestId)
        updateForEpoch(selectedEpoch) { it.copy(checkpoints = binding to result) }
    }

    fun inspectCandidate(binding: GatewayEngineeringBinding, checkId: String, offset: Int = 0, left: String? = null) = action { selectedEpoch ->
        val (text, next) = repository.candidatePage(binding.request.requestId, checkId, offset, left)
        updateForEpoch(selectedEpoch) { it.copy(evidencePreview = text, candidateCursor = CandidatePageCursor(binding, checkId, offset, next, left)) }
    }

    fun restoreCandidate(binding: GatewayEngineeringBinding, checkId: String) = action { selectedEpoch ->
        repository.restoreCandidate(binding.request.requestId, requireNotNull(binding.job), checkId)
        updateForEpoch(selectedEpoch) { it.copy(checkpoints = null) }
    }

    fun openWorkspace(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        updateForEpoch(selectedEpoch) {
            if (it.workspaceRequestId == binding.request.requestId) it else it.copy(workspaceRequestId = binding.request.requestId, workspace = null, workspaceCandidates = null, workspaceCandidate = null, workspaceDiff = null, workspaceTree = null, workspaceTreeQuery = "", workspaceSource = null, workspaceReceipt = null, workspaceNotice = null)
        }
        repository.observe(binding.request.requestId)
        val board = repository.workspace(binding.request.requestId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspace = board) else it }
        if (repository.bindings().single { it.request.requestId == binding.request.requestId }.workspaceCommand != null) {
            val receipt = repository.reconcileWorkspaceCommand(binding.request.requestId)
            updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceReceipt = receipt) else it }
        }
    }

    fun closeWorkspace() {
        mutable.update { it.copy(workspaceRequestId = null, workspace = null, workspaceCandidates = null, workspaceCandidate = null, workspaceDiff = null, workspaceTree = null, workspaceTreeQuery = "", workspaceSource = null, workspaceReceipt = null, workspaceNotice = null) }
    }

    fun workspaceCommand(binding: GatewayEngineeringBinding, action: String, arguments: JsonObject = JsonObject(emptyMap())) = action { selectedEpoch ->
        val board = requireNotNull(mutable.value.workspace).also {
            check(mutable.value.workspaceRequestId == binding.request.requestId && it.jobId == binding.job?.id)
        }
        try {
            val receipt = repository.workspaceCommand(binding.request.requestId, board, action, arguments)
            updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceReceipt = receipt, workspaceNotice = "${action.replace('_', ' ')} · ${receipt.status.lowercase()}") else it }
        } finally {
            // Retain the command identity in the UI even if its HTTP response was lost.
            val saved = repository.bindings(chat)
            updateForEpoch(selectedEpoch) { it.copy(bindings = saved) }
        }
        val refreshed = repository.workspace(binding.request.requestId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspace = refreshed) else it }
    }

    fun reconcileWorkspaceCommand(binding: GatewayEngineeringBinding, commandId: String? = null) = action { selectedEpoch ->
        val receipt = repository.reconcileWorkspaceCommand(binding.request.requestId, commandId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceReceipt = receipt, workspaceNotice = receipt?.let { value -> "Saved command · ${value.status.lowercase()}" }) else it }
        val board = repository.workspace(binding.request.requestId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspace = board) else it }
    }

    fun retryWorkspaceCommand(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        try {
            val receipt = repository.retryWorkspaceCommand(binding.request.requestId)
            updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceReceipt = receipt, workspaceNotice = "Original command · ${receipt.status.lowercase()}") else it }
        } finally {
            val saved = repository.bindings(chat)
            updateForEpoch(selectedEpoch) { it.copy(bindings = saved) }
        }
    }

    fun dismissAbsentWorkspaceCommand(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        repository.dismissAbsentWorkspaceCommand(binding.request.requestId)
        updateForEpoch(selectedEpoch) { it.copy(workspaceNotice = "Stale absent command dismissed locally. No command was sent.") }
    }

    fun acknowledgeUnknownWorkspaceCommand(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        repository.acknowledgeUnknownWorkspaceCommand(binding.request.requestId)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceNotice = "The original unresolved command remains saved. Safe maintenance is available while this job waits for review.") else it }
    }

    fun workspaceCandidates(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        val candidates = repository.checkpoints(binding.request.requestId)
        val job = requireNotNull(binding.job)
        check(candidates.headSha == job.headSha && candidates.jobId == job.id) { "The candidate source changed. Refresh the workspace." }
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceCandidates = candidates, workspaceCandidate = null, workspaceDiff = null) else it }
    }

    fun workspaceCandidate(binding: GatewayEngineeringBinding, checkId: String, offset: Int = 0, left: String? = null) = action { selectedEpoch ->
        val candidates = requireNotNull(mutable.value.workspaceCandidates)
        val job = requireNotNull(binding.job)
        check(candidates.jobId == job.id && candidates.headSha == job.headSha && candidates.candidates.any { it.checkId == checkId && it.sourceSha == candidates.headSha }) { "Refresh candidate evidence at the current source revision." }
        check(left == null || candidates.candidates.any { it.checkId == left && it.sourceSha == candidates.headSha })
        val (text, next) = repository.candidatePage(binding.request.requestId, checkId, offset, left)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId) it.copy(workspaceDiff = text, workspaceCandidate = CandidatePageCursor(binding, checkId, offset, next, left)) else it }
    }

    fun workspaceTree(binding: GatewayEngineeringBinding, query: String, offset: Int = 0) = action { selectedEpoch ->
        val board = requireNotNull(mutable.value.workspace)
        check(mutable.value.workspaceRequestId == binding.request.requestId && board.jobId == binding.job?.id)
        val tree = repository.workspaceTree(binding.request.requestId, board.headSha, query, offset)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId && it.workspace?.headSha == board.headSha) it.copy(workspaceTree = tree, workspaceTreeQuery = query, workspaceSource = null) else it }
    }

    fun workspaceSource(binding: GatewayEngineeringBinding, file: GatewayWorkspaceSourcePath, startLine: Int = 1) = action { selectedEpoch ->
        val tree = requireNotNull(mutable.value.workspaceTree)
        check(mutable.value.workspaceRequestId == binding.request.requestId && tree.headSha == mutable.value.workspace?.headSha && file in tree.paths)
        val source = repository.workspaceSource(binding.request.requestId, tree, file, startLine)
        updateForEpoch(selectedEpoch) { if (it.workspaceRequestId == binding.request.requestId && it.workspace?.headSha == tree.headSha) it.copy(workspaceSource = source) else it }
    }

    fun closeCheckpoints() {
        mutable.update { it.copy(checkpoints = null) }
    }

    fun setContinuousEngineering(enabled: Boolean) = action {
        repository.setContinuousEngineering(enabled)
    }

    fun closeEvidence() {
        mutable.update { it.copy(evidencePreview = null, candidateCursor = null) }
    }

    private fun updateForEpoch(selectedEpoch: Long, update: (GatewayEngineeringUiState) -> GatewayEngineeringUiState) {
        // A chat switch and its clear cannot interleave between this check and publication.
        synchronized(epoch) {
            if (epoch.get() == selectedEpoch) mutable.update(update)
        }
    }

    private fun action(block: suspend (Long) -> Unit) {
        val (selectedChat, selectedEpoch) = synchronized(epoch) {
            if (mutable.value.busy) return
            val selected = chat to epoch.get()
            // Reserve synchronously: two taps cannot enqueue competing domain operations.
            mutable.update { it.copy(busy = true, error = null) }
            selected
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                block(selectedEpoch)
                val saved = repository.bindings(selectedChat)
                val options = repository.options()
                updateForEpoch(selectedEpoch) { it.copy(bindings = saved, options = options) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateForEpoch(selectedEpoch) { it.copy(error = failure.message) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
