package dev.chungjungsoo.gptmobile.data.localruntime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

/** The policy enforced by the isolated worker while a load command owns the engine. */
@Serializable
data class RuntimeLoadDeadlinePolicy(
    val absoluteMs: Long,
    val stageBudgetsMs: Map<String, Long>,
    val supervisorAbsoluteMs: Long? = null,
    val clock: String = "android_elapsed_realtime",
    val policy: String = "isolated-worker-load-v1"
)

/** Immutable native load facts. Null means that the backend did not report the field. */
@Serializable
data class EngineLoadReceipt(
    val receiptId: String,
    val artifactDigest: String,
    val runtimeRevision: String,
    val requestedBackend: String,
    val actualBackend: String?,
    val engineInstanceId: String,
    val workerGenerationId: String? = null,
    val workerPid: Int? = null,
    val nativeOperationId: Long? = null,
    val backendRegistry: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val deviceIndex: Int? = null,
    val driverIdentity: String? = null,
    val requestedOffloadLayers: Int? = null,
    val actualOffloadLayers: Int? = null,
    val contextTokens: Int? = null,
    val cpuThreads: Int? = null,
    val promptThreads: Int? = null,
    val batchTokens: Int? = null,
    val microBatchTokens: Int? = null,
    val templateIdentity: String? = null,
    val tokenizerIdentity: String? = null,
    val startedAtElapsedMs: Long,
    val completedAtElapsedMs: Long?,
    val outcome: String,
    val nativeAcknowledgedFields: Set<String> = emptySet(),
    val allocation: NativeAllocationObservation? = null,
    val engineInstanceIdentitySource: String? = null,
    val requestedContextTokens: Int? = null,
    val configurationSource: String? = null,
    val loadDeadlinePolicy: RuntimeLoadDeadlinePolicy? = null,
    val memoryPlan: MemoryPlanReceipt? = null,
    val schemaVersion: Int = 1
)

/** Per-generation configuration; engine creation preferences never substitute for these facts. */
@Serializable
data class GenerationConfigReceipt(
    val generationId: String,
    val nativeOperationId: Long?,
    val engineInstanceId: String?,
    val requestedSampler: LocalSamplerConfig?,
    val requestedOutputTokens: Int?,
    val effectiveSampler: LocalSamplerConfig? = null,
    val effectiveOutputTokens: Int? = null,
    val appliedSampler: LocalSamplerConfig? = null,
    val appliedOutputTokens: Int? = null,
    val requestedResponseSchemaDigest: String? = null,
    val appliedResponseSchemaDigest: String? = null,
    val effectiveResponseSchemaDigest: String? = null,
    val structuredOutputMode: String? = null,
    val structuredOutputCompatibilityKey: String? = null,
    val requestedThinking: Boolean? = null,
    val appliedThinking: Boolean? = null,
    val requestedSpeculativeDecoding: Boolean? = null,
    val appliedSpeculativeDecoding: Boolean? = null,
    val configurationSource: String? = null,
    val measuredPromptTokens: Long? = null,
    val safetyTokens: Int? = null,
    val contextTokens: Int? = null,
    val cpuThreads: Int? = null,
    val promptThreads: Int? = null,
    val batchTokens: Int? = null,
    val microBatchTokens: Int? = null,
    val tokenizerIdentity: String? = null,
    val templateIdentity: String? = null,
    val renderedPromptDigest: String? = null,
    val reusedPromptTokens: Long? = null,
    val processedPromptTokens: Long? = null,
    val nativeAcknowledgedFields: Set<String> = emptySet(),
    val experiment: CpuExperimentTrial? = null,
    val deadlinePolicy: NativeGenerationDeadlinePolicy? = null,
    val observedAtElapsedMs: Long,
    val cpuTimeMs: Long? = null,
    val cpuTimeScope: String? = null,
    val availableProcessors: Int? = null,
    val cpuAffinity: String? = null,
    val competingNativeOperations: Int? = null,
    val thermalState: String? = null,
    val schemaVersion: Int = 1
)

/** The exact thresholds used for one physical generation, independent of the client deadline. */
@Serializable
data class NativeGenerationDeadlinePolicy(
    val absoluteMs: Long,
    val prefillStallMs: Long,
    val decodeStallMs: Long,
    val minimumPrefillTokensPerSecond: Double,
    val throughputWindowMs: Long,
    val clock: String = "android_elapsed_realtime",
    val prefillBudgetBasis: String = "prompt_utf16_character_estimate",
    val schemaVersion: Int = 1
)

/** Facts only advance for the same request/worker/native operation; cleanup never replaces them. */
@Serializable
data class RuntimeExecutionReceipt(
    val runtimeRequestId: String? = null,
    val physicalAttemptId: String? = null,
    val nativeGenerationId: String? = null,
    val workerGenerationId: String? = null,
    val engineInstanceId: String? = null,
    val nativeOperationId: Long? = null,
    val generationIdentitySource: String? = null,
    val adapterInvokedAtElapsedMs: Long? = null,
    val nativeEntered: Boolean? = null,
    val nativeEnteredAtElapsedMs: Long? = null,
    val firstVisibleTextAtElapsedMs: Long? = null,
    val nativeReturned: Boolean? = null,
    val nativeReturnedAtElapsedMs: Long? = null,
    val terminalKind: String? = null,
    val commandTerminalAcknowledged: Boolean? = null,
    val commandTerminalAtElapsedMs: Long? = null,
    val cancellationOrigin: String? = null,
    val deadlineKind: String? = null,
    val processDeadlinePolicy: RuntimeProcessDeadlinePolicy? = null,
    val lastStage: String? = null,
    val promptTokens: Long? = null,
    val processedPromptTokens: Long? = null,
    val sampledTokens: Long? = null,
    val configuration: GenerationConfigReceipt? = null,
    val engineLoad: EngineLoadReceipt? = null,
    val observedAtElapsedMs: Long? = null,
    val schemaVersion: Int = 1
) {
    val knownDispatched: Boolean get() = nativeEntered == true
    val hasNativeTerminal: Boolean get() =
        nativeReturned == true &&
            terminalKind in setOf("eos", "output_limit", "cancelled", "native_error", "sdk_done", "structured_output_rejected")
}

/** Independent from answer correctness. Every required cleanup obligation needs positive evidence. */
@Serializable
data class RuntimeCleanupReceipt(
    val origin: String,
    val runtimeRequestId: String? = null,
    val physicalAttemptId: String? = null,
    val workerGenerationId: String? = null,
    val engineInstanceId: String? = null,
    val nativeOperationId: Long? = null,
    val requiredActions: Set<String> = emptySet(),
    val obligationsObserved: Boolean = false,
    val cancellationRequested: Boolean = false,
    val cancellationAcknowledged: Boolean? = null,
    val nativeClosureConfirmed: Boolean? = null,
    val conversationClosed: Boolean? = null,
    val engineReleased: Boolean? = null,
    val engineHealthy: Boolean? = null,
    val terminationRequested: Boolean = false,
    val workerExitConfirmed: Boolean? = null,
    val workerExitEvidence: String? = null,
    val replacementWorkerReady: Boolean? = null,
    val quarantined: Boolean = false,
    val notRequiredReason: String? = null,
    val failureReason: String? = null,
    val startedAtElapsedMs: Long? = null,
    val observedAtElapsedMs: Long? = null,
    val schemaVersion: Int = 1
) {
    val safeForSuccessor: Boolean get() {
        if (!obligationsObserved || quarantined || failureReason != null) return false
        if (workerExitConfirmed == true) return true
        if ("cancel" in requiredActions && cancellationAcknowledged != true && nativeClosureConfirmed != true) return false
        if ("close_conversation" in requiredActions && conversationClosed != true) return false
        if ("release_engine" in requiredActions && engineReleased != true) return false
        if (requiredActions.isEmpty() && notRequiredReason != null) return nativeClosureConfirmed == true
        return nativeClosureConfirmed == true && conversationClosed == true && (engineHealthy == true || engineReleased == true)
    }
}

/** Explicit diagnostic request context, never a persistent preference mutation. */
@Serializable
data class CpuExperimentTrial(
    val experimentId: String,
    val fixtureDigest: String,
    val threadCount: Int,
    val cacheRegime: String,
    val repeatIndex: Int
) {
    fun validated() =
        apply {
            require(experimentId.isNotBlank() && experimentId.length <= 128)
            require(fixtureDigest.matches(Regex("[a-f0-9]{64}")))
            require(threadCount in setOf(2, 4, 6, 8) && cacheRegime in setOf("cold", "cache_hot") && repeatIndex >= 0)
        }

    fun applyTo(spec: LocalEngineSpec): LocalEngineSpec =
        validated().let {
            spec.copy(
                accelerator = LocalAccelerators.CPU,
                cpuThreads = threadCount,
                ggufPromptThreads = threadCount,
                tuningLocked = true,
                ggufGpuLayers = 0,
                cacheEnabled = true,
                contextPolicy = "MANUAL"
            )
        }
}

class LocalRuntimeTrialContext(
    val trial: CpuExperimentTrial
) : AbstractCoroutineContextElement(Key) {
    init {
        trial.validated()
    }

    companion object Key : CoroutineContext.Key<LocalRuntimeTrialContext>
}

/** Negative progress sentinels and later bookkeeping phases cannot erase earlier measurements. */
internal fun GgufNativeProgress.retainingMeasurements(previous: GgufNativeProgress?): GgufNativeProgress {
    if (previous == null || previous.operationId != operationId) return this

    fun known(
        next: Long,
        old: Long
    ) = if (next < 0) {
        old
    } else if (old < 0) {
        next
    } else {
        maxOf(next, old)
    }
    return copy(
        promptTokens = known(promptTokens, previous.promptTokens),
        processedTokens = known(processedTokens, previous.processedTokens),
        sampledTokens = known(sampledTokens, previous.sampledTokens),
        executionReceipt = executionReceipt ?: previous.executionReceipt,
        deadlineKind = deadlineKind ?: previous.deadlineKind
    )
}

internal fun RuntimeExecutionReceipt.fromProgress(progress: GgufNativeProgress): RuntimeExecutionReceipt {
    require(nativeOperationId == null || nativeOperationId == progress.operationId)
    val entered = progress.stage == "NATIVE_ENTRY"
    val returned = progress.stage == "NATIVE_RETURN"

    fun known(
        next: Long,
        old: Long?
    ): Long? = if (next < 0) old else maxOf(next, old ?: 0)
    return copy(
        nativeOperationId = progress.operationId,
        nativeEntered = if (entered) true else nativeEntered,
        nativeEnteredAtElapsedMs = if (entered && nativeEnteredAtElapsedMs == null) progress.observedAtMs else nativeEnteredAtElapsedMs,
        // The native callback reports completion; worker command acknowledgement remains independent.
        nativeReturned = if (returned) true else nativeReturned,
        nativeReturnedAtElapsedMs = if (returned) progress.observedAtMs else nativeReturnedAtElapsedMs,
        terminalKind = if (returned) nativeTerminalKind(progress.nativeCode.toLong()) else terminalKind,
        lastStage = progress.stage,
        promptTokens = known(progress.promptTokens, promptTokens),
        processedPromptTokens = known(progress.processedTokens, processedPromptTokens),
        sampledTokens = known(progress.sampledTokens, sampledTokens),
        deadlineKind = progress.deadlineKind ?: deadlineKind,
        observedAtElapsedMs = progress.observedAtMs
    )
}

internal fun nativeTerminalKind(code: Long): String =
    when (code) {
        0L -> "eos"
        1L -> "output_limit"
        2L -> "cancelled"
        else -> "native_error"
    }

/** Immutable worker correlation propagated through IO dispatch and native callbacks. */
internal class RuntimeWorkerContext(
    val workerGenerationId: String,
    val runtimeRequestId: String,
    val physicalAttemptId: String?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeWorkerContext>
}
