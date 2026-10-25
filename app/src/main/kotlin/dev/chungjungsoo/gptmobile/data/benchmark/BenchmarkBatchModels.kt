package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkBatchState { QUEUED, RUNNING, STOPPED, INTERRUPTED, STORAGE_PAUSED, COMPLETED, COMPLETED_WITH_FAILURES }

@Serializable
enum class BenchmarkQueueState { QUEUED, RUNNING, COMPLETED, FAILED, BLOCKED, SKIPPED, INTERRUPTED }

@Serializable
enum class BenchmarkStopOrigin { USER_STOP, USER_SKIP, SERVICE_INTERRUPTION, PROCESS_INTERRUPTION, CHILD_OPERATION_ABORTED }

@Serializable
enum class BenchmarkFailureScope { REQUEST, DEPENDENCY, PROFILE, RUNTIME, BATCH, USER }

@Serializable
enum class BenchmarkRecoveryAction { CONTINUE_INDEPENDENT_CASES, SKIP_DEPENDENTS, STOP_PROFILE, QUARANTINE_RUNTIME, STOP_BATCH, HONOR_USER_STOP }

@Serializable
data class BenchmarkFailure(
    val code: String,
    val stage: String,
    val message: String,
    val owner: String = "profile",
    val scope: String = "profile",
    val retryable: Boolean = false,
    val nextEligibleAt: Long? = null,
    val failureScope: BenchmarkFailureScope? = null,
    val recoveryAction: BenchmarkRecoveryAction? = null
)

@Serializable
data class BenchmarkQueueItem(
    val ordinal: Int,
    val profile: PlatformV2,
    val runId: String,
    val configKey: String,
    val state: BenchmarkQueueState = BenchmarkQueueState.QUEUED,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val failure: BenchmarkFailure? = null,
    val activeCaseId: String? = null,
    val completedCases: Int = 0,
    val retryOfRunId: String? = null
) {
    val terminal: Boolean get() = state !in setOf(BenchmarkQueueState.QUEUED, BenchmarkQueueState.RUNNING)
}

@Serializable
data class BenchmarkBatch(
    val id: String,
    val mode: BenchmarkMode,
    val createdAt: Long,
    val items: List<BenchmarkQueueItem>,
    val state: BenchmarkBatchState = BenchmarkBatchState.QUEUED,
    val suiteVersion: Int = BenchmarkSuiteRegistry.VERSION,
    val measurementVersion: Int = 3,
    val scoringVersion: Int = 3,
    val activeOrdinal: Int? = null,
    val endedAt: Long? = null,
    val stopOrigin: BenchmarkStopOrigin? = null,
    val orderPolicy: String = "selected-order-v1",
    val orderSeed: Long? = null,
    val retryOfBatchId: String? = null,
    val delegationPrimary: PlatformV2? = null,
    val delegationReviewer: PlatformV2? = null,
    val delegationSettings: ModelDelegationSettings? = null,
    val delegationPlan: BenchmarkMode? = null,
    val paired: Boolean = false,
    val diagnosticMode: Boolean = false,
    val localEnvironment: String = "",
    val storageWarning: String? = null,
    val recoveryPolicy: String? = null,
    val executionPolicy: BenchmarkExecutionPolicy? = null,
    val fixtureRotationSeed: Long? = null,
    val caseManifest: List<BenchmarkCase>? = null
) {
    val pending: List<BenchmarkQueueItem> get() = items.filter { it.state == BenchmarkQueueState.QUEUED }
    val handled: Int get() = items.count { it.terminal }
    val failed: Int get() = items.count { it.state in setOf(BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED, BenchmarkQueueState.INTERRUPTED) }
    val canResume: Boolean get() = state in setOf(BenchmarkBatchState.STOPPED, BenchmarkBatchState.INTERRUPTED, BenchmarkBatchState.STORAGE_PAUSED) && pending.isNotEmpty()
}

@Serializable
data class BenchmarkAttempt(
    val id: String,
    val batchId: String,
    val ordinal: Int,
    val runId: String,
    val caseId: String,
    val trialIndex: Int,
    val startedAt: Long,
    val deadlineAt: Long,
    val state: String = "STARTED",
    val requestDispatched: Boolean? = null,
    val sample: BenchmarkSample? = null,
    val dispatchUncertain: Boolean = true,
    val physicalRequestIds: List<String> = emptyList(),
    val logicalRequestId: String? = null,
    val invocationIds: List<String> = emptyList(),
    val queuedAt: Long? = null
)

@Serializable
data class BenchmarkBatchEvent(val batchId: String, val sequence: Long, val type: String, val timestamp: Long, val runId: String? = null, val attemptId: String? = null)

internal fun BenchmarkBatch.completionState(): BenchmarkBatchState = when {
    items.any { !it.terminal } -> state
    items.any { it.failure != null || it.state in setOf(BenchmarkQueueState.FAILED, BenchmarkQueueState.BLOCKED, BenchmarkQueueState.INTERRUPTED) } -> BenchmarkBatchState.COMPLETED_WITH_FAILURES
    else -> BenchmarkBatchState.COMPLETED
}

internal fun benchmarkHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun PlatformV2.benchmarkSnapshot(): PlatformV2 = copy(token = null)
