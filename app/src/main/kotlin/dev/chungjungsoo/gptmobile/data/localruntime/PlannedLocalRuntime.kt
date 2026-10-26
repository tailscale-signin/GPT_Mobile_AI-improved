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
import kotlinx.coroutines.withContext

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
    override val isNativeOperationBusy: Boolean get() = delegate.isNativeOperationBusy
    private val planner = LocalExecutionPlanner()
    private val admission = LocalWorkloadAdmission()
    private val memoryBackoff = LocalMemoryAdmissionBackoff()
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

    private suspend fun artifactIdentity(spec: LocalEngineSpec): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            RuntimeEvidenceIdentity.artifactDigest(File(spec.modelPath), File(context.noBackupFilesDir, "model-identity-cache"))
        }

    init {
        stateScope.launch {
            delegate.state.collect { actual ->
                synchronized(stateLock) {
                    val blocked = admissionFailure
                    mutableState.value =
                        actual.copy(
                            decision = mutableState.value.decision,
                            admissionReceipt =
                            actual.admissionReceipt
                                ?: mutableState.value.admissionReceipt?.let { receipt ->
                                    if (actual.loadProgress != null ||
                                        actual.allocationObservation != null
                                    ) {
                                        receipt.copy(nativeLoadDispatched = true)
                                    } else {
                                        receipt
                                    }
                                },
                            requestedMtp = effective?.speculativeDecoding,
                            lifecycle = if (blocked != null) "FAILED" else actual.lifecycle,
                            fallbackReason = blocked ?: actual.fallbackReason ?: tuningNotice
                        )
                }
            }
        }
    }

    private fun requireAdmission(
        estimate: LocalMemoryEstimate,
        stage: String,
        nativeLoadAttempted: Boolean,
        accelerator: String? = delegate.loadedEngineSpec()?.accelerator
    ) {
        val rejection =
            admission.evaluate(
                getHardwareState(),
                estimate.availableBytes,
                estimate.requiredBytes,
                estimate.reserveBytes,
                optional = false
            )
        mutableState.value =
            state.value.copy(admissionReceipt = estimate.receipt(stage, rejection == null, delegate.loadedEngineSpec() != null))
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
            val failure =
                if (rejection.reason == LocalAdmissionReason.MEMORY) {
                    estimate.rejection(rejection.message, stage, nativeLoadAttempted)
                } else {
                    IllegalStateException(
                        "${rejection.reason}_ADMISSION_REJECTED · stage=$stage · nativeLoadAttempted=$nativeLoadAttempted. ${rejection.message}"
                    )
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
                mutableState.value =
                    actual.copy(
                        decision = state.value.decision,
                        requestedMtp = effective?.speculativeDecoding,
                        fallbackReason =
                        actual.fallbackReason ?: tuningNotice
                    )
            }
        }
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        val artifact = artifactIdentity(spec)
        val current = settings.getFeatureSettings()
        val tuning = current.localEngineTuning()
        val retryIdentity = "$artifact|$spec|$tuning|${current.localMemoryReserveMb}|${current.localExecutionPolicy}"
        memoryBackoff.rejection(retryIdentity, availableMemory())?.let { throw it }
        delegate.preflightModel(spec.modelPath, spec.accelerator)
        val capabilities = delegate.inspectModel(spec.modelPath)
        val speculative = spec.speculativeDecoding ?: compatibleSpeculativeDecoding(tuning.speculativeDecoding, capabilities)
        val tuned =
            if (spec.tuningLocked) {
                spec
            } else {
                spec.copy(
                    cpuThreads = tuning.cpuThreads,
                    ggufBatchTokens = tuning.ggufBatchTokens,
                    ggufMicroBatchTokens = minOf(tuning.ggufMicroBatchTokens, tuning.ggufBatchTokens),
                    ggufPromptThreads = tuning.ggufPromptThreads,
                    cacheEnabled = tuning.cacheEnabled,
                    speculativeDecoding = speculative,
                    nativeMetricsEnabled = spec.nativeMetricsEnabled || tuning.nativeMetricsEnabled,
                    tuningLocked = true
                )
            }
        tuningNotice =
            if (!spec.tuningLocked && spec.speculativeDecoding == null && tuning.speculativeDecoding == true && tuned.speculativeDecoding == false) {
                "Speculative decoding is unavailable for this package; using ordinary decoding."
            } else {
                null
            }
        var evidenceKey: RuntimeQualificationKey? = null
        val decision =
            run {
                evidence.load()
                val key = RuntimeEvidenceIdentity.key(context, tuned, tuned.samplerIdentity, tuned.workload)
                evidenceKey = key
                val allowed =
                    evidence.runtimeQualifications.value.filter {
                        it.plan.languageBackend in capabilities?.backends.orEmpty() &&
                            (it.plan.speculativeDecoding != true || capabilities?.speculative == true) &&
                            (spec.speculativeDecoding == null || it.plan.speculativeDecoding == spec.speculativeDecoding)
                    }
                planner
                    .select(
                        if (spec.tuningLocked) LocalExecutionPolicy.PROFILE else current.localExecutionPolicy,
                        RuntimeEvidenceIdentity.plan(
                            key
                        ),
                        key,
                        allowed,
                        spec.expectedOutputTokens,
                        delegate.loadedEngineSpec()?.let {
                            state.value.decision
                                ?.selected
                                ?.id
                        }
                    )
            }
        val chosen =
            tuned.copy(
                accelerator = decision?.selected?.languageBackend ?: tuned.accelerator,
                speculativeDecoding =
                spec.speculativeDecoding ?: decision?.selected?.speculativeDecoding ?: tuned.speculativeDecoding,
                admissionReserveBytes =
                current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024
            )
        delegate.inspectModel(chosen)
        var warm = delegate.loadedEngineSpec()
        if (warm != null && warm.engineConfiguration() != chosen.engineConfiguration()) {
            // The holder owns this lease. Release the idle predecessor before
            // measuring headroom; otherwise its resident weights are counted twice.
            delegate.closeConversation()
            delegate.unloadEngine()
            requested = null
            effective = null
            artifactAtLoad = null
            warm = null
        }
        val assetBytes = withContext(Dispatchers.IO) { localModelAssetBytes(File(spec.modelPath)) }
        val estimate =
            if (warm?.engineConfiguration() == chosen.engineConfiguration()) {
                128L * 1024 * 1024
            } else {
                assetBytes + chosen.maxTokens * maxOf(256L * 1024, capabilities?.kvBytesPerToken ?: 0L) + 128L * 1024 * 1024 +
                    (if (chosen.speculativeDecoding == true) assetBytes / 3 else 0) +
                    (if (spec.isVisionEnabled) 256L * 1024 * 1024 else 0)
            }
        val isGguf =
            spec.modelPath.endsWith(".gguf", true) ||
                spec.modelPath.endsWith("manifest.json") &&
                dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
                    .read(File(spec.modelPath))
                    .runtime ==
                dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime.LLAMA_CPP
        val calibrationArtifact =
            withContext(Dispatchers.IO) {
                if (isGguf &&
                    spec.modelPath.endsWith("manifest.json")
                ) {
                    dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(File(spec.modelPath)).let { manifest ->
                        manifest.files
                            .first {
                                it.path ==
                                    manifest.entryFile
                            }.sha256
                    }
                } else {
                    artifact
                }
            }
        val calibrationKey = LocalMemoryCalibrationStore.key(calibrationArtifact, chosen)
        val calibration =
            withContext(Dispatchers.IO) {
                if (isGguf) {
                    LocalMemoryCalibrationStore(
                        File(context.noBackupFilesDir, "local-memory-calibration")
                    ).read(calibrationKey)
                } else {
                    null
                }
            }
        val components =
            if (isGguf &&
                warm?.engineConfiguration() != chosen.engineConfiguration()
            ) {
                ggufMemoryComponents(
                    assetBytes,
                    chosen.maxTokens,
                    capabilities?.kvBytesPerToken ?: 0,
                    chosen.ggufBatchTokens,
                    chosen.ggufMicroBatchTokens,
                    GgufCpuThreads.resolve(
                        chosen.ggufPromptThreads ?: chosen.cpuThreads,
                        Runtime.getRuntime().availableProcessors()
                    ),
                    chosen.accelerator,
                    calibration,
                    geometryKnown =
                    capabilities?.kvBytesPerToken != null
                ).withContextHysteresis(chosen.contextPolicy)
            } else {
                emptyMap()
            }
        val snapshotBytes = availableMemory()
        val planReceipt =
            if (isGguf) {
                MemoryPlanReceipt(
                    planId = calibrationKey,
                    artifactDigest = calibrationArtifact,
                    runtimeRevision = dev.melo.gguf.GgufNative.REVISION,
                    selectedBackend = chosen.accelerator,
                    contextPolicy = chosen.contextPolicy,
                    contextTokens = chosen.maxTokens,
                    requestedContextTokens = spec.maxTokens,
                    kvBytesPerToken = capabilities?.kvBytesPerToken,
                    kvType =
                    "f16".takeIf {
                        capabilities?.kvBytesPerToken !=
                            null
                    },
                    geometryEvidence =
                    if (capabilities?.kvBytesPerToken !=
                        null
                    ) {
                        "validated_uniform_metadata"
                    } else if (calibration?.schemaVersion ==
                        2
                    ) {
                        "exact_shape_native_high_water"
                    } else {
                        "unknown_conservative"
                    },
                    requestedOffloadLayers = chosen.ggufGpuLayers.takeIf { LocalAccelerators.isGpu(chosen.accelerator) } ?: 0,
                    actualOffloadLayers =
                    delegate.latestEngineLoadReceipt?.actualOffloadLayers.takeIf {
                        warm?.engineConfiguration() ==
                            chosen.engineConfiguration()
                    },
                    residentEngineReuse = warm?.engineConfiguration() == chosen.engineConfiguration(),
                    residentEngineInstanceId =
                    delegate.executionIdentity.engineInstanceId.takeIf {
                        warm?.engineConfiguration() ==
                            chosen.engineConfiguration()
                    },
                    mappedModelFileBytes = assetBytes,
                    components = components,
                    calibrationSchemaVersion = calibration?.schemaVersion,
                    uncertaintyBasis = "component_margin_plus_user_reserve;PSS_not_added",
                    availableBytes = snapshotBytes,
                    reserveBytes = chosen.admissionReserveBytes,
                    observedAtMillis = System.currentTimeMillis(),
                    observedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                )
            } else {
                null
            }
        val memoryEstimate =
            LocalMemoryEstimate(
                if (components.isEmpty()) estimate else components.totalMemoryBytes(),
                current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024,
                snapshotBytes,
                chosen.maxTokens,
                if (warm?.engineConfiguration() == chosen.engineConfiguration()) {
                    "warm_incremental_reserve"
                } else if (calibration != null) {
                    "conservative_component_calibrated"
                } else {
                    "conservative_unmeasured"
                },
                components,
                spec.maxTokens,
                calibrationKey,
                systemMemory = LocalWorkloadAdmission.memorySnapshot(context),
                memoryPlan = planReceipt
            )
        if (isGguf && chosen.contextPolicy == "AUTO" && components.isNotEmpty() && memoryEstimate.budget.hasShortfall) {
            val suggestion =
                runCatching {
                    memoryFeasibleContext(
                        chosen.maxTokens - 1,
                        assetBytes,
                        snapshotBytes,
                        chosen.admissionReserveBytes + AUTO_CONTEXT_HYSTERESIS_BYTES,
                        kvBytesPerToken = capabilities?.kvBytesPerToken ?: 256L * 1024,
                        batch = chosen.ggufBatchTokens,
                        microBatch = chosen.ggufMicroBatchTokens,
                        threads =
                        GgufCpuThreads.resolve(
                            chosen.ggufPromptThreads ?: chosen.cpuThreads,
                            Runtime.getRuntime().availableProcessors()
                        ),
                        accelerator = chosen.accelerator,
                        geometryKnown = capabilities?.kvBytesPerToken != null
                    )
                }.getOrNull()
            if (suggestion != null) throw LocalContextReplanRequired(chosen.maxTokens, suggestion, memoryEstimate.receipt("load", false))
        }
        mutableState.value = state.value.copy(decision = decision)
        try {
            requireAdmission(memoryEstimate, stage = "load", nativeLoadAttempted = false, accelerator = chosen.accelerator)
            memoryBackoff.clear()
        } catch (failure: LocalMemoryAdmissionException) {
            memoryBackoff.remember(retryIdentity, memoryEstimate)
            throw failure
        }
        mutableState.value = state.value.copy(lifecycle = "LOADING", decision = decision)
        try {
            delegate.loadEngine(chosen)
            effective = chosen
            artifactAtLoad = artifact
            requested = spec
            preference = current.localExecutionPolicy to tuning
            val actual = delegate.state.value
            val actualDecision =
                decision?.let {
                    if (actual.engineSpec?.accelerator ==
                        it.selected.languageBackend
                    ) {
                        it
                    } else {
                        it.copy(
                            qualification = QualificationStatus.NOT_TESTED,
                            reason = "Runtime used a fallback; its configuration requires separate measurement"
                        )
                    }
                }
            mutableState.value =
                actual.copy(
                    decision = actualDecision?.copy(actualLanguageBackend = actual.engineSpec?.accelerator),
                    lifecycle = "READY",
                    admissionReceipt = state.value.admissionReceipt,
                    requestedMtp = chosen.speculativeDecoding,
                    fallbackReason =
                    actual.fallbackReason ?: tuningNotice
                )
            if (actualDecision != null && evidenceKey != null) {
                evidence.saveRuntimeDecision(
                    RuntimePlanDecisionRecord(
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        System.currentTimeMillis(),
                        evidenceKey,
                        actualDecision,
                        actual.engineSpec?.accelerator
                    )
                )
            }
        } catch (error: Throwable) {
            requested = null
            effective = null
            mutableState.value =
                delegate.state.value.copy(
                    decision = decision,
                    lifecycle = "FAILED",
                    admissionReceipt = state.value.admissionReceipt,
                    fallbackReason = error.message
                )
            throw error
        }
    }

    override suspend fun contextPolicyForRequest(profileUid: String): String =
        (settings.getFeatureSettings().localContextPreferences[profileUid] ?: LocalContextPreference()).validated().policy

    override suspend fun contextForRequest(
        profileUid: String,
        modelPath: String,
        maximum: Int
    ): Int {
        val resizableGguf =
            withContext(Dispatchers.IO) {
                modelPath.endsWith(".gguf", ignoreCase = true) ||
                    modelPath.endsWith("manifest.json") &&
                    dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
                        .read(File(modelPath))
                        .runtime ==
                    dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime.LLAMA_CPP
            }
        if (!resizableGguf) return maximum
        val preferences = settings.getFeatureSettings()
        val preference = (preferences.localContextPreferences[profileUid] ?: LocalContextPreference()).validated()
        val manual = preference.tokens.takeIf { preference.policy == "MANUAL" }
        val warm = delegate.loadedEngineSpec()
        if (warm?.modelPath == modelPath && warm.maxTokens <= maximum && (manual == null || warm.maxTokens == manual)) return warm.maxTokens
        // The caller owns the outer lease: an incompatible idle engine can now be released.
        if (warm != null) {
            delegate.closeConversation()
            delegate.unloadEngine()
            requested = null
            effective = null
        }
        val bytes = withContext(Dispatchers.IO) { localModelAssetBytes(File(modelPath)) }
        val knownGeometry = delegate.inspectModel(modelPath)?.kvBytesPerToken
        val geometry = knownGeometry ?: 256L * 1024
        val accelerator =
            settings
                .fetchPlatformV2s()
                .firstOrNull { it.uid == profileUid }
                ?.accelerator
                ?.let(LocalAccelerators::normalize)
                ?: LocalAccelerators.CPU
        val tuning = preferences.localEngineTuning()
        return memoryFeasibleContext(
            maximum,
            bytes,
            availableMemory(),
            preferences.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024 +
                if (manual ==
                    null
                ) {
                    AUTO_CONTEXT_HYSTERESIS_BYTES
                } else {
                    0
                },
            manual,
            geometry,
            tuning.ggufBatchTokens,
            tuning.ggufMicroBatchTokens,
            GgufCpuThreads.resolve(
                tuning.ggufPromptThreads ?: tuning.cpuThreads,
                Runtime.getRuntime().availableProcessors()
            ),
            accelerator,
            geometryKnown =
            knownGeometry != null
        )
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean {
        val current = settings.getFeatureSettings()
        return requested == spec &&
            artifactAtLoad == artifactIdentity(spec) &&
            (spec.tuningLocked || preference == current.localExecutionPolicy to current.localEngineTuning()) &&
            effective?.let { delegate.isEngineLoaded(it) } == true
    }

    override suspend fun createConversation(config: LocalConversationConfig) =
        delegate.createConversation(
            config.copy(
                speculativeDecoding =
                config.speculativeDecoding ?: effective?.speculativeDecoding
            )
        )

    override fun sendMessage(
        text: String,
        images: List<ByteArray>
    ): Flow<LocalRuntimeEvent> = sendMessageWithBudget(text, images, 0)

    override fun sendMessageWithBudget(
        text: String,
        images: List<ByteArray>,
        safetyTokens: Int
    ): Flow<LocalRuntimeEvent> =
        flow {
            val current = settings.getFeatureSettings()
            val loaded = delegate.loadedEngineSpec()
            val estimate =
                LocalMemoryEstimate(
                    requiredBytes = 64L * 1024 * 1024,
                    reserveBytes = current.localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024,
                    availableBytes = availableMemory(),
                    contextTokens = loaded?.maxTokens ?: 0,
                    method = "generation_incremental_reserve",
                    systemMemory = LocalWorkloadAdmission.memorySnapshot(context)
                )
            requireAdmission(estimate, stage = "generate", nativeLoadAttempted = loaded != null)
            mutableState.value = state.value.copy(decision = state.value.decision?.copy(requestCompleted = null))
            delegate.sendMessageWithBudget(text, images, safetyTokens).collect { event ->
                mutableState.value =
                    state.value.copy(
                        lifecycle = delegate.state.value.lifecycle,
                        decision =
                        state.value.decision?.let { decision ->
                            decision.copy(
                                actualLanguageBackend =
                                delegate.state.value.engineSpec
                                    ?.accelerator,
                                requestCompleted =
                                when (event) {
                                    LocalRuntimeEvent.Done -> true
                                    is LocalRuntimeEvent.Error -> false
                                    else -> decision.requestCompleted
                                }
                            )
                        }
                    )
                emit(
                    if (event is LocalRuntimeEvent.Metrics) {
                        event.copy(
                            metrics =
                            event.metrics.copy(
                                planId =
                                state.value.decision
                                    ?.selected
                                    ?.id
                            )
                        )
                    } else {
                        event
                    }
                )
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
internal fun compatibleSpeculativeDecoding(
    requested: Boolean?,
    capabilities: LocalModelCapabilities?
): Boolean? = if (requested == true && capabilities?.speculative != true) false else requested
