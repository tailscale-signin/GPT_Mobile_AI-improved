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
    private val operationJournal by lazy { NativeExitEvidence.journal(context, java.io.File(context.noBackupFilesDir, "local-native-operations")) }
    private var nativeIdentity: String? = null

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

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = withContext(Dispatchers.IO) {
        val identity = "inspection|" + RuntimeEvidenceIdentity.key(context, LocalEngineSpec(modelPath, "cpu", 1)).toString()
        operationJournal.before(identity, "INSPECT")
        try {
            inspectMetadata(modelPath)
        } finally {
            operationJournal.finished()
        }
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) = withContext(Dispatchers.IO) {
        check(isInferenceProcess()) { "Verification reset requires the isolated worker" }
        operationJournal.allowVerification(RuntimeEvidenceIdentity.key(context, spec).toString())
        operationJournal.allowVerification("inspection|" + RuntimeEvidenceIdentity.key(context, LocalEngineSpec(spec.modelPath, "cpu", 1)).toString())
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        withContext(Dispatchers.IO) {
            require(spec.modelPath.endsWith(".litertlm", ignoreCase = true)) {
                "This runtime requires a LiteRT-LM (.litertlm) model package"
            }
            // Pre-flight model integrity verification to avoid native hard crashes (SIGSEGV)
            run {
                dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.validateInstalledCached(
                    java.io.File(spec.modelPath),
                    java.io.File(context.noBackupFilesDir, "model-identity-cache")
                )
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("LocalModel", "MODEL_INTEGRITY · status=${dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.trustStatus(java.io.File(spec.modelPath))} · accelerator=${spec.accelerator}")
            when (val validation = LocalModelValidator.validate(spec.modelPath)) {
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
            val inspected = inspectModel(spec.modelPath)
            inspected?.validate(spec)
            unloadEngine()
            val identity = RuntimeEvidenceIdentity.key(context, spec).toString()
            val cacheIdentity = if (spec.cacheEnabled) RuntimeEvidenceIdentity.hash(identity) else "disabled"
            operationJournal.before(identity, "LOAD")
            var nextEngine: Engine? = null
            try {
                nextEngine = createEngine(
                    EngineConfig(
                        modelPath = spec.modelPath,
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
        withContext(Dispatchers.IO) {
            val currentEngine = engine ?: error("LiteRT-LM engine is not loaded")
            capabilities?.let { supported ->
                require(config.tools.isEmpty() || supported.tools != false) { "This export does not support native tool calling. Use chat only or choose a tools-capable artifact." }
                require(config.thinkingEnabled != true || supported.thinking != false) { "This export does not support thinking mode." }
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
            val requestedMtp = config.speculativeDecoding ?: loadedSpec?.speculativeDecoding
            require(requestedMtp != true || capabilities?.speculative == true) { "This artifact has no verified drafter support." }
            conversationMtp = requestedMtp
            nativeIdentity?.let { operationJournal.before(it, "OPEN") }
            try {
                conversation = LiteRtExperimentalSettings.conversation(config.isConstrainedDecodingEnabled) {
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
                            maxOutputToken = config.maxOutputTokens,
                            enableSpeculativeDecoding = requestedMtp,
                            thinkingConfig = config.thinkingEnabled?.let { ThinkingConfig(enableThinking = it) },
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
            } catch (error: Throwable) {
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
        val journalToken = nativeIdentity?.let { operationJournal.before(it, "PREFILL_DECODE") }
        val completion = CompletableDeferred<Unit>()
        nativeCompletion = completion
        val requestJob = coroutineContext[Job]
        activeRequestJob = requestJob
        val startTimeMs = SystemClock.elapsedRealtime()
        val firstTokenTimeMs = AtomicLong(0L)
        val firstVisibleTimeMs = AtomicLong(0L)
        val segmentId = java.util.UUID.randomUUID().toString()
        val chunkCount = AtomicInteger(0)
        val totalCharacters = AtomicInteger(0)
        val lifecycle = NativeRequestLifecycle()
        val overflow = AtomicBoolean(false)
        fun offer(event: LocalRuntimeEvent) {
            if (!buffer.offer(event) && overflow.compareAndSet(false, true)) {
                launch(Dispatchers.IO) { runCatching { activeConversation.cancelProcess() } }
            }
        }
        fun offerText(value: String, thinking: Boolean) {
            for (chunk in value.unicodeChunks(4096)) {
                offer(if (thinking) LocalRuntimeEvent.ThinkingDelta(chunk) else LocalRuntimeEvent.TextDelta(chunk))
            }
        }
        offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
        var startupFailure: Throwable? = null
        try {
            launch(Dispatchers.IO) {
                try {
                    val contents = contentsOf(text, images)
                    lifecycle.enteringNative()
                    activeConversation.sendMessageAsync(
                        contents,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                if (lifecycle.outcome != null || overflow.get()) return
                                val now = SystemClock.elapsedRealtime()
                                val visible = message.visibleText()
                                val thought = message.channels[THOUGHT_CHANNEL].orEmpty()
                                if ((visible.isNotEmpty() || thought.isNotEmpty()) && firstTokenTimeMs.compareAndSet(0L, now)) {
                                    offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING))
                                }
                                if (thought.isNotEmpty()) offerText(thought, true)
                                if (visible.isNotEmpty()) {
                                    firstVisibleTimeMs.compareAndSet(0L, now)
                                    chunkCount.incrementAndGet()
                                    totalCharacters.addAndGet(visible.length)
                                    offerText(visible, false)
                                }
                            }

                            override fun onDone() {
                                if (!lifecycle.terminal(NativeRequestLifecycle.Outcome.COMPLETED, acknowledged = true)) {
                                    if (lifecycle.outcome == NativeRequestLifecycle.Outcome.FAILED && lifecycle.failure === startupFailure && startupFailure != null) completion.complete(Unit)
                                    return
                                }
                                try {
                                    val end = SystemClock.elapsedRealtime()
                                    val duration = end - startTimeMs
                                    val chars = totalCharacters.get()
                                    val tokens = (chars / 4).coerceAtLeast(chunkCount.get())
                                    val native = if (loadedSpec?.nativeMetricsEnabled == true) {
                                        runCatching {
                                            val info = activeConversation.getBenchmarkInfo()
                                            NativeInferenceMetrics(info.lastPrefillTokenCount, info.lastDecodeTokenCount, info.lastPrefillTokensPerSecond, info.lastDecodeTokensPerSecond).takeIf { it.isValid }
                                        }.getOrNull()
                                    } else {
                                        null
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
                                                requestedMtp = conversationMtp
                                            )
                                        )
                                    )
                                    offer(LocalRuntimeEvent.Done)
                                } finally {
                                    completion.complete(Unit)
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                if (!lifecycle.terminal(NativeRequestLifecycle.Outcome.FAILED, throwable, acknowledged = true)) {
                                    if (lifecycle.outcome == NativeRequestLifecycle.Outcome.FAILED && lifecycle.failure === startupFailure && startupFailure != null) completion.complete(Unit)
                                    return
                                }
                                buffer.offer(LocalRuntimeEvent.Error(throwable.message ?: "Local inference failed", throwable))
                                completion.complete(Unit)
                            }
                        }
                    )
                    lifecycle.active()
                } catch (failure: Throwable) {
                    // Entering JNI may have started native work even when startup throws.
                    // Deliver the root failure now, but retain the completion wait until a
                    // callback proves quiescence or the isolated worker is retired.
                    startupFailure = failure
                    val beforeNative = lifecycle.stage == NativeRequestLifecycle.Stage.PREPARING
                    if (lifecycle.terminal(NativeRequestLifecycle.Outcome.FAILED, failure, acknowledged = beforeNative)) {
                        buffer.offer(LocalRuntimeEvent.Error(failure.message ?: "Local model could not start this request", failure))
                    }
                    if (beforeNative) completion.complete(Unit)
                    if (failure is CancellationException) throw failure
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
                    lifecycle.cancelling()
                    withContext(Dispatchers.IO) { runCatching { activeConversation.cancelProcess() } }
                    if (withTimeoutOrNull(5000) {
                            completion.await()
                            true
                        } != true
                    ) {
                        throw NativeCancellationTimeoutException().also { timeout -> startupFailure?.let { timeout.initCause(it) } }
                    }
                }
                journalToken?.let { operationJournal.finished(it) }
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

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean = engine != null && loadedSpec?.engineConfiguration() == spec.engineConfiguration()

    override suspend fun closeConversation() {
        awaitNativeCompletion()
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            conversation = null
        }
    }

    override suspend fun unloadEngine() {
        awaitNativeCompletion()
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            conversation = null
            runCatching { engine?.close() }
            engine = null
            loadedSpec = null
            capabilities = null
            loadedAccelerator = LocalAccelerators.CPU
        }
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
