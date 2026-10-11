package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Plan changes only under the outer engine-holder lease and before opening a conversation. */
class PlannedLocalRuntime(
    private val context: Context,
    private val delegate: LocalRuntime,
    private val settings: SettingRepository,
    private val evidence: BenchmarkStore
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
    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            delegate.state.collect { actual -> mutableState.value = actual.copy(decision = mutableState.value.decision, requestedMtp = effective?.speculativeDecoding, fallbackReason = actual.fallbackReason ?: tuningNotice) }
        }
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        val current = settings.getFeatureSettings()
        val tuning = current.localEngineTuning()
        val isLiteRt = !spec.modelPath.endsWith("manifest.json")
        val capabilities = if (isLiteRt) delegate.inspectModel(spec.modelPath) else null
        val speculative = if (isLiteRt) compatibleSpeculativeDecoding(tuning.speculativeDecoding, capabilities) else tuning.speculativeDecoding
        val tuned = if (spec.tuningLocked) spec else spec.copy(cpuThreads = tuning.cpuThreads, cacheEnabled = tuning.cacheEnabled, speculativeDecoding = speculative, nativeMetricsEnabled = tuning.nativeMetricsEnabled, tuningLocked = true)
        tuningNotice = if (!spec.tuningLocked && tuning.speculativeDecoding == true && tuned.speculativeDecoding == false) {
            "Speculative decoding is unavailable for this package; using ordinary decoding."
        } else {
            null
        }
        var evidenceKey: RuntimeQualificationKey? = null
        val decision = if (isLiteRt) {
            evidence.load()
            val key = RuntimeEvidenceIdentity.key(context, tuned, tuned.samplerIdentity, tuned.workload)
            evidenceKey = key
            val allowed = evidence.runtimeQualifications.value.filter {
                it.plan.languageBackend in capabilities?.backends.orEmpty() &&
                    (it.plan.speculativeDecoding != true || capabilities?.speculative == true)
            }
            planner.select(if (spec.tuningLocked) LocalExecutionPolicy.PROFILE else current.localExecutionPolicy, RuntimeEvidenceIdentity.plan(key), key, allowed, spec.expectedOutputTokens, delegate.loadedEngineSpec()?.let { state.value.decision?.selected?.id })
        } else {
            null
        }
        val chosen = tuned.copy(accelerator = decision?.selected?.languageBackend ?: tuned.accelerator, speculativeDecoding = decision?.selected?.speculativeDecoding ?: tuned.speculativeDecoding)
        val warm = delegate.loadedEngineSpec()
        val estimate = if (warm?.engineConfiguration() == chosen.engineConfiguration()) {
            128L * 1024 * 1024
        } else {
            File(spec.modelPath).length() + spec.maxTokens * 256L * 1024 + (if (chosen.speculativeDecoding == true) File(spec.modelPath).length() / 3 else 0) + (if (spec.isVisionEnabled) 256L * 1024 * 1024 else 0)
        }
        admission.rejection(getHardwareState(), LocalWorkloadAdmission.availableMemory(context), estimate, current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024, optional = false)?.let { error(it) }
        mutableState.value = state.value.copy(lifecycle = "LOADING", decision = decision)
        try {
            delegate.loadEngine(chosen)
            effective = chosen
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
        return requested == spec && (spec.tuningLocked || preference == current.localExecutionPolicy to current.localEngineTuning()) && effective?.let { delegate.isEngineLoaded(it) } == true
    }

    override suspend fun createConversation(config: LocalConversationConfig) = delegate.createConversation(config.copy(speculativeDecoding = config.speculativeDecoding ?: effective?.speculativeDecoding))

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = flow {
        val current = settings.getFeatureSettings()
        admission.rejection(getHardwareState(), LocalWorkloadAdmission.availableMemory(context), 64L * 1024 * 1024, current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024, optional = false)?.let { error(it) }
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
        mutableState.value = delegate.state.value
    }

    override fun loadedEngineSpec(): LocalEngineSpec? = delegate.loadedEngineSpec()
}

/** A global speed preference must not prevent ordinary decoding on another package. */
internal fun compatibleSpeculativeDecoding(requested: Boolean?, capabilities: LocalModelCapabilities?): Boolean? =
    if (requested == true && capabilities?.speculative != true) false else requested
