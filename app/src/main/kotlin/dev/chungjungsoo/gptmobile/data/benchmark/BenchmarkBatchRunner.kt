package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Await a supervised child in the healthy queue parent. A child abort cannot cancel siblings. */
internal suspend fun <T> benchmarkProfileIsolated(block: suspend () -> T, failed: suspend (Exception) -> T, onChild: (Deferred<T>?) -> Unit = {}): T = supervisorScope {
    val child = async { block() }
    onChild(child)
    try {
        child.await()
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        if (failure is BenchmarkStorageFailure) throw failure
        failed(failure)
    } finally {
        onChild(null)
    }
}

/** Application-owned durable traversal, shared by single, standard batch and delegation. */
@Singleton
class BenchmarkBatchRunner @Inject constructor(
    private val store: BenchmarkStore,
    private val chats: ChatRepository,
    private val settings: SettingRepository,
    private val runtime: LocalRuntime,
    @param:ApplicationContext private val context: Context
) {
    @Volatile private var child: Deferred<BenchmarkRun>? = null

    @Volatile private var activeId: String? = null

    @Volatile private var activeOrdinal: Int? = null

    @Volatile private var requestedStop: BenchmarkStopOrigin? = null

    @Volatile private var localCleanupBlocked = false

    internal suspend fun requestStop(id: String, origin: BenchmarkStopOrigin): Boolean {
        if (activeId != id) return false
        val ordinal = activeOrdinal
        if (origin == BenchmarkStopOrigin.USER_SKIP && ordinal == null) return false
        // Keep the origin even if storage times out, then persist before signaling cancellation.
        requestedStop = origin
        if (!store.recordStop(id, origin, ordinal)) {
            if (requestedStop == origin) requestedStop = null
            return false
        }
        if (activeId != id || origin == BenchmarkStopOrigin.USER_SKIP && activeOrdinal != ordinal) return false
        if (origin == BenchmarkStopOrigin.USER_SKIP) child?.cancel(CancellationException("USER_SKIP"))
        return true
    }

    private suspend fun checkpoint(batch: BenchmarkBatch, run: BenchmarkRun? = null, attempts: List<BenchmarkAttempt> = emptyList(), event: String = "CHECKPOINT") {
        benchmarkCheckpointWithinDeadline { store.checkpoint(batch, run, attempts, event) }
    }

    private suspend fun checkpointAfterCleanup(batchId: String, run: BenchmarkRun, attempts: List<BenchmarkAttempt> = emptyList(), event: String) = withContext(NonCancellable) {
        benchmarkCheckpointWithinDeadline {
            // The read belongs to the same bound as its write, including on Stop.
            val current = checkNotNull(store.batch(batchId))
            store.checkpoint(current, run, attempts, event)
        }
    }

    internal suspend fun run(id: String, progress: (BenchmarkProgress) -> Unit) {
        var batch = checkNotNull(store.batch(id))
        activeId = id
        requestedStop = null
        localCleanupBlocked = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                batch = checkNotNull(store.batch(id))
                if (requestedStop in setOf(BenchmarkStopOrigin.USER_STOP, BenchmarkStopOrigin.SERVICE_INTERRUPTION)) {
                    interrupt(id, checkNotNull(requestedStop))
                    return
                }
                val item = batch.pending.firstOrNull() ?: break
                activeOrdinal = item.ordinal
                val running = item.copy(state = BenchmarkQueueState.RUNNING, startedAt = System.currentTimeMillis())
                batch = batch.copy(state = BenchmarkBatchState.RUNNING, activeOrdinal = item.ordinal, stopOrigin = null, items = batch.items.map { if (it.ordinal == item.ordinal) running else it })
                checkpoint(batch, event = "PROFILE_STARTED")
                val result = benchmarkProfileIsolated(
                    block = { executeProfile(batch, running, progress) },
                    failed = { failure -> failedProfile(batch, running, failure, requestedStop == BenchmarkStopOrigin.USER_SKIP) },
                    onChild = { child = it }
                )
                val latest = checkNotNull(store.batch(id))
                val skipped = requestedStop == BenchmarkStopOrigin.USER_SKIP
                val failure = result.samples.firstOrNull { it.outcome in setOf(BenchmarkOutcome.FAILED, BenchmarkOutcome.PARTIAL) || it.executionFailure() || it.cleanupReceipt == "cleanup_unconfirmed_local_blocked" }?.let { it.failure ?: BenchmarkFailure(it.failureCategory ?: "MODEL_QUALITY", it.runtimeStage ?: "validation", it.error ?: "A case did not meet its fixture requirements.", scope = "request", failureScope = BenchmarkFailureScope.REQUEST) }
                    ?: result.readiness?.takeIf { !it.ready }?.let { readiness -> readiness.probes.firstNotNullOfOrNull { it.sample?.failure } ?: BenchmarkFailure("READINESS_NOT_VERIFIED", "readiness", result.stoppedReason ?: "Readiness did not complete.") }
                val state = when {
                    skipped -> BenchmarkQueueState.SKIPPED
                    result.stoppedReason != null && result.samples.none { it.requestDispatched == true || it.chunks > 0 } && result.readiness?.probes.orEmpty().none { it.sample?.knownExecuted() == true } -> BenchmarkQueueState.BLOCKED
                    result.stoppedReason != null -> BenchmarkQueueState.FAILED
                    else -> BenchmarkQueueState.COMPLETED
                }
                val terminal = running.copy(state = state, endedAt = System.currentTimeMillis(), completedCases = result.samples.size, activeCaseId = null, failure = failure)
                batch = latest.copy(activeOrdinal = null, stopOrigin = if (skipped) null else latest.stopOrigin, items = latest.items.map { if (it.ordinal == item.ordinal) terminal else it })
                val runState = if (state == BenchmarkQueueState.COMPLETED && failure != null) "COMPLETED_WITH_FAILURES" else state.name
                checkpoint(batch, result.copy(finished = true, endedAt = terminal.endedAt, state = runState, thermalAfter = thermal(), batteryAfter = battery()), event = "PROFILE_$runState")
                activeOrdinal = null
                if (skipped) requestedStop = null
            }
            batch = checkNotNull(store.batch(id))
            val completion = batch.completionState()
            checkpoint(batch.copy(state = completion, activeOrdinal = null, endedAt = System.currentTimeMillis()), event = completion.name)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000) { interrupt(id, requestedStop ?: BenchmarkStopOrigin.SERVICE_INTERRUPTION) }
            }
            throw cancel
        } catch (failure: Exception) {
            // Never dispatch the next profile after uncertain canonical persistence.
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000) {
                    val latest = store.batch(id) ?: batch
                    val message = "Results could not be checkpointed. Queue paused; pending profiles are retained."
                    val now = System.currentTimeMillis()
                    val failedRun = latest.items.firstOrNull { it.state == BenchmarkQueueState.RUNNING }?.let { item -> store.history.value.firstOrNull { it.id == item.runId } }?.copy(finished = true, endedAt = now, state = "INTERRUPTED", stoppedReason = message)
                    store.checkpoint(latest.copy(state = BenchmarkBatchState.STORAGE_PAUSED, activeOrdinal = null, endedAt = now, storageWarning = message, items = latest.items.map { if (it.state == BenchmarkQueueState.RUNNING) it.copy(state = BenchmarkQueueState.INTERRUPTED, endedAt = now, failure = BenchmarkFailure("STORAGE_CHECKPOINT_FAILED", "checkpoint", message, owner = "storage", scope = "batch")) else it }), failedRun, event = "STORAGE_PAUSED")
                }
            }
            throw failure
        } finally {
            activeId = null
            activeOrdinal = null
            child = null
        }
    }

    private suspend fun executeProfile(batch: BenchmarkBatch, item: BenchmarkQueueItem, progress: (BenchmarkProgress) -> Unit): BenchmarkRun {
        val savedProfiles = settings.fetchPlatformV2s()
        fun authenticate(frozen: PlatformV2): PlatformV2 {
            val current = savedProfiles.firstOrNull { it.uid == frozen.uid } ?: error("PROFILE_UNAVAILABLE: ${frozen.name} was deleted.")
            return frozen.copy(token = current.token, secretRef = frozen.secretRef ?: current.secretRef)
        }
        val target = authenticate(item.profile)
        val primary = batch.delegationPrimary?.let(::authenticate) ?: target
        val reviewer = batch.delegationReviewer?.let(::authenticate)
        val usesLocal = listOfNotNull(primary, target, reviewer).any { it.compatibleType == ClientType.LITERT_LM }
        val policy = batch.executionPolicy ?: BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
        val plan = batch.caseManifest ?: when (batch.mode) {
            BenchmarkMode.CPU_EXPERIMENT -> BenchmarkCpuExperiment.cases(item.runId)
            BenchmarkMode.DELEGATION -> delegationBenchmarkSuite(batch.delegationPlan ?: BenchmarkMode.QUICK, batch.paired)
            else -> benchmarkSuiteForVersion(batch.suiteVersion, batch.mode, batch.fixtureRotationSeed ?: 0)
        }
        require(plan.isNotEmpty()) { "FIXTURE_INVALID: Unknown suite ${batch.suiteVersion}" }
        var run = newRun(batch, item, primary, plan.size)
        checkpoint(batch, run, event = "PREFLIGHT")
        var stage = "preflight"
        val preflightStarted = android.os.SystemClock.elapsedRealtime()
        try {
            progress(BenchmarkProgress(item.profile.name, "Waiting for resources", item.ordinal * plan.size, batch.items.size * plan.size))
            val contextTokens = InferenceAdmission.benchmark {
                try {
                    withTimeout(60_000) {
                        check(batch.mode !in setOf(BenchmarkMode.CPU_EXPERIMENT, BenchmarkMode.PERFORMANCE) || target.compatibleType == ClientType.LITERT_LM) { "UNSUPPORTED_CAPABILITY: Native performance diagnostics require an on-device profile." }
                        if (usesLocal) {
                            checkLocalConditions()
                            validateEnvironment(batch)
                        }
                        check(!usesLocal || !localCleanupBlocked) { "NATIVE_QUARANTINED: Previous native cleanup was not acknowledged." }
                        withContext(BenchmarkRequestContext(policy)) { chats.validateBenchmarkProfile(primary) }
                        if (batch.mode == BenchmarkMode.DELEGATION) chats.validateBenchmarkProfile(target)
                        reviewer?.let { chats.validateBenchmarkProfile(it) }
                        if (batch.mode == BenchmarkMode.DELEGATION) null else withContext(BenchmarkRequestContext(policy)) { chats.benchmarkContextTokens(primary) }
                    }
                } finally {
                    val cleanup = cleanupLocal(usesLocal, "benchmark:${run.id}:preflight")
                    run = run.copy(cleanupReceipt = benchmarkCleanupLabel(cleanup), runtimeCleanup = cleanup, preflightDurationMs = (android.os.SystemClock.elapsedRealtime() - preflightStarted).coerceAtLeast(0), runtimeSnapshot = if (usesLocal) runtime.state.value else null)
                    checkpointAfterCleanup(batch.id, run, event = "PREFLIGHT_CLEANUP")
                }
            }
            val suite = when (batch.mode) {
                BenchmarkMode.DELEGATION -> plan
                BenchmarkMode.PERFORMANCE -> BenchmarkPerformanceSuite.adapt(plan, contextTokens)
                else -> BenchmarkWorkloadPlanner.adapt(plan, contextTokens, if (batch.suiteVersion >= 8) 1280 else 1024)
            }
            val supportsTools = chats.supportsBenchmarkTools(primary)
            run = run.copy(fixtureContextTokens = contextTokens, caseManifest = suite, capabilityManifest = setOf("text", "structured", "reasoning") + if (supportsTools) setOf("tools") else emptySet(), fixtureManifestHash = benchmarkHash(suite.joinToString("\n") { it.id + it.prompt + it.expected }))
            if (batch.suiteVersion >= 10 && batch.mode !in setOf(BenchmarkMode.QUICK, BenchmarkMode.DELEGATION)) {
                stage = "readiness"
                val readiness = BenchmarkReadinessRunner(
                    metadata = { tools, schema -> withContext(BenchmarkRequestContext(policy, schema, readinessProbe = true)) { chats.benchmarkReadiness(primary, tools, schema) } },
                    execute = { test, request, retained ->
                        progress(BenchmarkProgress(item.profile.name, test.label, item.ordinal * suite.size, batch.items.size * suite.size))
                        executeReadinessProbe(batch, run.id, primary, test, request, supportsTools, usesLocal, retained)
                    },
                    persist = { receipt ->
                        run = run.copy(readiness = receipt, structuredOutputMode = receipt.structuredOutputMode, structuredSchemaModes = receipt.schemaModes, verifiedStructuredCompatibilityKeys = receipt.verifiedStructuredCompatibilityKeys)
                        checkpointAfterCleanup(batch.id, run, event = "READINESS_CHECKPOINT")
                    }
                ).run(suite, policy, usesLocal, supportsTools)
                if (!readiness.ready) {
                    val cause = readiness.probes.firstNotNullOfOrNull { it.sample?.failure }
                        ?: BenchmarkFailure("READINESS_NOT_VERIFIED", "readiness", readiness.checks.firstOrNull { it.id in readiness.requiredCheckIds && it.status != BenchmarkReadinessStatus.PASSED }?.detail ?: "Required readiness checks were not positively verified.", owner = "benchmark", scope = "profile")
                    return appendDependentSkips(run.copy(stoppedReason = cause.message), suite, cause)
                }
                checkpointAfterCleanup(batch.id, run, event = "READINESS_FINISHED")
            }
            val unsupportedBands = mutableSetOf<String>()
            for ((index, test) in suite.withIndex()) {
                currentCoroutineContext().ensureActive()
                stage = "request"
                val attemptId = "${run.id}:${test.id}:0"
                val requestId = "benchmark-${run.id}-${test.id}"
                val invocations = java.util.concurrent.CopyOnWriteArrayList<String>()
                val physicalRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
                var interruptedSample: BenchmarkSample? = null
                progress(BenchmarkProgress(item.profile.name, "Waiting for resources · ${test.label}", item.ordinal * suite.size + index, batch.items.size * suite.size))
                val admissionStarted = android.os.SystemClock.elapsedRealtime()
                val queuedAt = System.currentTimeMillis()
                var enteredAdmission = false
                try {
                    InferenceAdmission.benchmark {
                        enteredAdmission = true
                        val admissionWaitMs = (android.os.SystemClock.elapsedRealtime() - admissionStarted).coerceAtLeast(0)
                        val measurementLease = InferenceAdmission.currentLease()
                        val attemptStartedAt = System.currentTimeMillis()
                        val attemptStartedElapsed = android.os.SystemClock.elapsedRealtime()
                        val attempt = BenchmarkAttempt(attemptId, batch.id, item.ordinal, run.id, test.id, index, attemptStartedAt, attemptStartedAt + benchmarkCaseDeadlineMs(batch.mode, test, batch.suiteVersion), logicalRequestId = requestId, queuedAt = queuedAt)
                        val currentBatch = checkNotNull(store.batch(batch.id))
                        val caseCheckpoint = currentBatch.copy(items = currentBatch.items.map { if (it.ordinal == item.ordinal) it.copy(activeCaseId = test.id, completedCases = run.samples.size) else it })
                        checkpoint(caseCheckpoint, run, listOf(attempt), "CASE_STARTED")
                        var cleanup = "not_required"
                        var cleanupDurationMs: Long? = null
                        var nativeIdentity: BenchmarkNativeIdentity? = null
                        var cleanupDetails: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? = null
                        var before = if (usesLocal) observeBenchmarkRuntime(runtime) else null
                        val beforeInspectionId = if (usesLocal) runtime.latestInspectionReceipt?.inspectionId else null
                        var latestInspection: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt? = null
                        var after: BenchmarkRuntimeObservation? = null
                        var executionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? = null
                        val resources = BenchmarkResourceRecorder { scope ->
                            BenchmarkResourceSample(test.id, System.currentTimeMillis(), Debug.getPss(), Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), battery(), thermal(), if (usesLocal) runtime.processPssBytes else null, scope, android.os.SystemClock.elapsedRealtime())
                        }
                        runCatching { resources.observe("before_execution") }
                        val sampler = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                            while (isActive) {
                                delay(1000)
                                runCatching { resources.observe("execution") }
                            }
                        }
                        try {
                            val sample = try {
                                check(!usesLocal || !localCleanupBlocked) { "NATIVE_QUARANTINED: Previous native cleanup was not acknowledged." }
                                if (usesLocal) {
                                    checkLocalConditions()
                                    validateEnvironment(batch)
                                }
                                if (usesLocal && test.performanceTrial?.requestedLoadRegime == "cold") {
                                    withTimeout(30_000) { runtime.unloadEngine() }
                                    check(runtime.loadedEngineSpec() == null) { "COLD_ENGINE_RESET_UNCONFIRMED: The previous engine is still resident." }
                                    before = observeBenchmarkRuntime(runtime)
                                }
                                progress(BenchmarkProgress(item.profile.name, test.label, item.ordinal * suite.size + index, batch.items.size * suite.size))
                                val dependency = test.dependsOn.mapNotNull { predecessor -> run.samples.firstOrNull { it.testId == predecessor } }.firstOrNull { !it.completed }
                                if (dependency != null) {
                                    BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.SKIPPED, error = "Required predecessor ${dependency.testId} did not complete.", workload = test.workload, warmup = test.warmup, requestDispatched = false, failureCategory = "SKIPPED_DEPENDENCY")
                                } else if (test.workload in unsupportedBands) {
                                    BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.UNSUPPORTED, error = "Unsupported input band established by an earlier case; no request sent.", workload = test.workload, warmup = test.warmup, requestDispatched = false, failureCategory = "UNSUPPORTED_INPUT_BAND")
                                } else if (batch.mode == BenchmarkMode.DELEGATION) {
                                    chats.runDelegationBenchmark(primary, test, requestId, checkNotNull(batch.delegationSettings).copy(targetProfileUid = target.uid, fallbackToAnotherProfile = false), frozenProfiles = listOfNotNull(primary, target, reviewer).associateBy { it.uid })
                                } else {
                                    val requestContext = requestContext(run, test, policy)
                                    val runner = BenchmarkRunner(timeoutMs = benchmarkCaseDeadlineMs(batch.mode, test, batch.suiteVersion), openSession = { turns, tools -> chats.openBenchmarkSession(primary, turns, tools, requestId, test.requestCategory, onInvocation = { invocations.addIfAbsent(it) }, onPhysicalRequest = { physicalRequests.addIfAbsent(it) }) }, now = android.os.SystemClock::elapsedRealtime, onInterrupted = { interruptedSample = it }, observeRuntime = { if (usesLocal) runtime.latestExecutionReceipt else null })
                                    val prepared = prepareTest(test)
                                    withContext(requestContext) {
                                        if (test.cpuExperiment != null) withContext(dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeTrialContext(test.cpuExperiment)) { runner.run(prepared, supportsTools) } else runner.run(prepared, supportsTools)
                                    }
                                }
                            } finally {
                                withContext(NonCancellable) { sampler.cancelAndJoin() }
                                runCatching { resources.observe("before_cleanup") }
                                if (usesLocal) {
                                    runCatching {
                                        after = observeBenchmarkRuntime(runtime)
                                        executionReceipt = runtime.latestExecutionReceipt
                                        latestInspection = runtime.latestInspectionReceipt
                                        nativeIdentity = benchmarkNativeIdentity(runtime, run.id, attemptId, measurementLease, after!!.identity)
                                    }
                                }
                                val cleanupStarted = android.os.SystemClock.elapsedRealtime()
                                try {
                                    cleanupDetails = cleanupLocal(usesLocal, "benchmark:${run.id}:${test.id}")
                                    cleanup = benchmarkCleanupLabel(cleanupDetails)
                                } finally {
                                    cleanupDurationMs = (android.os.SystemClock.elapsedRealtime() - cleanupStarted).coerceAtLeast(0)
                                    runCatching { resources.observe("after_cleanup") }
                                }
                            }
                            if (sample.failureCategory == "UNSUPPORTED_INPUT_BAND") unsupportedBands += test.workload
                            val actual = after?.state
                            val runtimeVersion = actual?.backend?.let(::actualRuntimeVersion)
                            val executions = benchmarkExecutionReceipts(sample, executionReceipt, physicalRequests.toSet())
                            val resident = if (before != null && after != null) benchmarkResidentMatch(requireNotNull(before), after!!, executions.lastOrNull()) else null
                            var retained = sample.copy(
                                attemptId = attemptId, runtimeSnapshot = actual, actualRuntimeVersion = runtimeVersion, workerPssBytes = resources.snapshots().lastOrNull { it.scope == "before_cleanup" }?.workerPssBytes, workerPssScope = "before_cleanup", cleanupReceipt = cleanup, runtimeCleanup = cleanupDetails, cleanupDurationMs = cleanupDurationMs, admissionWaitMs = admissionWaitMs, nativeIdentity = nativeIdentity, failure = if (cleanup == "cleanup_unconfirmed_local_blocked") BenchmarkFailure("NATIVE_CLEANUP_UNCONFIRMED", "cleanup", "Native cleanup was not acknowledged; later local work is blocked.", owner = "runtime", scope = "local_runtime") else sample.failure, physicalRequestIds = physicalRequests.toList(), logicalRequestId = requestId, invocationIds = invocations.toList(),
                                runtimeExecutions = executions,
                                requestDispatched = if (executions.any { it.knownDispatched }) true else sample.requestDispatched,
                                requestAdmitted = if (physicalRequests.isNotEmpty() || sample.knownExecuted()) true else sample.requestAdmitted,
                                loadRegime = resident?.regime ?: "remote_unknown", residentMatch = resident, cacheConfigured = after?.loaded?.cacheEnabled,
                                endToEndDurationMs = (android.os.SystemClock.elapsedRealtime() - admissionStarted).coerceAtLeast(0),
                                generationDurationMs = benchmarkNativeGenerationDuration(executions),
                                peakExecutionWorkerPssBytes = resources.peakExecutionWorkerPssBytes, peakExecutionClientPssKb = resources.peakExecutionClientPssKb
                            )
                            retained = retained.withPreparationEvidence(beforeInspectionId, latestInspection, attemptStartedElapsed, android.os.SystemClock.elapsedRealtime(), before)
                            retained = retained.copy(deadlineReceipt = benchmarkDeadlineReceipt(batch.mode, test, batch.suiteVersion), settingsReceipt = benchmarkSettingsReceipt(primary, test, policy, retained, requestContext(run, test, policy)), cacheRegime = benchmarkObservedCache(retained)).withFailureScope(batch.recoveryPolicy == "INDEPENDENT_CASES_AFTER_CONFIRMED_CLEANUP_V1")
                            if (test.cpuExperiment?.cacheRegime == "cache_hot" && retained.cacheRegime != "observed_cached_prefix") retained = retained.copy(timingEligible = false, throughputExclusion = "REQUESTED_CACHE_HOT_REUSE_NOT_OBSERVED")
                            val allSamples = run.samples + retained
                            val observedTimingCache = allSamples.filter { it.category == "speed" && !it.warmup && it.knownExecuted() }.map { it.cacheRegime }.distinct()
                            run = run.copy(
                                samples = allSamples, runtimeSnapshot = actual, runtimeVersion = if (retained.knownExecuted()) runtimeVersion ?: run.runtimeVersion else run.runtimeVersion, backend = if (retained.knownExecuted()) actual?.backend?.displayName ?: run.backend else run.backend, accelerator = if (retained.knownExecuted()) actual?.engineSpec?.accelerator ?: run.accelerator else run.accelerator, peakClientPssKb = listOfNotNull(run.peakClientPssKb, resources.peakClientPssKb).maxOrNull(), cleanupReceipt = cleanup, runtimeCleanup = cleanupDetails, resources = run.resources + resources.snapshots(),
                                engineWasLoaded = if (index == 0) resident?.exactMatch == true else run.engineWasLoaded,
                                loadRegime = if (index == 0) retained.loadRegime else run.loadRegime,
                                cacheRegime = observedTimingCache.singleOrNull() ?: if (observedTimingCache.isEmpty()) "unknown" else "mixed_observed_reuse",
                                peakExecutionWorkerPssBytes = listOfNotNull(run.peakExecutionWorkerPssBytes, resources.peakExecutionWorkerPssBytes).maxOrNull(), peakExecutionClientPssKb = listOfNotNull(run.peakExecutionClientPssKb, resources.peakExecutionClientPssKb).maxOrNull()
                            )
                            checkpoint(caseCheckpoint.copy(items = caseCheckpoint.items.map { if (it.ordinal == item.ordinal) it.copy(completedCases = run.samples.size, activeCaseId = null) else it }), run, listOf(attempt.copy(state = "TERMINAL", requestDispatched = retained.requestDispatched, dispatchUncertain = retained.requestDispatched == null, sample = retained, physicalRequestIds = retained.physicalRequestIds, logicalRequestId = requestId, invocationIds = retained.invocationIds)), "CASE_FINISHED")
                        } catch (failure: Exception) {
                            if (failure is BenchmarkStorageFailure) throw failure
                            val reason = when (requestedStop) {
                                BenchmarkStopOrigin.USER_SKIP -> BenchmarkFailure("USER_SKIP", "request", "Skipped by user.", owner = "user")
                                BenchmarkStopOrigin.USER_STOP -> BenchmarkFailure("USER_STOP", "request", "Stopped by user.", owner = "user", scope = "batch")
                                else -> benchmarkFailure(failure, "request")
                            }
                            val childAborted = failure is CancellationException && currentCoroutineContext().isActive && requestedStop == null
                            val executions = benchmarkExecutionReceipts(interruptedSample, executionReceipt, physicalRequests.toSet())
                            var retained = (interruptedSample ?: BenchmarkSample(test.id, test.label, test.category, reason.outcome(), workload = test.workload, warmup = test.warmup)).copy(
                                outcome = if (reason.code == "USER_SKIP") {
                                    BenchmarkOutcome.SKIPPED
                                } else if (childAborted) {
                                    BenchmarkOutcome.ERROR
                                } else if (failure is CancellationException) {
                                    BenchmarkOutcome.CANCELED
                                } else {
                                    reason.outcome()
                                },
                                failure = reason, failureCategory = reason.code, runtimeStage = reason.stage, error = reason.message,
                                attemptId = attemptId, cleanupReceipt = cleanup, runtimeCleanup = cleanupDetails, cleanupDurationMs = cleanupDurationMs, admissionWaitMs = admissionWaitMs, nativeIdentity = nativeIdentity, physicalRequestIds = physicalRequests.toList(), logicalRequestId = requestId, invocationIds = invocations.toList(),
                                runtimeExecutions = executions,
                                requestDispatched = if (executions.any { it.knownDispatched }) true else interruptedSample?.requestDispatched,
                                requestAdmitted = if (physicalRequests.isNotEmpty() || executions.any { it.knownDispatched }) true else interruptedSample?.requestAdmitted,
                                runtimeSnapshot = after?.state, actualRuntimeVersion = after?.state?.backend?.let(::actualRuntimeVersion),
                                workerPssBytes = resources.snapshots().lastOrNull { it.scope == "before_cleanup" }?.workerPssBytes, workerPssScope = "before_cleanup",
                                endToEndDurationMs = (android.os.SystemClock.elapsedRealtime() - admissionStarted).coerceAtLeast(0),
                                generationDurationMs = benchmarkNativeGenerationDuration(executions),
                                peakExecutionWorkerPssBytes = resources.peakExecutionWorkerPssBytes, peakExecutionClientPssKb = resources.peakExecutionClientPssKb
                            )
                            retained = retained.withPreparationEvidence(beforeInspectionId, latestInspection, attemptStartedElapsed, android.os.SystemClock.elapsedRealtime(), before)
                            retained = retained.copy(deadlineReceipt = benchmarkDeadlineReceipt(batch.mode, test, batch.suiteVersion), settingsReceipt = benchmarkSettingsReceipt(primary, test, policy, retained, requestContext(run, test, policy)))
                            run = run.copy(samples = run.samples + retained, resources = run.resources + resources.snapshots(), peakClientPssKb = listOfNotNull(run.peakClientPssKb, resources.peakClientPssKb).maxOrNull(), peakExecutionWorkerPssBytes = listOfNotNull(run.peakExecutionWorkerPssBytes, resources.peakExecutionWorkerPssBytes).maxOrNull(), peakExecutionClientPssKb = listOfNotNull(run.peakExecutionClientPssKb, resources.peakExecutionClientPssKb).maxOrNull(), runtimeCleanup = cleanupDetails, cleanupReceipt = cleanup)
                            // The receipt is committed before releasing measurement/native ownership.
                            checkpointAfterCleanup(batch.id, run, listOf(attempt.copy(state = if (failure is CancellationException && !childAborted) "INTERRUPTED" else "TERMINAL", dispatchUncertain = retained.requestDispatched == null, requestDispatched = retained.requestDispatched, physicalRequestIds = retained.physicalRequestIds, logicalRequestId = requestId, invocationIds = retained.invocationIds, sample = retained)), "CASE_INTERRUPTED")
                            if (!childAborted || retained.recoveryAction(batch.recoveryPolicy == "INDEPENDENT_CASES_AFTER_CONFIRMED_CLEANUP_V1") != BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES) throw failure
                        }
                    }
                } catch (failure: Exception) {
                    if (!enteredAdmission && failure !is BenchmarkStorageFailure) {
                        val elapsed = (android.os.SystemClock.elapsedRealtime() - admissionStarted).coerceAtLeast(0)
                        val reason = benchmarkFailure(failure, "admission")
                        val waiting = benchmarkAdmissionOnlySample(test, reason, elapsed, failure is CancellationException).copy(attemptId = attemptId, logicalRequestId = requestId)
                        run = run.copy(samples = run.samples + waiting)
                        val receipt = BenchmarkAttempt(attemptId, batch.id, item.ordinal, run.id, test.id, index, queuedAt, queuedAt + benchmarkCaseDeadlineMs(batch.mode, test, batch.suiteVersion), state = "NOT_ADMITTED", requestDispatched = false, sample = waiting, logicalRequestId = requestId, queuedAt = queuedAt)
                        checkpointAfterCleanup(batch.id, run, listOf(receipt), "CASE_NOT_ADMITTED")
                    }
                    throw failure
                }
                val last = run.samples.last()
                if (last.recoveryAction(batch.recoveryPolicy == "INDEPENDENT_CASES_AFTER_CONFIRMED_CLEANUP_V1") !in setOf(BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES, BenchmarkRecoveryAction.SKIP_DEPENDENTS)) {
                    val failure = last.failure ?: BenchmarkFailure(last.failureCategory ?: "EXECUTION_FAILED", "request", last.error ?: "The profile did not finish this request.")
                    return appendDependentSkips(run.copy(stoppedReason = failure.message), suite, failure)
                }
            }
            return run
        } catch (error: Exception) {
            if (error is BenchmarkStorageFailure) throw error
            val failure = benchmarkFailure(error, if (stage == "preflight") "preflight_admission" else stage)
            val failed = if (stage == "readiness" || run.samples.lastOrNull()?.executionFailure() == true) appendDependentSkips(run.copy(stoppedReason = failure.message), benchmarkSuiteForRun(run), failure) else appendFailure(run, benchmarkSuiteForRun(run), failure)
            val retained = if (stage == "preflight" && run.samples.isEmpty()) {
                val elapsed = (android.os.SystemClock.elapsedRealtime() - preflightStarted).coerceAtLeast(0)
                failed.copy(preflightDurationMs = elapsed, samples = failed.samples.mapIndexed { index, sample -> if (index == 0) benchmarkAdmissionOnlySample(plan.first(), failure, elapsed, error is CancellationException) else sample })
            } else {
                failed
            }
            if (error is CancellationException) {
                if (stage == "preflight" && run.samples.isEmpty()) checkpointAfterCleanup(batch.id, retained, event = "PREFLIGHT_INTERRUPTED")
                throw error
            }
            return retained
        }
    }

    private fun requestContext(run: BenchmarkRun, test: BenchmarkCase, policy: BenchmarkExecutionPolicy): BenchmarkRequestContext {
        val digest = test.responseSchemaJson?.let(::benchmarkHash)
        return BenchmarkRequestContext(
            policy,
            test.responseSchemaJson,
            benchmarkCaseDeadlineMs(run.mode, test, run.suiteVersion),
            structuredOutputMode = digest?.let(run.structuredSchemaModes::get) ?: BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1,
            verifiedStructuredCompatibilityKey = digest?.let(run.verifiedStructuredCompatibilityKeys::get)
        )
    }

    /** Probe evidence commits before admission is released, including cancellation and cleanup failure. */
    private suspend fun executeReadinessProbe(
        batch: BenchmarkBatch,
        runId: String,
        profile: PlatformV2,
        test: BenchmarkCase,
        request: BenchmarkRequestContext,
        supportsTools: Boolean,
        usesLocal: Boolean,
        retained: suspend (BenchmarkSample) -> Unit
    ): BenchmarkSample = InferenceAdmission.benchmark {
        val started = android.os.SystemClock.elapsedRealtime()
        val physical = java.util.concurrent.CopyOnWriteArrayList<String>()
        val invocations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logical = "benchmark-$runId-${test.id}"
        val before = if (usesLocal) observeBenchmarkRuntime(runtime) else null
        val beforeInspection = if (usesLocal) runtime.latestInspectionReceipt?.inspectionId else null
        var after: BenchmarkRuntimeObservation? = null
        var latestExecution: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? = null
        var latestInspection: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt? = null
        var interrupted: BenchmarkSample? = null
        var failure: Exception? = null
        var sample: BenchmarkSample? = null
        var cleanup: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? = null
        var cleanupMs: Long? = null
        val resources = BenchmarkResourceRecorder { scope ->
            BenchmarkResourceSample(test.id, System.currentTimeMillis(), Debug.getPss(), Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), battery(), thermal(), if (usesLocal) runtime.processPssBytes else null, scope, android.os.SystemClock.elapsedRealtime())
        }
        runCatching { resources.observe("before_execution") }
        val sampler = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                runCatching { resources.observe("execution") }
            }
        }
        try {
            check(!usesLocal || !localCleanupBlocked) { "NATIVE_QUARANTINED: Previous native cleanup was not acknowledged." }
            if (usesLocal) {
                checkLocalConditions()
                validateEnvironment(batch)
            }
            val runner = BenchmarkRunner(
                timeoutMs = request.requestDeadlineMs ?: BenchmarkReadinessRunner.PROBE_DEADLINE_MS,
                openSession = { turns, tools -> chats.openBenchmarkSession(profile, turns, tools, logical, test.requestCategory, onInvocation = { invocations.addIfAbsent(it) }, onPhysicalRequest = { physical.addIfAbsent(it) }) },
                now = android.os.SystemClock::elapsedRealtime,
                onInterrupted = { interrupted = it },
                observeRuntime = { if (usesLocal) runtime.latestExecutionReceipt else null }
            )
            sample = withContext(request) { runner.run(test, supportsTools) }
        } catch (error: Exception) {
            if (error is BenchmarkStorageFailure) throw error
            failure = error
        } finally {
            withContext(NonCancellable) { sampler.cancelAndJoin() }
            runCatching { resources.observe("before_cleanup") }
            if (usesLocal) {
                runCatching {
                    after = observeBenchmarkRuntime(runtime)
                    latestExecution = runtime.latestExecutionReceipt
                    latestInspection = runtime.latestInspectionReceipt
                }
            }
            val cleanupStarted = android.os.SystemClock.elapsedRealtime()
            cleanup = cleanupLocal(usesLocal, "benchmark:$runId:${test.id}")
            cleanupMs = (android.os.SystemClock.elapsedRealtime() - cleanupStarted).coerceAtLeast(0)
            runCatching { resources.observe("after_cleanup") }
        }
        val reason = failure?.let { benchmarkFailure(it, "readiness") }
        var result = sample ?: interrupted ?: BenchmarkSample(test.id, test.label, test.category, if (failure is CancellationException) BenchmarkOutcome.CANCELED else reason?.outcome() ?: BenchmarkOutcome.ERROR, error = reason?.message, failure = reason, failureCategory = reason?.code, requestDispatched = if (physical.isEmpty()) false else null)
        val executions = benchmarkExecutionReceipts(result, latestExecution, physical.toSet())
        val resident = if (before != null && after != null) benchmarkResidentMatch(before, requireNotNull(after), executions.lastOrNull()) else null
        result = result.copy(
            attemptId = "$runId:${test.id}:0", logicalRequestId = logical, physicalRequestIds = physical.toList(), invocationIds = invocations.toList(),
            runtimeExecutions = executions, requestDispatched = if (executions.any { it.knownDispatched }) true else result.requestDispatched,
            requestAdmitted = if (physical.isNotEmpty()) true else result.requestAdmitted,
            runtimeSnapshot = after?.state, actualRuntimeVersion = after?.state?.backend?.let(::actualRuntimeVersion),
            cleanupReceipt = benchmarkCleanupLabel(cleanup), runtimeCleanup = cleanup, cleanupDurationMs = cleanupMs,
            endToEndDurationMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0), generationDurationMs = benchmarkNativeGenerationDuration(executions),
            workerPssBytes = resources.snapshots().lastOrNull { it.scope == "before_cleanup" }?.workerPssBytes, workerPssScope = "before_cleanup",
            peakExecutionWorkerPssBytes = resources.peakExecutionWorkerPssBytes, peakExecutionClientPssKb = resources.peakExecutionClientPssKb,
            loadRegime = resident?.regime ?: "remote_unknown", residentMatch = resident, cacheConfigured = after?.loaded?.cacheEnabled
        )
        result = result.withPreparationEvidence(beforeInspection, latestInspection, started, android.os.SystemClock.elapsedRealtime(), before)
        result = result.copy(deadlineReceipt = benchmarkDeadlineReceipt(batch.mode, test, batch.suiteVersion, readiness = true), settingsReceipt = benchmarkSettingsReceipt(profile, test, request.executionPolicy, result, request), cacheRegime = benchmarkObservedCache(result))
        withContext(NonCancellable) { retained(result) }
        failure?.takeIf { it is CancellationException }?.let { throw it }
        result
    }

    private suspend fun failedProfile(batch: BenchmarkBatch, item: BenchmarkQueueItem, error: Exception, skip: Boolean): BenchmarkRun {
        val run = store.history.value.firstOrNull { it.id == item.runId } ?: newRun(batch, item, batch.delegationPrimary ?: item.profile, benchmarkSuiteForVersion(batch.suiteVersion, batch.mode).size)
        val failure = if (skip) BenchmarkFailure("USER_SKIP", "request", "Skipped by user.", owner = "user") else benchmarkFailure(error, if (run.samples.isEmpty()) "preflight" else "request")
        val childAborted = !skip && error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException
        val retained = run.copy(
            samples = if (childAborted) run.samples.map { sample -> if (sample.outcome == BenchmarkOutcome.CANCELED) sample.copy(outcome = BenchmarkOutcome.ERROR, failure = failure, failureCategory = "CHILD_OPERATION_ABORTED") else sample } else run.samples,
            cancellationOrigin = if (skip) {
                BenchmarkStopOrigin.USER_SKIP
            } else if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
                BenchmarkStopOrigin.CHILD_OPERATION_ABORTED
            } else {
                null
            },
            stoppedReason = failure.message
        )
        return if (run.readiness != null && run.samples.isEmpty() || run.samples.lastOrNull()?.let { it.executionFailure() || it.failure?.code == "USER_SKIP" } == true) appendDependentSkips(retained, benchmarkSuiteForRun(run), failure) else appendFailure(retained, benchmarkSuiteForRun(run), failure)
    }

    private fun appendFailure(run: BenchmarkRun, suite: List<BenchmarkCase>, failure: BenchmarkFailure): BenchmarkRun {
        val pending = suite.filter { test -> run.samples.none { it.testId == test.id } }
        val first = pending.firstOrNull()
        val failed = if (first == null) run else run.copy(samples = run.samples + BenchmarkSample(first.id, first.label, first.category, if (failure.code == "USER_SKIP") BenchmarkOutcome.SKIPPED else failure.outcome(), error = failure.message, workload = first.workload, warmup = first.warmup, failureCategory = failure.code, failure = failure, runtimeStage = failure.stage, requestDispatched = if (failure.stage == "preflight") false else null))
        return appendDependentSkips(failed.copy(stoppedReason = failure.message), suite, failure)
    }

    private fun appendDependentSkips(run: BenchmarkRun, suite: List<BenchmarkCase>, failure: BenchmarkFailure): BenchmarkRun = run.copy(samples = run.samples + suite.filter { test -> run.samples.none { it.testId == test.id } }.map { test -> BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.SKIPPED, error = failure.message, failureCategory = "SKIPPED_DEPENDENCY", failure = failure, requestDispatched = false, workload = test.workload, warmup = test.warmup, tokenBasis = ReferenceTextTokenizer.VERSION) })

    private fun newRun(batch: BenchmarkBatch, item: BenchmarkQueueItem, profile: PlatformV2, planned: Int) = BenchmarkRun(
        item.runId, profile.uid, profile.name, profile.compatibleType.name, profile.model, item.configKey,
        profile.compatibleType == ClientType.LITERT_LM, batch.mode, System.currentTimeMillis(), finished = false,
        suiteVersion = batch.suiteVersion, measurementVersion = batch.measurementVersion, scoringVersion = batch.scoringVersion,
        plannedTrials = planned, appCommit = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT,
        runtimeVersion = "unreported", device = "${Build.MANUFACTURER} ${Build.MODEL}",
        thermalBefore = thermal(), batteryBefore = battery(), engineWasLoaded = false,
        batchId = batch.id, queueOrdinal = item.ordinal, state = "RUNNING", requestedAccelerator = if (batch.mode == BenchmarkMode.CPU_EXPERIMENT) "cpu" else profile.accelerator,
        loadRegime = if (profile.compatibleType != ClientType.LITERT_LM) {
            "remote_unknown"
        } else {
            "unverified_residency"
        },
        cacheRegime = if (profile.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec()?.cacheEnabled == true) "enabled_unknown_reuse" else "unknown",
        delegationSettings = batch.delegationSettings?.copy(targetProfileUid = item.profile.uid), delegationPlan = batch.delegationPlan,
        delegationSuiteVersion = if (batch.mode == BenchmarkMode.DELEGATION) if (batch.paired) 2 else 1 else 0,
        reviewerProvider = batch.delegationReviewer?.compatibleType?.name, reviewerModel = batch.delegationReviewer?.model,
        diagnosticMode = batch.diagnosticMode || batch.mode in setOf(BenchmarkMode.CPU_EXPERIMENT, BenchmarkMode.PERFORMANCE),
        deadlinePolicy = if (batch.suiteVersion >= 10) "benchmark-workload-deadlines-v2" else "benchmark-request-deadline-v1",
        executionPolicy = batch.executionPolicy, fixtureRotationSeed = batch.fixtureRotationSeed,
        fixtureSetVersion = if (batch.suiteVersion >= 10) BenchmarkIntelligenceFixtures.VERSION else null,
        deviceBuild = Build.FINGERPRINT.takeIf { it.isNotBlank() },
        measurementPolicy = "benchmark-measurement-v${batch.measurementVersion}", aggregatePolicy = if (batch.suiteVersion >= 10) "benchmark-score-v6" else "benchmark-score-v5"
    )

    private suspend fun interrupt(id: String, origin: BenchmarkStopOrigin) {
        val batch = store.batch(id) ?: return
        val ordinal = activeOrdinal
        val active = batch.items.firstOrNull { it.ordinal == ordinal }
        val run = active?.let { item -> store.history.value.firstOrNull { it.id == item.runId } }?.copy(finished = true, canceled = origin == BenchmarkStopOrigin.USER_STOP, state = "INTERRUPTED", cancellationOrigin = origin, endedAt = System.currentTimeMillis(), stoppedReason = if (origin == BenchmarkStopOrigin.USER_STOP) "Stopped by user." else "Benchmark interrupted; committed measurements are retained.")
        store.checkpoint(batch.copy(state = if (origin == BenchmarkStopOrigin.USER_STOP) BenchmarkBatchState.STOPPED else BenchmarkBatchState.INTERRUPTED, stopOrigin = origin, activeOrdinal = null, endedAt = System.currentTimeMillis(), items = batch.items.map { if (it.ordinal == ordinal && it.state == BenchmarkQueueState.RUNNING) it.copy(state = BenchmarkQueueState.INTERRUPTED, endedAt = System.currentTimeMillis()) else it }), run, event = origin.name)
    }

    private suspend fun prepareTest(test: BenchmarkCase): BenchmarkCase {
        val code = test.imageCode ?: return test
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = android.graphics.Bitmap.createBitmap(512, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val file = java.io.File(context.cacheDir, "benchmark-${UUID.randomUUID()}.png")
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = 110f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                canvas.drawText(code, 100f, 165f, paint)
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            test.copy(attachments = listOf(dev.chungjungsoo.gptmobile.data.model.ChatAttachment(file.path, file.path, "benchmark-image.png", "image/png", file.length(), width = 512, height = 256)))
        }
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    private fun thermal(): Int? = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    private fun actualRuntimeVersion(backend: dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend): String = when (backend) {
        dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.LLAMA_CPP -> dev.melo.gguf.GgufNative.REVISION
        dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.LITERT_LM -> dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION
        dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.QUALCOMM_QNN -> dev.chungjungsoo.gptmobile.BuildConfig.QAIRT_VERSION
        else -> "unreported"
    }

    private suspend fun cleanupLocal(usesLocal: Boolean, origin: String): dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? {
        if (!usesLocal) return null
        val receipt = cleanupBenchmarkLocalRuntimeReceipt(runtime, origin)
        if (!receipt.safeForSuccessor) localCleanupBlocked = true
        return receipt
    }

    private suspend fun validateEnvironment(batch: BenchmarkBatch) {
        if (batch.localEnvironment.isBlank()) return
        val features = settings.getFeatureSettings()
        val backend = settings.getLocalRuntimeBackend()
        val actual = "$backend|${features.localCpuThreads}|${features.localModelCache}|${features.qnnAutomaticFallback}|${features.localSpeculativeDecoding}|${features.localNativeMetrics}|${features.localExecutionPolicy}|${dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION}|${dev.chungjungsoo.gptmobile.BuildConfig.QAIRT_VERSION}"
        check(actual == batch.localEnvironment) { "BINDING_EXPIRED: Local runtime settings changed after this selection was frozen. Start a new batch with the current settings." }
    }

    private fun checkLocalConditions() {
        check((battery() ?: 100) >= 15) { "ENVIRONMENT_BLOCKED: Battery is below 15%." }
        check((thermal() ?: 0) < PowerManager.THERMAL_STATUS_SEVERE) { "ENVIRONMENT_BLOCKED: Severe thermal pressure." }
    }
}

/** Bounds the complete canonical operation, including reads before its transactional write. */
internal suspend fun <T> benchmarkCheckpointWithinDeadline(timeoutMs: Long = 10_000, block: suspend () -> T): T = try {
    withTimeout(timeoutMs) { block() }
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    if (failure is BenchmarkStorageFailure) throw failure
    throw BenchmarkStorageFailure(failure)
}

/** Called while the trial still owns admission; its receipt is checkpointed before release. */
internal suspend fun cleanupBenchmarkLocalRuntime(runtime: LocalRuntime): String = benchmarkCleanupLabel(cleanupBenchmarkLocalRuntimeReceipt(runtime, "benchmark"))

internal fun benchmarkCleanupLabel(receipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt?): String = when {
    receipt == null -> "not_required"
    !receipt.safeForSuccessor -> "cleanup_unconfirmed_local_blocked"
    receipt.notRequiredReason != null -> "not_required"
    else -> "conversation_closed"
}

internal suspend fun cleanupBenchmarkLocalRuntimeReceipt(runtime: LocalRuntime, origin: String): dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt = withContext(NonCancellable) {
    val receipt = try {
        withTimeoutOrNull(15_000) { runtime.cleanupAfterRequest(origin) }
    } catch (_: Exception) {
        null
    }
    if (receipt == null || !receipt.safeForSuccessor && !receipt.quarantined) runtime.quarantineAfterUnconfirmedCleanup("Benchmark cleanup was not positively acknowledged before lease release.") else receipt
}
