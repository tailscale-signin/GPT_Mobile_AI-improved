package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    val evidencePreview: String? = null
)

@HiltViewModel
class GatewayEngineeringViewModel @Inject constructor(private val repository: GatewayEngineeringRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayEngineeringUiState())
    val state = mutable.asStateFlow()

    @Volatile private var chat: Int? = null
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private var polling: Job? = null
    private var localRead: Job? = null
    private val visibleOwners = mutableMapOf<String, Int>()

    /** Cached jobs remain readable even when their original profile is unavailable. */
    fun bind(chatId: Int) {
        if (chat == chatId) return
        chat = chatId
        val selectedEpoch = epoch.incrementAndGet()
        polling?.cancel()
        localRead?.cancel()
        mutable.update { it.copy(bindings = emptyList(), evidencePreview = null, error = null) }
        localRead = viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = repository.bindings(chatId)
                val options = repository.options()
                if (epoch.get() == selectedEpoch) mutable.update { it.copy(bindings = saved, options = options) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch.get() == selectedEpoch) mutable.update { it.copy(error = failure.message) }
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
        polling = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && epoch.get() == selectedEpoch) {
                var nextDelayMillis = 30_000L
                try {
                    val bindings = repository.bindings(chatId)
                    for (binding in bindings) {
                        // Foreground observation cannot create or resume a saved request.
                        repository.observe(binding.request.requestId)
                    }
                    val saved = repository.bindings(chatId)
                    if (epoch.get() == selectedEpoch) mutable.update { it.copy(bindings = saved, error = null) }
                    val activeStates = setOf("QUEUED", "ACCEPTED", "STARTING", "RUNNING", "PLAN", "READ", "IMPLEMENT", "TEST", "COMMIT", "OPEN_PR", "CI", "WAIT_CI", "MERGE", "VERIFY")
                    val active = saved.any { binding -> binding.job == null || binding.job.state.uppercase() in activeStates }
                    nextDelayMillis = if (active) 5_000 else 30_000
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (epoch.get() == selectedEpoch) mutable.update { it.copy(error = failure.message) }
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

    fun evidence(binding: GatewayEngineeringBinding, receiptId: String) = action {
        val selectedEpoch = epoch.get()
        val text = repository.evidence(binding.request.requestId, receiptId)
        if (epoch.get() == selectedEpoch) mutable.update { it.copy(evidencePreview = text) }
    }

    fun closeEvidence() {
        mutable.update { it.copy(evidencePreview = null) }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val selectedChat = chat
        val selectedEpoch = epoch.get()
        // Reserve synchronously: two taps cannot enqueue competing domain operations.
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                val saved = repository.bindings(selectedChat)
                val options = repository.options()
                if (epoch.get() == selectedEpoch) mutable.update { it.copy(bindings = saved, options = options) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch.get() == selectedEpoch) mutable.update { it.copy(error = failure.message) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
