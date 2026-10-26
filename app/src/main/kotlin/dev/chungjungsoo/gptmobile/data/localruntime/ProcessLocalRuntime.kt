package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWireTransport
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.WireConversation
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Main-process facade: owns request identity and tool execution, never native pointers. */
class ProcessLocalRuntime(
    private val context: Context,
    private val settings: SettingRepository
) : LocalRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val readiness = LocalReadinessRegistry(java.io.File(context.noBackupFilesDir, "local-readiness-quarantine"))
    private val pending = ConcurrentHashMap<String, Pending>()
    private val thread = HandlerThread("local-runtime-client").apply { start() }
    private val incoming =
        Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(message: Message) {
                    runCatching {
                        receive(
                            RuntimeWireTransport.read(message)
                        )
                    }.onFailure { disconnect("Invalid inference response: ${it.message}") }
                }
            }
        )

    @Volatile private var remote: Messenger? = null

    @Volatile private var connection: ServiceConnection? = null

    @Volatile private var binding: CompletableDeferred<Messenger>? = null

    @Volatile private var quarantinedBinder: IBinder? = null

    @Volatile private var generation = ""

    @Volatile private var sessionId = ""

    @Volatile private var toolExecutor: LocalToolExecutor? = null
    private var allowedTools: Set<String> = emptySet()
    private val conversationPromptCharacters = java.util.concurrent.atomic.AtomicLong(0)
    private val deliveryReceipts = ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDeliveryReceipt>()
    private val toolCalls = ConcurrentHashMap<String, Boolean>()
    private var requestedSpec: LocalEngineSpec? = null
    private var tuning: LocalEngineTuning? = null
    private val mutableState = MutableStateFlow(LocalRuntimeState(processIsolated = true))
    override val state = mutableState.asStateFlow()
    override val handlesEngineFallback = true

    @Volatile private var cleanupQuarantine: String? = null
    private val execution =
        java.util.concurrent.atomic
            .AtomicReference<RuntimeExecutionReceipt?>(null)
    private val cleanup =
        java.util.concurrent.atomic
            .AtomicReference<RuntimeCleanupReceipt?>(null)

    @Volatile private var engineLoad: EngineLoadReceipt? = null

    @Volatile private var everBound = false

    @Volatile private var nativeReleaseAcknowledged = true

    @Volatile private var confirmedWorkerExit: RuntimeCleanupReceipt? = null

    @Volatile private var inspectionReceipt: RuntimeInspectionReceipt? = null
    override val latestInspectionReceipt get() = inspectionReceipt
    override val latestEngineLoadReceipt get() = engineLoad
    override val latestExecutionReceipt get() = execution.get()
    override val latestCleanupReceipt get() = cleanup.get()
    override val isBusy get() = pending.isNotEmpty() || quarantinedBinder?.isBinderAlive == true || cleanupQuarantine != null
    override val isNativeOperationBusy get() = isBusy
    override val executionIdentity: LocalRuntimeExecutionIdentity
        get() =
            LocalRuntimeExecutionIdentity(
                workerGenerationId = generation.takeIf { it.isNotBlank() },
                conversationId = sessionId.takeIf { it.isNotBlank() },
                engineInstanceId = state.value.engineLoadReceipt?.engineInstanceId.takeIf { state.value.engineSpec != null },
                driverIdentity = state.value.engineLoadReceipt?.driverIdentity,
                actualOffloadLayers = state.value.engineLoadReceipt?.actualOffloadLayers
            )

    @Volatile override var processPssBytes: Long? = null
        private set

    override val deviceRamGb: Long by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        (memory.totalMem / (1024L * 1024 * 1024)).coerceAtLeast(1)
    }

    override fun getHardwareState() = DeviceHardwareGovernor.inspectHardwareState(context)

    override fun loadedEngineSpec() = state.value.engineSpec

    private suspend fun nativeRouteIdentity(
        modelPath: String,
        accelerator: String?
    ): String =
        "native-route:" +
            RuntimeEvidenceIdentity.key(context, LocalEngineSpec(modelPath, LocalAccelerators.normalize(accelerator), 1)).toString()

    override suspend fun preflightModel(
        modelPath: String,
        accelerator: String?
    ) {
        requireSafeAdmission()
        readiness.assertNativeAllowed(nativeRouteIdentity(modelPath, accelerator))
    }

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = inspectRemote(modelPath, null)
    override suspend fun inspectModel(spec: LocalEngineSpec): LocalModelCapabilities? = inspectRemote(spec.modelPath, spec)

    private suspend fun inspectRemote(modelPath: String, spec: LocalEngineSpec?): LocalModelCapabilities? {
        val started = SystemClock.elapsedRealtime()
        val packet = RuntimeWire("INSPECT", UUID.randomUUID().toString(), sessionId, generation, text = modelPath, spec = spec)
        try {
            return command(packet).capabilities
        } finally {
            inspectionReceipt?.takeIf { it.runtimeRequestId == packet.requestId }?.let {
                inspectionReceipt = it.copy(transportRoundTripMs = SystemClock.elapsedRealtime() - started)
            }
        }
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val identity = readinessIdentity(spec, settings.getFeatureSettings())
        // Explicit Verify owns the existing outer lease, closes this exact worker,
        // and waits for positive exit evidence before a fresh isolated probe.
        val prepared = cleanupAfterRequest("explicit_verify")
        check(prepared.safeForSuccessor) { "Verification is waiting for confirmed native cleanup" }
        if (remote != null || quarantinedBinder != null) {
            check(terminateAndAwaitExit("explicit_verify", 5_000)) { "Verification is waiting for the old worker to exit" }
        }
        val snapshot = settings.getFeatureSettings()
        val configured = snapshot.localEngineTuning()
        val target =
            if (spec.tuningLocked) {
                spec
            } else {
                spec.copy(
                    cpuThreads = configured.cpuThreads,
                    ggufBatchTokens = configured.ggufBatchTokens,
                    ggufMicroBatchTokens = minOf(configured.ggufMicroBatchTokens, configured.ggufBatchTokens),
                    ggufPromptThreads = configured.ggufPromptThreads,
                    cacheEnabled = configured.cacheEnabled,
                    speculativeDecoding = spec.speculativeDecoding ?: configured.speculativeDecoding,
                    nativeMetricsEnabled = spec.nativeMetricsEnabled || configured.nativeMetricsEnabled
                )
            }
        command(RuntimeWire("VERIFY_RESET", UUID.randomUUID().toString(), generation = "", spec = target))
        readiness.reset(identity)
        readiness.reset(nativeRouteIdentity(spec.modelPath, spec.accelerator))
    }

    private suspend fun readinessIdentity(
        spec: LocalEngineSpec,
        snapshot: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
    ): String {
        val tuning = snapshot.localEngineTuning()
        val effective =
            if (spec.tuningLocked) {
                spec
            } else {
                spec.copy(
                    cpuThreads = tuning.cpuThreads,
                    ggufBatchTokens = tuning.ggufBatchTokens,
                    ggufMicroBatchTokens = minOf(tuning.ggufMicroBatchTokens, tuning.ggufBatchTokens),
                    ggufPromptThreads = tuning.ggufPromptThreads,
                    cacheEnabled = tuning.cacheEnabled,
                    speculativeDecoding = spec.speculativeDecoding ?: tuning.speculativeDecoding
                )
            }
        return RuntimeEvidenceIdentity.key(context, effective, effective.samplerIdentity, effective.workload).toString()
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        preflightModel(spec.modelPath, spec.accelerator)
        val snapshot = settings.getFeatureSettings()
        val packet =
            RuntimeWire(
                "LOAD",
                UUID.randomUUID().toString(),
                generation = generation,
                spec = spec,
                settings = snapshot,
                preferredBackend = settings.getLocalRuntimeBackend()
            )
        val artifact = java.io.File(spec.modelPath)
        check(artifact.isFile || artifact.isDirectory) { "MISSING_ASSET: Download or relink the selected model before running it." }
        val identity = readinessIdentity(spec, snapshot)
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        try {
            mutableState.value = state.value.copy(lifecycle = "LOADING")
            readiness.qualify(identity) {
                if (!isEngineLoaded(spec)) {
                    try {
                        command(packet, timeoutMs = RuntimeProcessDeadlinePolicy.loadCommandMs)
                    } catch (failure: GgufInitializationTimeoutException) {
                        // No generation has begun. The old binder must be dead before
                        // a single CPU recovery load can enter a fresh worker.
                        if (!failure.message.orEmpty().contains("backend=gpu") &&
                            !failure.message.orEmpty().contains("backend=opencl")
                        ) {
                            throw failure
                        }
                        check(remote == null && quarantinedBinder?.isBinderAlive != true) { "Waiting for the timed-out GPU worker to exit" }
                        AppLogRecorder.record(
                            "LocalModel",
                            "GGUF_FRESH_WORKER_CPU_RECOVERY · trigger=${failure.message} · previousWorkerStopped=true · generationDispatched=false",
                            "W"
                        )
                        command(
                            packet.copy(
                                requestId = UUID.randomUUID().toString(),
                                generation = "",
                                spec = spec.copy(accelerator = LocalAccelerators.CPU, ggufGpuLayers = 0)
                            ),
                            timeoutMs = RuntimeProcessDeadlinePolicy.loadCommandMs
                        )
                        mutableState.value =
                            state.value.copy(fallbackReason = "Running on CPU after GPU initialization timed out in the previous worker")
                    }
                }
            }
        } catch (error: Exception) {
            if (error !is kotlinx.coroutines.CancellationException &&
                error.message.orEmpty().contains("interrupted inside native code", true)
            ) {
                readiness.rememberNativeInterruption(nativeRouteIdentity(spec.modelPath, spec.accelerator))
            }
            if (error !is kotlinx.coroutines.CancellationException && LocalReadinessRegistry.deterministicFailure(error)) {
                // One unsupported tensor is enough to disqualify this artifact/backend;
                // tuning tweaks must not repeatedly crash the native worker.
                readiness.rememberIncompatibleRoute(nativeRouteIdentity(spec.modelPath, spec.accelerator))
                AppLogRecorder.record(
                    "LocalModel",
                    "INCOMPATIBLE_ROUTE_QUARANTINED · accelerator=${spec.accelerator} · nativeDispatched=false_for_future_attempts",
                    "W"
                )
            }
            mutableState.value = state.value.copy(lifecycle = "FAILED", fallbackReason = error.message)
            throw error
        }
        requestedSpec = spec
        tuning = snapshot.localEngineTuning()
        // Profile-compatibleType is not native execution evidence. Record the worker's
        // reported backend and actual admitted accelerator after LOAD succeeds.
        val native = state.value
        AppLogRecorder.record(
            "LocalModel",
            "ENGINE_READY · profileBackendPreference=${native.preferredBackend ?: settings.getLocalRuntimeBackend()} · actualBackend=${native.backend ?: "UNKNOWN"} · artifactRuntime=${if (spec.modelPath
                    .endsWith(
                        "manifest.json"
                    )
            ) {
                runCatching {
                    dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
                        .read(
                            artifact
                        ).runtime.name
                }.getOrDefault(
                    "MANIFEST_RUNTIME_UNREADABLE"
                )
            } else {
                "LITERT_MODEL"
            }} · requestedAccelerator=${spec.accelerator} · actualAccelerator=${native.engineSpec?.accelerator ?: "UNKNOWN"} · loadMs=${SystemClock.elapsedRealtime() - loadStartedAtMs} · model=${artifact.name} · workerPssBytes=${processPssBytes ?: -1} · processIsolated=${native.processIsolated}"
        )
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        sessionId = UUID.randomUUID().toString()
        toolExecutor = config.toolExecutor
        allowedTools = config.tools.map { it.name }.toSet()
        conversationPromptCharacters.set(
            (
                config.systemPrompt.orEmpty().length.toLong() +
                    config.initialMessages.sumOf { it.text.length.toLong() } +
                    config.tools.sumOf { it.name.length.toLong() + it.description.length + it.inputSchemaJson.length } +
                    config.finalResponseSchemaJson.orEmpty().length
                ).coerceAtMost(Int.MAX_VALUE.toLong())
        )
        toolCalls.clear()
        deliveryReceipts.clear()
        try {
            command(RuntimeWire("OPEN", UUID.randomUUID().toString(), sessionId, generation, conversation = WireConversation.from(config)))
        } catch (error: Throwable) {
            sessionId = ""
            toolExecutor = null
            throw error
        }
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
        flow {
            require(safetyTokens >= 0)
            requireSafeAdmission()
            check(sessionId.isNotBlank()) { "Local conversation is not open" }
            require(
                images.size <= 10 && images.sumOf { it.size.toLong() } <= 8L * 1024 * 1024
            ) { "Attached images exceed the local memory budget" }
            val physicalAttempt =
                kotlinx.coroutines
                    .currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]
                    ?.receipts
                    ?.lastOrNull()
                    ?.physicalAttemptId
            val packet =
                RuntimeWire(
                    "GENERATE",
                    UUID.randomUUID().toString(),
                    sessionId,
                    generation,
                    text = text,
                    images = images,
                    safetyTokens = safetyTokens,
                    physicalAttemptId = physicalAttempt,
                    experiment = kotlinx.coroutines.currentCoroutineContext()[LocalRuntimeTrialContext]?.trial
                )
            val request = Pending(packet, LocalEventBuffer(), physicalAttempt)
            val startedAtMs = SystemClock.elapsedRealtime()
            val knownCharacters = addConversationCharacters(text.length)
            // A native tool continuation may add evidence after this command starts.
            // Use the admitted context as a conservative estimate for its fixed prefill budget.
            val toolContextCharacters = if (allowedTools.isEmpty()) 0L else (state.value.engineSpec?.maxTokens ?: 0).toLong() * 4L
            val promptCharacters = maxOf(knownCharacters, toolContextCharacters).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val deadlinePolicy = RuntimeProcessDeadlinePolicy.forGeneration(state.value.backend, promptCharacters)
            val deadline = ProcessGenerationDeadline(startedAtMs, deadlinePolicy)
            request.generationDeadline = deadline
            pending[packet.requestId] = request
            val initialReceipt =
                RuntimeExecutionReceipt(
                    runtimeRequestId = packet.requestId,
                    physicalAttemptId = request.physicalAttemptId,
                    workerGenerationId = generation.takeIf { it.isNotBlank() },
                    engineInstanceId = engineLoad?.engineInstanceId,
                    adapterInvokedAtElapsedMs = startedAtMs,
                    engineLoad = engineLoad,
                    processDeadlinePolicy = deadlinePolicy,
                    observedAtElapsedMs = startedAtMs
                )
            request.execution = initialReceipt
            execution.set(initialReceipt)
            try {
                withProcessGenerationWatchdog(
                    deadline = deadline,
                    terminalAcknowledged = { request.finished.isCompleted },
                    onDeadline = { failProcessDeadline(request, it) },
                    nowMs = SystemClock::elapsedRealtime
                ) {
                    send(packet)
                    // The structured child keeps supervising native work even if downstream
                    // output delivery suspends, or the event buffer has no new callbacks.
                    while (true) {
                        val event = request.events!!.take() ?: break
                        emit(event)
                    }
                    // A buffer can close before the command terminal (for example, overflow).
                    // Do not turn that path into an unbounded wait outside the generation monitor.
                    if (!request.finished.isCompleted &&
                        withTimeoutOrNull(minOf(deadline.remainingMs(SystemClock.elapsedRealtime()), deadlinePolicy.terminalDeliveryMs).coerceAtLeast(1)) {
                            request.finished.await()
                        } == null
                    ) {
                        failProcessDeadline(
                            request,
                            deadline.expiration(SystemClock.elapsedRealtime()) ?: "LOCAL_PROCESS_TERMINAL_DELIVERY_DEADLINE"
                        )
                    }
                }
            } finally {
                finishRequest(request)
            }
        }

    private fun addConversationCharacters(count: Int): Long =
        conversationPromptCharacters.updateAndGet { (it + count.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()) }

    private fun observeGenerationWork(request: Pending, event: LocalRuntimeEvent) {
        val now = SystemClock.elapsedRealtime()
        if (request.generationDeadline?.observe(event, now) == true) {
            AppLogRecorder.record(
                "LocalModel",
                "LOCAL_FIRST_OUTPUT_OR_SAMPLE · runtimeRequestId=${request.packet.requestId} · workerGenerationId=${request.execution?.workerGenerationId ?: "unknown"} · nativeOperationId=${request.execution?.nativeOperationId ?: "unknown"} · backend=${state.value.backend ?: "UNKNOWN"} · elapsedMs=${now - (request.execution?.adapterInvokedAtElapsedMs ?: now)}"
            )
        }
    }

    private fun failProcessDeadline(request: Pending, kind: String): Nothing {
        val now = SystemClock.elapsedRealtime()
        request.cancellationOrigin = "PROCESS_WATCHDOG"
        request.execution?.let {
            publishExecution(request, it.copy(deadlineKind = kind, cancellationOrigin = "PROCESS_WATCHDOG", observedAtElapsedMs = now))
        }
        AppLogRecorder.record(
            "LocalModel",
            "LOCAL_PROCESS_DEADLINE · runtimeRequestId=${request.packet.requestId} · workerGenerationId=${request.execution?.workerGenerationId ?: "unknown"} · nativeOperationId=${request.execution?.nativeOperationId ?: "unknown"} · kind=$kind · policy=${request.generationDeadline?.policy} · action=cancel_and_quarantine_if_unresponsive",
            "E"
        )
        throw LocalProcessDeadlineException(kind)
    }

    override fun cancelActive() {
        mutableState.value = state.value.copy(lifecycle = "CANCELLING", engineHealth = "RESTART_REQUIRED")
        pending.values.forEach { request ->
            request.cancelling = true
            request.toolJobs.values.forEach { it.cancel() }
            scope.launch { runCatching { sendCancel(request.packet, "EXPLICIT_CANCEL_ACTIVE") } }
        }
    }

    override suspend fun closeConversation() {
        if (remote != null && sessionId.isNotBlank()) execute("CLOSE")
        sessionId = ""
        toolExecutor = null
        allowedTools = emptySet()
        conversationPromptCharacters.set(0)
    }

    override suspend fun unloadEngine() {
        if (remote != null) execute("UNLOAD")
        requestedSpec = null
        sessionId = ""
        toolExecutor = null
        conversationPromptCharacters.set(0)
        unbind()
        mutableState.value = LocalRuntimeState(processIsolated = true)
    }

    override fun hasOpenConversation() = sessionId.isNotBlank() && remote != null

    override suspend fun isEngineLoaded(spec: LocalEngineSpec) =
        requestedSpec?.engineConfiguration() == spec.engineConfiguration() &&
            remote != null &&
            state.value.engineSpec != null &&
            state.value.engineHealth == "HEALTHY" &&
            (spec.tuningLocked || tuning == settings.getFeatureSettings().localEngineTuning())

    private suspend fun execute(
        operation: String,
        text: String = ""
    ): RuntimeWire = command(RuntimeWire(operation, UUID.randomUUID().toString(), sessionId, generation, text = text))

    private suspend fun command(
        packet: RuntimeWire,
        timeoutMs: Long = 60_000
    ): RuntimeWire {
        val request = Pending(packet)
        pending[packet.requestId] = request
        if (packet.operation == "LOAD") nativeReleaseAcknowledged = false
        try {
            send(packet)
            return withTimeout(timeoutMs) { request.finished.await() }.also {
                if (it.operation == "ERROR") {
                    if (it.errorCode ==
                        "AUTO_CONTEXT_REPLAN"
                    ) {
                        throw LocalContextReplanRequired(
                            checkNotNull(it.attemptedContextTokens),
                            checkNotNull(it.suggestedContextTokens),
                            it.state?.admissionReceipt
                        )
                    }
                    if (it.errorCode == "GGUF_INITIALIZATION_TIMEOUT") throw GgufInitializationTimeoutException(it.text)
                    if (it.errorCode == "MODEL_CONFIGURATION") throw LocalModelConfigurationException(it.text)
                    if (it.errorCode == "NPU_PACKAGE") throw LocalNpuPackageException.fromRemote(it.text)
                    if (it.loadAttempts.isNotEmpty()) throw LocalLoadFailure(it.loadAttempts)
                    throw IllegalStateException(it.text)
                }
                if (it.operation == "CANCELLED") throw kotlinx.coroutines.CancellationException(it.text)
            }
        } finally {
            finishRequest(request)
        }
    }

    private suspend fun finishRequest(request: Pending) =
        withContext(NonCancellable + Dispatchers.IO) {
            if (!request.finished.isCompleted) {
                request.cancelling = true
                request.toolJobs.values.forEach { it.cancel() }
                mutableState.value = state.value.copy(lifecycle = "CANCELLING", engineHealth = "RESTART_REQUIRED")
                runCatching { sendCancel(request.packet, "COROUTINE_OR_DEADLINE") }
                if (withTimeoutOrNull(12_000) {
                        runCatching { request.finished.await() }
                        true
                    } != true
                ) {
                    quarantinedBinder = remote?.binder
                    quarantineAfterUnconfirmedCleanup("Cancellation was not acknowledged by the owning worker")
                    terminateAndAwaitExit("cancellation_unacknowledged", 5_000)
                    recordTerminal(request, "CANCELLATION_UNACKNOWLEDGED", false)
                    disconnect("Inference worker did not acknowledge cancellation. Partial output is preserved; start a new request.")
                }
            }
            pending.remove(request.packet.requestId)
        }

    private suspend fun sendCancel(
        packet: RuntimeWire,
        origin: String = "TOOL_PROTOCOL_REJECTED"
    ) = withContext(Dispatchers.IO) {
        pending[packet.requestId]?.let { if (it.cancellationOrigin == null) it.cancellationOrigin = origin }
        remote?.let {
            RuntimeWireTransport.send(
                context,
                it,
                packet.copy(operation = "CANCEL", text = "", images = emptyList(), spec = null, conversation = null, settings = null),
                incoming
            )
        }
    }

    private suspend fun send(packet: RuntimeWire) =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val reused = remote?.binder?.isBinderAlive == true
            val target = connect()
            pending[packet.requestId]?.let { request ->
                if (request.serviceBindingMs == null) {
                    request.serviceBindingMs = SystemClock.elapsedRealtime() - started
                    request.serviceBindingDisposition = if (reused) "existing_live_binder" else "bind_service"
                }
            }
            RuntimeWireTransport.send(context, target, packet, incoming)
        }

    private suspend fun connect(): Messenger =
        bindMutex.withLock {
            requireSafeAdmission()
            remote?.let { return@withLock it }
            val result = CompletableDeferred<Messenger>()
            binding = result
            val next =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        binder: IBinder
                    ) {
                        synchronized(this@ProcessLocalRuntime) {
                            if (connection !== this) return
                            try {
                                binder.linkToDeath({
                                    observeWorkerExit(binder, "binder_death")
                                    if (connection ===
                                        this
                                    ) {
                                        disconnect(
                                            "Native inference worker stopped. Completed output is preserved; tools will not be repeated."
                                        )
                                    }
                                }, 0)
                                check(binder.isBinderAlive) { "Inference worker stopped while connecting" }
                                val messenger = Messenger(binder)
                                remote = messenger
                                everBound = true
                                confirmedWorkerExit = null
                                result.complete(messenger)
                            } catch (error: Exception) {
                                disconnect("Inference worker could not connect: ${error.message}")
                                result.completeExceptionally(error)
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        if (connection === this) {
                            dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.record(
                                "WORKER_CONNECTION_LOST",
                                mapOf(
                                    "workerGenerationId" to generation,
                                    "runtimeRequestId" to (execution.get()?.runtimeRequestId ?: "unknown"),
                                    "nativeOperationId" to (execution.get()?.nativeOperationId?.toString() ?: "unknown"),
                                    "confirmedExit" to "unknown",
                                    "reason" to
                                        "Inference service disconnected; Binder death or Android exit history is required to confirm process exit"
                                )
                            )
                            disconnect("Native inference worker disconnected")
                        }
                    }

                    override fun onBindingDied(name: ComponentName) {
                        if (connection === this) disconnect("Inference binding expired")
                    }

                    override fun onNullBinding(name: ComponentName) {
                        result.completeExceptionally(IllegalStateException("Inference service unavailable"))
                    }
                }
            connection = next
            try {
                check(context.bindService(Intent(context, LocalInferenceService::class.java), next, Context.BIND_AUTO_CREATE)) {
                    "Could not bind inference service"
                }
                withTimeout(15_000) { result.await() }
            } catch (error: Throwable) {
                unbind()
                throw error
            }
        }

    private fun receive(packet: RuntimeWire) {
        val request = pending[packet.requestId] ?: return
        if (request.finished.isCompleted || packet.sessionId != request.packet.sessionId) return
        if (generation.isBlank()) generation = packet.generation
        if (packet.generation != generation) return
        check(packet.sequence == request.sequence + 1) { "Out-of-order local response" }
        request.sequence = packet.sequence
        packet.processPssBytes?.let { processPssBytes = it }
        packet.inspectionReceipt?.takeIf { it.runtimeRequestId == request.packet.requestId && it.workerGenerationId == packet.generation }?.let {
            inspectionReceipt = it.copy(serviceBindingMs = request.serviceBindingMs, serviceBindingDisposition = request.serviceBindingDisposition)
        }
        val reportedState = packet.state?.let { reported ->
            val load = reported.engineLoadReceipt?.let { received ->
                val priorPolicy = engineLoad?.takeIf { it.engineInstanceId == received.engineInstanceId }?.loadDeadlinePolicy
                received.copy(
                    workerGenerationId = packet.generation,
                    loadDeadlinePolicy = received.loadDeadlinePolicy?.let { policy ->
                        policy.copy(
                            supervisorAbsoluteMs = if (request.packet.operation == "LOAD") {
                                RuntimeProcessDeadlinePolicy.loadCommandMs
                            } else {
                                priorPolicy?.supervisorAbsoluteMs ?: policy.supervisorAbsoluteMs
                            }
                        )
                    }
                )
            }
            if (load != null) engineLoad = load
            reported.copy(engineLoadReceipt = load ?: reported.engineLoadReceipt).also { mutableState.value = it }
        }
        packet.executionReceipt?.takeIf { request.packet.operation == "GENERATE" }?.let { native ->
            val previous = request.execution
            publishExecution(
                request,
                native.copy(
                    runtimeRequestId = packet.requestId,
                    physicalAttemptId = request.physicalAttemptId,
                    workerGenerationId = packet.generation,
                    adapterInvokedAtElapsedMs = previous?.adapterInvokedAtElapsedMs ?: native.adapterInvokedAtElapsedMs,
                    firstVisibleTextAtElapsedMs = previous?.firstVisibleTextAtElapsedMs ?: native.firstVisibleTextAtElapsedMs,
                    engineLoad = engineLoad?.takeIf { it.engineInstanceId == native.engineLoad?.engineInstanceId }
                        ?: native.engineLoad?.copy(workerGenerationId = packet.generation) ?: engineLoad,
                    cancellationOrigin = request.cancellationOrigin ?: native.cancellationOrigin,
                    processDeadlinePolicy = request.generationDeadline?.policy ?: previous?.processDeadlinePolicy,
                    deadlineKind = previous?.deadlineKind ?: native.deadlineKind
                )
            )
        }
        when (packet.operation) {
            "TEXT", "THINKING" -> {
                val event = if (packet.operation == "TEXT") LocalRuntimeEvent.TextDelta(packet.text) else LocalRuntimeEvent.ThinkingDelta(packet.text)
                observeGenerationWork(request, event)
                addConversationCharacters(packet.text.length)
                request.execution?.let {
                    val visible = it.observingVisibleOutput(packet.text, packet.operation == "THINKING", SystemClock.elapsedRealtime())
                    if (visible !== it) publishExecution(request, visible)
                }
                val accepted = request.events?.offer(event) == true
                scope.launch {
                    if (!accepted) sendCancel(request.packet, "OUTPUT_BUFFER_FULL")
                    remote?.let { RuntimeWireTransport.send(context, it, packet.copy(operation = "ACK", text = ""), incoming) }
                }
            }
            "LOAD_PROGRESS" -> {
                val progress = packet.nativeProgress
                request.lastProgress = progress
                mutableState.value =
                    state.value.copy(
                        lifecycle = "LOADING",
                        loadProgress = progress,
                        allocationObservation =
                        progress?.allocationObservation ?: state.value.allocationObservation
                    )
                AppLogRecorder.record(
                    "LocalModel",
                    "GGUF_LOAD_PROGRESS · request=${packet.requestId} · stage=${progress?.stage} · fraction=${progress?.loadFraction} · elapsedMs=${progress?.elapsedMs} · observation=${progress?.allocationObservation}"
                )
            }
            "PHASE" -> {
                mutableState.value = state.value.copy(lifecycle = if (packet.text == "PREFILL") "PREFILLING" else "DECODING")
                packet.nativeProgress?.let { progress ->
                    AppLogRecorder.record(
                        "LocalModel",
                        "GGUF_NATIVE_PROGRESS · request=${packet.requestId} · session=${packet.sessionId} · generation=${packet.generation} · sequence=${packet.sequence} · operation=${progress.operationId} · stage=${progress.stage} · promptTokens=${progress.promptTokens} · processedTokens=${progress.processedTokens} · sampledTokens=${progress.sampledTokens} · elapsedMs=${progress.elapsedMs} · nativeCode=${progress.nativeCode} · observationMs=${progress.observedAtMs} · workerPssBytes=${processPssBytes ?: -1} · config=${progress.configuration.orEmpty()}"
                    )
                    if (progress.stage ==
                        "TIMEOUT_CANCEL_REQUESTED"
                    ) {
                        mutableState.value =
                            state.value.copy(lifecycle = "CANCELLING", engineHealth = "RESTART_REQUIRED")
                    }
                }
                val progress =
                    packet.nativeProgress
                        ?.copy(
                            runtimeRequestId = packet.requestId,
                            physicalAttemptId = request.physicalAttemptId
                        )?.retainingMeasurements(request.lastProgress)
                if (progress != null) {
                    request.lastProgress = progress
                    // Only the JNI's matching native-entry callback proves dispatch.
                    val previous =
                        request.execution
                            ?: RuntimeExecutionReceipt(runtimeRequestId = packet.requestId, physicalAttemptId = request.physicalAttemptId)
                    if (previous.nativeOperationId == null ||
                        previous.nativeOperationId == progress.operationId
                    ) {
                        publishExecution(request, previous.fromProgress(progress))
                    }
                }
                val event = LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.valueOf(packet.text), progress)
                observeGenerationWork(request, event)
                request.events?.offer(event)
            }
            "METRICS" ->
                packet.metrics?.let { metrics ->
                    request.events?.offer(
                        LocalRuntimeEvent.Metrics(
                            metrics.copy(
                                runtimeExecutionReceipt =
                                request.execution ?: metrics.runtimeExecutionReceipt
                            )
                        )
                    )
                }
            "TOOL" -> runTool(request, packet)
            "TOOL_RECEIVED" -> {
                request.generationDeadline?.toolResponseDelivered(packet.callId, SystemClock.elapsedRealtime())
                deliveryReceipts.remove(packet.callId)?.let { receipt -> scope.launch { receipt.acknowledge() } }
            }
            "RESULT", "DONE", "ERROR", "CANCELLED" -> {
                recordTerminal(request, packet.operation, true)
                if (packet.operation == "DONE") request.events?.offer(LocalRuntimeEvent.Done)
                if (packet.operation == "ERROR" || packet.operation == "CANCELLED") {
                    request.events?.offer(LocalRuntimeEvent.Error(packet.text, localRuntimeFailureFromRemote(packet.errorCode, packet.text)))
                    // Keep the command session until an explicit CLOSE acknowledgement;
                    // an output-limit terminal can leave a healthy resident engine.
                }
                mutableState.value =
                    when {
                        packet.operation == "ERROR" -> state.value.copy(lifecycle = "FAILED")
                        packet.operation == "CANCELLED" ->
                            if (reportedState?.engineSpec != null &&
                                reportedState.engineHealth == "HEALTHY"
                            ) {
                                reportedState.copy(lifecycle = "READY", processIsolated = true)
                            } else {
                                state.value.copy(lifecycle = "RECOVERING", engineSpec = null)
                            }
                        request.packet.operation == "UNLOAD" -> LocalRuntimeState(processIsolated = true)
                        reportedState != null -> reportedState.copy(processIsolated = true)
                        request.packet.operation == "INSPECT" ||
                            request.packet.operation == "PING" ||
                            request.packet.operation == "VERIFY_RESET" -> state.value
                        packet.operation == "DONE" && state.value.engineSpec != null -> state.value.copy(lifecycle = "READY")
                        else -> state.value
                    }
                if (request.packet.operation == "UNLOAD" && packet.operation == "RESULT") nativeReleaseAcknowledged = true
                request.finished.complete(packet)
            }
        }
    }

    private fun runTool(
        request: Pending,
        packet: RuntimeWire
    ) {
        val job =
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                if (request.cancelling ||
                    packet.toolName !in allowedTools ||
                    packet.text.length > 16 * 1024 ||
                    toolCalls.putIfAbsent(packet.callId, true) != null
                ) {
                    sendCancel(request.packet)
                    return@launch
                }
                request.generationDeadline?.toolStarted(packet.callId, SystemClock.elapsedRealtime())
                mutableState.value = state.value.copy(lifecycle = "WAITING_FOR_TOOL")
                val response =
                    try {
                        val receipt =
                            dev.chungjungsoo.gptmobile.data.telemetry.tool
                                .ToolDeliveryReceipt()
                        deliveryReceipts[packet.callId] = receipt
                        val result =
                            withTimeout(
                                request.generationDeadline?.policy?.toolCallbackMs ?: 120_000
                            ) { withContext(receipt) { checkNotNull(toolExecutor).execute(packet.toolName, packet.text) } }
                        require(result.toByteArray().size <= 512 * 1024) { "Tool result exceeded the IPC budget" }
                        packet.copy(operation = "TOOL_RESULT", text = result)
                    } catch (error: LocalToolLimitException) {
                        packet.copy(operation = "TOOL_RESULT", text = "", errorCode = error.code)
                    } catch (_: Exception) {
                        packet.copy(
                            operation = "TOOL_RESULT",
                            text = "{\"status\":\"error\",\"code\":\"OUTCOME_UNKNOWN\",\"message\":\"Tool interrupted; reconcile before repeating an action\"}"
                        )
                    }
                if (!request.finished.isCompleted && pending[packet.requestId] === request && sessionId == packet.sessionId) {
                    remote?.let {
                        addConversationCharacters(response.text.length)
                        RuntimeWireTransport.send(context, it, response, incoming)
                    }
                }
            }
        request.toolJobs[packet.callId] = job
        job.invokeOnCompletion {
            request.toolJobs.remove(packet.callId)
            request.generationDeadline?.toolFinished(packet.callId, SystemClock.elapsedRealtime())
        }
        if (request.cancelling) job.cancel() else job.start()
    }

    @Synchronized private fun disconnect(reason: String) {
        processPssBytes = null
        remote?.binder?.let { binder ->
            if (binder.isBinderAlive) {
                quarantinedBinder = binder
                quarantineAfterUnconfirmedCleanup(reason)
            } else {
                observeWorkerExit(binder, "binder_not_alive")
            }
        }
        unbind()
        generation = ""
        sessionId = ""
        requestedSpec = null
        toolExecutor = null
        mutableState.value =
            LocalRuntimeState(
                processIsolated = true,
                lifecycle = "FAILED",
                fallbackReason = reason,
                engineHealth =
                if (cleanupQuarantine !=
                    null
                ) {
                    "RESTART_REQUIRED"
                } else {
                    "RELEASED"
                }
            )
        binding?.completeExceptionally(IllegalStateException(reason))
        pending.values.forEach {
            recordTerminal(it, "WORKER_DISCONNECTED", false)
            it.cancelling = true
            it.toolJobs.values.forEach { job -> job.cancel() }
            it.events?.offer(LocalRuntimeEvent.Error(reason))
            val timedOut =
                it.packet.operation == "LOAD" &&
                    it.lastProgress?.stage in setOf("LOAD_STAGE_DEADLINE_EXCEEDED", "LOAD_OPERATION_DEADLINE_EXCEEDED")
            it.finished.complete(
                RuntimeWire(
                    "ERROR",
                    it.packet.requestId,
                    text = if (timedOut) "GGUF initialization timed out: ${it.lastProgress?.configuration}" else reason,
                    errorCode = if (timedOut) "GGUF_INITIALIZATION_TIMEOUT" else null
                )
            )
        }
    }

    private fun unbind() {
        val previous = connection
        connection = null
        remote = null
        generation = ""
        previous?.let { runCatching { context.unbindService(it) } }
    }

    private fun recordTerminal(
        request: Pending,
        terminal: String,
        acknowledged: Boolean
    ) {
        if (request.packet.operation != "GENERATE") return
        request.execution?.let { previous ->
            publishExecution(
                request,
                previous.copy(
                    commandTerminalAcknowledged = acknowledged,
                    commandTerminalAtElapsedMs = if (acknowledged) SystemClock.elapsedRealtime() else previous.commandTerminalAtElapsedMs,
                    cancellationOrigin = request.cancellationOrigin ?: previous.cancellationOrigin,
                    observedAtElapsedMs = SystemClock.elapsedRealtime()
                )
            )
        }
        if (!request.receiptRecorded.compareAndSet(false, true)) return
        val progress = request.lastProgress
        AppLogRecorder.record(
            "LocalModel",
            "LOCAL_TERMINAL_RECEIPT · request=${request.packet.requestId} · physicalAttempt=${request.physicalAttemptId ?: "unknown"} · nativeOperationId=${progress?.operationId ?: -1} · workerGenerationId=${request.execution?.workerGenerationId ?: "unknown"} · terminal=$terminal · cancellationOrigin=${request.cancellationOrigin ?: "none"} · acknowledged=$acknowledged · lastStage=${progress?.stage ?: "unknown"} · processed=${progress?.processedTokens ?: -1} · total=${progress?.promptTokens ?: -1} · sampled=${progress?.sampledTokens ?: -1}"
        )
    }

    private fun publishExecution(
        request: Pending,
        value: RuntimeExecutionReceipt
    ) {
        request.execution = value
        if (execution.get()?.runtimeRequestId == request.packet.requestId) execution.set(value)
    }

    private suspend fun requireSafeAdmission() {
        if (cleanupQuarantine != null || quarantinedBinder != null) {
            // SHUTDOWN is handled on the worker control thread, independently of
            // a stuck native coroutine. Never clear quarantine on elapsed time.
            terminateAndAwaitExit("quarantine_recovery", 5_000)
        }
        quarantinedBinder?.takeIf { !it.isBinderAlive }?.let { observeWorkerExit(it, "binder_not_alive") }
        check(cleanupQuarantine == null && quarantinedBinder?.isBinderAlive != true) {
            "NATIVE_SESSION_QUARANTINED: ${cleanupQuarantine ?: "waiting for the old worker to exit"}; no new inference was dispatched"
        }
    }

    override fun quarantineAfterUnconfirmedCleanup(reason: String): RuntimeCleanupReceipt {
        cleanupQuarantine = reason
        remote?.binder?.let { quarantinedBinder = it }
        val last = execution.get()
        val receipt =
            (cleanup.get() ?: RuntimeCleanupReceipt("outer_cleanup_watchdog")).copy(
                runtimeRequestId = last?.runtimeRequestId,
                physicalAttemptId = last?.physicalAttemptId,
                workerGenerationId = generation.takeIf { it.isNotBlank() } ?: last?.workerGenerationId,
                engineInstanceId = last?.engineInstanceId ?: engineLoad?.engineInstanceId,
                nativeOperationId = last?.nativeOperationId,
                quarantined = true,
                failureReason = reason,
                observedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        cleanup.set(receipt)
        recordCleanup(receipt)
        mutableState.value = state.value.copy(lifecycle = "QUARANTINED", engineHealth = "RESTART_REQUIRED")
        return receipt
    }

    @Synchronized private fun observeWorkerExit(
        binder: IBinder,
        evidence: String
    ) {
        if (binder !== quarantinedBinder && binder !== remote?.binder) return
        if (binder.isBinderAlive && evidence != "binder_death") return
        val last = execution.get()
        val previous = cleanup.get() ?: RuntimeCleanupReceipt("worker_exit")
        val exitReceipt =
            previous.copy(
                runtimeRequestId = previous.runtimeRequestId ?: last?.runtimeRequestId,
                physicalAttemptId = previous.physicalAttemptId ?: last?.physicalAttemptId,
                workerGenerationId = previous.workerGenerationId ?: generation.takeIf { it.isNotBlank() } ?: last?.workerGenerationId,
                engineInstanceId = previous.engineInstanceId ?: engineLoad?.engineInstanceId,
                nativeOperationId = previous.nativeOperationId ?: last?.nativeOperationId,
                obligationsObserved = true,
                nativeClosureConfirmed = true,
                conversationClosed = true,
                engineReleased = true,
                workerExitConfirmed = true,
                workerExitEvidence = evidence,
                quarantined = false,
                failureReason = null,
                observedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        cleanup.set(exitReceipt)
        confirmedWorkerExit = exitReceipt
        recordCleanup(exitReceipt)
        nativeReleaseAcknowledged = true
        quarantinedBinder = null
        cleanupQuarantine = null
        requestedSpec = null
    }

    /** At most 12s cancellation + 5s per close/release + 5s confirmed worker-exit wait. */
    override suspend fun cleanupAfterRequest(origin: String): RuntimeCleanupReceipt =
        withContext(NonCancellable + Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val last = execution.get()
            var receipt =
                RuntimeCleanupReceipt(
                    origin = origin,
                    runtimeRequestId = last?.runtimeRequestId,
                    physicalAttemptId = last?.physicalAttemptId,
                    workerGenerationId = generation.takeIf { it.isNotBlank() } ?: last?.workerGenerationId,
                    engineInstanceId = last?.engineInstanceId ?: engineLoad?.engineInstanceId,
                    nativeOperationId = last?.nativeOperationId,
                    obligationsObserved = true,
                    startedAtElapsedMs = started
                )
            cleanup.set(receipt)
            try {
                val outstanding = pending.values.filter { !it.finished.isCompleted }
                if (outstanding.isNotEmpty()) {
                    receipt = receipt.copy(requiredActions = setOf("cancel", "close_conversation"), cancellationRequested = true)
                    cleanup.set(receipt)
                    outstanding.forEach {
                        it.cancelling = true
                        sendCancel(it.packet, origin)
                    }
                    val acknowledged =
                        withTimeoutOrNull(12_000) {
                            outstanding.forEach { it.finished.await() }
                            true
                        } == true &&
                            outstanding.all { it.packet.operation != "GENERATE" || it.execution?.commandTerminalAcknowledged == true }
                    receipt = receipt.copy(cancellationAcknowledged = acknowledged)
                    if (!acknowledged) {
                        cleanup.set(receipt)
                        if (terminateAndAwaitExit(origin, 5_000)) return@withContext checkNotNull(cleanup.get())
                        return@withContext quarantineAfterUnconfirmedCleanup("Owning worker did not confirm cancellation or exit")
                    }
                }
                val active = remote
                val old = quarantinedBinder
                if (active == null) {
                    if (old != null) {
                        if (terminateAndAwaitExit(origin, 5_000)) return@withContext checkNotNull(cleanup.get())
                        return@withContext quarantineAfterUnconfirmedCleanup("Worker exit is unconfirmed")
                    }
                    val observedExit = confirmedWorkerExit?.workerExitConfirmed == true
                    val knownReleased = nativeReleaseAcknowledged && state.value.engineSpec == null
                    receipt =
                        receipt.copy(
                            nativeClosureConfirmed = (observedExit || knownReleased).takeIf { it },
                            conversationClosed = (observedExit || knownReleased).takeIf { it },
                            engineReleased = (observedExit || knownReleased).takeIf { it },
                            notRequiredReason =
                            if (!everBound) {
                                "No worker or native operation was created"
                            } else if (knownReleased) {
                                "Completed release acknowledged"
                            } else {
                                null
                            },
                            workerExitConfirmed = observedExit.takeIf { it },
                            workerExitEvidence = confirmedWorkerExit?.workerExitEvidence,
                            observedAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    cleanup.set(receipt)
                    if (!receipt.safeForSuccessor) {
                        return@withContext quarantineAfterUnconfirmedCleanup(
                            "No positive worker closure evidence"
                        )
                    }
                    cleanupQuarantine = null
                    recordCleanup(receipt)
                    return@withContext receipt
                }
                receipt = receipt.copy(requiredActions = receipt.requiredActions + "close_conversation")
                cleanup.set(receipt)
                val closed = cleanupCommand("CLOSE")
                receipt = receipt.copy(conversationClosed = closed, nativeClosureConfirmed = closed)
                if (!closed) {
                    cleanup.set(receipt)
                    if (terminateAndAwaitExit(origin, 5_000)) return@withContext checkNotNull(cleanup.get())
                    return@withContext quarantineAfterUnconfirmedCleanup("Conversation close was not acknowledged")
                }
                sessionId = ""
                toolExecutor = null
                allowedTools = emptySet()
                val healthy = state.value.engineSpec != null && state.value.engineHealth == "HEALTHY"
                receipt =
                    receipt.copy(
                        engineHealthy = healthy,
                        cancellationAcknowledged =
                        receipt.cancellationAcknowledged ?: receipt.cancellationRequested.takeIf { it }
                    )
                if (!healthy) {
                    receipt = receipt.copy(requiredActions = receipt.requiredActions + "release_engine")
                    cleanup.set(receipt)
                    val released = cleanupCommand("UNLOAD")
                    receipt = receipt.copy(engineReleased = released)
                    if (!released) {
                        cleanup.set(receipt)
                        if (terminateAndAwaitExit(origin, 5_000)) return@withContext checkNotNull(cleanup.get())
                        return@withContext quarantineAfterUnconfirmedCleanup("Engine release was not acknowledged")
                    }
                    requestedSpec = null
                }
                receipt = receipt.copy(observedAtElapsedMs = SystemClock.elapsedRealtime())
                cleanup.set(receipt)
                if (!receipt.safeForSuccessor) return@withContext quarantineAfterUnconfirmedCleanup("Cleanup obligations remain unresolved")
                cleanupQuarantine = null
                quarantinedBinder = null
                recordCleanup(receipt)
                receipt
            } catch (error: Throwable) {
                cleanup.set(receipt)
                if (terminateAndAwaitExit(origin, 5_000)) {
                    checkNotNull(cleanup.get())
                } else {
                    quarantineAfterUnconfirmedCleanup(
                        error.message ?: "Cleanup failed"
                    )
                }
            }
        }

    private fun recordCleanup(receipt: RuntimeCleanupReceipt) {
        dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.record(
            "NATIVE_CLEANUP_RECEIPT",
            buildMap {
                put("origin", receipt.origin)
                put("safeForSuccessor", receipt.safeForSuccessor.toString())
                receipt.runtimeRequestId?.let { put("runtimeRequestId", it) }
                receipt.physicalAttemptId?.let { put("physicalAttemptId", it) }
                receipt.workerGenerationId?.let { put("workerGenerationId", it) }
                receipt.nativeOperationId?.let { put("nativeOperationId", it.toString()) }
                put("nativeClosureConfirmed", receipt.nativeClosureConfirmed?.toString() ?: "unknown")
                put("terminationRequested", receipt.terminationRequested.toString())
                put(
                    "workerExitConfirmed",
                    receipt.workerExitConfirmed?.toString() ?: "unknown"
                )
                put("engineReleased", receipt.engineReleased?.toString() ?: "unknown")
                put(
                    "conversationClosed",
                    receipt.conversationClosed?.toString() ?: "unknown"
                )
                receipt.workerExitEvidence?.let { put("workerExitEvidence", it) }
                receipt.failureReason?.let { put("failureReason", it) }
            }
        )
    }

    private suspend fun cleanupCommand(operation: String): Boolean {
        val target = remote ?: return false
        val packet = RuntimeWire(operation, UUID.randomUUID().toString(), sessionId, generation)
        val request = Pending(packet)
        pending[packet.requestId] = request
        return try {
            RuntimeWireTransport.send(context, target, packet, incoming)
            withTimeoutOrNull(5_000) { request.finished.await().operation == "RESULT" } == true
        } finally {
            pending.remove(packet.requestId)
        }
    }

    private suspend fun terminateAndAwaitExit(
        origin: String,
        timeoutMs: Long
    ): Boolean {
        val target = remote ?: quarantinedBinder?.let(::Messenger)
        val binder = quarantinedBinder ?: target?.binder ?: return confirmedWorkerExit?.workerExitConfirmed == true
        quarantinedBinder = binder
        cleanupQuarantine = "Waiting for owning worker exit: $origin"
        cleanup.set(
            (
                cleanup.get() ?: RuntimeCleanupReceipt(
                    origin
                )
                ).copy(terminationRequested = true, quarantined = true, observedAtElapsedMs = SystemClock.elapsedRealtime())
        )
        if (binder.isBinderAlive && target != null) {
            runCatching {
                RuntimeWireTransport.send(
                    context,
                    target,
                    RuntimeWire("SHUTDOWN", UUID.randomUUID().toString(), sessionId, generation),
                    incoming
                )
            }
        }
        val exited =
            withTimeoutOrNull(timeoutMs) {
                while (binder.isBinderAlive) kotlinx.coroutines.delay(25)
                true
            } == true
        if (exited) {
            observeWorkerExit(binder, "binder_not_alive")
            if (remote?.binder === binder) unbind()
        }
        return exited
    }

    private class Pending(
        val packet: RuntimeWire,
        val events: LocalEventBuffer? = null,
        val physicalAttemptId: String? = null
    ) {
        @Volatile var serviceBindingMs: Long? = null

        @Volatile var serviceBindingDisposition: String? = null

        @Volatile var generationDeadline: ProcessGenerationDeadline? = null

        @Volatile var lastProgress: GgufNativeProgress? = null

        @Volatile var execution: RuntimeExecutionReceipt? = null

        @Volatile var cancellationOrigin: String? = null

        val receiptRecorded =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val finished = CompletableDeferred<RuntimeWire>()
        var sequence = 0L

        @Volatile var cancelling = false
        val toolJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    }
}
