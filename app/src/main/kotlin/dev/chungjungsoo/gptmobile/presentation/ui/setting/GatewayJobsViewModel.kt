package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationConflict
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayHistoryUnsupported
import dev.chungjungsoo.gptmobile.data.github.GatewayJobControl
import dev.chungjungsoo.gptmobile.data.github.GatewayJobCounts
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRoute
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopOutcome
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopResult
import dev.chungjungsoo.gptmobile.data.github.GatewayJobStopReview
import dev.chungjungsoo.gptmobile.data.github.GatewayJobSummary
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class GatewayJobsViewModel @Inject constructor(
    private val repository: GatewayJobRepository,
    private val conversations: GatewayConversationRepository,
    private val settings: SettingRepository,
    private val control: GatewayJobControl
) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayJobsState())
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private val refreshLock = Mutex()
    private var epoch = 0
    private var bindingGeneration = 0L
    private var cachedRows = emptyList<GatewayJobRecord>()
    private var stopAction: Job? = null
    private var boundRoute: GatewayJobRoute? = null
    private var visibleEpoch = 0L

    fun bind(profileUid: String?, force: Boolean = false) {
        if (!force && mutable.value.profileUid == profileUid && observer?.isActive == true) return
        val binding = ++bindingGeneration
        epoch++
        observer?.cancel()
        stopAction?.cancel()
        cachedRows = emptyList()
        boundRoute = null
        mutable.value = GatewayJobsState(profileUid = profileUid)
        if (profileUid == null) return
        observer = viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.observeConfiguration(profileUid).collectLatest { route ->
                    if (binding != bindingGeneration) return@collectLatest
                    val generation = ++epoch
                    stopAction?.cancel()
                    cachedRows = emptyList()
                    boundRoute = route
                    mutable.value = GatewayJobsState(profileUid = profileUid)
                    if (route == null) return@collectLatest
                    val trusted = repository.cachedScope(route)
                    val profiles = settings.fetchPlatformV2s().associate { it.uid to it.name }
                    if (generation != epoch || binding != bindingGeneration) return@collectLatest
                    mutable.value = GatewayJobsState(
                        profileUid = profileUid,
                        configured = true,
                        profileNames = profiles,
                        connectionName = profiles[route.profileUid] ?: "Gateway",
                        gatewayId = trusted?.gatewayId,
                        principalId = trusted?.principalId
                    )
                    repository.observe(route).collect { rows ->
                        if (generation == epoch) cachedRows = rows
                        if (generation == epoch) {
                            mutable.update { current ->
                                val previous = current.rows.associateBy { it.record.key }
                                current.copy(
                                    syncedAtMillis = current.syncedAtMillis.takeIf { it > 0 } ?: rows.filter { it.gatewayId == current.gatewayId && it.principalId == current.principalId }.maxOfOrNull { it.observedAtMillis } ?: 0,
                                    rows = rows.filter { row -> row.gatewayId == current.gatewayId && row.principalId == current.principalId && (!current.inventoryComplete || row.key in current.catalogKeys) }.map { row ->
                                        val prior = previous[row.key]
                                        val observed = if (prior?.record?.observedAtMillis == row.observedAtMillis) {
                                            prior.observedAtElapsed
                                        } else if (current.refreshStartedAtMillis > 0 && row.observedAtMillis >= current.refreshStartedAtMillis) {
                                            SystemClock.elapsedRealtime()
                                        } else {
                                            0
                                        }
                                        GatewayJobRow(row, repository.summary(row), observed)
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (binding == bindingGeneration) mutable.update { it.copy(error = failure.message) }
            }
        }
    }

    /** The visible screen owns catalog polling, never the server job's lifetime. */
    suspend fun visible(profileUid: String?) {
        val visibility = ++visibleEpoch
        bind(profileUid)
        try {
            if (profileUid == null) return
            state.first { it.profileUid != profileUid || it.configured || it.error != null }
            while (visibility == visibleEpoch && mutable.value.profileUid == profileUid) {
                refresh()
                delay(5_000)
            }
        } finally {
            if (visibility == visibleEpoch) {
                visibleEpoch++
                bindingGeneration++
                epoch++
                observer?.cancel()
                observer = null
                mutable.update { it.copy(loading = false, opening = null) }
            }
        }
    }

    private fun currentRow(candidate: GatewayJobRow): GatewayJobRow? {
        val current = mutable.value
        if (current.legacy) return null
        val route = boundRoute ?: return null
        return current.rows.singleOrNull { row ->
            row.record.key == candidate.record.key &&
                row.record.connectionProfileUid == route.profileUid &&
                row.record.endpoint == route.endpoint &&
                row.record.gatewayId == current.gatewayId &&
                row.record.principalId == current.principalId
        }
    }

    suspend fun refresh(loadMore: Boolean = false, restart: Boolean = false) = refreshLock.withLock {
        val profileUid = mutable.value.profileUid ?: return@withLock
        if (!mutable.value.configured || mutable.value.legacy) return@withLock
        val generation = epoch
        val cursor = if (loadMore) mutable.value.nextCursor ?: return@withLock else null
        mutable.update { it.copy(loading = true, refreshStartedAtMillis = System.currentTimeMillis()) }
        try {
            val route = repository.route(profileUid, requireEnabled = false)
            if (route != boundRoute) {
                bind(profileUid, force = true)
                return@withLock
            }
            val page = repository.refresh(profileUid, cursor)
            if (generation != epoch) return@withLock
            if (loadMore) {
                check(page.nextCursor != cursor || !page.hasMore) { "The gateway catalog cursor did not advance." }
                check(page.gatewayId == mutable.value.gatewayId && page.principalId == mutable.value.principalId) { "The catalog account changed during pagination." }
            }
            mutable.update {
                val reset = !it.loaded || restart || !loadMore && (!it.hasMore || page.complete)
                val pageKeys = page.items.map { job -> gatewayIdentityKey(page.gatewayId, page.principalId, job.jobKind, job.jobId) }.toSet()
                val keys = if (reset) pageKeys else it.catalogKeys + pageKeys
                val complete = if (reset || loadMore) page.complete else it.inventoryComplete
                it.copy(
                    loading = false, error = null, legacy = false, loaded = true, counts = page.counts,
                    hasMore = if (reset || loadMore) page.hasMore else it.hasMore,
                    nextCursor = if (reset || loadMore) page.nextCursor else it.nextCursor, serverNow = page.serverNow,
                    inventoryComplete = complete, catalogKeys = keys,
                    rows = cachedRows.filter { row -> row.gatewayId == page.gatewayId && row.principalId == page.principalId && (!complete || row.key in keys) }.map { row ->
                        val prior = it.rows.find { old -> old.record.key == row.key }
                        val observed = if (prior?.record?.observedAtMillis == row.observedAtMillis) {
                            prior.observedAtElapsed
                        } else if (row.observedAtMillis >= it.refreshStartedAtMillis) {
                            SystemClock.elapsedRealtime()
                        } else {
                            0
                        }
                        GatewayJobRow(row, repository.summary(row), observed)
                    },
                    observedAtElapsed = SystemClock.elapsedRealtime(), syncedAtMillis = System.currentTimeMillis(),
                    gatewayId = page.gatewayId, principalId = page.principalId
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: GatewayHistoryUnsupported) {
            val legacy = repository.legacyJobs(profileUid)
            if (generation == epoch) mutable.update { it.copy(loading = false, loaded = true, error = unsupported.message, legacyRows = legacy.map { row -> GatewayJobRow(row, repository.summary(row)) }) }
        } catch (failure: Exception) {
            if (generation == epoch) mutable.update { it.copy(loading = false, error = failure.message ?: "Gateway history unavailable") }
        }
    }

    fun requestRefresh(loadMore: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { refresh(loadMore, restart = !loadMore) }
    }

    fun open(candidate: GatewayJobRow, onOpen: (Int) -> Unit) {
        if (mutable.value.legacy) {
            openLegacyEvidence(candidate)
            return
        }
        val row = currentRow(candidate) ?: return
        if (mutable.value.opening != null) return
        mutable.update { it.copy(opening = row.record.key, error = null) }
        val generation = epoch
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (generation != epoch) return@launch
                if (row.summary.conversationId == null || mutable.value.legacy) {
                    val binding = repository.legacyBinding(row.record.remoteJobId)
                    if (generation == epoch) mutable.update { it.copy(legacyDetail = binding, opening = null) }
                    return@launch
                }
                val attached = conversations.attach(row.record.key)
                if (generation == epoch) {
                    mutable.update { it.copy(opening = null, partialHistoryKeys = if (attached.historyComplete) it.partialHistoryKeys - row.record.key else it.partialHistoryKeys + row.record.key) }
                    kotlinx.coroutines.withContext(Dispatchers.Main) { if (generation == epoch && currentRow(row) != null) onOpen(attached.chatId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val cached = conversations.cachedAttachment(row.record.key)
                if (generation == epoch) mutable.update { it.copy(opening = null, error = failure.message, cachedChatId = cached?.chatId) }
            }
        }
    }

    fun showLegacyEvidence() {
        if (mutable.value.legacyRows.isEmpty() || mutable.value.opening != null || mutable.value.stop?.busy == true || mutable.value.instruction?.sending == true) return
        mutable.update { it.copy(legacy = true, stop = null, instruction = null, cachedChatId = null, transcriptKey = null) }
    }

    fun closeLegacyEvidence() {
        mutable.update { it.copy(legacy = false, legacyDetail = null, opening = null) }
    }

    private fun openLegacyEvidence(candidate: GatewayJobRow) {
        val row = mutable.value.legacyRows.singleOrNull { it.record.key == candidate.record.key } ?: return
        if (mutable.value.opening != null) return
        val generation = epoch
        mutable.update { it.copy(opening = row.record.key) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val binding = repository.legacyBinding(row.record.remoteJobId)
                if (generation == epoch && mutable.value.legacy) mutable.update { it.copy(legacyDetail = binding, opening = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == epoch && mutable.value.legacy) mutable.update { it.copy(opening = null) }
            }
        }
    }

    fun showInstruction(candidate: GatewayJobRow) {
        val row = currentRow(candidate)?.takeIf { it.summary.acceptsInstruction } ?: return
        if (mutable.value.instruction?.sending == true) return
        mutable.update { it.copy(instruction = GatewayInstructionDraft(row, UUID.randomUUID().toString()), instructionQueued = false) }
    }

    fun editInstruction(content: String) {
        mutable.update { current -> current.copy(instruction = current.instruction?.let { if (!it.sending && !it.attempted) it.copy(content = content.take(16000)) else it }) }
    }

    fun dismissInstruction() {
        if (mutable.value.instruction?.sending != true) mutable.update { it.copy(instruction = null) }
    }

    fun sendInstruction() {
        val draft = mutable.value.instruction?.takeIf { !it.sending && it.content.isNotBlank() } ?: return
        if (currentRow(draft.row) == null) return
        val generation = epoch
        mutable.update { it.copy(instruction = draft.copy(sending = true, attempted = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (generation != epoch) return@launch
                conversations.queueInstruction(draft.row.record.key, draft.content, draft.commandId)
                if (generation == epoch) mutable.update { it.copy(instruction = null, instructionQueued = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == epoch) {
                    mutable.update {
                        it.copy(instruction = draft.copy(sending = false, attempted = failure !is GatewayConversationConflict, commandId = if (failure is GatewayConversationConflict) UUID.randomUUID().toString() else draft.commandId, error = failure.message))
                    }
                }
            }
        }
    }

    fun dismissLegacy() {
        mutable.update { it.copy(legacyDetail = null) }
    }
    fun dismissCached() {
        mutable.update { it.copy(cachedChatId = null) }
    }

    fun showTranscript(candidate: GatewayJobRow) {
        val row = currentRow(candidate) ?: return
        val conversationId = row.summary.conversationId ?: return
        if (mutable.value.readingTranscript != null) return
        val generation = epoch
        mutable.update { it.copy(readingTranscript = row.record.key, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (generation != epoch) return@launch
                val key = conversations.prepareTranscript(row.record.connectionProfileUid, row.record.gatewayId, row.record.principalId, conversationId)
                if (generation == epoch) mutable.update { it.copy(transcriptKey = key, readingTranscript = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == epoch) mutable.update { it.copy(transcriptKey = row.record.conversationKey, readingTranscript = null, error = failure.message) }
            }
        }
    }

    fun dismissTranscript() {
        mutable.update { it.copy(transcriptKey = null) }
    }

    fun showStop(candidate: GatewayJobRow) {
        val row = currentRow(candidate) ?: return
        if (mutable.value.stop?.busy == true) return
        mutable.update { it.copy(stop = GatewayStopDraft(row)) }
        checkStop()
    }

    /** Initial review and subsequent status checks never send a cancellation request. */
    fun checkStop() {
        val draft = mutable.value.stop?.takeIf { !it.busy } ?: return
        if (currentRow(draft.row) == null) return
        val generation = epoch
        mutable.update { it.copy(stop = draft.copy(busy = true, error = null)) }
        stopAction = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (generation != epoch) return@launch
                val review = control.inspect(draft.row.record)
                if (generation == epoch) mutable.update { it.copy(stop = draft.copy(review = review, result = review.observedResult)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == epoch) mutable.update { it.copy(stop = draft.copy(error = failure.message, result = GatewayJobStopResult(GatewayJobStopOutcome.UNKNOWN))) }
            }
        }
    }

    /** Only the confirmation button enters this method; refresh and attach cannot dispatch Stop. */
    fun confirmStop() {
        val draft = mutable.value.stop?.takeIf { !it.busy } ?: return
        if (currentRow(draft.row) == null) return
        val review = draft.review ?: return
        if (draft.result?.outcome !in setOf(null, GatewayJobStopOutcome.UNKNOWN)) return
        val generation = epoch
        mutable.update { it.copy(stop = draft.copy(busy = true, error = null)) }
        stopAction = viewModelScope.launch(Dispatchers.IO) {
            if (generation != epoch) return@launch
            val result = control.stop(review)
            if (generation == epoch) {
                mutable.update { it.copy(stop = draft.copy(result = result)) }
                refresh()
            }
        }
    }

    fun dismissStop() {
        if (mutable.value.stop?.busy != true) mutable.update { it.copy(stop = null) }
    }
}

data class GatewayInstructionDraft(val row: GatewayJobRow, val commandId: String, val content: String = "", val sending: Boolean = false, val attempted: Boolean = false, val error: String? = null)

data class GatewayJobRow(val record: GatewayJobRecord, val summary: GatewayJobSummary, val observedAtElapsed: Long = 0)

data class GatewayStopDraft(val row: GatewayJobRow, val review: GatewayJobStopReview? = null, val busy: Boolean = false, val result: GatewayJobStopResult? = null, val error: String? = null)

data class GatewayJobsState(
    val profileUid: String? = null,
    val configured: Boolean = false,
    val connectionName: String = "",
    val profileNames: Map<String, String> = emptyMap(),
    val rows: List<GatewayJobRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val legacy: Boolean = false,
    val legacyRows: List<GatewayJobRow> = emptyList(),
    val error: String? = null,
    val counts: GatewayJobCounts? = null,
    val hasMore: Boolean = false,
    val inventoryComplete: Boolean = false,
    val catalogKeys: Set<String> = emptySet(),
    val nextCursor: String? = null,
    val serverNow: Double = 0.0,
    val observedAtElapsed: Long = 0,
    val refreshStartedAtMillis: Long = 0,
    val syncedAtMillis: Long = 0,
    val gatewayId: String? = null,
    val principalId: String? = null,
    val opening: String? = null,
    val legacyDetail: GatewayEngineeringBinding? = null,
    val cachedChatId: Int? = null,
    val instruction: GatewayInstructionDraft? = null,
    val instructionQueued: Boolean = false,
    val stop: GatewayStopDraft? = null,
    val partialHistoryKeys: Set<String> = emptySet(),
    val readingTranscript: String? = null,
    val transcriptKey: String? = null
)
