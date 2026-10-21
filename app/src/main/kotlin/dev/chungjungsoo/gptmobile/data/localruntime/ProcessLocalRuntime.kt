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
class ProcessLocalRuntime(private val context: Context, private val settings: SettingRepository) : LocalRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val readiness = LocalReadinessRegistry(java.io.File(context.noBackupFilesDir, "local-readiness-quarantine"))
    private val pending = ConcurrentHashMap<String, Pending>()
    private val thread = HandlerThread("local-runtime-client").apply { start() }
    private val incoming = Messenger(object : Handler(thread.looper) {
        override fun handleMessage(message: Message) {
            runCatching { receive(RuntimeWireTransport.read(message)) }.onFailure { disconnect("Invalid inference response: ${it.message}") }
        }
    })

    @Volatile private var remote: Messenger? = null

    @Volatile private var connection: ServiceConnection? = null

    @Volatile private var binding: CompletableDeferred<Messenger>? = null

    @Volatile private var quarantinedBinder: IBinder? = null

    @Volatile private var generation = ""

    @Volatile private var sessionId = ""

    @Volatile private var toolExecutor: LocalToolExecutor? = null
    private var allowedTools: Set<String> = emptySet()
    private val deliveryReceipts = ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDeliveryReceipt>()
    private val toolCalls = ConcurrentHashMap<String, Boolean>()
    private var requestedSpec: LocalEngineSpec? = null
    private var tuning: LocalEngineTuning? = null
    private val mutableState = MutableStateFlow(LocalRuntimeState(processIsolated = true))
    override val state = mutableState.asStateFlow()
    override val handlesEngineFallback = true
    override val isBusy get() = pending.isNotEmpty() || quarantinedBinder?.isBinderAlive == true

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

    private suspend fun nativeRouteIdentity(modelPath: String, accelerator: String?): String =
        "native-route:" + RuntimeEvidenceIdentity.key(context, LocalEngineSpec(modelPath, LocalAccelerators.normalize(accelerator), 1)).toString()

    override suspend fun preflightModel(modelPath: String, accelerator: String?) {
        check(quarantinedBinder?.isBinderAlive != true) { "NATIVE_SESSION_QUARANTINED: waiting for the cancelled worker process to stop; no new inference was dispatched" }
        readiness.assertNativeAllowed(nativeRouteIdentity(modelPath, accelerator))
    }

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = execute("INSPECT", text = modelPath).capabilities

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val identity = readinessIdentity(spec, settings.getFeatureSettings())
        command(RuntimeWire("VERIFY_RESET", UUID.randomUUID().toString(), generation = generation, spec = spec))
        readiness.reset(identity)
        readiness.reset(nativeRouteIdentity(spec.modelPath, spec.accelerator))
    }

    private suspend fun readinessIdentity(spec: LocalEngineSpec, snapshot: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings): String {
        val tuning = snapshot.localEngineTuning()
        val effective = if (spec.tuningLocked) spec else spec.copy(cpuThreads = tuning.cpuThreads, ggufBatchTokens = tuning.ggufBatchTokens, ggufMicroBatchTokens = minOf(tuning.ggufMicroBatchTokens, tuning.ggufBatchTokens), ggufPromptThreads = tuning.ggufPromptThreads, cacheEnabled = tuning.cacheEnabled, speculativeDecoding = tuning.speculativeDecoding)
        return RuntimeEvidenceIdentity.key(context, effective, effective.samplerIdentity, effective.workload).toString()
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        preflightModel(spec.modelPath, spec.accelerator)
        val snapshot = settings.getFeatureSettings()
        val packet = RuntimeWire("LOAD", UUID.randomUUID().toString(), generation = generation, spec = spec, settings = snapshot, preferredBackend = settings.getLocalRuntimeBackend())
        val artifact = java.io.File(spec.modelPath)
        check(artifact.isFile || artifact.isDirectory) { "MISSING_ASSET: Download or relink the selected model before running it." }
        val identity = readinessIdentity(spec, snapshot)
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        try {
            mutableState.value = state.value.copy(lifecycle = "LOADING")
            readiness.qualify(identity) {
                if (!isEngineLoaded(spec)) command(packet, timeoutMs = 180_000)
            }
        } catch (error: Exception) {
            if (error !is kotlinx.coroutines.CancellationException && error.message.orEmpty().contains("interrupted inside native code", true)) {
                readiness.rememberNativeInterruption(nativeRouteIdentity(spec.modelPath, spec.accelerator))
            }
            if (error !is kotlinx.coroutines.CancellationException && LocalReadinessRegistry.deterministicFailure(error)) {
                // One unsupported tensor is enough to disqualify this artifact/backend;
                // tuning tweaks must not repeatedly crash the native worker.
                readiness.rememberIncompatibleRoute(nativeRouteIdentity(spec.modelPath, spec.accelerator))
                AppLogRecorder.record("LocalModel", "INCOMPATIBLE_ROUTE_QUARANTINED · accelerator=${spec.accelerator} · nativeDispatched=false_for_future_attempts", "W")
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
            "ENGINE_READY · profileBackendPreference=${native.preferredBackend ?: settings.getLocalRuntimeBackend()} · actualBackend=${native.backend ?: "UNKNOWN"} · artifactRuntime=${if (spec.modelPath.endsWith("manifest.json")) runCatching { dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(artifact).runtime.name }.getOrDefault("MANIFEST_RUNTIME_UNREADABLE") else "LITERT_MODEL"} · requestedAccelerator=${spec.accelerator} · actualAccelerator=${native.engineSpec?.accelerator ?: "UNKNOWN"} · loadMs=${SystemClock.elapsedRealtime() - loadStartedAtMs} · model=${artifact.name} · workerPssBytes=${processPssBytes ?: -1} · processIsolated=${native.processIsolated}"
        )
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        sessionId = UUID.randomUUID().toString()
        toolExecutor = config.toolExecutor
        allowedTools = config.tools.map { it.name }.toSet()
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

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = flow {
        check(sessionId.isNotBlank()) { "Local conversation is not open" }
        require(images.size <= 10 && images.sumOf { it.size.toLong() } <= 8L * 1024 * 1024) { "Attached images exceed the local memory budget" }
        val packet = RuntimeWire("GENERATE", UUID.randomUUID().toString(), sessionId, generation, text = text, images = images)
        val request = Pending(packet, LocalEventBuffer(), kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.receipts?.lastOrNull()?.physicalAttemptId)
        pending[packet.requestId] = request
        val startedAtMs = SystemClock.elapsedRealtime()
        val watchdogBackend = state.value.backend
        val watchdogBudgetMs = LocalPrefillDeadline.processWatchdogBudgetMs(text.length, watchdogBackend)
        var receivedNativeToken = false
        val progressTracker = NativeProgressTracker()
        var lastMeaningfulAtMs = startedAtMs
        try {
            send(packet)
            // A native engine can remain in PREFILL with no callbacks indefinitely.
            // Bound only the zero-token interval: productive decoding keeps its
            // existing progress watchdog, and native work is NEVER replayed here.
            while (true) {
                val next = if (receivedNativeToken) {
                    true to request.events!!.take()
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val remaining = if (watchdogBackend in setOf(dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.LLAMA_CPP, dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.GENIEX_LLAMA_CPP)) {
                        minOf(600_000L - (now - startedAtMs), watchdogBudgetMs - (now - lastMeaningfulAtMs)).coerceAtLeast(0)
                    } else LocalPrefillDeadline.processWatchdogRemainingMs(startedAtMs, now, text.length, watchdogBackend)
                    if (remaining <= 0L) null else withTimeoutOrNull(remaining) { true to request.events!!.take() }
                }
                if (next == null) {
                    val engine = state.value
                    val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                    AppLogRecorder.record(
                        "LocalModel",
                        "LOCAL_PREFILL_TIMEOUT · backend=${engine.backend ?: "UNKNOWN"} · accelerator=${engine.engineSpec?.accelerator ?: "UNKNOWN"} · promptCharacters=${text.length} · watchdogBudgetMs=$watchdogBudgetMs · elapsedMs=$elapsed · workerPssBytes=${processPssBytes ?: -1} · nativeOutputTokens=unknown · action=cancel_and_quarantine_if_unresponsive",
                        "E"
                    )
                    throw IllegalStateException("LOCAL_PREFILL_TIMEOUT: The native engine produced no tokens after ${elapsed / 1000}s. Generation was cancelled; check the selected backend and model compatibility, then retry manually.")
                }
                val event = next.second ?: break
                if (event is LocalRuntimeEvent.PhaseChanged && progressTracker.advance(event.nativeProgress)) lastMeaningfulAtMs = SystemClock.elapsedRealtime()
                if (!receivedNativeToken && (event is LocalRuntimeEvent.TextDelta || event is LocalRuntimeEvent.ThinkingDelta)) {
                    receivedNativeToken = true
                    val engine = state.value
                    AppLogRecorder.record(
                        "LocalModel",
                        "LOCAL_FIRST_TOKEN · backend=${engine.backend ?: "UNKNOWN"} · accelerator=${engine.engineSpec?.accelerator ?: "UNKNOWN"} · elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} · workerPssBytes=${processPssBytes ?: -1}"
                    )
                }
                emit(event)
            }
            request.finished.await()
        } finally {
            finishRequest(request)
        }
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
    }

    override suspend fun unloadEngine() {
        if (remote != null) execute("UNLOAD")
        requestedSpec = null
        sessionId = ""
        toolExecutor = null
        unbind()
        mutableState.value = LocalRuntimeState(processIsolated = true)
    }

    override fun hasOpenConversation() = sessionId.isNotBlank() && remote != null
    override suspend fun isEngineLoaded(spec: LocalEngineSpec) = requestedSpec?.engineConfiguration() == spec.engineConfiguration() &&
        remote != null &&
        state.value.engineSpec != null &&
        state.value.engineHealth == "HEALTHY" &&
        (spec.tuningLocked || tuning == settings.getFeatureSettings().localEngineTuning())

    private suspend fun execute(operation: String, text: String = ""): RuntimeWire = command(RuntimeWire(operation, UUID.randomUUID().toString(), sessionId, generation, text = text))

    private suspend fun command(packet: RuntimeWire, timeoutMs: Long = 60_000): RuntimeWire {
        val request = Pending(packet)
        pending[packet.requestId] = request
        try {
            send(packet)
            return withTimeout(timeoutMs) { request.finished.await() }.also {
                if (it.operation == "ERROR") {
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

    private suspend fun finishRequest(request: Pending) = withContext(NonCancellable + Dispatchers.IO) {
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
                remote?.let { runCatching { RuntimeWireTransport.send(context, it, request.packet.copy(operation = "SHUTDOWN"), incoming) } }
                recordTerminal(request, "CANCELLATION_UNACKNOWLEDGED", false)
                disconnect("Inference worker did not acknowledge cancellation. Partial output is preserved; start a new request.")
            }
        }
        pending.remove(request.packet.requestId)
    }

    private suspend fun sendCancel(packet: RuntimeWire, origin: String = "TOOL_PROTOCOL_REJECTED") = withContext(Dispatchers.IO) {
        pending[packet.requestId]?.let { if (it.cancellationOrigin == null) it.cancellationOrigin = origin }
        remote?.let { RuntimeWireTransport.send(context, it, packet.copy(operation = "CANCEL", text = "", images = emptyList(), spec = null, conversation = null, settings = null), incoming) }
    }

    private suspend fun send(packet: RuntimeWire) = withContext(Dispatchers.IO) {
        RuntimeWireTransport.send(context, connect(), packet, incoming)
    }

    private suspend fun connect(): Messenger = bindMutex.withLock {
        val quarantined = quarantinedBinder
        check(quarantined?.isBinderAlive != true) { "NATIVE_SESSION_QUARANTINED: waiting for the cancelled worker process to stop; no new inference was dispatched" }
        quarantinedBinder = null
        remote?.let { return@withLock it }
        val result = CompletableDeferred<Messenger>()
        binding = result
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                synchronized(this@ProcessLocalRuntime) {
                    if (connection !== this) return
                    try {
                        binder.linkToDeath({
                            if (quarantinedBinder === binder) quarantinedBinder = null
                            if (connection === this) disconnect("Native inference worker stopped. Completed output is preserved; tools will not be repeated.")
                        }, 0)
                        check(binder.isBinderAlive) { "Inference worker stopped while connecting" }
                        val messenger = Messenger(binder)
                        remote = messenger
                        result.complete(messenger)
                    } catch (error: Exception) {
                        disconnect("Inference worker could not connect: ${error.message}")
                        result.completeExceptionally(error)
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) disconnect("Native inference worker disconnected")
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
            check(context.bindService(Intent(context, LocalInferenceService::class.java), next, Context.BIND_AUTO_CREATE)) { "Could not bind inference service" }
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
        packet.state?.let { mutableState.value = it }
        when (packet.operation) {
            "TEXT", "THINKING" -> {
                val accepted = request.events?.offer(if (packet.operation == "TEXT") LocalRuntimeEvent.TextDelta(packet.text) else LocalRuntimeEvent.ThinkingDelta(packet.text)) == true
                scope.launch {
                    if (!accepted) sendCancel(request.packet, "OUTPUT_BUFFER_FULL")
                    remote?.let { RuntimeWireTransport.send(context, it, packet.copy(operation = "ACK", text = ""), incoming) }
                }
            }
            "LOAD_PROGRESS" -> {
                val progress = packet.nativeProgress
                mutableState.value = state.value.copy(lifecycle = "LOADING", loadProgress = progress, allocationObservation = progress?.allocationObservation ?: state.value.allocationObservation)
                AppLogRecorder.record("LocalModel", "GGUF_LOAD_PROGRESS · request=${packet.requestId} · stage=${progress?.stage} · fraction=${progress?.loadFraction} · elapsedMs=${progress?.elapsedMs} · observation=${progress?.allocationObservation}")
            }
            "PHASE" -> {
                mutableState.value = state.value.copy(lifecycle = if (packet.text == "PREFILL") "PREFILLING" else "DECODING")
                packet.nativeProgress?.let { progress ->
                    AppLogRecorder.record("LocalModel", "GGUF_NATIVE_PROGRESS · request=${packet.requestId} · session=${packet.sessionId} · generation=${packet.generation} · sequence=${packet.sequence} · operation=${progress.operationId} · stage=${progress.stage} · promptTokens=${progress.promptTokens} · processedTokens=${progress.processedTokens} · sampledTokens=${progress.sampledTokens} · elapsedMs=${progress.elapsedMs} · nativeCode=${progress.nativeCode} · observationMs=${progress.observedAtMs} · workerPssBytes=${processPssBytes ?: -1} · config=${progress.configuration.orEmpty()}")
                    if (progress.stage == "TIMEOUT_CANCEL_REQUESTED") mutableState.value = state.value.copy(lifecycle = "CANCELLING", engineHealth = "RESTART_REQUIRED")
                }
                val progress = packet.nativeProgress?.copy(runtimeRequestId = packet.requestId, physicalAttemptId = request.physicalAttemptId)
                if (progress != null) request.lastProgress = progress
                request.events?.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.valueOf(packet.text), progress))
            }
            "METRICS" -> packet.metrics?.let { request.events?.offer(LocalRuntimeEvent.Metrics(it)) }
            "TOOL" -> runTool(request, packet)
            "TOOL_RECEIVED" -> deliveryReceipts.remove(packet.callId)?.let { receipt -> scope.launch { receipt.acknowledge() } }
            "RESULT", "DONE", "ERROR", "CANCELLED" -> {
                recordTerminal(request, packet.operation, true)
                if (packet.operation == "DONE") request.events?.offer(LocalRuntimeEvent.Done)
                if (packet.operation == "ERROR" || packet.operation == "CANCELLED") {
                    request.events?.offer(LocalRuntimeEvent.Error(packet.text, packet.errorCode?.let(::LocalToolLimitException)))
                    sessionId = ""
                }
                mutableState.value = when {
                    packet.operation == "ERROR" -> state.value.copy(lifecycle = "FAILED")
                    packet.operation == "CANCELLED" -> if (packet.state?.engineSpec != null && packet.state.engineHealth == "HEALTHY") packet.state.copy(lifecycle = "READY", processIsolated = true) else state.value.copy(lifecycle = "RECOVERING", engineSpec = null)
                    request.packet.operation == "UNLOAD" -> LocalRuntimeState(processIsolated = true)
                    packet.state != null -> packet.state.copy(processIsolated = true)
                    request.packet.operation == "INSPECT" || request.packet.operation == "PING" || request.packet.operation == "VERIFY_RESET" -> state.value
                    packet.operation == "DONE" && state.value.engineSpec != null -> state.value.copy(lifecycle = "READY")
                    else -> state.value
                }
                request.finished.complete(packet)
            }
        }
    }

    private fun runTool(request: Pending, packet: RuntimeWire) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (request.cancelling || packet.toolName !in allowedTools || packet.text.length > 16 * 1024 || toolCalls.putIfAbsent(packet.callId, true) != null) {
                sendCancel(request.packet)
                return@launch
            }
            mutableState.value = state.value.copy(lifecycle = "WAITING_FOR_TOOL")
            val response = try {
                val receipt = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDeliveryReceipt()
                deliveryReceipts[packet.callId] = receipt
                val result = withTimeout(120_000) { withContext(receipt) { checkNotNull(toolExecutor).execute(packet.toolName, packet.text) } }
                require(result.toByteArray().size <= 512 * 1024) { "Tool result exceeded the IPC budget" }
                packet.copy(operation = "TOOL_RESULT", text = result)
            } catch (error: LocalToolLimitException) {
                packet.copy(operation = "TOOL_RESULT", text = "", errorCode = error.code)
            } catch (_: Exception) {
                packet.copy(operation = "TOOL_RESULT", text = "{\"status\":\"error\",\"code\":\"OUTCOME_UNKNOWN\",\"message\":\"Tool interrupted; reconcile before repeating an action\"}")
            }
            if (!request.finished.isCompleted && pending[packet.requestId] === request && sessionId == packet.sessionId) {
                remote?.let { RuntimeWireTransport.send(context, it, response, incoming) }
            }
        }
        request.toolJobs[packet.callId] = job
        job.invokeOnCompletion { request.toolJobs.remove(packet.callId) }
        if (request.cancelling) job.cancel() else job.start()
    }

    @Synchronized private fun disconnect(reason: String) {
        processPssBytes = null
        unbind()
        generation = ""
        sessionId = ""
        requestedSpec = null
        toolExecutor = null
        mutableState.value = LocalRuntimeState(processIsolated = true, lifecycle = "FAILED", fallbackReason = reason)
        binding?.completeExceptionally(IllegalStateException(reason))
        pending.values.forEach {
            it.cancelling = true
            it.toolJobs.values.forEach { job -> job.cancel() }
            it.events?.offer(LocalRuntimeEvent.Error(reason))
            it.finished.complete(RuntimeWire("ERROR", it.packet.requestId, text = reason))
        }
    }

    private fun unbind() {
        val previous = connection
        connection = null
        remote = null
        generation = ""
        previous?.let { runCatching { context.unbindService(it) } }
    }

    private fun recordTerminal(request: Pending, terminal: String, acknowledged: Boolean) {
        if (request.packet.operation != "GENERATE" || request.receiptRecorded) return
        request.receiptRecorded = true
        val progress = request.lastProgress
        AppLogRecorder.record("LocalModel", "LOCAL_TERMINAL_RECEIPT · request=${request.packet.requestId} · physicalAttempt=${request.physicalAttemptId ?: "unknown"} · operation=${progress?.operationId ?: -1} · terminal=$terminal · cancellationOrigin=${request.cancellationOrigin ?: "none"} · acknowledged=$acknowledged · lastStage=${progress?.stage ?: "unknown"} · processed=${progress?.processedTokens ?: -1} · total=${progress?.promptTokens ?: -1} · sampled=${progress?.sampledTokens ?: -1}")
    }

    private class Pending(val packet: RuntimeWire, val events: LocalEventBuffer? = null, val physicalAttemptId: String? = null) {
        @Volatile var lastProgress: GgufNativeProgress? = null
        @Volatile var cancellationOrigin: String? = null
        @Volatile var receiptRecorded = false
        val finished = CompletableDeferred<RuntimeWire>()
        var sequence = 0L

        @Volatile var cancelling = false
        val toolJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    }
}
