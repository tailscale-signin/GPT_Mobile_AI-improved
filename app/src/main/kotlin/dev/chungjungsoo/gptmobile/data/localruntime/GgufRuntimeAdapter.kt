package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest
import dev.melo.gguf.GgufNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

/** Pinned CPU/Vulkan runtime, QAIRT-independent; the existing holder/process owns every lease. */
internal class GgufRuntimeAdapter(private val context: Context) : LocalRuntime {
    private val journal = NativeExitEvidence.journal(context, File(context.noBackupFilesDir, "gguf-native"))

    @Volatile private var handle = 0L
    private var loaded: LocalEngineSpec? = null

    @Volatile private var healthy = false

    @Volatile private var activeOperation = 0L

    @Volatile private var activeOperationHandle = 0L
    private var tuple: String? = null
    private var modelFallbackKey: String? = null
    private var modelReceipt: String = ""
    private var calibrationPlanKey: String? = null
    private var configuration: LocalConversationConfig? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val gpuFallbackModelKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var loadObserver: ((GgufNativeProgress) -> Unit)? = null

    @Volatile private var allocation: NativeAllocationObservation? = null
    override val latestAllocationObservation get() = allocation
    override fun setLoadProgressObserver(observer: ((GgufNativeProgress) -> Unit)?) {
        loadObserver = observer
    }
    private val descriptors = ConcurrentHashMap<String, Map<String, String>>()
    private fun metadata(model: File, manifest: ModelArtifactManifest): Map<String, String> {
        val digest = manifest.files.first { it.path == manifest.entryFile }.sha256
        val key = "${model.absolutePath}|$digest|${model.length()}|${model.lastModified()}"
        descriptors.keys.removeIf { it.startsWith(model.absolutePath + "|") && it != key }
        return descriptors.computeIfAbsent(key) { dev.chungjungsoo.gptmobile.data.localmodel.GgufImportAdapter.inspect(model) }
    }
    override fun loadedEngineSpec() = loaded
    override suspend fun isEngineLoaded(spec: LocalEngineSpec) = healthy && handle != 0L && loaded?.engineConfiguration() == spec.engineConfiguration()
    override fun hasOpenConversation() = configuration != null

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        val manifest = ArtifactManifestStore.read(file)
        manifest.validate(requireNotNull(file.parentFile))
        check(manifest.runtime == ArtifactRuntime.LLAMA_CPP) { "GenieX artifacts require the separate GenieX preview APK; this is the standard llama.cpp runtime." }
        val model = ModelArtifactManifest.safeFile(requireNotNull(file.parentFile), manifest.entryFile)
        val metadata = metadata(model, manifest)
        val templateName = dev.chungjungsoo.gptmobile.data.localmodel.GgufChatTemplateIdentity.describe(metadata["tokenizer.chat_template"])
        check(metadata["tokenizer.chat_template"].isNullOrBlank().not()) { "GGUF chat template '$templateName' is missing; import a chat-tuned GGUF export." }
        check(metadata["general.architecture"] == manifest.architecture) { "GGUF metadata architecture does not match its manifest." }
        AppLogRecorder.record("LocalModel", "GGUF_TEMPLATE_METADATA · model=${file.name} · template=$templateName · embeddedBytes=${metadata["tokenizer.chat_template"]?.length ?: 0} · nativeProbe=pending")
        check(!manifest.vision && manifest.projectorFile == null) { "This GGUF runtime supports self-contained text models only" }
        check("cpu" in manifest.supportedBackends) { "This artifact does not permit the packaged CPU backend" }
        val backends = buildSet {
            add("cpu")
            // A GGUF model is not compiled for a specific accelerator. Vulkan is an
            // engine capability and may be used for any compatible tensor layers.
            if (RuntimeCapabilityRegistry.gguf(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()).backends.contains("gpu")) add("gpu")
        }
        LocalModelCapabilities(minOf(manifest.contextTokens, 32768), true, backends, false, false, false, false, kvBytesPerToken = uniformGgufKvBytesPerToken(metadata))
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) = withContext(Dispatchers.IO) {
        RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.LLAMA_CPP)
        val file = File(spec.modelPath)
        val manifest = ArtifactManifestStore.read(file)
        val model = ModelArtifactManifest.safeFile(requireNotNull(file.parentFile), manifest.entryFile)
        val modelHash = manifest.files.first { it.path == manifest.entryFile }.sha256
        val fallbackKey = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}"
        val effectiveSpec = if (
            LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.GPU && fallbackKey in gpuFallbackModelKeys
        ) {
            spec.copy(accelerator = LocalAccelerators.CPU)
        } else {
            spec
        }
        if (isEngineLoaded(effectiveSpec)) return@withContext
        inspectModel(effectiveSpec.modelPath).validate(effectiveSpec)
        val preflightMetadata = metadata(model, manifest)
        val templateName = dev.chungjungsoo.gptmobile.data.localmodel.GgufChatTemplateIdentity.describe(preflightMetadata["tokenizer.chat_template"])
        val template = preflightMetadata["tokenizer.chat_template"]
            ?: error("GGUF chat template '$templateName' is missing; no model memory was allocated.")
        try {
            GgufNative.validateChatTemplate(template.toByteArray(Charsets.UTF_8))
        } catch (failure: IllegalStateException) {
            if (failure is CancellationException) throw failure
            AppLogRecorder.record("LocalModel", "GGUF_TEMPLATE_REJECTED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=metadata_only_preflight · modelLoadStarted=false · reason=${failure.message.orEmpty().take(180)}", "E")
            throw IllegalStateException("GGUF chat template '$templateName' could not be parsed as Jinja. No model weights or context were loaded. ${failure.message.orEmpty().take(180)}", failure)
        }
        AppLogRecorder.record("LocalModel", "GGUF_TEMPLATE_PARSED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=syntax_only_preflight · modelLoadStarted=false")
        val accelerator = LocalAccelerators.normalize(effectiveSpec.accelerator)
        check(accelerator in setOf(LocalAccelerators.CPU, LocalAccelerators.GPU)) { "Standalone GGUF supports CPU or Vulkan GPU only" }
        if (accelerator == LocalAccelerators.GPU) check(RuntimeCapabilityRegistry.gguf(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()).backends.contains("gpu")) { "This build does not include GGUF Vulkan GPU offload; rebuild with ggufVulkan=true" }
        if (manifest.runtime == ArtifactRuntime.LLAMA_CPP) check(manifest.runtimeVersion == GgufNative.REVISION) { "This artifact requires another GGUF engine revision" }
        val identity = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|$accelerator|${effectiveSpec.maxTokens}"
        unloadEngine()
        journal.before(identity, "LOAD")
        try {
            cancellableNative(null) { loadNative(model, effectiveSpec, accelerator, templateName).also { handle = it } }
            healthy = true
            modelReceipt = "file=${model.name};architecture=${manifest.architecture ?: "unknown"};quantization=${manifest.weightPrecision ?: "unknown"};templateHash=${dev.chungjungsoo.gptmobile.data.research.researchHash(template)};modelBytes=${model.length()}"
            loaded = effectiveSpec
            tuple = identity
            modelFallbackKey = fallbackKey
        } catch (failure: Throwable) {
            withContext(NonCancellable) { unloadEngine() }
            if (failure !is CancellationException && accelerator == LocalAccelerators.GPU && failure.isVulkanComputePipelineFailure()) {
                gpuFallbackModelKeys += fallbackKey
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_VULKAN_LOAD_FALLBACK · model=${file.name} · accelerator=gpu->cpu · visibleOutput=0 · reason=${failure.message.orEmpty().take(180)}",
                    "W"
                )
                val cpuSpec = effectiveSpec.copy(accelerator = LocalAccelerators.CPU)
                val cpuIdentity = "$modelHash|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|${LocalAccelerators.CPU}|${cpuSpec.maxTokens}"
                try {
                    journal.before(cpuIdentity, "LOAD")
                    cancellableNative(null) {
                        loadNative(model, cpuSpec, LocalAccelerators.CPU, templateName).also { handle = it }
                    }
                    healthy = true
                    modelReceipt = "file=${model.name};architecture=${manifest.architecture ?: "unknown"};quantization=${manifest.weightPrecision ?: "unknown"};templateHash=${dev.chungjungsoo.gptmobile.data.research.researchHash(template)};modelBytes=${model.length()}"
                    loaded = cpuSpec
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
            journal.finished()
        }
    }

    private fun loadNative(model: File, spec: LocalEngineSpec, accelerator: String, templateName: String): Long = try {
        val manifest = ArtifactManifestStore.read(File(spec.modelPath))
        val kvBytes = uniformGgufKvBytesPerToken(metadata(model, manifest)) ?: 256L * 1024
        val digest = manifest.files.first { it.path == manifest.entryFile }.sha256
        val planKey = LocalMemoryCalibrationStore.key(digest, spec.copy(accelerator = accelerator))
        val calibrations = LocalMemoryCalibrationStore(File(context.noBackupFilesDir, "local-memory-calibration"))
        val observed = calibrations.read(planKey)
        val components = ggufMemoryComponents(model.length(), spec.maxTokens, kvBytes, spec.ggufBatchTokens, spec.ggufMicroBatchTokens, GgufCpuThreads.resolve(spec.ggufPromptThreads ?: spec.cpuThreads, Runtime.getRuntime().availableProcessors()), accelerator, observed)
        val estimate = LocalMemoryEstimate(components.totalMemoryBytes(), spec.admissionReserveBytes, LocalWorkloadAdmission.availableMemory(context), spec.maxTokens, if (observed == null) "conservative_unmeasured" else "conservative_component_calibrated", components, spec.maxTokens, planKey, systemMemory = LocalWorkloadAdmission.memorySnapshot(context))
        if (estimate.budget.hasShortfall) throw estimate.rejection("Fresh $accelerator admission failed after cleanup; no native allocation was dispatched.")
        AppLogRecorder.record("LocalModel", "NATIVE_LOAD_STARTED · backend=$accelerator · context=${spec.maxTokens} · requiredBytes=${estimate.requiredBytes} · reserveBytes=${estimate.reserveBytes} · availableBytes=${estimate.availableBytes}")
        val operation = activeOperation
        GgufNative.loadConfiguredProgress(
            model.absolutePath.toByteArray(),
            spec.maxTokens,
            GgufCpuThreads.resolve(spec.cpuThreads, Runtime.getRuntime().availableProcessors()),
            if (accelerator == LocalAccelerators.GPU) 999 else 0,
            activeOperation,
            GgufCpuThreads.resolve(spec.ggufPromptThreads ?: spec.cpuThreads, Runtime.getRuntime().availableProcessors()),
            spec.ggufBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 256,
            minOf(spec.ggufMicroBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 128, spec.ggufBatchTokens.takeIf { it in setOf(64, 128, 256) } ?: 256),
            GgufNative.LoadProgress { stage, fraction, elapsed ->
                if (operation == activeOperation) loadObserver?.invoke(GgufNativeProgress(operation, stage, elapsedMs = elapsed, observedAtMs = SystemClock.elapsedRealtime(), loadFraction = fraction.toDouble().takeIf { it in 0.0..1.0 }))
            }
        ).also { engine ->
            handle = engine
            calibrationPlanKey = planKey
            val receipt = observeAllocation(engine, planKey)
            loadObserver?.invoke(GgufNativeProgress(operation, "NATIVE_ALLOCATION_OBSERVED", observedAtMs = SystemClock.elapsedRealtime(), loadFraction = 1.0, allocationObservation = receipt))
        }
    } catch (failure: Throwable) {
        val message = failure.message.orEmpty()
        if (message.contains("template", ignoreCase = true)) {
            AppLogRecorder.record("LocalModel", "GGUF_TEMPLATE_REJECTED · model=${model.name} · template=$templateName · runtimeRevision=${GgufNative.REVISION} · renderer=jinja · stage=before_context_allocation · reason=${message.take(180)}", "E")
            throw IllegalStateException("GGUF chat template '$templateName' could not be rendered with the model's tokenizer. No context was allocated. ${message.take(180)}", failure)
        }
        throw failure
    }

    private fun observeAllocation(engine: Long, planKey: String): NativeAllocationObservation {
        val values = GgufNative.allocationObservation(engine)
        require(values.size == 10) { "GGUF native allocation receipt is incomplete" }
        val receipt = NativeAllocationObservation(contextTokens = values[0].toInt(), batchTokens = values[1].toInt(), microBatchTokens = values[2].toInt(), hostModelBytes = values[3], hostContextBytes = values[4], hostComputeBytes = values[5], deviceModelBytes = values[6], deviceContextBytes = values[7], deviceComputeBytes = values[8], modelTensorBytes = values[9], processPssBytes = android.os.Debug.getPss() * 1024L).validated()
        allocation = receipt
        runCatching { LocalMemoryCalibrationStore(File(context.noBackupFilesDir, "local-memory-calibration")).write(planKey, receipt) }.onFailure { AppLogRecorder.record("LocalModel", "GGUF_CALIBRATION_SAVE_FAILED · exception=${it.javaClass.simpleName}", "W") }
        AppLogRecorder.record("LocalModel", "GGUF_NATIVE_ALLOCATION · plan=$planKey · observation=${kotlinx.serialization.json.Json.encodeToString(NativeAllocationObservation.serializer(), receipt)}")
        return receipt
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val manifest = ArtifactManifestStore.read(File(spec.modelPath))
        journal.allowVerification("${manifest.files.first { it.path == manifest.entryFile }.sha256}|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|${LocalAccelerators.normalize(spec.accelerator)}|${spec.maxTokens}")
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        check(handle != 0L) { "GGUF model is not loaded" }
        require(config.tools.isEmpty() && !config.isConstrainedDecodingEnabled && config.thinkingEnabled != true && config.speculativeDecoding != true) { "This GGUF route supports plain text without tools, thinking or speculative decoding" }
        require(config.initialMessages.none { it.images.isNotEmpty() }) { "GGUF image input is not qualified" }
        configuration = config
        history.clear()
        config.systemPrompt?.takeIf { it.isNotBlank() }?.let { history += "system" to it }
        history += config.initialMessages.map { (if (it.role == LocalHistoryRole.USER) "user" else "assistant") to it.text }
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = channelFlow {
        require(images.isEmpty()) { "This GGUF runtime is text-only" }
        val config = checkNotNull(configuration) { "GGUF conversation is not open" }
        val active = handle
        val messages = history.toList() + ("user" to text)
        val started = SystemClock.elapsedRealtime()
        val segment = java.util.UUID.randomUUID().toString()
        var output = StringBuilder()
        var decoder = Utf8GenerationDecoder()
        var first: Long? = null
        var saturated = false
        send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
        journal.before(checkNotNull(tuple), "GENERATE", segment)
        try {
            val promptCharacters = messages.sumOf { (role, content) -> role.length + content.length }
            val prefillBudgetMs = LocalPrefillDeadline.budgetMs(promptCharacters)
            suspend fun runAttempt(engineHandle: Long): GgufDeadlineOutcome<LongArray> {
                val latest = java.util.concurrent.atomic.AtomicReference<GgufNativeProgress?>(null)
                val lastWorkAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                val attemptStarted = SystemClock.elapsedRealtime()
                var lastEmission = 0L
                var lastStage = ""
                var effectiveBatch: Int? = null
                var reusedPromptTokens = 0L
                var prefillStartedMs = 0L
                val spec = checkNotNull(loaded)
                fun observe(stage: String, total: Long, processed: Long, sampled: Long, elapsed: Long, code: Int) {
                    val now = SystemClock.elapsedRealtime()
                    if (stage == "PREFILL_BEGIN") { reusedPromptTokens = processed.coerceAtLeast(0); prefillStartedMs = elapsed }
                    if (stage == "PREFILL_BATCH_BEGIN" && code > 0) effectiveBatch = code
                    val progress = GgufNativeProgress(activeOperation, stage, total, processed, sampled, elapsed, if (stage == "PREFILL_BATCH_BEGIN") 0 else code, now,
                        effectiveBatch = effectiveBatch,
                        prefillTokensPerSecond = if (elapsed > prefillStartedMs && processed >= reusedPromptTokens && sampled <= 0) (processed - reusedPromptTokens) * 1000.0 / (elapsed - prefillStartedMs) else null,
                        actualRuntime = "llama.cpp · " + if (LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.GPU) "Vulkan" else "CPU",
                        modelName = modelReceipt.substringAfter("file=", "").substringBefore(';'))
                    val previous = latest.getAndSet(progress)
                    if (processed > (previous?.processedTokens ?: -1) || sampled > (previous?.sampledTokens ?: -1) || stage in setOf("RENDER_END", "TOKENIZE_END")) lastWorkAt.set(now)
                    if ((!stage.startsWith("PREFILL_BATCH") && stage != lastStage) || now - lastEmission >= 250 || stage == "NATIVE_RETURN") {
                        lastEmission = now
                        lastStage = stage
                        trySend(LocalRuntimeEvent.PhaseChanged(if (sampled > 0) LocalInferencePhase.GENERATING else LocalInferencePhase.PREFILL, progress))
                    }
                }
                return withGgufDeadlineReceipt(prefillBudgetMs, cancelNative = {
                    healthy = false
                    GgufNative.cancelOperation(engineHandle, activeOperation)
                    val last = latest.get() ?: GgufNativeProgress(activeOperation, "UNKNOWN")
                    launch(Dispatchers.Default) {
                        send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.CANCELLING, last.copy(stage = "TIMEOUT_CANCEL_REQUESTED", observedAtMs = SystemClock.elapsedRealtime(), configuration = "lastStage=${last.stage};cleanup=restart_if_unresponsive")))
                    }
                }, expirationCause = {
                    val now = SystemClock.elapsedRealtime()
                    val decoding = (latest.get()?.sampledTokens ?: 0) > 0
                    when {
                        now - attemptStarted >= 600000L -> "GGUF_OPERATION_DEADLINE"
                        now - lastWorkAt.get() >= if (decoding) 45000L else prefillBudgetMs -> if (decoding) "GGUF_DECODE_STALL" else "GGUF_NATIVE_STALL"
                        else -> null
                    }
                }) { acceptOutput ->
                    cancellableNative(engineHandle) {
                        val configuration = "workerPid=${android.os.Process.myPid()};$modelReceipt;build=${GgufNative.buildInfo()};revision=${GgufNative.REVISION};accelerator=${spec.accelerator};context=${spec.maxTokens};threads=${GgufCpuThreads.resolve(spec.cpuThreads, Runtime.getRuntime().availableProcessors())};promptThreads=${GgufCpuThreads.resolve(spec.ggufPromptThreads ?: spec.cpuThreads, Runtime.getRuntime().availableProcessors())};batch=${spec.ggufBatchTokens};microbatch=${minOf(spec.ggufMicroBatchTokens, spec.ggufBatchTokens)};abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()};modelTuple=${tuple?.substringBefore('|')}"
                        trySend(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(activeOperation, "NATIVE_ENTRY", observedAtMs = SystemClock.elapsedRealtime(), configuration = configuration)))
                        GgufNative.generatePrepared(
                            engineHandle,
                            messages.map { it.first }.toTypedArray(),
                            messages.map { it.second.toByteArray() }.toTypedArray(),
                            config.maxOutputTokens?.coerceIn(1, 8192) ?: 512,
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
                                        if (first == null) first = SystemClock.elapsedRealtime() - started
                                        if (output.length + chunk.length > 262144) {
                                            saturated = true
                                            false
                                        } else {
                                            output.append(chunk)
                                            trySend(LocalRuntimeEvent.TextDelta(chunk)).isSuccess.also { if (!it) saturated = true }
                                        }
                                    }
                                }
                            },
                            { stage, total, processed, sampled, elapsed, code -> observe(stage, total, processed, sampled, elapsed, code) },
                            activeOperation
                        ).also { calibrationPlanKey?.let { key -> observeAllocation(engineHandle, key) } }
                    }
                }
            }
            val outcome = try {
                runAttempt(active)
            } catch (failure: Throwable) {
                val failedSpec = loaded
                if (failure is kotlinx.coroutines.CancellationException ||
                    failedSpec == null ||
                    LocalAccelerators.normalize(failedSpec.accelerator) != LocalAccelerators.GPU ||
                    output.isNotEmpty() ||
                    !failure.isVulkanComputePipelineFailure()
                ) {
                    throw failure
                }
                modelFallbackKey?.let(gpuFallbackModelKeys::add)
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_VULKAN_GENERATION_FALLBACK · model=${File(failedSpec.modelPath).name} · accelerator=gpu->cpu · visibleOutput=0 · reason=${failure.message.orEmpty().take(180)}",
                    "W"
                )
                unloadEngine()
                loadEngine(failedSpec.copy(accelerator = LocalAccelerators.CPU))
                createConversation(config)
                output = StringBuilder()
                decoder = Utf8GenerationDecoder()
                first = null
                saturated = false
                send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
                runAttempt(handle)
            }
            val counts = outcome.receipt
            if (outcome.timedOut) {
                healthy = false
                val elapsed = SystemClock.elapsedRealtime() - started
                send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(activeOperation, "TIMEOUT_RECEIPT", processedTokens = counts?.getOrNull(6) ?: -1, sampledTokens = counts?.getOrNull(1) ?: -1, elapsedMs = elapsed, nativeCode = counts?.getOrNull(4)?.toInt() ?: -1, configuration = "partial=true;cleanup=unload_before_reuse")))
                if (counts != null && counts.size >= 5) send(LocalRuntimeEvent.Metrics(LocalInferenceMetrics(totalDurationMs = elapsed, totalCharacters = output.length, native = NativeInferenceMetrics(counts[0].toInt(), counts[1].toInt(), if (counts[2] > 0) (counts.getOrNull(6) ?: counts[0]) * 1000.0 / counts[2] else 0.0, if (counts[3] > 0) counts[1] * 1000.0 / counts[3] else 0.0), metricSource = "native_partial", firstVisibleAnswerMs = first, segmentId = segment, finishReason = outcome.terminalCode ?: "GGUF_PREFILL_TIMEOUT")))
                withContext(NonCancellable) { unloadEngine() }
                send(LocalRuntimeEvent.Error("${outcome.terminalCode ?: "GGUF_PREFILL_TIMEOUT"}: The local model stopped making useful progress. Partial measurements were retained; the engine was unloaded before reuse."))
                return@channelFlow
            }
            checkNotNull(counts) { "GGUF native receipt missing" }
            check(counts.size == 5 || counts.size == 7) { "Malformed GGUF native receipt" }
            val unicodeComplete = decoder.isComplete()
            val finish = when (counts[4]) {
                0L -> "eos"
                1L -> "length"
                2L -> "canceled"
                else -> "native_error"
            }
            if (counts.size >= 7) AppLogRecorder.record("LocalModel", "GGUF_PREFIX_REUSE · reusedTokens=${counts[5]} · processedPromptTokens=${counts[6]} · totalPromptTokens=${counts[0]} · finish=$finish")
            // Control and final counters use suspending delivery, independent of callback queue pressure.
            send(
                LocalRuntimeEvent.Metrics(
                    LocalInferenceMetrics(
                        totalDurationMs = SystemClock.elapsedRealtime() - started,
                        totalCharacters = output.length,
                        native = NativeInferenceMetrics(counts[0].toInt(), counts[1].toInt(), if (counts[2] > 0) (counts.getOrNull(6) ?: counts[0]) * 1000.0 / counts[2] else 0.0, if (counts[3] > 0) counts[1] * 1000.0 / counts[3] else 0.0),
                        metricSource = "native_segment",
                        firstVisibleAnswerMs = first,
                        decodeDurationMs = counts[3],
                        segmentId = segment,
                        finishReason = finish
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
            healthy = false
            withContext(NonCancellable) { unloadEngine() }
            throw failure
        } finally {
            if (!healthy) withContext(NonCancellable) { unloadEngine() }
            journal.finished()
        }
    }

    private suspend fun <T> cancellableNative(activeHandle: Long?, block: () -> T): T = coroutineScope {
        val completed = AtomicBoolean(false)
        val operation = GgufNative.nextOperationId()
        activeOperation = operation
        activeOperationHandle = activeHandle ?: 0L
        GgufNative.prepareOperation(activeHandle ?: 0L, operation)
        val watcher = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
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
        }
    }
    override fun cancelActive() {
        GgufNative.cancelOperation(activeOperationHandle, activeOperation)
    }
    override suspend fun closeConversation() {
        configuration = null
        history.clear()
    }
    override suspend fun unloadEngine() {
        closeConversation()
        val active = handle
        handle = 0
        healthy = false
        loaded = null
        allocation = null
        calibrationPlanKey = null
        tuple = null
        modelFallbackKey = null
        modelReceipt = ""
        if (active != 0L) withContext(Dispatchers.IO) { GgufNative.unload(active) }
    }
}

private fun Throwable.isVulkanComputePipelineFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message.orEmpty().contains("vk::Device::createComputePipeline", ignoreCase = true)) return true
        current = current.cause
    }
    return false
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
