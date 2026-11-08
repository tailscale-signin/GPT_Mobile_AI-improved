package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewaySavedJob
import dev.chungjungsoo.gptmobile.data.github.GatewayWorkStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GatewayJobsSelection(val profileUid: String?, val gatewayUid: String?, val label: String, val error: String? = null)

data class GatewayJobsState(
    val selection: GatewayJobsSelection = GatewayJobsSelection(null, null, "Select an engineering gateway in GitHub settings"),
    val jobs: List<GatewaySavedJob> = emptyList(),
    val pending: List<GatewayCommandRecord> = emptyList(),
    val loading: Boolean = false,
    val notice: String? = null,
    val limit: Int = 200
)

/** Visibility drives bounded reads. Elapsed-time rendering never enters this model. */
@HiltViewModel
class GatewayJobsViewModel @Inject constructor(
    private val repository: GatewayConversationRepository,
    private val store: GatewayWorkStore,
    private val settings: SettingRepository
) : ViewModel() {
    private val selected = MutableStateFlow(GatewayJobsSelection(null, null, "Select an engineering gateway in GitHub settings"))
    private val limit = MutableStateFlow(200)
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val refreshLock = Mutex()
    private var visibleGeneration = 0L
    private val pendingActions = mutableSetOf<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cached = combine(selected, limit) { selection, count -> selection.gatewayUid.orEmpty() to count }.flatMapLatest { (gateway, count) -> repository.jobs(gateway, count) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val commands = selected.flatMapLatest { store.dao.observeCommandsForGateway(it.gatewayUid.orEmpty()) }
    private val rows = combine(cached, commands, store.dao.observeScopes(), selected) { jobs, commands, scopes, selection ->
        val scopeIds = scopes.filter { it.connectionUid == selection.gatewayUid }.map { it.id }.toSet()
        GatewayJobsState(selection, jobs.filter { it.connection.connectionUid == selection.gatewayUid }, commands.filter { it.scopeId in scopeIds })
    }
    val state = combine(rows, busy, notice, limit) { rows, loading, message, count -> rows.copy(loading = loading, notice = message, limit = count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GatewayJobsState())

    suspend fun visible(profileUid: String?) {
        val generation = ++visibleGeneration
        try {
            bind(profileUid, generation)
            store.importLegacy()
            while (generation == visibleGeneration) {
                bind(profileUid, generation)
                refreshNow()
                delay(10_000)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == visibleGeneration) notice.value = failure.message ?: "Saved gateway work is temporarily unavailable."
        } finally {
            if (generation == visibleGeneration) visibleGeneration++
        }
    }

    private suspend fun bind(profileUid: String?, generation: Long) {
        val profiles = settings.fetchPlatformV2s()
        val gatewayUid = settings.getFeatureSettings().githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid
        val gateway = profiles.firstOrNull { it.uid == gatewayUid }
        if (generation != visibleGeneration) return
        if (selected.value.profileUid != profileUid || selected.value.gatewayUid != gatewayUid) {
            notice.value = null
            limit.value = 200
        }
        selected.value = GatewayJobsSelection(profileUid, gatewayUid, gateway?.name ?: "Original engineering gateway unavailable", if (gatewayUid == null) "Choose an explicit engineering gateway in this GitHub profile's settings. Saved work is never moved to another gateway." else null)
    }

    fun refresh() = viewModelScope.launch { refreshNow() }

    private suspend fun refreshNow() {
        if (!refreshLock.tryLock()) return
        val selection = selected.value
        val generation = visibleGeneration
        try {
            busy.value = true
            val uid = selection.gatewayUid ?: return
            repository.refreshCatalog(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrent(generation, selection)) notice.value = failure.message ?: "Gateway unavailable. Retained jobs remain readable."
        } finally {
            busy.value = false
            refreshLock.unlock()
        }
    }

    fun loadMore() {
        limit.value = (limit.value + 200).coerceAtMost(5000)
        refresh()
    }

    fun reconnect(job: GatewaySavedJob) = action("reconnect:${job.row.id}", job.connection.connectionUid) { current ->
        repository.reconnectSaved(job.conversation)
        if (current()) notice.value = "Reconnected to the original conversation."
    }

    fun reconcile(command: GatewayCommandRecord) = action("command:${command.id}", command = command) { current ->
        repository.observeRequest(command.id)
        if (current()) notice.value = "Read the original command receipt. No new work was submitted."
    }

    fun retry(command: GatewayCommandRecord) = action("command:${command.id}", command = command) { current ->
        val observed = repository.retrySavedSubmission(command.id)
        if (current()) notice.value = if (observed.job != null) "The gateway confirmed the original saved command." else "The original command was retained. Read its submission status before any further action."
    }

    fun open(job: GatewaySavedJob, connectionUid: String, onOpen: (Int) -> Unit) = action("open:${job.row.id}", job.connection.connectionUid) { current ->
        val chatId = repository.openConversation(job.conversation.id, connectionUid)
        if (current()) onOpen(chatId)
    }

    fun stop(job: GatewaySavedJob) = action("stop:${job.row.id}", job.connection.connectionUid) { current ->
        val outcome = repository.stopJob(job)
        if (current()) notice.value = outcome.message
    }

    private fun isCurrent(generation: Long, selection: GatewayJobsSelection): Boolean = generation == visibleGeneration && selection == selected.value

    private fun action(key: String, gatewayUid: String? = null, command: GatewayCommandRecord? = null, block: suspend (() -> Boolean) -> Unit) {
        val selection = selected.value
        val generation = visibleGeneration
        if (gatewayUid != null && gatewayUid != selection.gatewayUid) return
        if (command != null && state.value.pending.none { it.id == command.id && it.scopeId == command.scopeId }) return
        if (!pendingActions.add(key)) return
        viewModelScope.launch {
            try {
                refreshLock.withLock {
                    if (!isCurrent(generation, selection)) return@withLock
                    busy.value = true
                    try {
                        block { isCurrent(generation, selection) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (isCurrent(generation, selection)) notice.value = failure.message ?: "The original gateway is unavailable. Saved work remains retained."
                    } finally {
                        busy.value = false
                    }
                }
            } finally {
                pendingActions.remove(key)
            }
        }
    }
}
