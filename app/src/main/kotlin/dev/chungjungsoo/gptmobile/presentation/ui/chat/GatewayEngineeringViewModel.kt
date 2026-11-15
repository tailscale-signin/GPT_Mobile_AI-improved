package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
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

data class GatewayEngineeringUiState(
    val bindings: List<GatewayEngineeringBinding> = emptyList(),
    val options: GatewayEngineeringSettings = GatewayEngineeringSettings(),
    val busy: Boolean = false,
    val error: String? = null,
    val evidencePreview: String? = null,
    val checkpoints: Pair<GatewayEngineeringBinding, dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringCheckpoints>? = null
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
                mutable.update { it.copy(bindings = emptyList(), evidencePreview = null, checkpoints = null, error = null) }
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
                    for (binding in bindings) {
                        // Foreground observation cannot create or resume a saved request.
                        repository.observe(binding.request.requestId)
                    }
                    val saved = repository.bindings(chatId)
                    updateForEpoch(selectedEpoch) { it.copy(bindings = saved, error = null) }
                    val activeStates = setOf("QUEUED", "ACCEPTED", "STARTING", "RUNNING", "PLAN", "READ", "IMPLEMENT", "TEST", "COMMIT", "OPEN_PR", "CI", "WAIT_CI", "MERGE", "VERIFY")
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
        updateForEpoch(selectedEpoch) { it.copy(evidencePreview = text) }
    }

    fun checkpoints(binding: GatewayEngineeringBinding) = action { selectedEpoch ->
        val result = repository.checkpoints(binding.request.requestId)
        updateForEpoch(selectedEpoch) { it.copy(checkpoints = binding to result) }
    }

    fun inspectCandidate(binding: GatewayEngineeringBinding, checkId: String) = action { selectedEpoch ->
        val text = repository.candidateDetails(binding.request.requestId, checkId)
        updateForEpoch(selectedEpoch) { it.copy(evidencePreview = text) }
    }

    fun restoreCandidate(binding: GatewayEngineeringBinding, checkId: String) = action { selectedEpoch ->
        repository.restoreCandidate(binding.request.requestId, requireNotNull(binding.job), checkId)
        updateForEpoch(selectedEpoch) { it.copy(checkpoints = null) }
    }

    fun closeCheckpoints() {
        mutable.update { it.copy(checkpoints = null) }
    }

    fun closeEvidence() {
        mutable.update { it.copy(evidencePreview = null) }
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
