package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest
import dev.melo.gguf.GgufNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pinned CPU/Vulkan runtime, QAIRT-independent; the existing holder/process owns every lease. */
internal class GgufRuntimeAdapter(
    private val context: Context
) : LocalRuntime {
    init {
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory
            .initialize(context)
        dev.chungjungsoo.gptmobile.data.localmodel.GgufImportAdapter
            .configureMetadataCache(File(context.noBackupFilesDir, "gguf-metadata"))
        dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.configureReceiptDirectory(
            File(context.noBackupFilesDir, "model-verification")
        )
    }

    private val journal = NativeExitEvidence.journal(context, File(context.noBackupFilesDir, "gguf-native"))

    @Volatile private var handle = 0L
    private var loaded: LocalEngineSpec? = null
    private var requested: LocalEngineSpec? = null
    private var devicePlanKey: String? = null

    @Volatile private var workerContext: RuntimeWorkerContext? = null

    override fun getHardwareState() = DeviceHardwareGovernor.inspectHardwareState(context)

    private var effectiveThreads = 4
    private var effectivePromptThreads = 4
    private var executionReceipt = ""
    private var backendFallback: String? = null
    override val latestBackendFallbackReason get() = backendFallback
    private val devicePlans = GgufDevicePlans(File(context.noBackupFilesDir, "gguf-device-plans"))

    @Volatile private var healthy = false
    private val nativeRunning = AtomicBoolean(false)

    @Volatile private var cleanupBlocked: String? = null

    @Volatile private var engineInstanceId: String? = null

    @Volatile private var engineLoad: EngineLoadReceipt? = null
    private val execution =
        java.util.concurrent.atomic
            .AtomicReference<RuntimeExecutionReceipt?>(null)

    @Volatile private var cleanupReceipt: RuntimeCleanupReceipt? = null
    override val latestEngineLoadReceipt get() = engineLoad
    override val latestExecutionReceipt get() = execution.get()
    override val latestCleanupReceipt get() = cleanupReceipt
    override val isNativeOperationBusy get() = nativeRunning.get() || cleanupBlocked != null
    override val isBusy get() = isNativeOperationBusy
    override val executionIdentity get() =
        LocalRuntimeExecutionIdentity(
            engineInstanceId = engineInstanceId,
            driverIdentity = engineLoad?.driverIdentity,
            actualOffloadLayers = engineLoad?.actualOffloadLayers
        )

    private fun requireSafeAdmission() {
        check(cleanupBlocked == null) { "NATIVE_SESSION_QUARANTINED: $cleanupBlocked" }
    }

    @Volatile private var activeOperation = 0L

    @Volatile private var activeOperationHandle = 0L
    private var tuple: String? = null
    private var modelFallbackKey: String? = null
    private var modelReceipt: String = ""
    private var calibrationPlanKey: String? = null
    private var configuration: LocalConversationConfig? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val negativeCache = GgufNegativeCache(File(context.noBackupFilesDir, "gguf-negative-cache"))

    private val nativeOperations =
        java.util.concurrent.atomic
            .AtomicLong()

    @Volatile private var loadObserver: ((GgufNativeProgress) -> Unit)? = null

    @Volatile private var allocation: NativeAllocationObservation? = null
    override val latestAllocationObservation get() = allocation

    override fun setLoadProgressObserver(observer: ((GgufNativeProgress) -> Unit)?) {
        loadObserver = observer
    }

    private fun incident(
        kind: String,
        fields: Map<String, String>
    ) {
        val operation = activeOperation.takeIf { it > 0 && kind !in setOf("INSPECTION_FINISHED", "LOAD_STARTED") }
        val correlated =
            fields +
                buildMap {
                    workerContext?.let {
                        put("workerGenerationId", it.workerGenerationId)
                        put("runtimeRequestId", it.runtimeRequestId)
                        it.physicalAttemptId?.let { id ->
                            put("physicalAttemptId", id)
                        }
                    }
                    operation?.let { put("nativeOperationId", it.toString()) }
                }
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory
            .record(kind, correlated)
    }

    private fun metadata(
        model: File,
        manifest: ModelArtifactManifest
    ): Map<String, String> =
        dev.chungjungsoo.gptmobile.data.localmodel.GgufImportAdapter
            .inspect(model)

    override fun loadedEngineSpec() = loaded

    override suspend fun isEngineLoaded(spec: LocalEngineSpec) =
        healthy &&
            handle != 0L &&
            (requested?.engineConfiguration() == spec.engineConfiguration() || loaded?.engineConfiguration() == spec.engineConfiguration())

    override fun hasOpenConversation() = configuration != null

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities =
        withContext(Dispatchers.IO) {
            workerContext = kotlinx.coroutines.currentCoroutineContext()[RuntimeWorkerContext]
            val inspectionStarted = SystemClock.elapsedRealtime()
            val file = File(modelPath)
            val manifest = ArtifactManifestStore.read(file)
            manifest.validate(requireNotNull(file.parentFile))
            check(manifest.runtime == ArtifactRuntime.LLAMA_CPP) {
                "GenieX artifacts require the separate GenieX preview APK; this is the standard llama.cpp runtime."
            }
            val model = ModelArtifactManifest.safeFile(requireNotNull(file.parentFile), manifest.entryFile)
            val metadata = metadata(model, manifest)
            val templateName =
                dev.chungjungsoo.gptmobile.data.localmodel.GgufChatTemplateIdentity
                    .describe(metadata["tokenizer.chat_template"])
            check(metadata["tokenizer.chat_template"].isNullOrBlank().not()) {
                "GGUF chat template '$templateName' is missing; import a chat-tuned GGUF export."
            }
            check(metadata["general.architecture"] == manifest.architecture) { "GGUF metadata architecture does not match its manifest." }
            AppLogRecorder.record(
                "LocalModel",
                "GGUF_TEMPLATE_METADATA · model=${file.name} · template=$templateName · embeddedBytes=${metadata["tokenizer.chat_template"]?.length ?: 0} · nativeProbe=pending"
            )
            check(!manifest.vision && manifest.projectorFile == null) { "This GGUF runtime supports self-contained text models only" }
            check("cpu" in manifest.supportedBackends) { "This artifact does not permit the packaged CPU backend" }
            val backends =
                buildSet {
                    add("cpu")
                    add("auto")
                    if (RuntimeCapabilityRegistry
                            .gguf(
                                android.os.Build.SUPPORTED_ABIS
                                    ?.toList()
                                    .orEmpty()
                            ).backends
                            .contains("opencl")
                    ) {
                        add("opencl")
                    }
                    // A GGUF model is not compiled for a specific accelerator. Vulkan is an
                    // engine capability and may be used for any compatible tensor layers.
                    if (RuntimeCapabilityRegistry
                            .gguf(
                                android.os.Build.SUPPORTED_ABIS
                                    ?.toList()
                                    .orEmpty()
                            ).backends
                            .contains("gpu")
                    ) {
                        add("gpu")
                    }
                }
            AppLogRecorder.record("LocalModel", "GGUF_INSPECTION · durationMs=${SystemClock.elapsedRealtime() - inspectionStarted}")
            incident("INSPECTION_FINISHED", mapOf("durationMs" to (SystemClock.elapsedRealtime() - inspectionStarted).toString()))
            LocalModelCapabilities(
                minOf(manifest.contextTokens, 32768),
                true,
                backends,
                false,
                false,
                false,
                false,
                kvBytesPerToken = uniformGgufKvBytesPerToken(metadata)
            )
        }

    override suspend fun loadEngine(spec: LocalEngineSpec) =
        withContext(Dispatchers.IO) {
            requireSafeAdmission()
            workerContext = kotlinx.coroutines.currentCoroutineContext()[RuntimeWorkerContext]
            RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.LLAMA_CPP)
            val incidents = File(context.noBackupFilesDir, "local-runtime-incidents").also { check(it.isDirectory || it.mkdirs()) }
            if (isEngineLoaded(spec)) return@withContext
            backendFallback = null
            val file = File(spec.modelPath)
            val manifest = ArtifactManifestStore.read(file)
            val model = ModelArtifactManifest.safeFile(requireNotNull(file.parentFile), manifest.entryFile)
            val modelHash = manifest.files.first { it.path == manifest.entryFile }.sha256
            val requestedBackend = LocalAccelerators.normalize(spec.accelerator)
            val baseKey = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}"

            // Qualification lookup must never initialize a vendor driver. Firmware,
            // artifact, runtime, app and allocation shape invalidate saved samples.
            fun key(backend: String) =
                dev.chungjungsoo.gptmobile.data.research.researchHash(
                    "v2|$baseKey|$backend|${dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT}|${spec.maxTokens}|${spec.ggufBatchTokens}|${spec.ggufMicroBatchTokens}"
                )
            val cpuSamples = devicePlans.read(key("cpu"))
            val automatic =
                if (requestedBackend == LocalAccelerators.AUTO) {
                    listOf("gpu", "opencl")
                        .filter {
                            it in
                                RuntimeCapabilityRegistry
                                    .gguf(
                                        android.os.Build.SUPPORTED_ABIS
                                            ?.toList()
                                            .orEmpty()
                                    ).backends
                        }.filterNot {
                            journal.isQuarantined(
                                "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|$it|${spec.maxTokens}"
                            )
                        }.firstNotNullOfOrNull { GgufDevicePlans.gpu(devicePlans.read(key(it))) }
                } else {
                    null
                }
            val selectedSpec =
                if (requestedBackend ==
                    LocalAccelerators.AUTO
                ) {
                    spec.copy(accelerator = automatic?.backend ?: "cpu", ggufGpuLayers = automatic?.layers ?: 0)
                } else {
                    spec
                }
            val fallbackKey = GgufNegativeCache.identity(modelHash, selectedSpec)
            val planKey = key(selectedSpec.accelerator)
            val samples = devicePlans.read(planKey)
            effectiveThreads =
                GgufCpuThreads.resolve(
                    spec.cpuThreads,
                    Runtime.getRuntime().availableProcessors(),
                    GgufDevicePlans.threads(cpuSamples, false)
                )
            effectivePromptThreads =
                GgufCpuThreads.resolve(
                    spec.ggufPromptThreads ?: spec.cpuThreads,
                    Runtime.getRuntime().availableProcessors(),
                    GgufDevicePlans.threads(cpuSamples, true)
                )
            if (LocalAccelerators.isGpu(selectedSpec.accelerator) && selectedSpec.ggufGpuLayers > 8) {
                check(
                    GgufDevicePlans.hasProductiveGpuGeneration(
                        samples,
                        selectedSpec.accelerator,
                        minOf(selectedSpec.ggufGpuLayers / 2, 8)
                    )
                ) { "Qualify eight GPU layers with a real generation before increasing offload" }
            }
            val rememberedFailure =
                if (LocalAccelerators.isGpu(
                        selectedSpec.accelerator
                    )
                ) {
                    negativeCache.beforeAttempt(fallbackKey, LocalWorkloadAdmission.availableMemory(context))
                } else {
                    null
                }
            val effectiveSpec =
                if (rememberedFailure != null) {
                    backendFallback =
                        "Running on CPU · ${selectedSpec.accelerator} has a ${rememberedFailure.kind.name.lowercase()} verdict. ${rememberedFailure.reason}"
                    selectedSpec.copy(accelerator = LocalAccelerators.CPU, ggufGpuLayers = 0)
                } else {
                    selectedSpec
                }
            if (isEngineLoaded(effectiveSpec)) return@withContext
            inspectModel(effectiveSpec.modelPath).validate(effectiveSpec)
            val preflightMetadata = metadata(model, manifest)
            val templateName =
                dev.chungjungsoo.gptmobile.data.localmodel.GgufChatTemplateIdentity.describe(
                    preflightMetadata["tokenizer.chat_template"]
                )
            val template =
                preflightMetadata["tokenizer.chat_template"]
                    ?: error("GGUF chat template '$templateName' is missing; no model memory was allocated.")
            val accelerator = LocalAccelerators.normalize(effectiveSpec.accelerator)
            check(
                accelerator in
                    RuntimeCapabilityRegistry
                        .gguf(
                            android.os.Build.SUPPORTED_ABIS
                                ?.toList()
                                .orEmpty()
                        ).backends
            ) {
                "Requested GGUF backend is not packaged in this build"
            }
            if (accelerator ==
                LocalAccelerators.GPU
            ) {
                check(
                    RuntimeCapabilityRegistry
                        .gguf(
                            android.os.Build.SUPPORTED_ABIS
                                ?.toList()
                                .orEmpty()
                        ).backends
                        .contains("gpu")
                ) {
                    "This build does not include GGUF Vulkan GPU offload; rebuild with ggufVulkan=true"
                }
            }
            AppLogRecorder.record(
                "LocalModel",
                "GGUF_RUNTIME_COMPATIBILITY · importRevision=${manifest.runtimeVersion} · executionRevision=${GgufNative.REVISION} · qualification=pending_native_load"
            )
            val identity = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|$accelerator|${effectiveSpec.maxTokens}"
            unloadEngine()
            devicePlanKey = key(effectiveSpec.accelerator)
            incident(
                "LOAD_STARTED",
                mapOf(
                    "modelIdentity" to identity,
                    "requestedBackend" to spec.accelerator,
                    "selectedBackend" to accelerator,
                    "layers" to effectiveSpec.ggufGpuLayers.toString()
                )
            )
            if (journal.isQuarantined(
                    identity
                )
            ) {
                negativeCache.remember(
                    fallbackKey,
                    GgufNegativeKind.NATIVE_CRASH,
                    "The prior exact native operation has no confirmed clean exit"
                )
            }
            journal.before(identity, "LIBRARY_LOADING")
            val operation = nativeOperations.incrementAndGet()
            activeOperation = operation
            loadBackend = accelerator
            loadStage("LIBRARY_LOADING")
            try {
                cancellableNative(null, operation) {
                    GgufNative.configureDiagnosticWorker(workerContext?.workerGenerationId.orEmpty().toByteArray())
                    GgufNative.configureDiagnosticFile(File(incidents, "native-error.log").absolutePath.toByteArray())
                    loadStage("VALIDATING_TEMPLATE")
                    try {
                        GgufNative.validateChatTemplate(template.toByteArray(Charsets.UTF_8))
                    } catch (failure: IllegalStateException) {
                        if (failure is CancellationException) throw failure
                        AppLogRecorder.record(
                            "LocalModel",
                            "GGUF_TEMPLATE_REJECTED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=metadata_only_preflight · modelLoadStarted=false · reason=${failure.message.orEmpty().take(
                                180
                            )}",
                            "E"
                        )
                        throw IllegalStateException(
                            "GGUF chat template '$templateName' could not be parsed as Jinja. No model weights or context were loaded. ${failure.message.orEmpty().take(
                                180
                            )}",
                            failure
                        )
                    }
                    AppLogRecorder.record(
                        "LocalModel",
                        "GGUF_TEMPLATE_PARSED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=syntax_only_preflight · modelLoadStarted=false"
                    )
                    loadNative(model, effectiveSpec, accelerator, templateName).also { handle = it }
                }
                healthy = true
                modelReceipt =
                    "file=${model.name};architecture=${manifest.architecture ?: "unknown"};quantization=${manifest.weightPrecision ?: "unknown"};templateHash=${dev.chungjungsoo.gptmobile.data.research.researchHash(
                        template
                    )};modelBytes=${model.length()}"
                loaded = effectiveSpec
                requested = spec
                tuple = identity
                modelFallbackKey = fallbackKey
                if (rememberedFailure == null) negativeCache.success(fallbackKey)
            } catch (failure: Throwable) {
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_STAGE_FAILED · nativeOperationId=$activeOperation · workerGenerationId=${workerContext?.workerGenerationId ?: "unknown"} · stage=${lastLoadStage.orEmpty()} · durationMs=${SystemClock.elapsedRealtime() - stageStartedAtMs} · category=${failure.ggufFailureCategory().name}",
                    "W"
                )
                incident(
                    "LOAD_FAILED",
                    mapOf(
                        "modelIdentity" to identity,
                        "requestedBackend" to spec.accelerator,
                        "category" to failure.ggufFailureCategory().name,
                        "error" to failure.message.orEmpty()
                    )
                )
                withContext(NonCancellable) { unloadEngine() }
                if (failure !is CancellationException &&
                    LocalAccelerators.isGpu(accelerator) &&
                    failure.isRecoverableGgufGpuLoadFailure()
                ) {
                    GgufNegativeCache.category(failure)?.let {
                        negativeCache.remember(fallbackKey, it, failure.message.orEmpty(), LocalWorkloadAdmission.availableMemory(context))
                    }
                    backendFallback = "Running on CPU · $accelerator initialization failed: ${failure.message.orEmpty().take(500)}"
                    AppLogRecorder.record(
                        "LocalModel",
                        "GGUF_VULKAN_LOAD_FALLBACK · model=${file.name} · accelerator=gpu->cpu · visibleOutput=0 · reason=${failure.message.orEmpty().take(
                            180
                        )}",
                        "W"
                    )
                    val cpuSpec = effectiveSpec.copy(accelerator = LocalAccelerators.CPU)
                    devicePlanKey = key("cpu")
                    val cpuIdentity = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|${LocalAccelerators.CPU}|${cpuSpec.maxTokens}"
                    try {
                        // The failed GPU call returned and was fully unloaded. Finish its
                        // journal before starting CPU; otherwise before() interprets our
                        // own completed attempt as a native process interruption.
                        journal.finished()
                        journal.before(cpuIdentity, "LOAD")
                        loadBackend = "cpu"
                        cancellableNative(null) {
                            loadNative(model, cpuSpec, LocalAccelerators.CPU, templateName).also { handle = it }
                        }
                        healthy = true
                        modelReceipt =
                            "file=${model.name};architecture=${manifest.architecture ?: "unknown"};quantization=${manifest.weightPrecision ?: "unknown"};templateHash=${dev.chungjungsoo.gptmobile.data.research.researchHash(
                                template
                            )};modelBytes=${model.length()}"
                        incident(
                            "CPU_FALLBACK",
                            mapOf(
                                "requestedBackend" to spec.accelerator,
                                "actualBackend" to "cpu",
                                "reason" to failure.message.orEmpty()
                            )
                        )
                        loaded = cpuSpec
                        requested = spec
                        tuple = cpuIdentity
                        modelFallbackKey = fallbackKey
                        return@withContext
                    } catch (cpuFailure: Throwable) {
                        withContext(NonCancellable) { unloadEngine() }
                        cpuFailure.addSuppressed(failure)
                        throw cpuFailure
                    }
                }
                throw failure
            } finally {
                lastLoadStage = null
                journal.finished()
            }
        }

    private fun loadNative(
        model: File,
        spec: LocalEngineSpec,
        accelerator: String,
        templateName: String
    ): Long =
        try {
            val manifest = ArtifactManifestStore.read(File(spec.modelPath))
            val kvGeometry = uniformGgufKvBytesPerToken(metadata(model, manifest))
            val kvBytes = kvGeometry ?: 256L * 1024
            val digest = manifest.files.first { it.path == manifest.entryFile }.sha256
            val planKey =
                LocalMemoryCalibrationStore.key(
                    digest,
                    spec.copy(accelerator = accelerator, cpuThreads = effectiveThreads, ggufPromptThreads = effectivePromptThreads)
                )
            val calibrations = LocalMemoryCalibrationStore(File(context.noBackupFilesDir, "local-memory-calibration"))
            val observed = calibrations.read(planKey)
            val components =
                ggufMemoryComponents(
                    model.length(),
                    spec.maxTokens,
                    kvBytes,
                    spec.ggufBatchTokens,
                    spec.ggufMicroBatchTokens,
                    effectivePromptThreads,
                    accelerator,
                    observed,
                    geometryKnown =
                    kvGeometry != null
                ).withContextHysteresis(spec.contextPolicy)
            val available = LocalWorkloadAdmission.availableMemory(context)
            val memoryPlan =
                MemoryPlanReceipt(
                    planId = planKey,
                    artifactDigest = digest,
                    runtimeRevision = GgufNative.REVISION,
                    selectedBackend = accelerator,
                    contextPolicy = spec.contextPolicy,
                    contextTokens = spec.maxTokens,
                    requestedContextTokens = spec.maxTokens,
                    kvBytesPerToken = kvGeometry,
                    kvType = "f16".takeIf { kvGeometry != null },
                    geometryEvidence =
                    if (kvGeometry !=
                        null
                    ) {
                        "validated_uniform_metadata"
                    } else if (observed?.schemaVersion ==
                        2
                    ) {
                        "exact_shape_native_high_water"
                    } else {
                        "unknown_conservative"
                    },
                    requestedOffloadLayers = if (LocalAccelerators.isGpu(accelerator)) spec.ggufGpuLayers else 0,
                    residentEngineReuse = false,
                    mappedModelFileBytes = model.length(),
                    components = components,
                    calibrationSchemaVersion = observed?.schemaVersion,
                    uncertaintyBasis = "component_margin_plus_user_reserve;PSS_not_added",
                    availableBytes = available,
                    reserveBytes = spec.admissionReserveBytes,
                    observedAtMillis = System.currentTimeMillis(),
                    observedAtElapsedMs = SystemClock.elapsedRealtime()
                )
            val estimate =
                LocalMemoryEstimate(
                    components.totalMemoryBytes(),
                    spec.admissionReserveBytes,
                    available,
                    spec.maxTokens,
                    if (observed ==
                        null
                    ) {
                        "conservative_unmeasured"
                    } else {
                        "conservative_component_calibrated"
                    },
                    components,
                    spec.maxTokens,
                    planKey,
                    systemMemory = LocalWorkloadAdmission.memorySnapshot(context),
                    memoryPlan = memoryPlan
                )
            if (estimate.budget.hasShortfall) {
                if (spec.contextPolicy == "AUTO") {
                    val suggestion =
                        runCatching {
                            memoryFeasibleContext(
                                spec.maxTokens - 1,
                                model.length(),
                                available,
                                spec.admissionReserveBytes + AUTO_CONTEXT_HYSTERESIS_BYTES,
                                kvBytesPerToken = kvBytes,
                                batch = spec.ggufBatchTokens,
                                microBatch = spec.ggufMicroBatchTokens,
                                threads = effectivePromptThreads,
                                accelerator = accelerator,
                                geometryKnown =
                                kvGeometry != null
                            )
                        }.getOrNull()
                    if (suggestion !=
                        null
                    ) {
                        throw LocalContextReplanRequired(spec.maxTokens, suggestion, estimate.receipt("native_load", false))
                    }
                }
                throw estimate.rejection("Fresh $accelerator admission failed after cleanup; no native allocation was dispatched.")
            }
            AppLogRecorder.record(
                "LocalModel",
                "NATIVE_LOAD_STARTED · backend=$accelerator · context=${spec.maxTokens} · requiredBytes=${estimate.requiredBytes} · reserveBytes=${estimate.reserveBytes} · availableBytes=${estimate.availableBytes}"
            )
            val operation = activeOperation
            val loadStarted = SystemClock.elapsedRealtime()
            val nextEngineId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            engineLoad =
                EngineLoadReceipt(
                    receiptId =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    artifactDigest = digest,
                    runtimeRevision = GgufNative.REVISION,
                    requestedBackend = accelerator,
                    actualBackend = null,
                    engineInstanceId = nextEngineId,
                    workerGenerationId = workerContext?.workerGenerationId,
                    workerPid = android.os.Process.myPid(),
                    nativeOperationId = operation,
                    requestedOffloadLayers = if (LocalAccelerators.isGpu(accelerator)) spec.ggufGpuLayers else 0,
                    startedAtElapsedMs = loadStarted,
                    completedAtElapsedMs = null,
                    outcome = "IN_FLIGHT",
                    loadDeadlinePolicy = GgufLoadDeadline.policy.takeIf { workerContext != null },
                    memoryPlan = memoryPlan
                )
            GgufNative
                .loadBackendProgress(
                    model.absolutePath.toByteArray(),
                    spec.maxTokens,
                    effectiveThreads,
                    if (LocalAccelerators.isGpu(accelerator)) spec.ggufGpuLayers.coerceIn(1, 256) else 0,
                    activeOperation,
                    effectivePromptThreads,
                    spec.ggufBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 256,
                    minOf(
                        spec.ggufMicroBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 128,
                        spec.ggufBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 256
                    ),
                    GgufNative.LoadProgress { stage, fraction, elapsed ->
                        if (operation == activeOperation) loadStage(stage, elapsed, fraction.toDouble().takeIf { it in 0.0..1.0 })
                    },
                    accelerator
                ).also { engine ->
                    handle = engine
                    executionReceipt = GgufNative.executionInfo(engine)
                    incident(
                        "LOAD_FINISHED",
                        mapOf(
                            "durationMs" to (SystemClock.elapsedRealtime() - loadStarted).toString(),
                            "execution" to executionReceipt,
                            "threads" to effectiveThreads.toString(),
                            "promptThreads" to effectivePromptThreads.toString(),
                            "batch" to spec.ggufBatchTokens.toString(),
                            "microbatch" to spec.ggufMicroBatchTokens.toString()
                        )
                    )
                    calibrationPlanKey = planKey
                    val receipt = observeAllocation(engine, planKey)
                    val native =
                        kotlinx.serialization.json.Json.parseToJsonElement(GgufNative.engineReceipt(engine)).jsonObject.mapValues {
                            it.value.jsonPrimitive.contentOrNull
                        }
                    val actualLayers = native["actualRepeatingLayers"]?.toIntOrNull()
                    engineInstanceId = nextEngineId
                    engineLoad =
                        checkNotNull(engineLoad).copy(
                            actualBackend = if (actualLayers == 0) "cpu" else native["backend"],
                            backendRegistry = native["registry"],
                            deviceName = native["device"],
                            deviceType = native["deviceType"],
                            deviceIndex = native["deviceIndex"]?.toIntOrNull(),
                            driverIdentity = native["driver"]?.takeUnless { it == "unavailable" },
                            actualOffloadLayers = actualLayers,
                            contextTokens = receipt.contextTokens,
                            cpuThreads = native["threads"]?.toIntOrNull(),
                            promptThreads = native["promptThreads"]?.toIntOrNull(),
                            batchTokens = receipt.batchTokens,
                            microBatchTokens = receipt.microBatchTokens,
                            templateIdentity = native["templateIdentity"],
                            tokenizerIdentity = native["tokenizerIdentity"],
                            completedAtElapsedMs = SystemClock.elapsedRealtime(),
                            outcome = "READY",
                            allocation = receipt,
                            nativeAcknowledgedFields =
                            setOf(
                                "actualBackend",
                                "backendRegistry",
                                "deviceName",
                                "deviceType",
                                "deviceIndex",
                                "actualOffloadLayers",
                                "contextTokens",
                                "cpuThreads",
                                "promptThreads",
                                "batchTokens",
                                "microBatchTokens",
                                "templateIdentity",
                                "tokenizerIdentity"
                            ) +
                                if (native["driver"]?.let { it != "unavailable" } == true) setOf("driverIdentity") else emptySet()
                        )
                    if (LocalAccelerators.isGpu(accelerator) &&
                        actualLayers == 0
                    ) {
                        throw dev.melo.gguf.GgufNativeException(
                            "UNSUPPORTED_OPERATION",
                            "GGUF_GPU_UNSUPPORTED_OPERATION: the selected registry accepted no repeating layers; GPU execution remains unqualified"
                        )
                    }
                    loadObserver?.invoke(
                        GgufNativeProgress(
                            operation,
                            "NATIVE_ALLOCATION_OBSERVED",
                            observedAtMs = SystemClock.elapsedRealtime(),
                            loadFraction = 1.0,
                            allocationObservation = receipt
                        )
                    )
                }
        } catch (failure: Throwable) {
            engineLoad = engineLoad?.copy(outcome = "FAILED", completedAtElapsedMs = SystemClock.elapsedRealtime())
            val message = failure.message.orEmpty()
            if (message.contains("template", ignoreCase = true)) {
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_TEMPLATE_REJECTED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=before_context_allocation · reason=${message.take(
                        180
                    )}",
                    "E"
                )
                throw IllegalStateException(
                    "GGUF chat template '$templateName' could not be rendered with the model's tokenizer. No context was allocated. ${message.take(
                        180
                    )}",
                    failure
                )
            }
            throw failure
        }

    private fun observeAllocation(
        engine: Long,
        planKey: String,
        generationObserved: Boolean = false
    ): NativeAllocationObservation {
        val values = GgufNative.allocationObservation(engine)
        require(values.size == 20 && values[19] == 2L) { "GGUF native allocation ownership receipt is incomplete" }
        val receipt =
            NativeAllocationObservation(
                schemaVersion = 2,
                contextTokens = values[0].toInt(),
                batchTokens = values[1].toInt(),
                microBatchTokens = values[2].toInt(),
                hostModelBytes = values[3],
                hostContextBytes = values[4],
                hostComputeBytes = values[5],
                deviceModelBytes = values[6],
                deviceContextBytes = values[7],
                deviceComputeBytes = values[8],
                modelTensorBytes = values[9],
                processPssBytes = android.os.Debug.getPss() * 1024L,
                sharedModelBytes = values[10],
                sharedContextBytes = values[11],
                sharedComputeBytes = values[12],
                unknownModelBytes = values[13],
                unknownContextBytes = values[14],
                unknownComputeBytes = values[15],
                hostAccessibleModelBytes = values[16],
                hostAccessibleContextBytes = values[17],
                hostAccessibleComputeBytes = values[18],
                generationObserved = generationObserved
            ).validated()
        allocation = receipt
        runCatching {
            LocalMemoryCalibrationStore(File(context.noBackupFilesDir, "local-memory-calibration")).write(planKey, receipt)
        }.onFailure { AppLogRecorder.record("LocalModel", "GGUF_CALIBRATION_SAVE_FAILED · exception=${it.javaClass.simpleName}", "W") }
        AppLogRecorder.record(
            "LocalModel",
            "GGUF_NATIVE_ALLOCATION · plan=$planKey · observation=${kotlinx.serialization.json.Json.encodeToString(
                NativeAllocationObservation.serializer(),
                receipt
            )}"
        )
        return receipt
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val manifest = ArtifactManifestStore.read(File(spec.modelPath))
        check(!nativeRunning.get() && handle == 0L) { "Verification requires a fresh isolated worker without a resident engine" }
        val digest = manifest.files.first { it.path == manifest.entryFile }.sha256
        val backend = LocalAccelerators.normalize(spec.accelerator).let { if (it == LocalAccelerators.AUTO) LocalAccelerators.CPU else it }
        journal.allowVerification("$digest|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|$backend|${spec.maxTokens}")
        negativeCache.allowOneVerification(GgufNegativeCache.identity(digest, spec.copy(accelerator = backend)))
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        requireSafeAdmission()
        check(handle != 0L) { "GGUF model is not loaded" }
        require(
            config.tools.isEmpty() &&
                !config.isConstrainedDecodingEnabled &&
                config.thinkingEnabled != true &&
                config.speculativeDecoding != true
        ) { "This GGUF route supports plain text without tools, thinking or speculative decoding" }
        require(config.initialMessages.none { it.images.isNotEmpty() }) { "GGUF image input is not qualified" }
        configuration = config
        history.clear()
        config.systemPrompt?.takeIf { it.isNotBlank() }?.let { history += "system" to it }
        history += config.initialMessages.map { (if (it.role == LocalHistoryRole.USER) "user" else "assistant") to it.text }
    }

    override fun sendMessage(
        text: String,
        images: List<ByteArray>
    ): Flow<LocalRuntimeEvent> = sendMessageWithBudget(text, images, 0)

    override fun sendMessageWithBudget(
        text: String,
        images: List<ByteArray>,
        safetyTokens: Int
    ): Flow<LocalRuntimeEvent> =
        channelFlow {
            requireSafeAdmission()
            require(safetyTokens >= 0)
            require(images.isEmpty()) { "This GGUF runtime is text-only" }
            val config = checkNotNull(configuration) { "GGUF conversation is not open" }
            require((config.maxOutputTokens ?: 512) in 1..8192) { "GGUF output allowance must be explicitly within 1..8192 tokens" }
            val experiment = kotlinx.coroutines.currentCoroutineContext()[LocalRuntimeTrialContext]?.trial
            workerContext = kotlinx.coroutines.currentCoroutineContext()[RuntimeWorkerContext]
            if (experiment != null) {
                val spec = checkNotNull(loaded)
                require(
                    spec.accelerator == LocalAccelerators.CPU &&
                        effectiveThreads == experiment.threadCount &&
                        effectivePromptThreads == experiment.threadCount
                ) { "CPU experiment requires acknowledged GGUF CPU thread configuration" }
            }
            val active = handle
            val previousHistory = history.toList()
            val messages = previousHistory + ("user" to text)
            val started = SystemClock.elapsedRealtime()
            val segment =
                java.util.UUID
                    .randomUUID()
                    .toString()
            var output = StringBuilder()
            var decoder = Utf8GenerationDecoder()
            var first: Long? = null
            var saturated = false
            var retainedProgress: GgufNativeProgress? = null
            send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
            journal.before(checkNotNull(tuple), "GENERATE", segment)
            try {
                val promptCharacters = messages.sumOf { (role, content) -> role.length + content.length }
                val prefillBudgetMs = LocalPrefillDeadline.budgetMs(promptCharacters)

                suspend fun runAttempt(engineHandle: Long): GgufDeadlineOutcome<LongArray> {
                    val latest =
                        java.util.concurrent.atomic
                            .AtomicReference<GgufNativeProgress?>(null)
                    val attemptStarted = SystemClock.elapsedRealtime()
                    val generationId =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                    val cpuStarted = android.os.Process.getElapsedCpuTime()
                    val thermal =
                        runCatching {
                            (
                                context.getSystemService(
                                    Context.POWER_SERVICE
                                ) as? android.os.PowerManager
                                )?.let { getHardwareState().thermalState.name }
                        }.getOrNull()
                    val deadline = GgufGenerationDeadline(attemptStarted, prefillBudgetMs)
                    val requestConfig =
                        GenerationConfigReceipt(
                            generationId = generationId,
                            nativeOperationId = null,
                            engineInstanceId = engineInstanceId,
                            requestedSampler = config.sampler,
                            requestedOutputTokens = config.maxOutputTokens ?: 512,
                            requestedResponseSchemaDigest = config.finalResponseSchemaJson?.let(RuntimeEvidenceIdentity::hash),
                            structuredOutputMode = config.structuredOutputMode.name.takeIf { config.finalResponseSchemaJson != null },
                            structuredOutputCompatibilityKey = config.structuredOutputCompatibilityKey,
                            experiment = experiment,
                            deadlinePolicy = deadline.policy,
                            observedAtElapsedMs = attemptStarted,
                            thermalState = thermal,
                            availableProcessors = Runtime.getRuntime().availableProcessors(),
                            cpuTimeScope = "worker_process",
                            cpuAffinity =
                            runCatching {
                                File("/proc/self/status").useLines { lines ->
                                    lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
                                }
                            }.getOrNull(),
                            competingNativeOperations = 0
                        )
                    execution.set(
                        RuntimeExecutionReceipt(
                            runtimeRequestId = workerContext?.runtimeRequestId,
                            physicalAttemptId = workerContext?.physicalAttemptId,
                            workerGenerationId = workerContext?.workerGenerationId,
                            nativeGenerationId = generationId,
                            engineInstanceId = engineInstanceId,
                            adapterInvokedAtElapsedMs = attemptStarted,
                            configuration = requestConfig,
                            engineLoad = engineLoad,
                            observedAtElapsedMs = attemptStarted
                        )
                    )
                    var lastEmission = 0L
                    var lastStage = ""
                    var effectiveBatch: Int? = null
                    var reusedPromptTokens = 0L
                    var prefillStartedMs = 0L
                    val spec = checkNotNull(loaded)

                    fun observe(
                        stage: String,
                        total: Long,
                        processed: Long,
                        sampled: Long,
                        elapsed: Long,
                        code: Int
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (stage == "PREFILL_BEGIN") {
                            reusedPromptTokens = processed.coerceAtLeast(0)
                            prefillStartedMs = elapsed
                        }
                        if (stage == "PREFILL_BATCH_BEGIN" && code > 0) effectiveBatch = code
                        var progress =
                            GgufNativeProgress(
                                activeOperation,
                                stage,
                                total,
                                processed,
                                sampled,
                                elapsed,
                                if (stage == "PREFILL_BATCH_BEGIN") 0 else code,
                                now,
                                effectiveBatch = effectiveBatch,
                                prefillTokensPerSecond =
                                if (elapsed > prefillStartedMs &&
                                    processed >= reusedPromptTokens &&
                                    sampled <= 0
                                ) {
                                    (processed - reusedPromptTokens) * 1000.0 / (elapsed - prefillStartedMs)
                                } else {
                                    null
                                },
                                actualRuntime =
                                "llama.cpp · " +
                                    if (LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.GPU) {
                                        "Vulkan"
                                    } else if (spec.accelerator == "opencl") {
                                        "OpenCL"
                                    } else {
                                        "CPU"
                                    },
                                modelName = modelReceipt.substringAfter("file=", "").substringBefore(';')
                            )
                        progress = progress.retainingMeasurements(latest.get())
                        var facts = checkNotNull(execution.get()).fromProgress(progress)
                        val measuredConfig = facts.configuration
                        if (measuredConfig != null) {
                            val observedConfig =
                                when (stage) {
                                    "TOKEN_COUNT_MEASURED" ->
                                        measuredConfig.copy(
                                            measuredPromptTokens =
                                            total.takeIf {
                                                it >= 0
                                            },
                                            templateIdentity = engineLoad?.templateIdentity,
                                            tokenizerIdentity = engineLoad?.tokenizerIdentity,
                                            renderedPromptDigest = GgufNative.generationPromptIdentity(engineHandle, activeOperation),
                                            nativeAcknowledgedFields =
                                            measuredConfig.nativeAcknowledgedFields +
                                                setOf(
                                                    "measuredPromptTokens",
                                                    "templateIdentity",
                                                    "tokenizerIdentity",
                                                    "renderedPromptDigest"
                                                )
                                        )
                                    "ADMISSION_SAFETY_TOKENS" ->
                                        measuredConfig.copy(
                                            safetyTokens = code,
                                            nativeAcknowledgedFields =
                                            measuredConfig.nativeAcknowledgedFields + "safetyTokens"
                                        )
                                    "ADMISSION_OUTPUT_TOKENS" ->
                                        measuredConfig.copy(
                                            requestedOutputTokens = code,
                                            nativeAcknowledgedFields =
                                            measuredConfig.nativeAcknowledgedFields + "requestedOutputTokens"
                                        )
                                    "ADMISSION_CONTEXT_TOKENS" ->
                                        measuredConfig.copy(
                                            contextTokens = code,
                                            nativeAcknowledgedFields =
                                            measuredConfig.nativeAcknowledgedFields + "contextTokens"
                                        )
                                    else -> measuredConfig
                                }
                            facts = facts.copy(configuration = observedConfig.copy(observedAtElapsedMs = now))
                        }
                        execution.set(facts)
                        progress = progress.copy(executionReceipt = facts)
                        latest.set(progress)
                        retainedProgress = progress
                        deadline.observe(progress, now)
                        if ((!stage.startsWith("PREFILL_BATCH") && stage != lastStage) ||
                            now - lastEmission >= 250 ||
                            stage == "NATIVE_RETURN"
                        ) {
                            lastEmission = now
                            lastStage = stage
                            trySend(
                                LocalRuntimeEvent.PhaseChanged(
                                    if (sampled >
                                        0
                                    ) {
                                        LocalInferencePhase.GENERATING
                                    } else {
                                        LocalInferencePhase.PREFILL
                                    },
                                    progress
                                )
                            )
                        }
                    }
                    return withGgufDeadlineReceipt(prefillBudgetMs, cancelNative = {
                        healthy = false
                        GgufNative.cancelOperation(engineHandle, activeOperation)
                        val last = latest.get() ?: GgufNativeProgress(activeOperation, "UNKNOWN")
                        val reason = deadline.expiration(SystemClock.elapsedRealtime())
                        execution.updateAndGet {
                            it?.copy(
                                cancellationOrigin = "NATIVE_WATCHDOG",
                                deadlineKind = reason,
                                observedAtElapsedMs = SystemClock.elapsedRealtime()
                            )
                        }
                        launch(Dispatchers.Default) {
                            send(
                                LocalRuntimeEvent.PhaseChanged(
                                    LocalInferencePhase.CANCELLING,
                                    last.copy(
                                        stage = "TIMEOUT_CANCEL_REQUESTED",
                                        observedAtMs = SystemClock.elapsedRealtime(),
                                        configuration = "lastStage=${last.stage};cleanup=restart_if_unresponsive",
                                        deadlineKind = reason,
                                        executionReceipt = execution.get()
                                    )
                                )
                            )
                        }
                    }, expirationCause = { deadline.expiration(SystemClock.elapsedRealtime()) }) { acceptOutput ->
                        cancellableNative(engineHandle) {
                            val configuration = "execution=$executionReceipt;workerPid=${android.os.Process.myPid()};$modelReceipt;build=${GgufNative.buildInfo()};revision=${GgufNative.REVISION};accelerator=${spec.accelerator};context=${spec.maxTokens};threads=$effectiveThreads;promptThreads=$effectivePromptThreads;batch=${spec.ggufBatchTokens};microbatch=${minOf(
                                spec.ggufMicroBatchTokens,
                                spec.ggufBatchTokens
                            )};abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()};modelTuple=${tuple?.substringBefore('|')}"
                            trySend(
                                LocalRuntimeEvent.PhaseChanged(
                                    LocalInferencePhase.PREFILL,
                                    GgufNativeProgress(
                                        activeOperation,
                                        "DISPATCHING_NATIVE",
                                        observedAtMs = SystemClock.elapsedRealtime(),
                                        configuration = configuration,
                                        executionReceipt = execution.get()
                                    )
                                )
                            )
                            execution.updateAndGet {
                                it?.copy(
                                    nativeOperationId = activeOperation,
                                    configuration = requestConfig.copy(nativeOperationId = activeOperation)
                                )
                            }
                            val nativeCounts =
                                try {
                                    GgufNative.generateBudgeted(
                                        engineHandle,
                                        messages.map { it.first }.toTypedArray(),
                                        messages.map { it.second.toByteArray() }.toTypedArray(),
                                        config.maxOutputTokens ?: 512,
                                        config.sampler.topK.coerceIn(1, 1024),
                                        config.sampler.topP.coerceIn(0f, 1f),
                                        config.sampler.temperature.coerceIn(0f, 5f),
                                        { bytes ->
                                            if (!isActive) {
                                                false
                                            } else {
                                                val chunk = decoder.append(bytes)
                                                if (chunk.isEmpty()) {
                                                    true
                                                } else if (!acceptOutput()) {
                                                    false
                                                } else {
                                                    if (first == null) {
                                                        first = SystemClock.elapsedRealtime() - started
                                                        execution.updateAndGet {
                                                            it?.copy(
                                                                firstVisibleTextAtElapsedMs = SystemClock.elapsedRealtime()
                                                            )
                                                        }
                                                    }
                                                    if (output.length + chunk.length > 262144) {
                                                        saturated = true
                                                        false
                                                    } else {
                                                        output.append(chunk)
                                                        trySend(LocalRuntimeEvent.TextDelta(chunk)).isSuccess.also {
                                                            if (!it) {
                                                                saturated =
                                                                    true
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        {
                                                stage,
                                                total,
                                                processed,
                                                sampled,
                                                elapsed,
                                                code
                                            ->
                                            observe(stage, total, processed, sampled, elapsed, code)
                                        },
                                        safetyTokens,
                                        activeOperation
                                    )
                                } catch (failure: Throwable) {
                                    execution.updateAndGet { previous ->
                                        previous?.copy(
                                            nativeReturned = true.takeIf { previous.nativeEntered == true },
                                            nativeReturnedAtElapsedMs =
                                            SystemClock.elapsedRealtime().takeIf {
                                                previous.nativeEntered ==
                                                    true
                                            },
                                            terminalKind = "native_error".takeIf { previous.nativeEntered == true },
                                            observedAtElapsedMs = SystemClock.elapsedRealtime()
                                        )
                                    }
                                    throw failure
                                }
                            val measured =
                                if (nativeCounts.size == 19 && nativeCounts[18] == 2L) {
                                    requestConfig.copy(
                                        nativeOperationId = activeOperation,
                                        effectiveSampler =
                                        LocalSamplerConfig(
                                            config.sampler.topK.coerceIn(1, 1024),
                                            config.sampler.topP.coerceIn(0f, 1f),
                                            config.sampler.temperature.coerceIn(0f, 5f)
                                        ),
                                        effectiveOutputTokens = nativeCounts[9].toInt(),
                                        measuredPromptTokens = nativeCounts[7],
                                        safetyTokens = nativeCounts[10].toInt(),
                                        contextTokens = nativeCounts[11].toInt(),
                                        cpuThreads = nativeCounts[13].toInt(),
                                        promptThreads = nativeCounts[14].toInt(),
                                        batchTokens = nativeCounts[15].toInt(),
                                        microBatchTokens = nativeCounts[16].toInt(),
                                        templateIdentity = engineLoad?.templateIdentity,
                                        tokenizerIdentity = engineLoad?.tokenizerIdentity,
                                        renderedPromptDigest = GgufNative.generationPromptIdentity(engineHandle, activeOperation),
                                        reusedPromptTokens = nativeCounts[5],
                                        processedPromptTokens = nativeCounts[6],
                                        nativeAcknowledgedFields =
                                        setOf(
                                            "effectiveOutputTokens",
                                            "measuredPromptTokens",
                                            "safetyTokens",
                                            "contextTokens",
                                            "cpuThreads",
                                            "promptThreads",
                                            "batchTokens",
                                            "microBatchTokens",
                                            "templateIdentity",
                                            "tokenizerIdentity",
                                            "renderedPromptDigest",
                                            "reusedPromptTokens",
                                            "processedPromptTokens",
                                            "sampler.temperature"
                                        ) +
                                            if (config.sampler.temperature > 0) setOf("sampler.topK", "sampler.topP") else emptySet(),
                                        observedAtElapsedMs = SystemClock.elapsedRealtime(),
                                        cpuTimeMs =
                                        android.os.Process.getElapsedCpuTime() - cpuStarted
                                    )
                                } else {
                                    requestConfig.copy(
                                        nativeOperationId = activeOperation,
                                        observedAtElapsedMs = SystemClock.elapsedRealtime()
                                    )
                                }
                            execution.updateAndGet { previous ->
                                previous?.copy(
                                    nativeReturned = true,
                                    nativeReturnedAtElapsedMs = SystemClock.elapsedRealtime(),
                                    terminalKind = nativeTerminalKind(nativeCounts[4]),
                                    configuration = measured,
                                    promptTokens = nativeCounts.getOrNull(7) ?: previous.promptTokens,
                                    processedPromptTokens =
                                    (nativeCounts.getOrNull(5)?.plus(nativeCounts.getOrNull(6) ?: 0)) ?: previous.processedPromptTokens,
                                    sampledTokens = nativeCounts[1],
                                    observedAtElapsedMs = SystemClock.elapsedRealtime()
                                )
                            }
                            calibrationPlanKey?.let { key -> observeAllocation(engineHandle, key, generationObserved = true) }
                            nativeCounts
                        }
                    }
                }
                val outcome =
                    try {
                        runAttempt(active)
                    } catch (failure: Throwable) {
                        val failedSpec = loaded
                        if (!config.allowsAutomaticBackendRecovery() ||
                            failure is kotlinx.coroutines.CancellationException ||
                            failedSpec == null ||
                            !LocalAccelerators.isGpu(failedSpec.accelerator) ||
                            output.isNotEmpty() ||
                            !failure.isRecoverableGgufGpuLoadFailure()
                        ) {
                            throw failure
                        }
                        modelFallbackKey?.let { key ->
                            GgufNegativeCache.category(failure)?.let { kind ->
                                negativeCache.remember(
                                    key,
                                    kind,
                                    failure.message.orEmpty(),
                                    LocalWorkloadAdmission.availableMemory(context)
                                )
                            }
                        }
                        AppLogRecorder.record(
                            "LocalModel",
                            "GGUF_VULKAN_GENERATION_FALLBACK · model=${File(
                                failedSpec.modelPath
                            ).name} · accelerator=gpu->cpu · visibleOutput=0 · reason=${failure.message.orEmpty().take(180)}",
                            "W"
                        )
                        val originalRequest = requested
                        unloadEngine()
                        journal.finished()
                        loadEngine(failedSpec.copy(accelerator = LocalAccelerators.CPU))
                        requested = originalRequest
                        backendFallback =
                            "Running on CPU · ${failedSpec.accelerator} generation failed: ${failure.message.orEmpty().take(500)}"
                        incident(
                            "CPU_FALLBACK",
                            mapOf(
                                "requestedBackend" to failedSpec.accelerator,
                                "actualBackend" to "cpu",
                                "category" to failure.ggufFailureCategory().name,
                                "reason" to failure.message.orEmpty()
                            )
                        )
                        createConversation(config)
                        // Reopening restores only the initial messages, not turns completed
                        // since then. Preserve those turns for the next request after fallback.
                        history.clear()
                        history.addAll(previousHistory)
                        output = StringBuilder()
                        decoder = Utf8GenerationDecoder()
                        first = null
                        saturated = false
                        send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
                        journal.before(checkNotNull(tuple), "GENERATE", segment)
                        runAttempt(handle)
                    }
                val counts = outcome.receipt
                if (outcome.timedOut) {
                    healthy = false
                    val elapsed = SystemClock.elapsedRealtime() - started
                    execution.updateAndGet {
                        it?.copy(
                            deadlineKind = outcome.terminalCode,
                            cancellationOrigin = "NATIVE_WATCHDOG",
                            observedAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    }
                    val last = retainedProgress ?: GgufNativeProgress(activeOperation, "UNKNOWN")
                    send(
                        LocalRuntimeEvent.PhaseChanged(
                            LocalInferencePhase.CANCELLING,
                            last.copy(
                                stage = "TIMEOUT_RECEIPT",
                                elapsedMs = elapsed,
                                nativeCode =
                                counts?.getOrNull(4)?.toInt() ?: last.nativeCode,
                                configuration = "partial=true;cleanup=unload_before_reuse",
                                deadlineKind = outcome.terminalCode,
                                executionReceipt = execution.get()
                            )
                        )
                    )
                    if (counts != null &&
                        counts.size >= 5
                    ) {
                        send(
                            LocalRuntimeEvent.Metrics(
                                LocalInferenceMetrics(
                                    totalDurationMs = elapsed,
                                    totalCharacters = output.length,
                                    native =
                                    NativeInferenceMetrics(
                                        counts[0].toInt(),
                                        counts[1].toInt(),
                                        if (counts[2] >
                                            0
                                        ) {
                                            (counts.getOrNull(6) ?: counts[0]) * 1000.0 / counts[2]
                                        } else {
                                            0.0
                                        },
                                        if (counts[3] >
                                            0
                                        ) {
                                            counts[1] * 1000.0 / counts[3]
                                        } else {
                                            0.0
                                        },
                                        counts.getOrNull(5)?.toInt(),
                                        counts.getOrNull(6)?.toInt(),
                                        counts[2],
                                        counts[3]
                                    ),
                                    metricSource = "native_partial",
                                    firstVisibleAnswerMs = first,
                                    segmentId = segment,
                                    finishReason =
                                    outcome.terminalCode ?: "GGUF_PREFILL_TIMEOUT",
                                    generationConfigReceipt = execution.get()?.configuration,
                                    runtimeExecutionReceipt = execution.get()
                                )
                            )
                        )
                    }
                    withContext(NonCancellable) { unloadEngine() }
                    val reason =
                        when (outcome.terminalCode) {
                            "GGUF_OPERATION_DEADLINE", "GGUF_FIRST_TEXT_DEADLINE" -> "The absolute wall-time budget expired while the last known progress was retained."
                            "GGUF_PREFILL_THROUGHPUT_DEADLINE" -> "Measured prefill throughput stayed below its configured floor."
                            else -> "No new useful native progress was observed within the stall interval."
                        }
                    send(
                        LocalRuntimeEvent.Error(
                            "${outcome.terminalCode ?: "GGUF_PREFILL_TIMEOUT"}: $reason The engine was unloaded before reuse."
                        )
                    )
                    return@channelFlow
                }
                checkNotNull(counts) { "GGUF native receipt missing" }
                check(counts.size == 5 || counts.size == 7 || counts.size == 19 && counts[18] == 2L) { "Malformed GGUF native receipt" }
                val unicodeComplete = decoder.isComplete()
                val finish =
                    when (counts[4]) {
                        0L -> "eos"
                        1L -> "length"
                        2L -> "canceled"
                        else -> "native_error"
                    }
                if (counts.size >=
                    7
                ) {
                    AppLogRecorder.record(
                        "LocalModel",
                        "GGUF_PREFIX_REUSE · reusedTokens=${counts[5]} · processedPromptTokens=${counts[6]} · totalPromptTokens=${counts[0]} · finish=$finish"
                    )
                }
                incident(
                    "GENERATION_FINISHED",
                    mapOf(
                        "modelIdentity" to tuple.orEmpty(),
                        "actualBackend" to loaded?.accelerator.orEmpty(),
                        "promptTokens" to counts[0].toString(),
                        "outputTokens" to counts[1].toString(),
                        "reusedPromptTokens" to counts.getOrNull(5).toString(),
                        "processedPromptTokens" to counts.getOrNull(6).toString(),
                        "prefillMs" to counts[2].toString(),
                        "decodeMs" to counts[3].toString(),
                        "finish" to finish
                    )
                )
                if (counts[4] in 0L..1L && unicodeComplete && output.isNotBlank() && !saturated) {
                    val activeSpec = checkNotNull(loaded)
                    val actualLayers =
                        Regex("actualRepeatingLayers=(\\d+)")
                            .find(executionReceipt)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: 0
                    if (!LocalAccelerators.isGpu(activeSpec.accelerator) || actualLayers > 0) {
                        devicePlanKey?.let { key ->
                            runCatching {
                                devicePlans.record(
                                    key,
                                    GgufDeviceSample(
                                        activeSpec.accelerator,
                                        actualLayers,
                                        effectiveThreads,
                                        effectivePromptThreads,
                                        if (counts[2] > 0) (counts.getOrNull(6) ?: counts[0]) * 1000.0 / counts[2] else 0.0,
                                        if (counts[3] > 0) counts[1] * 1000.0 / counts[3] else 0.0,
                                        (counts.getOrNull(6) ?: counts[0]).toInt(),
                                        counts[1].toInt(),
                                        experiment != null,
                                        experiment = experiment,
                                        nativeGenerationId = execution.get()?.nativeGenerationId,
                                        engineInstanceId = engineInstanceId,
                                        totalPromptTokens = counts.getOrNull(7) ?: execution.get()?.promptTokens,
                                        renderedPromptDigest = execution.get()?.configuration?.renderedPromptDigest,
                                        reusedPromptTokens = counts.getOrNull(5),
                                        cpuTimeMs = execution.get()?.configuration?.cpuTimeMs,
                                        thermalState = execution.get()?.configuration?.thermalState,
                                        terminalKind = nativeTerminalKind(counts[4]),
                                        cancellationRecovered =
                                        devicePlans.read(key).lastOrNull()?.terminalKind == "cancelled"
                                    )
                                )
                            }
                        }
                    }
                }
                if (counts[4] == 2L) {
                    devicePlanKey?.let { key ->
                        devicePlans.record(
                            key,
                            GgufDeviceSample(
                                loaded?.accelerator ?: "unknown",
                                engineLoad?.actualOffloadLayers ?: 0,
                                effectiveThreads,
                                effectivePromptThreads,
                                0.0,
                                0.0,
                                (counts.getOrNull(6) ?: counts[0]).toInt(),
                                counts[1].toInt(),
                                experiment != null,
                                experiment = experiment,
                                nativeGenerationId = execution.get()?.nativeGenerationId,
                                engineInstanceId = engineInstanceId,
                                totalPromptTokens = counts.getOrNull(7) ?: execution.get()?.promptTokens,
                                renderedPromptDigest = execution.get()?.configuration?.renderedPromptDigest,
                                reusedPromptTokens = counts.getOrNull(5),
                                terminalKind = "cancelled"
                            )
                        )
                    }
                }
                // Control and final counters use suspending delivery, independent of callback queue pressure.
                send(
                    LocalRuntimeEvent.Metrics(
                        LocalInferenceMetrics(
                            totalDurationMs = SystemClock.elapsedRealtime() - started,
                            totalCharacters = output.length,
                            native =
                            NativeInferenceMetrics(
                                counts[0].toInt(),
                                counts[1].toInt(),
                                if (counts[2] >
                                    0
                                ) {
                                    (counts.getOrNull(6) ?: counts[0]) * 1000.0 / counts[2]
                                } else {
                                    0.0
                                },
                                if (counts[3] >
                                    0
                                ) {
                                    counts[1] * 1000.0 / counts[3]
                                } else {
                                    0.0
                                },
                                counts.getOrNull(5)?.toInt(),
                                counts.getOrNull(6)?.toInt(),
                                counts[2],
                                counts[3]
                            ),
                            metricSource = "native_segment",
                            firstVisibleAnswerMs = first,
                            decodeDurationMs = counts[3],
                            segmentId = segment,
                            finishReason = finish,
                            generationConfigReceipt = execution.get()?.configuration,
                            runtimeExecutionReceipt = execution.get()
                        )
                    )
                )
                when {
                    !unicodeComplete -> send(LocalRuntimeEvent.Error("GGUF stopped inside a Unicode sequence; retained output is partial"))
                    saturated -> send(LocalRuntimeEvent.Error("GGUF output queue or character limit reached; retained output is partial"))
                    counts[4] == 0L -> {
                        history += "user" to text
                        history += "assistant" to output.toString()
                        send(LocalRuntimeEvent.Done)
                    }
                    counts[4] == 1L -> send(LocalRuntimeEvent.Error("The response reached its output limit; saved output is partial"))
                    else -> {
                        healthy = false
                        send(LocalRuntimeEvent.Error("GGUF generation ended: $finish; saved output is partial"))
                    }
                }
            } catch (failure: Throwable) {
                incident(
                    "GENERATION_FAILED",
                    mapOf(
                        "modelIdentity" to tuple.orEmpty(),
                        "backend" to loaded?.accelerator.orEmpty(),
                        "category" to failure.ggufFailureCategory().name,
                        "error" to failure.message.orEmpty()
                    )
                )
                healthy = false
                withContext(NonCancellable) { unloadEngine() }
                throw failure
            } finally {
                if (!healthy) withContext(NonCancellable) { unloadEngine() }
                journal.finished()
            }
        }

    private var loadBackend = "cpu"
    private var lastLoadStage: String? = null
    private var stageStartedAtMs = 0L

    private fun loadStage(
        stage: String,
        elapsed: Long = 0,
        fraction: Double? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (stage != lastLoadStage) {
            lastLoadStage?.let {
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_STAGE_FINISHED · nativeOperationId=$activeOperation · workerGenerationId=${workerContext?.workerGenerationId ?: "unknown"} · stage=$it · durationMs=${now - stageStartedAtMs}"
                )
                incident(
                    "LOAD_STAGE_FINISHED",
                    mapOf(
                        "operation" to activeOperation.toString(),
                        "stage" to it,
                        "durationMs" to (now - stageStartedAtMs).toString()
                    )
                )
            }
            journal.stage(stage, activeOperation)
            AppLogRecorder.record(
                "LocalModel",
                "GGUF_STAGE_STARTED · nativeOperationId=$activeOperation · workerGenerationId=${workerContext?.workerGenerationId ?: "unknown"} · stage=$stage"
            )
            incident("LOAD_STAGE_STARTED", mapOf("operation" to activeOperation.toString(), "stage" to stage, "backend" to loadBackend))
            lastLoadStage = stage
            stageStartedAtMs = now
        }
        loadObserver?.invoke(
            GgufNativeProgress(
                activeOperation,
                stage,
                elapsedMs = elapsed,
                observedAtMs = now,
                loadFraction = fraction,
                configuration = "backend=$loadBackend"
            )
        )
        if (stage == "NATIVE_READY") lastLoadStage = null
    }

    private suspend fun <T> cancellableNative(
        activeHandle: Long?,
        operation: Long = nativeOperations.incrementAndGet(),
        block: () -> T
    ): T =
        coroutineScope {
            val completed = AtomicBoolean(false)
            check(nativeRunning.compareAndSet(false, true)) { "GGUF native operation is already active" }
            activeOperation = operation
            activeOperationHandle = activeHandle ?: 0L
            try {
                GgufNative.prepareOperation(activeHandle ?: 0L, operation)
            } catch (
                error: Throwable
            ) {
                nativeRunning.set(false)
                throw error
            }
            val watcher =
                launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!completed.get()) GgufNative.cancelOperation(activeHandle ?: 0L, operation)
                    }
                }
            try {
                withContext(Dispatchers.IO) { block().also { completed.set(true) } }
            } finally {
                withContext(NonCancellable) { watcher.cancelAndJoin() }
                nativeRunning.set(false)
            }
        }

    override fun cancelActive() {
        GgufNative.cancelOperation(activeOperationHandle, activeOperation)
    }

    override suspend fun closeConversation() {
        check(!nativeRunning.get()) { "Native conversation cannot close while its operation remains active" }
        configuration = null
        history.clear()
    }

    private val unloading = AtomicBoolean(false)

    override suspend fun unloadEngine() {
        check(unloading.compareAndSet(false, true)) { "GGUF native unload is already in progress" }
        try {
            closeConversation()
            val active = handle
            // Keep the handle owned until native unload finishes. A failed native unload
            // must not allow a second caller to free an unknown native allocation.
            if (active != 0L) {
                try {
                    withContext(Dispatchers.IO) { GgufNative.unload(active) }
                } catch (failure: Throwable) {
                    quarantineAfterUnconfirmedCleanup("GGUF_UNLOAD_FAILED: ${failure.javaClass.simpleName}")
                    throw failure
                }
            }
            handle = 0L
            healthy = false
            engineInstanceId = null
            loaded = null
            requested = null
            allocation = null
            calibrationPlanKey = null
            tuple = null
            modelFallbackKey = null
            modelReceipt = ""
        } finally {
            unloading.set(false)
        }
    }

    override fun quarantineAfterUnconfirmedCleanup(reason: String): RuntimeCleanupReceipt {
        cleanupBlocked = reason
        healthy = false
        val last = execution.get()
        return RuntimeCleanupReceipt(
            origin = "native_cleanup",
            nativeOperationId = last?.nativeOperationId,
            engineInstanceId = last?.engineInstanceId,
            quarantined = true,
            failureReason = reason,
            observedAtElapsedMs = SystemClock.elapsedRealtime()
        ).also { cleanupReceipt = it }
    }

    override suspend fun cleanupAfterRequest(origin: String): RuntimeCleanupReceipt =
        withContext(NonCancellable + Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val last = execution.get()
            val cancelling = nativeRunning.get()
            if (cancelling) cancelActive()
            if (kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    while (nativeRunning.get()) kotlinx.coroutines.delay(25)
                    true
                } != true
            ) {
                return@withContext quarantineAfterUnconfirmedCleanup(
                    "Native closure is unconfirmed; isolated worker termination is required"
                )
            }
            try {
                closeConversation()
                val residentHealthy = healthy && handle != 0L
                if (!residentHealthy) unloadEngine()
                RuntimeCleanupReceipt(
                    origin = origin,
                    nativeOperationId = last?.nativeOperationId,
                    engineInstanceId = last?.engineInstanceId,
                    requiredActions =
                    setOf("close_conversation") + (if (cancelling) setOf("cancel") else emptySet()) +
                        (if (!residentHealthy) setOf("release_engine") else emptySet()),
                    obligationsObserved = true,
                    cancellationRequested = cancelling,
                    cancellationAcknowledged = true.takeIf { cancelling },
                    nativeClosureConfirmed = true,
                    conversationClosed = true,
                    engineReleased = !residentHealthy,
                    engineHealthy = residentHealthy,
                    startedAtElapsedMs = started,
                    observedAtElapsedMs = SystemClock.elapsedRealtime()
                ).also {
                    cleanupReceipt = it
                    cleanupBlocked = null
                }
            } catch (error: Throwable) {
                quarantineAfterUnconfirmedCleanup(error.message ?: "Native cleanup failed")
            }
        }
}

/** Decode complete Unicode scalars only; a token boundary may split a UTF-8 sequence. */
internal class Utf8GenerationDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): String {
        val input = java.nio.ByteBuffer.wrap(pending + bytes)
        val output = java.nio.CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false).throwIfError()
        pending = ByteArray(input.remaining()).also { input.get(it) }
        check(pending.size <= 3) { "Invalid UTF-8 generation boundary" }
        output.flip()
        return output.toString()
    }

    fun isComplete(): Boolean = pending.isEmpty()

    private fun java.nio.charset.CoderResult.throwIfError() {
        if (isError) throwException()
    }
}
