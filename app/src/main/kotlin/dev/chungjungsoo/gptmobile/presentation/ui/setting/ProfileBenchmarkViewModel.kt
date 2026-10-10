package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRunner
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite
import dev.chungjungsoo.gptmobile.data.benchmark.runBenchmarkSuite
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BenchmarkProgress(val profileName: String, val testName: String, val completed: Int, val total: Int)

data class EverydayToolPerformance(val profileUid: String, val provider: String, val model: String, val completed: Int, val failed: Int) {
    val successPercent: Double? get() = (completed + failed).takeIf { it > 0 }?.let { 100.0 * completed / it }
}

@HiltViewModel
class ProfileBenchmarkViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val chats: ChatRepository,
    private val store: BenchmarkStore,
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
    val localEnvironment = combine(settings.observeLocalRuntimeBackend(), settings.observeFeatureSettings()) { backend, features ->
        "$backend|${features.localCpuThreads}|${features.localModelCache}|${features.qnnAutomaticFallback}|" +
            "${features.localSpeculativeDecoding}|${features.localNativeMetrics}|${dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION}"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val delegationSettings = settings.observeFeatureSettings().map { it.delegation.normalized() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings())
    private val selectedBenchmarkUids = MutableStateFlow<Set<String>?>(null)
    val benchmarkCandidates = profiles.map { list -> list.filter { it.enabled && it.model.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedBenchmarks = combine(benchmarkCandidates, selectedBenchmarkUids) { list, selected ->
        val ids = selected ?: emptySet()
        list.filter { it.uid in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val delegateUid = MutableStateFlow("")
    private val selectedDelegateUids = MutableStateFlow<Set<String>?>(null)
    val delegates = combine(profiles, selected, delegationSettings) { list, primary, config ->
        val reviewer = list.firstOrNull { it.uid == config.reviewerProfileUid }
        list.filter {
            it.enabled &&
                it.uid != primary?.uid &&
                (!config.reviewerEnabled || it.uid != reviewer?.uid) &&
                (!config.reviewerEnabled || reviewer == null || !it.model.trim().equals(reviewer.model.trim(), ignoreCase = true)) &&
                !it.excludesMemory() &&
                (config.allowRemoteWorkers || it.isPrivateDestination()) &&
                !(primary?.compatibleType == ClientType.LITERT_LM && it.compatibleType == ClientType.LITERT_LM)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val delegate = combine(delegates, delegateUid, delegationSettings) { list, uid, config ->
        list.firstOrNull { it.uid == uid.ifBlank { config.targetProfileUid } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val selectedDelegates = combine(delegates, selectedDelegateUids, delegationSettings) { list, chosen, config ->
        val ids = chosen ?: setOf(config.targetProfileUid).filter { it.isNotBlank() }.toSet()
        list.filter { it.uid in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val legacyReport: String? = context.getSharedPreferences("connection_doctor", Context.MODE_PRIVATE)
        .getString("last_report", null)?.takeIf { it.startsWith("Benchmark v1") }
    private val mutableProgress = MutableStateFlow<BenchmarkProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableDays = MutableStateFlow(30)
    val days = mutableDays.asStateFlow()
    private var job: Job? = null

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

    private var interruptionReason: String? = null
    private val application = context.applicationContext as? android.app.Application
    private val foregroundCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(activity: android.app.Activity) {
            if (!activity.isChangingConfigurations && job?.isActive == true) {
                interruptionReason = "App backgrounded; foreground-interactive benchmark interrupted."
                job?.cancel()
            }
        }
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: android.app.Activity) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    override fun onCleared() {
        application?.unregisterActivityLifecycleCallbacks(foregroundCallbacks)
        super.onCleared()
    }

    init {
        application?.registerActivityLifecycleCallbacks(foregroundCallbacks)
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
        if (job?.isActive != true) selectedUid.value = profile.uid
    }

    fun toggleBenchmarkProfile(profile: PlatformV2) {
        if (job?.isActive == true) return
        val current = selectedBenchmarkUids.value ?: selectedBenchmarks.value.map { it.uid }.toSet()
        selectedBenchmarkUids.value = if (profile.uid in current) current - profile.uid else current + profile.uid
    }

    fun selectAllBenchmarks() {
        if (job?.isActive != true) selectedBenchmarkUids.value = benchmarkCandidates.value.map { it.uid }.toSet()
    }

    fun clearBenchmarks() {
        if (job?.isActive != true) selectedBenchmarkUids.value = emptySet()
    }

    fun selectDelegate(profile: PlatformV2) {
        if (job?.isActive != true) delegateUid.value = profile.uid
    }

    fun toggleDelegate(profile: PlatformV2) {
        if (job?.isActive == true) return
        val current = selectedDelegateUids.value ?: selectedDelegates.value.map { it.uid }.toSet()
        selectedDelegateUids.value = if (profile.uid in current) current - profile.uid else current + profile.uid
    }

    fun selectAllDelegates() {
        if (job?.isActive != true) selectedDelegateUids.value = delegates.value.map { it.uid }.toSet()
    }

    fun clearDelegates() {
        if (job?.isActive != true) selectedDelegateUids.value = emptySet()
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun selectRange(days: Int) {
        require(days in setOf(0, 7, 30))
        mutableDays.value = days
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(id: String) {
        if (job?.isActive == true) return
        viewModelScope.launch {
            try {
                store.delete(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = safeMessage(error)
            }
        }
    }

    fun start(mode: BenchmarkMode) {
        if (mode == BenchmarkMode.DELEGATION) {
            startDelegationBatch()
            return
        }
        val profile = selected.value ?: return
        if (job?.isActive == true || !mutableReady.value) return
        if (profile.compatibleType == ClientType.LITERT_LM && localEnvironment.value.isBlank()) return
        if (activeRequests.value) {
            mutableError.value = "Wait for active model requests to finish before benchmarking."
            return
        }
        if (profile.model.isBlank()) {
            mutableError.value = "Choose a model in this AI profile first."
            return
        }
        val helper = delegate.value
        if (mode == BenchmarkMode.DELEGATION && helper == null) {
            mutableError.value = "Choose an enabled delegate. Benchmarks never fall back to another profile."
            return
        }
        val config = delegationSettings.value.copy(targetProfileUid = helper?.uid.orEmpty(), fallbackToAnotherProfile = false)
        mutableError.value = null
        mutableProgress.value = BenchmarkProgress(profile.name, "Validating profile", 0, benchmarkSuite(mode).size)
        job = launchBenchmark {
            try {
                chats.validateBenchmarkProfile(profile)
                if (mode == BenchmarkMode.DELEGATION) {
                    check(config.enabled && config.processingOwnership < 100) { "Enable delegation and give the helper a share of the work before testing." }
                    check(config.researchEnabled && config.maxPages > 0) { "Enable delegate research and allow at least one page for the research test." }
                    check(!profile.disableAllTools && !profile.disableLocalTools && !profile.excludesMemory()) { "Enable tools on the primary profile before testing delegation." }
                    val worker = checkNotNull(helper) { "Choose a delegate before testing." }
                    chats.validateBenchmarkProfile(worker)
                    check(!worker.disableAllTools && chats.supportsBenchmarkTools(worker)) { "The delegate needs tool calling enabled and a model that supports tools." }
                }
            } catch (error: Exception) {
                mutableProgress.value = null
                if (error is CancellationException) throw error
                mutableError.value = safeMessage(error)
                return@launchBenchmark
            }
            val suite = benchmarkSuite(mode)
            var run = BenchmarkRun(
                UUID.randomUUID().toString(), profile.uid, profile.name, profile.compatibleType.name,
                profile.model, benchmarkConfigKey(profile, localEnvironment.value), profile.compatibleType == ClientType.LITERT_LM,
                mode, System.currentTimeMillis(), finished = false, suiteVersion = 2,
                plannedTrials = benchmarkSuite(mode).size, measurementVersion = 2, scoringVersion = 2, appCommit = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT, runtimeVersion = dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION,
                device = "${Build.MANUFACTURER} ${Build.MODEL}", thermalBefore = thermal(), batteryBefore = battery(),
                engineWasLoaded = profile.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec() != null,
                delegationSettings = config.takeIf { mode == BenchmarkMode.DELEGATION }
            )
            var currentTest = suite.first()
            try {
                mutableProgress.value = BenchmarkProgress(profile.name, "Preparing tests", 0, suite.size)
                store.save(run)
                val supportsTools = chats.supportsBenchmarkTools(profile)
                val stoppedReason = runBenchmarkSuite(
                    suite = suite,
                    runCase = { index, test ->
                        currentTest = test
                        if (profile.compatibleType == ClientType.LITERT_LM) checkLocalConditions()
                        mutableProgress.value = BenchmarkProgress(profile.name, test.label, index, suite.size)
                        if (mode == BenchmarkMode.DELEGATION) {
                            try {
                                chats.runDelegationBenchmark(profile, test, "benchmark-${run.id}-${test.id}", config)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, error = safeMessage(failure))
                            }
                        } else {
                            BenchmarkRunner(openSession = { turns, tools ->
                                chats.openBenchmarkSession(profile, turns, tools, "benchmark-${run.id}-${test.id}")
                            }).run(test, supportsTools)
                        }
                    },
                    onSample = { sample ->
                        val pss = withContext(Dispatchers.IO) { Debug.getPss() }
                        val actual = runtime.state.value.takeIf { run.local && sample.completed }
                        run = run.copy(
                            samples = run.samples + sample,
                            peakClientPssKb = maxOf(run.peakClientPssKb ?: 0, pss),
                            backend = actual?.backend?.displayName ?: run.backend,
                            accelerator = actual?.engineSpec?.accelerator ?: run.accelerator
                        )
                        store.save(run)
                    },
                    stopOnError = mode != BenchmarkMode.DELEGATION
                )
                if (stoppedReason != null) {
                    run = run.copy(stoppedReason = stoppedReason)
                    mutableError.value = "Benchmark stopped: $stoppedReason"
                }
            } catch (_: CancellationException) {
                val canceledSample = if (run.samples.any { it.testId == currentTest.id }) emptyList() else listOf(BenchmarkSample(currentTest.id, currentTest.label, currentTest.category, BenchmarkOutcome.CANCELED))
                run = run.copy(canceled = true, stoppedReason = interruptionReason, samples = run.samples + canceledSample)
            } catch (error: Exception) {
                mutableError.value = safeMessage(error)
                run = run.copy(canceled = true)
            } finally {
                withContext(NonCancellable) {
                    try {
                        val finished = run.copy(finished = true, thermalAfter = thermal(), batteryAfter = battery())
                        store.save(finished)
                        if (finished.mode == BenchmarkMode.DELEGATION) logDelegationRating(finished)
                    } catch (error: Exception) {
                        mutableError.value = "Could not save benchmark: ${safeMessage(error)}"
                    }
                    mutableProgress.value = null
                }
            }
        }
    }

    fun startStandardBatch(mode: BenchmarkMode) {
        require(mode != BenchmarkMode.DELEGATION)
        val enrolledTargets = selectedBenchmarks.value
        val completedBlocks = history.value.count { it.mode == mode && it.finished }
        val targets = if (enrolledTargets.isEmpty()) enrolledTargets else (enrolledTargets.drop(completedBlocks % enrolledTargets.size) + enrolledTargets.take(completedBlocks % enrolledTargets.size)).let { if (completedBlocks % 2 == 0) it else it.asReversed() }
        if (job?.isActive == true || !mutableReady.value) return
        if (activeRequests.value) {
            mutableError.value = "Wait for active model requests to finish before benchmarking."
            return
        }
        if (targets.isEmpty()) {
            mutableError.value = "Select at least one AI model to benchmark."
            return
        }
        val suite = benchmarkSuite(mode)
        val totalTests = suite.size * targets.size
        mutableError.value = null
        mutableProgress.value = BenchmarkProgress("Batch benchmark", "Preparing models", 0, totalTests)
        job = launchBenchmark {
            val failures = mutableListOf<String>()
            try {
                targets.forEachIndexed { profileIndex, target ->
                    val baseProgress = profileIndex * suite.size
                    var run = BenchmarkRun(
                        UUID.randomUUID().toString(), target.uid, target.name, target.compatibleType.name,
                        target.model, benchmarkConfigKey(target, localEnvironment.value), target.compatibleType == ClientType.LITERT_LM,
                        mode, System.currentTimeMillis(), finished = false, suiteVersion = 2,
                        plannedTrials = benchmarkSuite(mode).size, measurementVersion = 2, scoringVersion = 2, appCommit = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT, runtimeVersion = dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}", thermalBefore = thermal(), batteryBefore = battery(),
                        engineWasLoaded = target.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec() != null
                    )
                    var currentTest = suite.first()
                    try {
                        if (target.compatibleType == ClientType.LITERT_LM && localEnvironment.value.isBlank()) {
                            error("Local runtime details are not ready.")
                        }
                        mutableProgress.value = BenchmarkProgress(target.name, "Validating profile", baseProgress, totalTests)
                        chats.validateBenchmarkProfile(target)
                        store.save(run)
                        val supportsTools = chats.supportsBenchmarkTools(target)
                        val stoppedReason = runBenchmarkSuite(
                            suite = suite,
                            runCase = { index, test ->
                                currentTest = test
                                if (target.compatibleType == ClientType.LITERT_LM) checkLocalConditions()
                                mutableProgress.value = BenchmarkProgress(target.name, test.label, baseProgress + index, totalTests)
                                BenchmarkRunner(openSession = { turns, tools ->
                                    chats.openBenchmarkSession(target, turns, tools, "benchmark-${run.id}-${test.id}")
                                }).run(test, supportsTools)
                            },
                            onSample = { sample ->
                                val pss = withContext(Dispatchers.IO) { Debug.getPss() }
                                val actual = runtime.state.value.takeIf { run.local && sample.completed }
                                run = run.copy(
                                    samples = run.samples + sample,
                                    peakClientPssKb = maxOf(run.peakClientPssKb ?: 0, pss),
                                    backend = actual?.backend?.displayName ?: run.backend,
                                    accelerator = actual?.engineSpec?.accelerator ?: run.accelerator
                                )
                                store.save(run)
                            },
                            stopOnError = true
                        )
                        if (stoppedReason != null) {
                            run = run.copy(stoppedReason = stoppedReason)
                            failures += "${target.name}: $stoppedReason"
                        }
                    } catch (cancelled: CancellationException) {
                        val canceledSample = if (run.samples.any { it.testId == currentTest.id }) {
                            emptyList()
                        } else {
                            listOf(BenchmarkSample(currentTest.id, currentTest.label, currentTest.category, BenchmarkOutcome.CANCELED))
                        }
                        run = run.copy(canceled = true, stoppedReason = interruptionReason, samples = run.samples + canceledSample)
                        throw cancelled
                    } catch (error: Exception) {
                        val reason = safeMessage(error)
                        failures += "${target.name}: $reason"
                        run = run.copy(stoppedReason = reason)
                    } finally {
                        withContext(NonCancellable) {
                            runCatching {
                                store.save(run.copy(finished = true, thermalAfter = thermal(), batteryAfter = battery()))
                            }.onFailure { failures += "${target.name}: could not save benchmark" }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // The current run is persisted as canceled by the per-profile finally block.
            } finally {
                mutableProgress.value = null
                if (failures.isNotEmpty()) mutableError.value = failures.joinToString("\n").take(1500)
            }
        }
    }

    private fun logDelegationRating(run: BenchmarkRun) {
        val rating = dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkRating(listOf(run))
        val rankings = dev.chungjungsoo.gptmobile.data.benchmark.delegateRankings(store.history.value, run.configKey, run.delegationSettings)
        val rank = rankings.indexOfFirst { it.run.id == run.id }.takeIf { it >= 0 }?.plus(1)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "DelegationBenchmark",
            "DELEGATION_SCORE · run=${run.id} score=${rating.score} rank=$rank passed=${rating.passed}/${rating.attempts} " +
                "toolSuccessPercent=${rating.toolTaskSuccessPercent} tokPerSec=${rating.medianDecodeSpeed} estimated=${rating.estimated}"
        )
    }

    private fun startDelegationBatch() {
        val profile = selected.value ?: return
        val helpers = selectedDelegates.value
        if (job?.isActive == true || !mutableReady.value) return
        if (profile.compatibleType == ClientType.LITERT_LM && localEnvironment.value.isBlank()) return
        if (activeRequests.value) {
            mutableError.value = "Wait for active model requests to finish before benchmarking."
            return
        }
        if (profile.model.isBlank()) {
            mutableError.value = "Choose a model in the primary AI profile first."
            return
        }
        if (helpers.isEmpty()) {
            mutableError.value = "Select at least one delegate to benchmark."
            return
        }
        val baseConfig = delegationSettings.value
        val suite = benchmarkSuite(BenchmarkMode.DELEGATION)
        val totalTests = suite.size * helpers.size
        mutableError.value = null
        mutableProgress.value = BenchmarkProgress(profile.name, "Validating delegation batch", 0, totalTests)
        job = launchBenchmark {
            val failures = mutableListOf<String>()
            val reviewer = if (baseConfig.reviewerEnabled) profiles.value.firstOrNull { it.uid == baseConfig.reviewerProfileUid } else null
            try {
                chats.validateBenchmarkProfile(profile)
                check(baseConfig.enabled && baseConfig.processingOwnership < 100) { "Enable delegation and give helpers a share of the work before testing." }
                check(baseConfig.researchEnabled && baseConfig.maxPages > 0) { "Enable delegate research and allow at least one page for the research test." }
                check(!profile.disableAllTools && !profile.disableLocalTools && !profile.excludesMemory()) { "Enable tools on the primary profile before testing delegation." }
                if (baseConfig.reviewerEnabled) {
                    val selectedReviewer = checkNotNull(reviewer) { "Choose a Reviewer model before running delegation benchmarks." }
                    check(selectedReviewer.uid != profile.uid) { "Reviewer must be different from the primary model." }
                    chats.validateBenchmarkProfile(selectedReviewer)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = safeMessage(error)
                mutableProgress.value = null
                return@launchBenchmark
            }
            try {
                helpers.forEachIndexed { helperIndex, helper ->
                    val config = baseConfig.copy(targetProfileUid = helper.uid, fallbackToAnotherProfile = false)
                    val baseProgress = helperIndex * suite.size
                    var run = BenchmarkRun(
                        UUID.randomUUID().toString(), profile.uid, profile.name, profile.compatibleType.name,
                        profile.model, benchmarkConfigKey(profile, localEnvironment.value), profile.compatibleType == ClientType.LITERT_LM,
                        BenchmarkMode.DELEGATION, System.currentTimeMillis(), finished = false, suiteVersion = 2,
                        plannedTrials = dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkSuite().size, measurementVersion = 2, scoringVersion = 2, appCommit = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT, runtimeVersion = dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}", thermalBefore = thermal(), batteryBefore = battery(),
                        engineWasLoaded = profile.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec() != null,
                        delegationSettings = config
                    )
                    var currentTest = suite.first()
                    try {
                        mutableProgress.value = BenchmarkProgress("${profile.name} → ${helper.name}", "Validating helper", baseProgress, totalTests)
                        chats.validateBenchmarkProfile(helper)
                        check(!helper.disableAllTools && chats.supportsBenchmarkTools(helper)) { "The delegate needs tool calling enabled and a model that supports tools." }
                        reviewer?.let { selectedReviewer ->
                            check(selectedReviewer.uid != helper.uid) { "Reviewer must be a different profile from the delegate." }
                            check(!selectedReviewer.model.trim().equals(helper.model.trim(), ignoreCase = true)) { "Reviewer must use a different model from the delegate." }
                        }
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("DelegationBenchmark", "BATCH_WORKER_START · worker=${helper.uid} model=${helper.model} index=${helperIndex + 1}/${helpers.size}")
                        store.save(run)
                        val stoppedReason = runBenchmarkSuite(
                            suite = suite,
                            runCase = { index, test ->
                                currentTest = test
                                if (helper.compatibleType == ClientType.LITERT_LM || profile.compatibleType == ClientType.LITERT_LM) checkLocalConditions()
                                mutableProgress.value = BenchmarkProgress("${profile.name} → ${helper.name}", test.label, baseProgress + index, totalTests)
                                try {
                                    chats.runDelegationBenchmark(profile, test, "benchmark-${run.id}-${test.id}", config)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.ERROR, error = safeMessage(failure))
                                }
                            },
                            onSample = { sample ->
                                val pss = withContext(Dispatchers.IO) { Debug.getPss() }
                                val actual = runtime.state.value.takeIf { run.local && sample.completed }
                                run = run.copy(
                                    samples = run.samples + sample,
                                    peakClientPssKb = maxOf(run.peakClientPssKb ?: 0, pss),
                                    backend = actual?.backend?.displayName ?: run.backend,
                                    accelerator = actual?.engineSpec?.accelerator ?: run.accelerator
                                )
                                store.save(run)
                            },
                            stopOnError = false
                        )
                        if (stoppedReason != null) {
                            run = run.copy(stoppedReason = stoppedReason)
                            failures += "${helper.name}: $stoppedReason"
                        }
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("DelegationBenchmark", "BATCH_WORKER_COMPLETE · worker=${helper.uid} passed=${run.samples.count { it.outcome == BenchmarkOutcome.PASSED }}/${run.samples.size}")
                    } catch (cancelled: CancellationException) {
                        val canceledSample = if (run.samples.any { it.testId == currentTest.id }) emptyList() else listOf(BenchmarkSample(currentTest.id, currentTest.label, currentTest.category, BenchmarkOutcome.CANCELED))
                        run = run.copy(canceled = true, stoppedReason = interruptionReason, samples = run.samples + canceledSample)
                        throw cancelled
                    } catch (error: Exception) {
                        val reason = "Delegate ${helper.name} could not be benchmarked: ${safeMessage(error)}"
                        failures += reason
                        run = run.copy(stoppedReason = reason)
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("DelegationBenchmark", "BATCH_WORKER_ERROR · worker=${helper.uid} · ${safeMessage(error)}", "E")
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                val finished = run.copy(finished = true, thermalAfter = thermal(), batteryAfter = battery())
                                store.save(finished)
                                if (finished.mode == BenchmarkMode.DELEGATION) logDelegationRating(finished)
                            } catch (error: Exception) {
                                failures += "${helper.name}: could not save benchmark: ${safeMessage(error)}"
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("DelegationBenchmark", "BATCH_CANCELLED", "W")
            } finally {
                mutableProgress.value = null
                if (failures.isNotEmpty()) mutableError.value = failures.joinToString("\n").take(1000)
            }
        }
    }

    private fun launchBenchmark(block: suspend () -> Unit): Job = viewModelScope.launch {
        interruptionReason = null
        try {
            dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.benchmark(block)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            mutableError.value = "Benchmark could not acquire inference resources within 60 seconds. Stop active requests and try again."
        } finally {
            mutableProgress.value = null
        }
    }

    private fun thermal(): Int? = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    private fun checkLocalConditions() {
        check((battery() ?: 100) >= 15) { "Local benchmark stopped: battery is below 15%." }
        check((thermal() ?: 0) < PowerManager.THERMAL_STATUS_SEVERE) { "Local benchmark stopped: severe thermal pressure." }
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    private fun safeMessage(error: Exception) = DiagnosticRedactor.redact(error.message ?: "Unknown error").take(500)
}
