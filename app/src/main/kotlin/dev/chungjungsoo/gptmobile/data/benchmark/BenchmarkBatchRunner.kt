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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                val failure = result.samples.firstOrNull { it.executionFailure() || it.cleanupReceipt == "cleanup_unconfirmed_local_blocked" }?.failure
                val state = when {
                    skipped -> BenchmarkQueueState.SKIPPED
                    result.stoppedReason != null && result.samples.none { it.requestDispatched == true || it.chunks > 0 } -> BenchmarkQueueState.BLOCKED
                    result.stoppedReason != null -> BenchmarkQueueState.FAILED
                    else -> BenchmarkQueueState.COMPLETED
                }
                val terminal = running.copy(state = state, endedAt = System.currentTimeMillis(), completedCases = result.samples.size, activeCaseId = null, failure = failure)
                batch = latest.copy(activeOrdinal = null, stopOrigin = if (skipped) null else latest.stopOrigin, items = latest.items.map { if (it.ordinal == item.ordinal) terminal else it })
                checkpoint(batch, result.copy(finished = true, endedAt = terminal.endedAt, state = state.name, thermalAfter = thermal(), batteryAfter = battery()), event = "PROFILE_${state.name}")
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
        val plan = if (batch.mode == BenchmarkMode.DELEGATION) delegationBenchmarkSuite(batch.delegationPlan ?: BenchmarkMode.QUICK, batch.paired) else benchmarkSuiteForVersion(batch.suiteVersion, batch.mode)
        require(plan.isNotEmpty()) { "FIXTURE_INVALID: Unknown suite ${batch.suiteVersion}" }
        var run = newRun(batch, item, primary, plan.size)
        checkpoint(batch, run, event = "PREFLIGHT")
        var stage = "preflight"
        try {
            progress(BenchmarkProgress(item.profile.name, "Waiting for resources", item.ordinal * plan.size, batch.items.size * plan.size))
            val contextTokens = InferenceAdmission.benchmark {
                try {
                    withTimeout(60_000) {
                        if (usesLocal) {
                            checkLocalConditions()
                            validateEnvironment(batch)
                        }
                        check(!usesLocal || !localCleanupBlocked) { "NATIVE_QUARANTINED: Previous native cleanup was not acknowledged." }
                        chats.validateBenchmarkProfile(primary)
                        if (batch.mode == BenchmarkMode.DELEGATION) chats.validateBenchmarkProfile(target)
                        reviewer?.let { chats.validateBenchmarkProfile(it) }
                        if (batch.mode == BenchmarkMode.DELEGATION) null else chats.benchmarkContextTokens(primary)
                    }
                } finally {
                    run = run.copy(cleanupReceipt = cleanupLocal(usesLocal), runtimeSnapshot = if (usesLocal) runtime.state.value else null)
                    checkpointAfterCleanup(batch.id, run, event = "PREFLIGHT_CLEANUP")
                }
            }
            val suite = if (batch.mode == BenchmarkMode.DELEGATION) plan else BenchmarkWorkloadPlanner.adapt(plan, contextTokens, if (batch.suiteVersion >= 8) 1280 else 1024)
            val supportsTools = chats.supportsBenchmarkTools(primary)
            run = run.copy(fixtureContextTokens = contextTokens, capabilityManifest = setOf("text", "structured", "reasoning") + if (supportsTools) setOf("tools") else emptySet(), fixtureManifestHash = benchmarkHash(suite.joinToString("\n") { it.id + it.prompt + it.expected }))
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
                InferenceAdmission.benchmark {
                    val admissionWaitMs = (android.os.SystemClock.elapsedRealtime() - admissionStarted).coerceAtLeast(0)
                    val measurementLease = InferenceAdmission.currentLease()
                    val attemptStartedAt = System.currentTimeMillis()
                    val attempt = BenchmarkAttempt(attemptId, batch.id, item.ordinal, run.id, test.id, index, attemptStartedAt, attemptStartedAt + benchmarkCaseDeadlineMs(batch.mode, test), logicalRequestId = requestId)
                    val currentBatch = checkNotNull(store.batch(batch.id))
                    val caseCheckpoint = currentBatch.copy(items = currentBatch.items.map { if (it.ordinal == item.ordinal) it.copy(activeCaseId = test.id, completedCases = run.samples.size) else it })
                    checkpoint(caseCheckpoint, run, listOf(attempt), "CASE_STARTED")
                    var cleanup = "not_required"
                    var cleanupDurationMs: Long? = null
                    var nativeIdentity: BenchmarkNativeIdentity? = null
                    try {
                        val sample = try {
                            check(!usesLocal || !localCleanupBlocked) { "NATIVE_QUARANTINED: Previous native cleanup was not acknowledged." }
                            if (usesLocal) {
                                checkLocalConditions()
                                validateEnvironment(batch)
                            }
                            progress(BenchmarkProgress(item.profile.name, test.label, item.ordinal * suite.size + index, batch.items.size * suite.size))
                            if (test.workload in unsupportedBands) {
                                BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.UNSUPPORTED, error = "Unsupported input band established by an earlier case; no request sent.", workload = test.workload, warmup = test.warmup, requestDispatched = false, failureCategory = "UNSUPPORTED_INPUT_BAND")
                            } else if (batch.mode == BenchmarkMode.DELEGATION) {
                                chats.runDelegationBenchmark(primary, test, requestId, checkNotNull(batch.delegationSettings).copy(targetProfileUid = target.uid, fallbackToAnotherProfile = false), frozenProfiles = listOfNotNull(primary, target, reviewer).associateBy { it.uid })
                            } else {
                                BenchmarkRunner(openSession = { turns, tools -> chats.openBenchmarkSession(primary, turns, tools, requestId, test.requestCategory, onInvocation = { invocations.addIfAbsent(it) }, onPhysicalRequest = { physicalRequests.addIfAbsent(it) }) }, now = android.os.SystemClock::elapsedRealtime, onInterrupted = { interruptedSample = it }).run(prepareTest(test), supportsTools)
                            }
                        } finally {
                            if (usesLocal) nativeIdentity = benchmarkNativeIdentity(runtime, run.id, attemptId, measurementLease)
                            val cleanupStarted = android.os.SystemClock.elapsedRealtime()
                            try {
                                cleanup = cleanupLocal(usesLocal)
                            } finally {
                                cleanupDurationMs = (android.os.SystemClock.elapsedRealtime() - cleanupStarted).coerceAtLeast(0)
                            }
                        }
                        if (sample.failureCategory == "UNSUPPORTED_INPUT_BAND") unsupportedBands += test.workload
                        val actual = if (usesLocal) runtime.state.value else null
                        val runtimeVersion = actual?.backend?.let(::actualRuntimeVersion)
                        val retained = sample.copy(
                            attemptId = attemptId, runtimeSnapshot = actual, actualRuntimeVersion = runtimeVersion, workerPssBytes = if (usesLocal) runtime.processPssBytes else null, cleanupReceipt = cleanup, cleanupDurationMs = cleanupDurationMs, admissionWaitMs = admissionWaitMs, nativeIdentity = nativeIdentity, failure = if (cleanup == "cleanup_unconfirmed_local_blocked") BenchmarkFailure("NATIVE_CLEANUP_UNCONFIRMED", "cleanup", "Native cleanup was not acknowledged; later local work is blocked.", owner = "runtime", scope = "local_runtime") else sample.failure, physicalRequestIds = physicalRequests.toList(), logicalRequestId = requestId, invocationIds = invocations.toList(),
                            loadRegime = if (!usesLocal) {
                                "remote_unknown"
                            } else if (runtime.loadedEngineSpec() == null) {
                                "cold_or_unknown"
                            } else if (index == 0 && !run.engineWasLoaded) {
                                "cold"
                            } else {
                                "ready"
                            },
                            cacheRegime = if (usesLocal && runtime.loadedEngineSpec()?.cacheEnabled == true) "enabled_unknown_reuse" else "unknown"
                        )
                        run = run.copy(samples = run.samples + retained, runtimeSnapshot = actual, runtimeVersion = runtimeVersion ?: run.runtimeVersion, backend = actual?.backend?.displayName ?: run.backend, accelerator = actual?.engineSpec?.accelerator ?: run.accelerator, peakClientPssKb = maxOf(run.peakClientPssKb ?: 0, Debug.getPss()), cleanupReceipt = cleanup, resources = run.resources + BenchmarkResourceSample(test.id, System.currentTimeMillis(), Debug.getPss(), Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), battery(), thermal(), if (usesLocal) runtime.processPssBytes else null))
                        checkpoint(caseCheckpoint.copy(items = caseCheckpoint.items.map { if (it.ordinal == item.ordinal) it.copy(completedCases = run.samples.size, activeCaseId = null) else it }), run, listOf(attempt.copy(state = "TERMINAL", requestDispatched = retained.requestDispatched, dispatchUncertain = retained.requestDispatched == null, sample = retained, physicalRequestIds = retained.physicalRequestIds, logicalRequestId = requestId, invocationIds = retained.invocationIds)), "CASE_FINISHED")
                    } catch (failure: Exception) {
                        if (failure is BenchmarkStorageFailure) throw failure
                        val reason = when (requestedStop) {
                            BenchmarkStopOrigin.USER_SKIP -> BenchmarkFailure("USER_SKIP", "request", "Skipped by user.", owner = "user")
                            BenchmarkStopOrigin.USER_STOP -> BenchmarkFailure("USER_STOP", "request", "Stopped by user.", owner = "user", scope = "batch")
                            else -> benchmarkFailure(failure, "request")
                        }
                        val retained = (interruptedSample ?: BenchmarkSample(test.id, test.label, test.category, reason.outcome(), workload = test.workload, warmup = test.warmup)).copy(
                            outcome = if (reason.code == "USER_SKIP") {
                                BenchmarkOutcome.SKIPPED
                            } else if (failure is CancellationException) {
                                BenchmarkOutcome.CANCELED
                            } else {
                                reason.outcome()
                            },
                            failure = reason, failureCategory = reason.code, runtimeStage = reason.stage, error = reason.message,
                            attemptId = attemptId, cleanupReceipt = cleanup, cleanupDurationMs = cleanupDurationMs, admissionWaitMs = admissionWaitMs, nativeIdentity = nativeIdentity, physicalRequestIds = physicalRequests.toList(), logicalRequestId = requestId, invocationIds = invocations.toList()
                        )
                        run = run.copy(samples = run.samples + retained)
                        // The receipt is committed before releasing measurement/native ownership.
                        checkpointAfterCleanup(batch.id, run, listOf(attempt.copy(state = if (failure is CancellationException) "INTERRUPTED" else "TERMINAL", dispatchUncertain = retained.requestDispatched == null, requestDispatched = retained.requestDispatched, physicalRequestIds = retained.physicalRequestIds, logicalRequestId = requestId, invocationIds = retained.invocationIds, sample = retained)), "CASE_INTERRUPTED")
                        throw failure
                    }
                }
                val last = run.samples.last()
                if (last.executionFailure() || last.cleanupReceipt == "cleanup_unconfirmed_local_blocked") {
                    val failure = last.failure ?: BenchmarkFailure(last.failureCategory ?: "EXECUTION_FAILED", "request", last.error ?: "The profile did not finish this request.")
                    return appendDependentSkips(run.copy(stoppedReason = failure.message), suite, failure)
                }
            }
            return run
        } catch (error: Exception) {
            if (error is CancellationException || error is BenchmarkStorageFailure) throw error
            val failure = benchmarkFailure(error, stage)
            return appendFailure(run, benchmarkSuiteForRun(run), failure)
        }
    }

    private suspend fun failedProfile(batch: BenchmarkBatch, item: BenchmarkQueueItem, error: Exception, skip: Boolean): BenchmarkRun {
        val run = store.history.value.firstOrNull { it.id == item.runId } ?: newRun(batch, item, batch.delegationPrimary ?: item.profile, benchmarkSuiteForVersion(batch.suiteVersion, batch.mode).size)
        val failure = if (skip) BenchmarkFailure("USER_SKIP", "request", "Skipped by user.", owner = "user") else benchmarkFailure(error, if (run.samples.isEmpty()) "preflight" else "request")
        val retained = run.copy(
            cancellationOrigin = if (skip) {
                BenchmarkStopOrigin.USER_SKIP
            } else if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
                BenchmarkStopOrigin.CHILD_OPERATION_ABORTED
            } else {
                null
            },
            stoppedReason = failure.message
        )
        return if (run.samples.lastOrNull()?.let { it.executionFailure() || it.failure?.code == "USER_SKIP" } == true) appendDependentSkips(retained, benchmarkSuiteForRun(run), failure) else appendFailure(retained, benchmarkSuiteForRun(run), failure)
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
        thermalBefore = thermal(), batteryBefore = battery(), engineWasLoaded = profile.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec() != null,
        batchId = batch.id, queueOrdinal = item.ordinal, state = "RUNNING", requestedAccelerator = profile.accelerator,
        loadRegime = if (profile.compatibleType != ClientType.LITERT_LM) {
            "remote_unknown"
        } else if (runtime.loadedEngineSpec() != null) {
            "ready"
        } else {
            "cold"
        },
        cacheRegime = if (profile.compatibleType == ClientType.LITERT_LM && runtime.loadedEngineSpec()?.cacheEnabled == true) "enabled_unknown_reuse" else "unknown",
        delegationSettings = batch.delegationSettings?.copy(targetProfileUid = item.profile.uid), delegationPlan = batch.delegationPlan,
        delegationSuiteVersion = if (batch.mode == BenchmarkMode.DELEGATION) if (batch.paired) 2 else 1 else 0,
        reviewerProvider = batch.delegationReviewer?.compatibleType?.name, reviewerModel = batch.delegationReviewer?.model,
        diagnosticMode = batch.diagnosticMode
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

    private suspend fun cleanupLocal(usesLocal: Boolean): String {
        if (!usesLocal) return "not_required"
        val receipt = cleanupBenchmarkLocalRuntime(runtime)
        if (receipt == "cleanup_unconfirmed_local_blocked") localCleanupBlocked = true
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
internal suspend fun cleanupBenchmarkLocalRuntime(runtime: LocalRuntime): String {
    if (!runtime.isNativeOperationBusy && !runtime.hasOpenConversation()) return "not_required"
    return withContext(NonCancellable) {
        val closed = try {
            withTimeoutOrNull(15_000) {
                if (runtime.isNativeOperationBusy) runtime.cancelActive()
                runtime.closeConversation()
                !runtime.isNativeOperationBusy && !runtime.hasOpenConversation()
            } == true
        } catch (_: Exception) {
            false
        }
        if (closed) "conversation_closed" else "cleanup_unconfirmed_local_blocked"
    }
}
