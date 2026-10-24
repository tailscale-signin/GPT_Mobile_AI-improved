package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionCoordinator
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProgress
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSnapshot
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EverydayToolPerformance(val profileUid: String, val provider: String, val model: String, val completed: Int, val failed: Int) {
    val successPercent: Double? get() = (completed + failed).takeIf { it > 0 }?.let { 100.0 * completed / it }
}

@HiltViewModel
class ProfileBenchmarkViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val chats: ChatRepository,
    private val store: BenchmarkStore,
    private val execution: BenchmarkExecutionCoordinator,
    private val runtime: LocalRuntime,
    database: ChatDatabaseV2,
    runDao: AgentRunDao,
    persistenceDao: AgentPersistenceDao,
    savedState: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val selectedUid = MutableStateFlow(savedState.get<String>("profileUid").orEmpty())
    val selected = combine(profiles, selectedUid) { list, uid -> list.firstOrNull { it.uid == uid } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal val scoreSnapshot = store.snapshot
    val history = store.history
    val batches = store.batches
    val storageWarning = store.warning
    val benchmarkSession = execution.sessionId
    private val finishedRuns = history.map { runs -> runs.filter { it.finished } }.distinctUntilChanged()
    internal val retests = finishedRuns.map { dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRetests(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scoreboardRuns = finishedRuns.map { runs -> runs.mapNotNull(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard::fromRun) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val localEnvironment = combine(settings.observeLocalRuntimeBackend(), settings.observeFeatureSettings()) { backend, features ->
        "$backend|${features.localCpuThreads}|${features.localModelCache}|${features.qnnAutomaticFallback}|" +
            "${features.localSpeculativeDecoding}|${features.localNativeMetrics}|${features.localExecutionPolicy}|${dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION}|${dev.chungjungsoo.gptmobile.BuildConfig.QAIRT_VERSION}"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val delegationSettings = settings.observeFeatureSettings().map { it.delegation.normalized() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings())
    private val selectedBenchmarkUids = MutableStateFlow<Set<String>?>(null)
    val benchmarkCandidates = profiles.map { list -> list.filter { it.enabled && it.model.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedBenchmarks = combine(benchmarkCandidates, selectedBenchmarkUids) { list, selected ->
        val ids = selected ?: emptySet()
        ids.mapNotNull { uid -> list.firstOrNull { it.uid == uid } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val delegateUid = MutableStateFlow("")
    private val selectedDelegateUids = MutableStateFlow<Set<String>?>(null)
    private val reviewerUid = MutableStateFlow<String?>(null)
    val benchmarkReviewers = combine(profiles, selected) { list, primary ->
        list.filter { it.enabled && !dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.sameModel(it, primary) && !it.excludesMemory() && it.model.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pairedComparison = kotlinx.coroutines.flow.MutableStateFlow(false)
    val benchmarkReviewer = combine(benchmarkReviewers, reviewerUid, delegationSettings) { list, uid, config ->
        list.firstOrNull { it.uid == (uid ?: config.reviewerProfileUid) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun selectBenchmarkReviewer(profile: PlatformV2) {
        if (!execution.isRunning) reviewerUid.value = profile.uid
    }
    val delegates = combine(profiles, selected, benchmarkReviewer) { list, primary, reviewer ->
        list.filter {
            it.enabled &&
                it.model.isNotBlank() &&
                !dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.sameModel(it, primary) &&
                it.uid != reviewer?.uid &&
                !dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.sameModel(it, reviewer) &&
                !it.excludesMemory() &&
                !(primary?.compatibleType == ClientType.LITERT_LM && it.compatibleType == ClientType.LITERT_LM)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val delegate = combine(delegates, delegateUid, delegationSettings) { list, uid, config ->
        list.firstOrNull { it.uid == uid.ifBlank { config.targetProfileUid } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val selectedDelegates = combine(delegates, selectedDelegateUids, delegationSettings) { list, chosen, config ->
        val ids = chosen ?: setOf(config.targetProfileUid).filter { it.isNotBlank() }.toSet()
        ids.mapNotNull { uid -> list.firstOrNull { it.uid == uid } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val legacyReport: String? = context.getSharedPreferences("connection_doctor", Context.MODE_PRIVATE)
        .getString("last_report", null)?.takeIf { it.startsWith("Benchmark v1") }
    private val mutableProgress = execution.progressMutable
    val progress = mutableProgress.asStateFlow()
    private val mutableError = execution.errorMutable
    val error = mutableError.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableDays = MutableStateFlow(30)
    val days = mutableDays.asStateFlow()

    private val invocations = database.invocationDao().statistics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeRequests = invocations.map { requests -> requests.any { it.status == "RUNNING" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val everyday = combine(invocations, profiles, days) { requests, list, range ->
        val since = if (range == 0) 0L else System.currentTimeMillis() - range.toLong() * 24 * 60 * 60 * 1000
        profilePerformance(requests.filter { it.kind != "benchmark" && !it.parentRunId.startsWith("benchmark-") && it.startedAt >= since }, list.associate { it.uid to it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val everydayTools = combine(runDao.observeRecent(10_000), persistenceDao.observeRecentToolEvents(10_000), days) { runs, events, range ->
        val since = if (range == 0) 0L else System.currentTimeMillis() / 1000 - range.toLong() * 24 * 60 * 60
        val byId = runs.associateBy { it.runId }
        events.filter { (it.startedAt ?: 0) >= since && it.status in setOf(ToolEventStatus.COMPLETED, ToolEventStatus.FAILED) }
            .mapNotNull { event -> byId[event.runId]?.let { Triple(it.profileUid, it.providerSnapshot, it.modelSnapshot) to event } }
            .groupBy({ it.first }, { it.second }).map { (key, tools) ->
                val failures = tools.count { it.isError || it.status == ToolEventStatus.FAILED }
                EverydayToolPerformance(key.first, key.second, key.third, tools.size - failures, failures)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(profiles, localEnvironment) { list, environment -> list.filter { it.enabled }.associate { it.uid to benchmarkConfigKey(it, environment) } }.collect { enrollment ->
                try {
                    store.updateEnrollment(enrollment)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = "Could not update benchmark eligibility: ${safeMessage(error)}"
                }
            }
        }
        viewModelScope.launch {
            try {
                store.load()
                mutableError.value = store.loadWarning
                mutableReady.value = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = "Could not load benchmark history: ${safeMessage(error)}"
            }
        }
    }

    fun select(profile: PlatformV2) {
        if (!execution.isRunning) selectedUid.value = profile.uid
    }

    fun toggleBenchmarkProfile(profile: PlatformV2) {
        if (execution.isRunning) return
        val current = selectedBenchmarkUids.value ?: selectedBenchmarks.value.map { it.uid }.toSet()
        selectedBenchmarkUids.value = if (profile.uid in current) current - profile.uid else current + profile.uid
    }

    fun selectAllBenchmarks() {
        if (!execution.isRunning) selectedBenchmarkUids.value = benchmarkCandidates.value.map { it.uid }.toSet()
    }

    fun clearBenchmarks() {
        if (!execution.isRunning) selectedBenchmarkUids.value = emptySet()
    }

    fun selectDelegate(profile: PlatformV2) {
        if (!execution.isRunning) delegateUid.value = profile.uid
    }

    fun toggleDelegate(profile: PlatformV2) {
        if (execution.isRunning) return
        val current = selectedDelegateUids.value ?: selectedDelegates.value.map { it.uid }.toSet()
        selectedDelegateUids.value = if (profile.uid in current) current - profile.uid else current + profile.uid
    }

    fun selectAllDelegates() {
        if (!execution.isRunning) selectedDelegateUids.value = delegates.value.map { it.uid }.toSet()
    }

    fun clearDelegates() {
        if (!execution.isRunning) selectedDelegateUids.value = emptySet()
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun selectRange(days: Int) {
        require(days in setOf(0, 7, 30))
        mutableDays.value = days
    }

    fun cancel() {
        execution.cancel()
    }

    fun delete(id: String) {
        if (execution.isRunning) return
        viewModelScope.launch {
            try {
                store.delete(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = safeMessage(error)
            }
        }
    }

    val debugMode = settings.observeDebugMode().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val mutableExporting = MutableStateFlow(false)
    val exporting = mutableExporting.asStateFlow()
    private val mutableExportNotice = MutableStateFlow<String?>(null)
    val exportNotice = mutableExportNotice.asStateFlow()

    fun startDebugBenchmark() {
        if (debugMode.value) start(BenchmarkMode.FULL, diagnostics = true)
    }

    fun exportBenchmarks(uri: android.net.Uri, detailed: Boolean = false) {
        if (mutableExporting.value) return
        mutableExporting.value = true
        mutableExportNotice.value = null
        viewModelScope.launch {
            try {
                check(!detailed || settings.getDebugMode()) { "Enable Debug Mode to export diagnostic benchmark data." }
                val raw = store.exportJson(includeScoreHistory = detailed)
                val payload = withContext(Dispatchers.Default) { dev.chungjungsoo.gptmobile.data.benchmark.benchmarkExport(raw, detailed) }
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Unable to open the export destination." }.bufferedWriter().use { it.write(payload) }
                }
                mutableExportNotice.value = "Exported all stored benchmark runs, scores and runtime evidence."
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableError.value = "Benchmark export failed: ${safeMessage(error)}"
            } finally {
                mutableExporting.value = false
            }
        }
    }

    fun start(mode: BenchmarkMode, diagnostics: Boolean = false) {
        if (mode == BenchmarkMode.DELEGATION) {
            startDelegationBatch(BenchmarkMode.QUICK)
            return
        }
        val profile = selected.value ?: return
        startSelected(listOf(profile), mode, diagnostics)
    }

    fun startStandardBatch(mode: BenchmarkMode) = startSelected(selectedBenchmarks.value, mode)

    private fun startSelected(targets: List<PlatformV2>, mode: BenchmarkMode, diagnostics: Boolean = false) {
        if (execution.isRunning || !mutableReady.value) return
        if (targets.isEmpty()) {
            mutableError.value = "Select at least one AI model to benchmark."
            return
        }
        val batch = BenchmarkBatch(
            id = UUID.randomUUID().toString(),
            mode = mode,
            createdAt = System.currentTimeMillis(),
            items = targets.mapIndexed { ordinal, profile -> BenchmarkQueueItem(ordinal, profile.benchmarkSnapshot(), UUID.randomUUID().toString(), benchmarkConfigKey(profile, localEnvironment.value)) },
            localEnvironment = localEnvironment.value,
            diagnosticMode = diagnostics && debugMode.value
        )
        mutableError.value = null
        mutableProgress.value = BenchmarkProgress("Benchmark queue", "Saving selection", 0, targets.size * benchmarkSuite(mode).size)
        if (execution.startBatch(batch) == null) mutableProgress.value = null
    }

    fun startDelegationBatch(plan: BenchmarkMode) {
        if (execution.isRunning || !mutableReady.value) return
        val primary = selected.value ?: return
        val helpers = selectedDelegates.value
        if (helpers.isEmpty()) {
            mutableError.value = "Select at least one delegate to benchmark."
            return
        }
        val reviewer = benchmarkReviewer.value
        val paired = pairedComparison.value
        val config = dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkSettings(reviewer?.uid.orEmpty()).copy(taskPlan = if (paired) delegationSettings.value.taskPlan else dev.chungjungsoo.gptmobile.data.delegation.DelegationTaskPlan())
        val batch = BenchmarkBatch(
            UUID.randomUUID().toString(), BenchmarkMode.DELEGATION, System.currentTimeMillis(),
            helpers.mapIndexed { ordinal, helper -> BenchmarkQueueItem(ordinal, helper.benchmarkSnapshot(), UUID.randomUUID().toString(), benchmarkConfigKey(primary, localEnvironment.value)) },
            delegationPrimary = primary.benchmarkSnapshot(), delegationReviewer = reviewer?.benchmarkSnapshot(), delegationSettings = config,
            delegationPlan = plan, paired = paired, localEnvironment = localEnvironment.value
        )
        mutableProgress.value = BenchmarkProgress(primary.name, "Saving delegation selection", 0, helpers.size * dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkSuite(plan, paired).size)
        if (execution.startBatch(batch) == null) mutableProgress.value = null
    }

    fun skipProfile() = execution.skipProfile()
    fun resumeRemaining(batchId: String) {
        if (!execution.isRunning) execution.resumeBatch(batchId)
    }

    fun retryFailed(batchId: String) {
        if (execution.isRunning) return
        val previous = batches.value.firstOrNull { it.id == batchId } ?: return
        val failed = previous.items.filter { it.state in setOf(BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED, BenchmarkQueueState.INTERRUPTED) }
        if (failed.isEmpty()) return
        val next = previous.copy(
            id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(), state = dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState.QUEUED,
            activeOrdinal = null, endedAt = null, stopOrigin = null, retryOfBatchId = previous.id, storageWarning = null,
            items = failed.mapIndexed { ordinal, item -> item.copy(ordinal = ordinal, runId = UUID.randomUUID().toString(), retryOfRunId = item.runId, state = BenchmarkQueueState.QUEUED, failure = null, startedAt = null, endedAt = null, activeCaseId = null, completedCases = 0) }
        )
        execution.startBatch(next)
    }

    fun moveSelectedProfile(uid: String, offset: Int) {
        if (execution.isRunning) return
        val ordered = selectedBenchmarks.value.map { it.uid }.toMutableList()
        val index = ordered.indexOf(uid)
        val destination = index + offset
        if (index < 0 || destination !in ordered.indices) return
        ordered.add(destination, ordered.removeAt(index))
        selectedBenchmarkUids.value = ordered.toSet()
    }

    private fun safeMessage(error: Exception) = DiagnosticRedactor.redact(error.message ?: "Unknown error").take(500)
}
