package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

@kotlinx.serialization.Serializable
data class LocalEngineSpec(
    val modelPath: String,
    val accelerator: String,
    val maxTokens: Int,
    val isVisionEnabled: Boolean = false,
    val litertDispatchLibDir: String? = null,
    val visionAccelerator: String = LocalAccelerators.GPU,
    val cpuThreads: Int? = null,
    val cacheEnabled: Boolean = true,
    val speculativeDecoding: Boolean? = null,
    val nativeMetricsEnabled: Boolean = false,
    val tuningLocked: Boolean = false,
    val samplerIdentity: String = "runtime_default",
    val workload: String = "plain_text",
    val expectedOutputTokens: Int = 128,
    val ggufBatchTokens: Int = 256,
    val ggufMicroBatchTokens: Int = 128,
    val ggufPromptThreads: Int? = null,
    val ggufGpuLayers: Int = 8,
    val admissionReserveBytes: Long = 768L * 1024 * 1024
)

/** Workload labels affect plan selection, but do not invalidate the native engine configuration. */
internal fun LocalEngineSpec.engineConfiguration() = copy(samplerIdentity = "runtime_default", workload = "plain_text", expectedOutputTokens = 128, admissionReserveBytes = 768L * 1024 * 1024)

@kotlinx.serialization.Serializable
data class LocalSamplerConfig(
    val topK: Int,
    val topP: Float,
    val temperature: Float
)

@kotlinx.serialization.Serializable
enum class LocalHistoryRole {
    USER,
    MODEL
}

@kotlinx.serialization.Serializable
data class LocalHistoryMessage(
    val role: LocalHistoryRole,
    val text: String,
    val imageIds: List<String> = emptyList(),
    val images: List<ByteArray> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalHistoryMessage) return false
        return role == other.role && text == other.text && imageIds == other.imageIds
    }

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + imageIds.hashCode()
        return result
    }
}

@kotlinx.serialization.Serializable
data class LocalToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

fun interface LocalToolExecutor {
    suspend fun execute(toolName: String, argumentsJson: String): String
}

data class LocalConversationConfig(
    val sampler: LocalSamplerConfig,
    val systemPrompt: String?,
    val initialMessages: List<LocalHistoryMessage>,
    val tools: List<LocalToolDescriptor> = emptyList(),
    val isConstrainedDecodingEnabled: Boolean = false,
    val toolExecutor: LocalToolExecutor? = null,
    val maxOutputTokens: Int? = null,
    val thinkingEnabled: Boolean? = null,
    val speculativeDecoding: Boolean? = null,
    val finalResponseSchemaJson: String? = null,
    val allowAutomaticRecovery: Boolean = true
)

/** Execution phase of local on-device inference. */
enum class LocalInferencePhase {
    QUEUED,
    INSPECTING,
    LOADING,
    PREFILL,
    GENERATING,
    CANCELLING,
    RESETTING
}

/** Performance and generation telemetry emitted during local model execution. */
@kotlinx.serialization.Serializable
data class LocalInferenceMetrics(
    val timeToFirstTokenMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val totalChunks: Int = 0,
    val totalCharacters: Int = 0,
    val estimatedTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val native: NativeInferenceMetrics? = null,
    val metricSource: String = "estimated_characters",
    val firstVisibleAnswerMs: Long? = null,
    val decodeDurationMs: Long? = null,
    val segmentId: String? = null,
    val schemaVersion: Int = 2,
    val metricScope: String = "last_segment",
    val requestedMtp: Boolean? = null,
    val effectiveMtp: Boolean? = null,
    val planId: String? = null,
    val finishReason: String? = null
)

/** Native counters cover the last prefill/decode segment, not an entire multi-tool turn. */
@kotlinx.serialization.Serializable
data class NativeInferenceMetrics(
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val reusedPromptTokens: Int? = null,
    val processedPromptTokens: Int? = null,
    val prefillDurationMs: Long? = null,
    val decodeDurationMs: Long? = null
) {
    val hasValidTokenCounts: Boolean get() = prefillTokens >= 0 && decodeTokens >= 0
    val isValid: Boolean get() = prefillTokens >= 0 &&
        decodeTokens > 0 &&
        prefillTokensPerSecond.isFinite() &&
        prefillTokensPerSecond >= 0 &&
        decodeTokensPerSecond.isFinite() &&
        decodeTokensPerSecond > 0
}

/** Redacted, sampled worker observation. Negative counters mean unknown, never measured zero. */
@kotlinx.serialization.Serializable
data class GgufNativeProgress(
    val operationId: Long,
    val stage: String,
    val promptTokens: Long = -1,
    val processedTokens: Long = -1,
    val sampledTokens: Long = -1,
    val elapsedMs: Long = 0,
    val nativeCode: Int = 0,
    val observedAtMs: Long = 0,
    val configuration: String? = null,
    val loadFraction: Double? = null,
    val allocationObservation: NativeAllocationObservation? = null,
    val effectiveBatch: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val actualRuntime: String? = null,
    val modelName: String? = null,
    val runtimeRequestId: String? = null,
    val physicalAttemptId: String? = null
)

sealed interface LocalRuntimeEvent {
    /** Internal ordering fence, consumed by the native adapter before crossing IPC. */
    data class Drain(val acknowledged: kotlinx.coroutines.CompletableDeferred<Unit>) : LocalRuntimeEvent
    data class PhaseChanged(val phase: LocalInferencePhase, val nativeProgress: GgufNativeProgress? = null) : LocalRuntimeEvent
    data class TextDelta(val text: String) : LocalRuntimeEvent
    data class ThinkingDelta(val text: String) : LocalRuntimeEvent
    data class Metrics(val metrics: LocalInferenceMetrics) : LocalRuntimeEvent
    data object Done : LocalRuntimeEvent
    data class Error(val message: String, val cause: Throwable? = null) : LocalRuntimeEvent
}

/** The engine that actually initialized, independent of the saved preference. */
@kotlinx.serialization.Serializable
data class LocalRuntimeState(
    val backend: LocalRuntimeBackend? = null,
    val engineSpec: LocalEngineSpec? = null,
    val fallbackReason: String? = null,
    val preferredBackend: LocalRuntimeBackend? = null,
    val decision: LocalPlanDecision? = null,
    val processIsolated: Boolean = false,
    val lifecycle: String = "IDLE",
    val admissionReceipt: LocalAdmissionReceipt? = null,
    val engineHealth: String = "HEALTHY",
    val requestedMtp: Boolean? = null,
    val effectiveMtp: Boolean? = null,
    val loadProgress: GgufNativeProgress? = null,
    val allocationObservation: NativeAllocationObservation? = null
)

private val EMPTY_RUNTIME_STATE: StateFlow<LocalRuntimeState> = MutableStateFlow(LocalRuntimeState())

class LocalRuntimeFallbackDisabledException(cause: Throwable) :
    IllegalStateException(
        "${cause.message ?: "QNN could not start"}. Automatic fallback is disabled; enable it or choose a compatible GPU/CPU model edition.",
        cause
    )

class LocalNpuPackageException private constructor(message: String, cause: Throwable?) : IllegalStateException(message, cause) {
    constructor(cause: Throwable) : this(
        "NPU initialization failed: ${cause.message.orEmpty()}. This compiled package cannot use GPU/CPU fallback. " +
            "Export diagnostics, retry if the failure was transient, or download a separate compatible GPU / CPU edition from the marketplace.",
        cause
    )

    companion object {
        fun fromRemote(message: String): LocalNpuPackageException = LocalNpuPackageException(message, null)
    }
}

interface LocalRuntime {
    val isBusy: Boolean get() = false

    /**
     * Native work or unacknowledged worker teardown, without an outer admission lease.
     * A caller that already owns the measurement/resource lease uses this after close to
     * distinguish its own reservation from an operation that can still affect a successor.
     * This is an observation, never permission to bypass admission or the generation lock.
     */
    val isNativeOperationBusy: Boolean get() = isBusy
    val executionIdentity: LocalRuntimeExecutionIdentity get() = LocalRuntimeExecutionIdentity()
    val handlesEngineFallback: Boolean get() = false
    val ownsEngineSwitching: Boolean get() = false
    val state: StateFlow<LocalRuntimeState> get() = EMPTY_RUNTIME_STATE

    fun loadedEngineSpec(): LocalEngineSpec? = null

    /** Exact request admitted by the outer holder, before runtime tuning. */
    fun admittedEngineSpec(): LocalEngineSpec? = loadedEngineSpec()

    val processPssBytes: Long? get() = null

    val deviceRamGb: Long get() = 8L

    fun getHardwareState(): DeviceHardwareState = DeviceHardwareState()

    fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        DeviceHardwareGovernor.computeThrottlingPolicy(getHardwareState(), deviceRamGb >= 10L)

    /** Read-only failure admission before native metadata inspection, queueing or preparation. */
    suspend fun preflightModel(modelPath: String, accelerator: String?) = Unit

    suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = null

    /** A planning hint; allocation still requires fresh admission under the engine lease. */
    suspend fun contextForRequest(profileUid: String, modelPath: String, maximum: Int): Int = maximum

    /** Only an explicit user-requested isolated verification may clear an exact quarantine tuple. */
    suspend fun allowVerification(spec: LocalEngineSpec) {
        error("Controlled verification is unavailable in this runtime")
    }

    val latestAllocationObservation: NativeAllocationObservation? get() = null
    val latestBackendFallbackReason: String? get() = null

    fun setLoadProgressObserver(observer: ((GgufNativeProgress) -> Unit)?) = Unit

    suspend fun loadEngine(spec: LocalEngineSpec)
    suspend fun createConversation(config: LocalConversationConfig)
    fun sendMessage(text: String, images: List<ByteArray> = emptyList()): Flow<LocalRuntimeEvent>
    fun cancelActive()
    suspend fun closeConversation()
    suspend fun unloadEngine()

    /**
     * Unloads the engine if it has been idle without active requests for at least [idleThresholdMs].
     * Returns true if unloaded, false otherwise.
     */
    suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean = false

    suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean = false

    fun hasOpenConversation(): Boolean = false

    /** Optional background work must never queue behind or interrupt a generation. */
    suspend fun <T> tryRunExclusive(block: suspend LocalRuntime.() -> T): T? = null

    suspend fun <T> runExclusive(block: suspend LocalRuntime.() -> T): T = block(this)

    fun <T> runExclusiveFlow(block: suspend LocalRuntime.() -> Flow<T>): Flow<T> = runExclusiveFlow(onContended = {}, block = block)

    fun <T> runExclusiveFlow(
        onContended: suspend () -> Unit,
        block: suspend LocalRuntime.() -> Flow<T>
    ): Flow<T> = flow {
        block(this@LocalRuntime).collect { emit(it) }
    }
}

/** Observed runtime identifiers only. Configured GPU layers are not actual offload evidence. */
data class LocalRuntimeExecutionIdentity(
    val workerGenerationId: String? = null,
    val conversationId: String? = null,
    val engineInstanceId: String? = null,
    val driverIdentity: String? = null,
    val actualOffloadLayers: Int? = null
)

/** Ends a native callback loop without replaying any dispatched action. */
internal class LocalToolLimitException(val code: String) : IllegalStateException("Local tool execution stopped: $code")
