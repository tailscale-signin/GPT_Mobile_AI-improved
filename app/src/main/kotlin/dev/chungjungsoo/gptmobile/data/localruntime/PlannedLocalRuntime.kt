package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Plan changes only under the outer engine-holder lease and before opening a conversation. */
class PlannedLocalRuntime(
    private val context: Context,
    private val delegate: LocalRuntime,
    private val settings: SettingRepository,
    private val evidence: BenchmarkStore,
    private val availableMemory: () -> Long? = { LocalWorkloadAdmission.availableMemory(context) },
    stateScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : LocalRuntime by delegate {
    override val ownsEngineSwitching = true
    private val planner = LocalExecutionPlanner()
    private val admission = LocalWorkloadAdmission()
    private val mutableState = MutableStateFlow(delegate.state.value)
    override val state = mutableState.asStateFlow()
    private var requested: LocalEngineSpec? = null
    private var effective: LocalEngineSpec? = null
    private var preference: Pair<LocalExecutionPolicy, LocalEngineTuning>? = null
    private var tuningNotice: String? = null
    private var artifactAtLoad: String? = null

    private val stateLock = Any()

    @Volatile
    private var admissionFailure: String? = null

    private suspend fun artifactIdentity(spec: LocalEngineSpec): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        RuntimeEvidenceIdentity.artifactDigest(File(spec.modelPath), File(context.noBackupFilesDir, "model-identity-cache"))
    }
    init {
        stateScope.launch {
            delegate.state.collect { actual ->
                synchronized(stateLock) {
                    val blocked = admissionFailure
                    mutableState.value = actual.copy(
                        decision = mutableState.value.decision,
                        requestedMtp = effective?.speculativeDecoding,
                        lifecycle = if (blocked != null) "FAILED" else actual.lifecycle,
                        fallbackReason = blocked ?: actual.fallbackReason ?: tuningNotice
                    )
                }
            }
        }
    }

    private fun requireAdmission(estimate: LocalMemoryEstimate, stage: String, nativeLoadAttempted: Boolean, accelerator: String? = delegate.loadedEngineSpec()?.accelerator) {
        val rejection = admission.evaluate(getHardwareState(), estimate.availableBytes, estimate.requiredBytes, estimate.reserveBytes, optional = false)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "LocalModel",
            "LOCAL_MEMORY_PREFLIGHT · stage=$stage · status=${if (rejection == null) "ADMITTED" else "REJECTED"}" +
                " · reason=${rejection?.reason ?: "NONE"} · requiredBytes=${estimate.requiredBytes}" +
                " · reserveBytes=${estimate.reserveBytes} · configuredReserveMb=${estimate.reserveBytes / (1024L * 1024)}" +
                " · accelerator=${accelerator ?: "unknown"} · availableBytes=${estimate.availableBytes ?: "unknown"}" +
                " · totalBudgetBytes=${estimate.budget.totalBytes} · shortfallBytes=${estimate.budget.shortfallBytes ?: "unknown"}" +
                " · contextTokens=${estimate.contextTokens} · estimate=${estimate.method} · nativeLoadAttempted=$nativeLoadAttempted"
        )
        if (rejection != null) {
            val failure = if (rejection.reason == LocalAdmissionReason.MEMORY) {
                estimate.rejection(rejection.message, stage, nativeLoadAttempted)
            } else {
                IllegalStateException("${rejection.reason}_ADMISSION_REJECTED · stage=$stage · nativeLoadAttempted=$nativeLoadAttempted. ${rejection.message}")
            }
            synchronized(stateLock) {
                admissionFailure = failure.message
                mutableState.value = state.value.copy(lifecycle = "FAILED", fallbackReason = failure.message)
            }
            throw failure
        }
        synchronized(stateLock) {
            if (admissionFailure != null) {
                admissionFailure = null
                val actual = delegate.state.value
                mutableState.value = actual.copy(decision = state.value.decision, requestedMtp = effective?.speculativeDecoding, fallbackReason = actual.fallbackReason ?: tuningNotice)
            }
        }
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        delegate.preflightModel(spec.modelPath, spec.accelerator)
        val artifact = artifactIdentity(spec)
        val current = settings.getFeatureSettings()
        val tuning = current.localEngineTuning()
        val capabilities = delegate.inspectModel(spec.modelPath)
        val speculative = compatibleSpeculativeDecoding(tuning.speculativeDecoding, capabilities)
        val tuned = if (spec.tuningLocked) spec else spec.copy(cpuThreads = tuning.cpuThreads, cacheEnabled = tuning.cacheEnabled, speculativeDecoding = speculative, nativeMetricsEnabled = tuning.nativeMetricsEnabled, tuningLocked = true)
        tuningNotice = if (!spec.tuningLocked && tuning.speculativeDecoding == true && tuned.speculativeDecoding == false) {
            "Speculative decoding is unavailable for this package; using ordinary decoding."
        } else {
            null
        }
        var evidenceKey: RuntimeQualificationKey? = null
        val decision = run {
            evidence.load()
            val key = RuntimeEvidenceIdentity.key(context, tuned, tuned.samplerIdentity, tuned.workload)
            evidenceKey = key
            val allowed = evidence.runtimeQualifications.value.filter {
                it.plan.languageBackend in capabilities?.backends.orEmpty() &&
                    (it.plan.speculativeDecoding != true || capabilities?.speculative == true)
            }
            planner.select(if (spec.tuningLocked) LocalExecutionPolicy.PROFILE else current.localExecutionPolicy, RuntimeEvidenceIdentity.plan(key), key, allowed, spec.expectedOutputTokens, delegate.loadedEngineSpec()?.let { state.value.decision?.selected?.id })
        }
        val chosen = tuned.copy(accelerator = decision?.selected?.languageBackend ?: tuned.accelerator, speculativeDecoding = decision?.selected?.speculativeDecoding ?: tuned.speculativeDecoding)
        val warm = delegate.loadedEngineSpec()
        val assetBytes = localModelAssetBytes(File(spec.modelPath))
        val estimate = if (warm?.engineConfiguration() == chosen.engineConfiguration()) {
            128L * 1024 * 1024
        } else {
            assetBytes + chosen.maxTokens * 256L * 1024 + 128L * 1024 * 1024 + (if (chosen.speculativeDecoding == true) assetBytes / 3 else 0) + (if (spec.isVisionEnabled) 256L * 1024 * 1024 else 0)
        }
        val memoryEstimate = LocalMemoryEstimate(estimate, current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024, availableMemory(), chosen.maxTokens, if (warm?.engineConfiguration() == chosen.engineConfiguration()) "warm_incremental_reserve" else "conservative_unmeasured")
        mutableState.value = state.value.copy(decision = decision)
        requireAdmission(memoryEstimate, stage = "load", nativeLoadAttempted = false, accelerator = chosen.accelerator)
        mutableState.value = state.value.copy(lifecycle = "LOADING", decision = decision)
        try {
            delegate.loadEngine(chosen)
            effective = chosen
            artifactAtLoad = artifact
            requested = spec
            preference = current.localExecutionPolicy to tuning
            val actual = delegate.state.value
            val actualDecision = decision?.let {
                if (actual.engineSpec?.accelerator == it.selected.languageBackend) it else it.copy(qualification = QualificationStatus.NOT_TESTED, reason = "Runtime used a fallback; its configuration requires separate measurement")
            }
            mutableState.value = actual.copy(decision = actualDecision, lifecycle = "READY", requestedMtp = chosen.speculativeDecoding, fallbackReason = actual.fallbackReason ?: tuningNotice)
            if (actualDecision != null && evidenceKey != null) {
                evidence.saveRuntimeDecision(RuntimePlanDecisionRecord(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), evidenceKey, actualDecision, actual.engineSpec?.accelerator))
            }
        } catch (error: Throwable) {
            requested = null
            effective = null
            mutableState.value = delegate.state.value.copy(decision = decision, lifecycle = "FAILED", fallbackReason = error.message)
            throw error
        }
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean {
        val current = settings.getFeatureSettings()
        return requested == spec && artifactAtLoad == artifactIdentity(spec) && (spec.tuningLocked || preference == current.localExecutionPolicy to current.localEngineTuning()) && effective?.let { delegate.isEngineLoaded(it) } == true
    }

    override suspend fun createConversation(config: LocalConversationConfig) = delegate.createConversation(config.copy(speculativeDecoding = config.speculativeDecoding ?: effective?.speculativeDecoding))

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = flow {
        val current = settings.getFeatureSettings()
        val loaded = delegate.loadedEngineSpec()
        val estimate = LocalMemoryEstimate(
            requiredBytes = 64L * 1024 * 1024,
            reserveBytes = current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024,
            availableBytes = availableMemory(),
            contextTokens = loaded?.maxTokens ?: 0,
            method = "generation_incremental_reserve"
        )
        requireAdmission(estimate, stage = "generate", nativeLoadAttempted = loaded != null)
        delegate.sendMessage(text, images).collect { event ->
            mutableState.value = state.value.copy(lifecycle = delegate.state.value.lifecycle)
            emit(if (event is LocalRuntimeEvent.Metrics) event.copy(metrics = event.metrics.copy(planId = state.value.decision?.selected?.id)) else event)
        }
    }

    override fun cancelActive() {
        mutableState.value = state.value.copy(lifecycle = "CANCELLING")
        delegate.cancelActive()
    }

    override suspend fun unloadEngine() {
        delegate.unloadEngine()
        requested = null
        effective = null
        tuningNotice = null
        artifactAtLoad = null
        synchronized(stateLock) {
            admissionFailure = null
            mutableState.value = delegate.state.value
        }
    }

    override fun loadedEngineSpec(): LocalEngineSpec? = delegate.loadedEngineSpec()
}

/** A global speed preference must not prevent ordinary decoding on another package. */
internal fun compatibleSpeculativeDecoding(requested: Boolean?, capabilities: LocalModelCapabilities?): Boolean? =
    if (requested == true && capabilities?.speculative != true) false else requested
