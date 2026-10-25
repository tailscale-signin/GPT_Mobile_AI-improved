package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Production [LocalRuntime] implementation wrapping LiteRT-LM. */
class LocalRuntimeImpl(
    private val context: Context,
    private val inspectMetadata: (String) -> LocalModelCapabilities? = LiteRtModelInspector::inspect,
    private val createEngine: (EngineConfig) -> Engine = { Engine(it) }
) : LocalRuntime {
    private val nativeOwner = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "litert-lifecycle-owner").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val nativeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + nativeOwner + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "NATIVE_OWNER_FAILURE · cause=${error.javaClass.simpleName}", "E") })

    @Volatile private var lifecycleFailed = false
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile private var activeRequestJob: Job? = null
    private var loadedAccelerator: String = LocalAccelerators.CPU
    private var loadedSpec: LocalEngineSpec? = null
    private val policyStabilizer = AdaptivePolicyStabilizer()
    private var capabilities: LocalModelCapabilities? = null

    @Volatile private var nativeCompletion: CompletableDeferred<Unit>? = null

    @Volatile private var activeBuffer: LocalEventBuffer? = null
    private var conversationMtp: Boolean? = null
    private var conversationResponseFormat: ResponseFormat? = null
    private var conversationConfig: LocalConversationConfig? = null
    private var conversationThinking: Boolean? = null
    private var conversationInstanceId: String? = null

    @Volatile private var engineReceipt: EngineLoadReceipt? = null
    private val executionReceipt = java.util.concurrent.atomic.AtomicReference<RuntimeExecutionReceipt?>()
    override val latestEngineLoadReceipt: EngineLoadReceipt? get() = engineReceipt
    override val latestExecutionReceipt: RuntimeExecutionReceipt? get() = executionReceipt.get()
    override val executionIdentity: LocalRuntimeExecutionIdentity get() = LocalRuntimeExecutionIdentity(
        conversationId = conversationInstanceId,
        engineInstanceId = engineReceipt?.engineInstanceId
    )
    private val operationJournal by lazy { NativeExitEvidence.journal(context, java.io.File(context.noBackupFilesDir, "local-native-operations")) }
    private var nativeIdentity: String? = null

    private val inspectionCache by lazy {
        LiteRtInspectionCache(
            directory = java.io.File(context.noBackupFilesDir, "litert-inspection-cache"),
            identityDirectory = java.io.File(context.noBackupFilesDir, "model-identity-cache"),
            environment = LiteRtInspectionEnvironment.current(context),
            readMetadata = inspectMetadata,
            beforeNative = { operationJournal.before(it, "INSPECT") },
            afterNative = { operationJournal.finished() }
        )
    }

    @Volatile private var inspectionReceipt: RuntimeInspectionReceipt? = null
    override val latestInspectionReceipt get() = inspectionReceipt

    private val activityManager: ActivityManager? by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    override val deviceRamGb: Long by lazy {
        val memoryInfo = ActivityManager.MemoryInfo()
        if (activityManager != null) {
            activityManager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024L * 1024L * 1024L)
        } else {
            4L
        }
    }

    private val isHighRamDevice: Boolean by lazy {
        deviceRamGb >= 10L
    }

    override fun getHardwareState(): DeviceHardwareState =
        DeviceHardwareGovernor.inspectHardwareState(context)

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy {
        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(getHardwareState(), isHighRamDevice)
        return policyStabilizer.update(policy, SystemClock.elapsedRealtime())
    }

    override fun loadedEngineSpec(): LocalEngineSpec? = loadedSpec

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = inspectModelInternal(modelPath, null)

    override suspend fun inspectModel(spec: LocalEngineSpec): LocalModelCapabilities? = inspectModelInternal(spec.modelPath, spec)

    private suspend fun inspectModelInternal(modelPath: String, spec: LocalEngineSpec?): LocalModelCapabilities? = withContext(nativeOwner) {
        check(!lifecycleFailed) { "Native lifecycle is quarantined until this worker restarts." }
        val activeContext = coroutineContext
        val baselineInspectionId = inspectionCache.latestReceipt?.inspectionId
        inspectionReceipt = null
        val resolutionStarted = SystemClock.elapsedRealtime()
        var resolutionMs: Long? = null
        try {
            val file = dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.resolveFile(java.io.File(modelPath))
            resolutionMs = SystemClock.elapsedRealtime() - resolutionStarted
            inspectionCache.inspect(file, spec) { activeContext.ensureActive() }
        } finally {
            val worker = activeContext[RuntimeWorkerContext]
            inspectionReceipt = inspectionCache.latestReceipt?.takeIf { it.inspectionId != baselineInspectionId }?.copy(runtimeRequestId = worker?.runtimeRequestId, workerGenerationId = worker?.workerGenerationId, fileResolutionMs = resolutionMs)
            inspectionReceipt?.let {
                dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.record(
                    "MODEL_INSPECTION_FINISHED",
                    mapOf(
                        "inspectionId" to it.inspectionId, "runtimeRequestId" to (it.runtimeRequestId ?: "unknown"),
                        "workerGenerationId" to (it.workerGenerationId ?: "unknown"), "outcome" to it.outcome,
                        "cache" to it.metadataCacheDisposition, "digestCache" to it.digestCacheDisposition,
                        "fileValidationMs" to it.fileValidationMs.toString(), "digestValidationMs" to it.digestValidationMs.toString(),
                        "modelBytesHashed" to it.modelBytesHashed.toString(), "opaqueMetadataMs" to it.opaqueMetadataMs.toString(),
                        "tokenizerMetadataMs" to "unknown", "totalMs" to it.totalMs.toString()
                    )
                )
            }
        }
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) = withContext(nativeOwner) {
        check(isInferenceProcess()) { "Verification reset requires the isolated worker" }
        operationJournal.allowVerification(RuntimeEvidenceIdentity.key(context, spec).toString())
        inspectionCache.allowVerification(dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.resolveFile(java.io.File(spec.modelPath))) {
            operationJournal.allowVerification(it)
        }
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        withContext(nativeOwner) {
            check(!lifecycleFailed) { "Native lifecycle is quarantined until this worker restarts." }
            require(spec.modelPath.endsWith(".litertlm", ignoreCase = true)) {
                "This runtime requires a LiteRT-LM (.litertlm) model package"
            }
            val nativeModel = dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.resolveFile(java.io.File(spec.modelPath))
            // Pre-flight model integrity verification to avoid native hard crashes (SIGSEGV)
            run {
                dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.validateInstalledCached(
                    nativeModel,
                    java.io.File(context.noBackupFilesDir, "model-identity-cache")
                )
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("LocalModel", "MODEL_INTEGRITY · status=${dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.trustStatus(nativeModel)} · accelerator=${spec.accelerator}")
            when (val validation = LocalModelValidator.validate(nativeModel.path)) {
                is ModelValidationResult.Invalid -> {
                    Log.e(TAG, "Model validation failed for path '${spec.modelPath}': ${validation.reason} (${validation.details})")
                    when (validation.reason) {
                        ModelValidationResult.Invalid.Reason.NOT_FOUND -> {
                            throw FileNotFoundException("Model file not found at path: ${spec.modelPath}")
                        }
                        ModelValidationResult.Invalid.Reason.FILE_TOO_SMALL -> {
                            throw IllegalStateException("Model file is truncated or incomplete: ${validation.details}")
                        }
                        else -> {
                            throw IllegalStateException("Model file validation failed (${validation.reason}): ${validation.details}")
                        }
                    }
                }
                is ModelValidationResult.Valid -> {
                    Log.i(TAG, "Model header/size validated: ${validation.file.name} (${validation.sizeBytes} bytes)")
                }
            }

            require(spec.maxTokens > 0) { "Local context size must be positive" }
            // Fallback belongs to the router/adapter so it can honor settings and report
            // the real accelerator. Never silently mutate the caller's context budget.
            val inspected = inspectModel(spec)
            inspected?.validate(spec)
            unloadEngine()
            val evidenceIdentity = RuntimeEvidenceIdentity.key(context, spec)
            val identity = evidenceIdentity.toString()
            val loadStartedAt = SystemClock.elapsedRealtime()
            val engineInstanceId = "litert-app:" + java.util.UUID.randomUUID().toString()
            val cacheIdentity = if (spec.cacheEnabled) RuntimeEvidenceIdentity.hash(identity) else "disabled"
            operationJournal.before(identity, "LOAD")
            var nextEngine: Engine? = null
            try {
                nextEngine = createEngine(
                    EngineConfig(
                        modelPath = nativeModel.path,
                        backend = backendFor(spec.accelerator, spec.litertDispatchLibDir, spec.cpuThreads),
                        visionBackend = visionBackendFor(spec, spec.litertDispatchLibDir),
                        audioBackend = null,
                        maxNumTokens = spec.maxTokens,
                        maxNumImages = if (spec.isVisionEnabled) MAX_IMAGES_PER_MESSAGE else null,
                        cacheDir = if (spec.cacheEnabled) context.cacheDir.resolve("litert-lm/" + cacheIdentity).apply { mkdirs() }.absolutePath else ":nocache"
                    )
                )
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("LocalModel", "NATIVE_ENGINE_INITIALIZE_BEGIN · accelerator=${spec.accelerator} · maxContext=${spec.maxTokens} · vision=${spec.isVisionEnabled}")
                LiteRtExperimentalSettings.engine(spec) { nextEngine.initialize() }
                nativeIdentity = identity
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("LocalModel", "NATIVE_ENGINE_INITIALIZE_SUCCEEDED · accelerator=${spec.accelerator} · executionQualified=false")
                // JNI initialization is blocking. Release the result if its caller was
                // cancelled while native code was working.
                coroutineContext.ensureActive()
                engine = nextEngine
                loadedAccelerator = LocalAccelerators.normalize(spec.accelerator)
                loadedSpec = spec
                capabilities = inspected
                engineReceipt = EngineLoadReceipt(
                    receiptId = java.util.UUID.randomUUID().toString(),
                    artifactDigest = evidenceIdentity.artifactDigest,
                    runtimeRevision = evidenceIdentity.runtimeIdentity,
                    requestedBackend = spec.accelerator,
                    actualBackend = null,
                    engineInstanceId = engineInstanceId,
                    engineInstanceIdentitySource = "app-owned LiteRT Engine object; SDK native identity unavailable",
                    requestedContextTokens = spec.maxTokens,
                    configurationSource = "SDK configuration submitted; native effective settings unavailable",
                    loadDeadlinePolicy = GgufLoadDeadline.policy.takeIf { coroutineContext[RuntimeWorkerContext] != null },
                    startedAtElapsedMs = loadStartedAt,
                    completedAtElapsedMs = SystemClock.elapsedRealtime(),
                    outcome = "initialized"
                )
            } catch (error: Throwable) {
                nextEngine?.let { if (it.isInitialized()) runCatching { it.close() } }
                throw error
            } finally {
                operationJournal.finished()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun createConversation(config: LocalConversationConfig) {
        withContext(nativeOwner) {
            check(!lifecycleFailed) { "Native lifecycle is quarantined until this worker restarts." }
            val currentEngine = engine ?: error("LiteRT-LM engine is not loaded")
            val structured = config.finalResponseSchemaJson != null
            val appliedThinking = if (structured) false else config.thinkingEnabled
            if (structured) {
                require(loadedSpec?.speculativeDecoding == false) {
                    "Structured output requires speculative decoding disabled before engine initialization"
                }
                require(
                    config.structuredOutputMode != LocalStructuredOutputMode.PROMPT_JSON_VERIFIED_V1 ||
                        config.structuredOutputProbe ||
                        !config.structuredOutputCompatibilityKey.isNullOrBlank()
                ) {
                    "Prompt JSON mode requires a successful exact-configuration compatibility probe"
                }
            }
            capabilities?.let { supported ->
                require(config.tools.isEmpty() || supported.tools != false) { "This export does not support native tool calling. Use chat only or choose a tools-capable artifact." }
                require(appliedThinking != true || supported.thinking != false) { "This export does not support thinking mode." }
            }
            require(config.maxOutputTokens == null || config.maxOutputTokens in 1 until (loadedSpec?.maxTokens ?: Int.MAX_VALUE)) {
                "The output budget must leave room for the prompt within this export's context."
            }
            closeConversation()
            yield() // Cooperative yield checkpoint before creating conversation and allocating KV-cache
            val toolProviders = config.tools.map { descriptor ->
                tool(
                    BridgedOpenApiTool(descriptor, config.toolExecutor, beforeTool = {
                        val barrier = CompletableDeferred<Unit>()
                        check(activeBuffer?.offer(LocalRuntimeEvent.Drain(barrier)) == true) { "Native stream stopped before tool dispatch" }
                        barrier.await()
                    }) { activeRequestJob }
                )
            }
            val requestedMtp = if (structured) false else config.speculativeDecoding ?: loadedSpec?.speculativeDecoding
            require(requestedMtp != true || capabilities?.speculative == true) { "This artifact has no verified drafter support." }
            val responseFormat = config.finalResponseSchemaJson?.let { schema ->
                require(schema.toByteArray().size <= 32 * 1024) { "Final-response schema exceeds 32 KiB" }
                require(kotlinx.serialization.json.Json.parseToJsonElement(schema) is kotlinx.serialization.json.JsonObject)
                if (config.structuredOutputMode == LocalStructuredOutputMode.NATIVE_JSON_SAFE_V1) ResponseFormat.json(schema) else null
            }
            conversationMtp = requestedMtp
            nativeIdentity?.let { operationJournal.before(it, "OPEN") }
            var nextConversation: Conversation? = null
            try {
                nextConversation = LiteRtExperimentalSettings.conversation(config.isConstrainedDecodingEnabled) {
                    currentEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = config.systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                            initialMessages = config.initialMessages.map { message ->
                                when (message.role) {
                                    LocalHistoryRole.USER -> Message.user(contentsOf(message.text, message.images))
                                    LocalHistoryRole.MODEL -> Message.model(contentsOf(message.text, message.images))
                                }
                            },
                            tools = toolProviders,
                            enableResponseFormat = responseFormat != null,
                            maxOutputToken = config.maxOutputTokens,
                            enableSpeculativeDecoding = requestedMtp,
                            thinkingConfig = appliedThinking?.let { ThinkingConfig(enableThinking = it) },
                            samplerConfig = if (LocalAccelerators.shouldApplySampler(loadedAccelerator)) {
                                SamplerConfig(
                                    topK = config.sampler.topK,
                                    topP = config.sampler.topP.toDouble(),
                                    temperature = config.sampler.temperature.toDouble()
                                )
                            } else {
                                null
                            }
                        )
                    )
                }
                coroutineContext.ensureActive()
                conversation = nextConversation
                conversationResponseFormat = responseFormat
                conversationConfig = config
                conversationThinking = appliedThinking
                conversationInstanceId = "litert-conversation:" + java.util.UUID.randomUUID().toString()
            } catch (error: Throwable) {
                nextConversation?.let { runCatching { it.close() } }
                conversation = null
                throw error
            } finally {
                operationJournal.finished()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = channelFlow {
        awaitNativeCompletion()
        val activeConversation = conversation
        if (activeConversation == null) {
            send(LocalRuntimeEvent.Error("LiteRT-LM conversation is not ready"))
            return@channelFlow
        }
        val buffer = LocalEventBuffer()
        activeBuffer = buffer
        nativeIdentity?.let { operationJournal.before(it, "PREFILL_DECODE") }
        val completion = CompletableDeferred<Unit>()
        nativeCompletion = completion
        val requestJob = coroutineContext[Job]
        activeRequestJob = requestJob
        val startTimeMs = SystemClock.elapsedRealtime()
        val firstTokenTimeMs = AtomicLong(0L)
        val firstVisibleTimeMs = AtomicLong(0L)
        val segmentId = java.util.UUID.randomUUID().toString()
        val configured = conversationConfig
        val schemaCapture = configured?.finalResponseSchemaJson?.let(::LocalStructuredOutputCapture)
        val schemaDigest = configured?.finalResponseSchemaJson?.let(RuntimeEvidenceIdentity::hash)
        val generation = GenerationConfigReceipt(
            generationId = segmentId,
            nativeOperationId = null,
            engineInstanceId = engineReceipt?.engineInstanceId,
            requestedSampler = configured?.sampler,
            requestedOutputTokens = configured?.maxOutputTokens,
            appliedSampler = configured?.sampler?.takeIf { LocalAccelerators.shouldApplySampler(loadedAccelerator) },
            appliedOutputTokens = configured?.maxOutputTokens,
            requestedResponseSchemaDigest = schemaDigest,
            appliedResponseSchemaDigest = schemaDigest.takeIf { conversationResponseFormat != null },
            structuredOutputMode = configured?.structuredOutputMode?.name?.takeIf { schemaDigest != null },
            structuredOutputCompatibilityKey = configured?.structuredOutputCompatibilityKey,
            requestedThinking = configured?.thinkingEnabled,
            appliedThinking = conversationThinking,
            requestedSpeculativeDecoding = configured?.speculativeDecoding,
            appliedSpeculativeDecoding = conversationMtp,
            configurationSource = "SDK_CONFIGURATION_NOT_NATIVE_ACKNOWLEDGMENT",
            observedAtElapsedMs = startTimeMs
        )
        executionReceipt.set(
            RuntimeExecutionReceipt(
                nativeGenerationId = segmentId,
                engineInstanceId = engineReceipt?.engineInstanceId,
                generationIdentitySource = "app-assigned SDK request; SDK native generation identity unavailable",
                adapterInvokedAtElapsedMs = startTimeMs,
                configuration = generation,
                engineLoad = engineReceipt,
                observedAtElapsedMs = startTimeMs
            )
        )
        val chunkCount = AtomicInteger(0)
        val totalCharacters = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val overflow = AtomicBoolean(false)
        fun offer(event: LocalRuntimeEvent) {
            if (!buffer.offer(event) && overflow.compareAndSet(false, true) && !completion.isCompleted) {
                launch(Dispatchers.IO) { runCatching { activeConversation.cancelProcess() } }
            }
        }
        fun offerText(value: String, thinking: Boolean) {
            for (chunk in value.unicodeChunks(4096)) {
                offer(if (thinking) LocalRuntimeEvent.ThinkingDelta(chunk) else LocalRuntimeEvent.TextDelta(chunk))
            }
        }
        offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
        try {
            nativeScope.launch {
                var enteredNative = false
                try {
                    if (requestJob?.isActive != true) throw kotlinx.coroutines.CancellationException("Local request cancelled before native start")
                    enteredNative = true
                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (finished.get() || overflow.get()) return
                            val now = SystemClock.elapsedRealtime()
                            val visible = message.visibleText()
                            val thought = message.channels[THOUGHT_CHANNEL].orEmpty()
                            if ((visible.isNotEmpty() || thought.isNotEmpty()) && firstTokenTimeMs.compareAndSet(0L, now)) {
                                offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING))
                            }
                            if (thought.isNotEmpty()) offerText(thought, true)
                            if (visible.isNotEmpty()) {
                                firstVisibleTimeMs.compareAndSet(0L, now)
                                schemaCapture?.append(visible)
                                executionReceipt.updateAndGet { it?.copy(firstVisibleTextAtElapsedMs = it.firstVisibleTextAtElapsedMs ?: now, observedAtElapsedMs = now) }
                                chunkCount.incrementAndGet()
                                totalCharacters.addAndGet(visible.length)
                                offerText(visible, false)
                            }
                        }

                        private fun finish(error: Throwable?) {
                            if (!finished.compareAndSet(false, true)) return
                            nativeScope.launch {
                                try {
                                    val end = SystemClock.elapsedRealtime()
                                    val duration = end - startTimeMs
                                    val chars = totalCharacters.get()
                                    val tokens = (chars / 4).coerceAtLeast(chunkCount.get())
                                    val native = if (loadedSpec?.nativeMetricsEnabled == true) {
                                        runCatching {
                                            val info = activeConversation.getBenchmarkInfo()
                                            NativeInferenceMetrics(info.lastPrefillTokenCount, info.lastDecodeTokenCount, info.lastPrefillTokensPerSecond, info.lastDecodeTokensPerSecond).takeIf { it.hasValidTokenCounts }
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                                    val failure = error?.let(::structuredNativeFailure) ?: schemaCapture?.failure()
                                    val terminalKind = when {
                                        error != null -> "native_error"
                                        failure != null -> "structured_output_rejected"
                                        else -> "sdk_done"
                                    }
                                    val receipt = executionReceipt.updateAndGet {
                                        it?.copy(
                                            nativeEntered = native?.decodeTokens?.takeIf { it > 0 }?.let { true },
                                            nativeReturned = true,
                                            nativeReturnedAtElapsedMs = end,
                                            terminalKind = terminalKind,
                                            promptTokens = native?.prefillTokens?.toLong(),
                                            sampledTokens = native?.decodeTokens?.toLong(),
                                            observedAtElapsedMs = end
                                        )
                                    }
                                    offer(
                                        LocalRuntimeEvent.Metrics(
                                            LocalInferenceMetrics(
                                                timeToFirstTokenMs = firstTokenTimeMs.get().takeIf { it > 0 }?.minus(startTimeMs) ?: duration,
                                                totalDurationMs = duration,
                                                totalChunks = chunkCount.get(),
                                                totalCharacters = chars,
                                                estimatedTokens = tokens,
                                                tokensPerSecond = if (duration > 0) tokens * 1000.0 / duration else 0.0,
                                                native = native,
                                                metricSource = if (native != null) "native_segment" else "estimated_characters",
                                                firstVisibleAnswerMs = firstVisibleTimeMs.get().takeIf { it > 0 }?.minus(startTimeMs),
                                                decodeDurationMs = firstTokenTimeMs.get().takeIf { it > 0 }?.let { end - it },
                                                segmentId = segmentId,
                                                requestedMtp = conversationMtp,
                                                finishReason = terminalKind,
                                                generationConfigReceipt = generation,
                                                runtimeExecutionReceipt = receipt
                                            )
                                        )
                                    )
                                    acknowledgeNativeTerminal(completion) {
                                        offer(if (failure == null) LocalRuntimeEvent.Done else LocalRuntimeEvent.Error(failure.message ?: "Local inference failed", failure))
                                    }
                                } finally {
                                    completion.complete(Unit)
                                }
                            }
                        }

                        override fun onDone() = finish(null)
                        override fun onError(throwable: Throwable) = finish(throwable)
                    }
                    val responseFormat = conversationResponseFormat
                    if (configured?.finalResponseSchemaJson == null) {
                        activeConversation.sendMessageAsync(contentsOf(text, images), callback)
                    } else {
                        activeConversation.sendMessageAsync(contentsOf(text, images), callback, thinkingConfig = ThinkingConfig(enableThinking = false), responseFormat = responseFormat)
                    }
                } catch (failure: Throwable) {
                    if (!enteredNative && finished.compareAndSet(false, true)) {
                        acknowledgeNativeTerminal(completion) { buffer.offer(LocalRuntimeEvent.Error("Local request cancelled before native start", failure)) }
                    } else {
                        // JNI may have started before throwing. Keep ownership until an actual
                        // native terminal callback or the existing isolated-worker quarantine.
                        buffer.offer(LocalRuntimeEvent.Error("Native start outcome is unknown; awaiting safe shutdown: ${safeQnnDetail(failure.message.orEmpty())}", structuredNativeFailure(failure)))
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "NATIVE_START_OUTCOME_UNKNOWN · cause=${failure.javaClass.simpleName}", "E")
                    }
                }
            }
            while (true) {
                val event = buffer.take() ?: break
                if (event is LocalRuntimeEvent.Error && event.cause is CancellationException) throw event.cause
                send(event)
            }
        } finally {
            withContext(NonCancellable) {
                if (!completion.isCompleted) {
                    withContext(Dispatchers.IO) { runCatching { activeConversation.cancelProcess() } }
                    if (withTimeoutOrNull(5000) {
                            completion.await()
                            true
                        } != true
                    ) {
                        throw NativeCancellationTimeoutException()
                    }
                }
                operationJournal.finished()
                if (activeRequestJob === requestJob) {
                    activeRequestJob = null
                    activeBuffer = null
                }
            }
        }
    }.transform { event ->
        // Previous downstream emits have returned before a tool is allowed to cross IPC.
        if (event is LocalRuntimeEvent.Drain) event.acknowledged.complete(Unit) else emit(event)
    }

    override fun cancelActive() {
        activeRequestJob?.cancel()
        // Native cancellation runs on the request's IO cleanup path, never on the UI thread.
    }

    private suspend fun awaitNativeCompletion() {
        val completion = nativeCompletion ?: return
        if (withTimeoutOrNull(5000) {
                completion.await()
                true
            } != true
        ) {
            throw NativeCancellationTimeoutException()
        }
    }

    override fun hasOpenConversation(): Boolean = conversation != null

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean = withContext(nativeOwner) {
        !lifecycleFailed && loadedSpec?.engineConfiguration() == spec.engineConfiguration() && engine?.isInitialized() == true
    }

    override suspend fun closeConversation() {
        awaitNativeCompletion()
        withContext(nativeOwner) {
            val closing = conversation
            conversation = null
            conversationInstanceId = null
            conversationConfig = null
            conversationResponseFormat = null
            conversationThinking = null
            try {
                closing?.close()
            } catch (failure: Throwable) {
                cleanupFailed(failure)
            }
        }
    }

    override suspend fun unloadEngine() {
        awaitNativeCompletion()
        withContext(nativeOwner) {
            val closing = conversation
            conversation = null
            conversationInstanceId = null
            conversationConfig = null
            conversationResponseFormat = null
            conversationThinking = null
            try {
                closing?.close()
            } catch (failure: Throwable) {
                cleanupFailed(failure)
            }
            val closingEngine = engine
            engine = null
            try {
                closingEngine?.close()
            } catch (failure: Throwable) {
                cleanupFailed(failure)
            }
            loadedSpec = null
            capabilities = null
            engineReceipt = null
            loadedAccelerator = LocalAccelerators.CPU
        }
    }

    private fun cleanupFailed(failure: Throwable): Nothing {
        lifecycleFailed = true
        loadedSpec = null
        capabilities = null
        nativeIdentity?.let { runCatching { operationJournal.quarantine(it) } }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "NATIVE_CLEANUP_FAILED · cause=${failure.javaClass.simpleName} · reuseAllowed=false", "E")
        throw NativeResourceCleanupException(failure)
    }

    private fun backendFor(accelerator: String, dispatchLibDir: String? = null, cpuThreads: Int? = null): Backend = when (LocalAccelerators.normalize(accelerator)) {
        LocalAccelerators.GPU -> Backend.GPU()
        LocalAccelerators.NPU -> {
            val probe = QnnEnvironment.getProbeStatus(context)
            check(probe.isReady) { probe.errorMessage ?: "NPU prerequisites are unavailable" }
            Backend.NPU(nativeLibraryDir = dispatchLibDir ?: probe.dispatchDir)
        }
        else -> Backend.CPU(threadCount = cpuThreads)
    }

    private fun visionBackendFor(spec: LocalEngineSpec, dispatchLibDir: String? = null): Backend? {
        if (!spec.isVisionEnabled) return null
        // Vision is a separate executor. Gemma 3n requires GPU vision even when
        // its language model uses CPU; NPU language execution does not imply NPU vision.
        return backendFor(spec.visionAccelerator, dispatchLibDir)
    }

    private fun contentsOf(text: String, images: List<ByteArray>): Contents {
        if (images.isEmpty()) return Contents.of(text)
        return Contents.of(
            buildList {
                images.forEach { image -> add(Content.ImageBytes(image)) }
                if (text.isNotBlank()) add(Content.Text(text))
            }
        )
    }

    private fun Message.visibleText(): String {
        val textList = contents.contents.filterIsInstance<Content.Text>()
        if (textList.isEmpty()) return ""
        if (textList.size == 1) return textList[0].text
        val totalLength = textList.sumOf { it.text.length }
        val sb = java.lang.StringBuilder(totalLength)
        for (item in textList) {
            sb.append(item.text)
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "LocalRuntimeImpl"
        const val THOUGHT_CHANNEL = "thought"
        const val MAX_IMAGES_PER_MESSAGE = 10
    }
}

internal class BridgedOpenApiTool(
    private val descriptor: LocalToolDescriptor,
    private val executor: LocalToolExecutor?,
    private val beforeTool: suspend () -> Unit = {},
    private val requestJob: () -> Job?
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", descriptor.name)
        put("description", descriptor.description)
        put("parameters", Json.parseToJsonElement(descriptor.inputSchemaJson))
    }.toString()

    override fun execute(paramsJsonString: String): String = runBlocking(
        checkNotNull(requestJob()) { "No active local inference request" }
    ) {
        val current = executor ?: error("LiteRT-LM tool executor is not registered")
        try {
            beforeTool()
            current.execute(descriptor.name, paramsJsonString)
        } catch (error: LocalToolLimitException) {
            throw error
        } catch (error: TimeoutCancellationException) {
            dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.encode(descriptor.name, dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error("native", "TIMEOUT_OUTCOME_UNKNOWN", "Native tool callback timed out; reconcile before repeating an action.", dispatched = true))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.encode(descriptor.name, dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error("native", "PROVIDER_UNAVAILABLE", "Native tool callback failed. Check connection diagnostics.", dispatched = true))
        }
    }
}
