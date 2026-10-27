package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWireTransport
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Native engines and inspection live here. No Hilt, databases, credentials or network tools. */
class LocalInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thread = HandlerThread("local-inference-ipc")
    private val generation = UUID.randomUUID().toString()
    private val terminationGate = NativeWorkerTerminationGate()
    private var settings = AppFeatureSettings()
    private var backend = LocalRuntimeBackend.DEFAULT
    private lateinit var runtime: LocalRuntime
    private lateinit var messenger: Messenger
    private val operationLock = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val replies = ConcurrentHashMap<String, Reply>()
    private val toolResults = ConcurrentHashMap<String, CompletableDeferred<RuntimeWire>>()
    private var sessionId = ""

    @Volatile private var loadProgress: GgufNativeProgress? = null

    @Volatile private var loadStageStartedAt = 0L

    @Volatile private var loadDeadlineGroup: String? = null

    @Volatile private var generatingRequest: String? = null

    @Volatile private var activeCommandRequest: String? = null

    override fun onCreate() {
        super.onCreate()
        check(isInferenceProcess()) { "Native inference service must run in its private process" }
        runtime =
            LocalRuntimeRouter(
                qnnRuntime = LocalRuntimeQnnImpl(this),
                liteRtRuntime = LocalRuntimeImpl(this),
                artifactRuntime = NativeArtifactRuntimeFactory.create(this),
                settingsSnapshot = { settings },
                backendSnapshot = { backend }
            )
        thread.start()
        messenger =
            Messenger(
                object : Handler(thread.looper) {
                    override fun handleMessage(message: Message) {
                        if (message.sendingUid != Process.myUid()) return
                        val target = message.replyTo ?: return
                        val packet = runCatching { RuntimeWireTransport.read(message) }.getOrElse { return }
                        when (packet.operation) {
                            "ACK" ->
                                if (packet.generation.isBlank() || packet.generation == generation) {
                                    replies[packet.requestId]?.acknowledge(packet.sequence)
                                }
                            "TOOL_RESULT" ->
                                if (packet.generation == generation &&
                                    packet.requestId == generatingRequest &&
                                    packet.sessionId == sessionId
                                ) {
                                    toolResults[packet.callId]?.complete(packet)
                                }
                            // Delayed control messages from an old worker generation must
                            // never cancel or kill a newly started native process.
                            "CANCEL" ->
                                if (packet.generation.isBlank() || packet.generation == generation) {
                                    cancel(packet.requestId)
                                }
                            "SHUTDOWN" ->
                                if (packet.generation.isBlank() || packet.generation == generation) {
                                    terminateWorker("CLIENT_SHUTDOWN", packet.requestId)
                                }
                            else -> start(packet, target)
                        }
                    }
                }
            )
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun start(
        packet: RuntimeWire,
        target: Messenger
    ) {
        val reply = Reply(packet, target)
        if (packet.generation.isNotBlank() && packet.generation != generation) {
            reply.send("ERROR", text = "Inference worker changed. Start a new request; interrupted work is not replayed.")
            return
        }
        if (terminationGate.isTerminating()) {
            reply.send("ERROR", text = "Native worker is terminating; reconnect to a fresh process.")
            return
        }
        if (!operationLock.tryLock()) {
            reply.send("ERROR", text = "The native worker is still busy or cancelling")
            return
        }
        // A shutdown may win the race between the first check and acquiring the
        // operation lease. Never start native work after termination is requested.
        if (terminationGate.isTerminating()) {
            operationLock.unlock()
            reply.send("ERROR", text = "Native worker is terminating; reconnect to a fresh process.")
            return
        }
        reply.deferTerminal = true
        activeCommandRequest = packet.requestId
        val job =
            scope.launch(RuntimeWorkerContext(generation, packet.requestId, packet.physicalAttemptId), start = CoroutineStart.LAZY) {
                try {
                    // QNN load/verification prepare their stack in the qualified wrapper. Only
                    // a generic compiled-package metadata query needs preparation at this boundary.
                    if (packet.operation == "INSPECT" &&
                        (packet.spec?.accelerator == LocalAccelerators.NPU || dev.chungjungsoo.gptmobile.data.localmodel.LocalModelPackages.isCompiledNpuPackage(packet.text))
                    ) {
                        QnnEnvironment.initialize(this@LocalInferenceService)
                    }
                    when (packet.operation) {
                        "PING" -> reply.send("RESULT", text = packet.text)
                        "INSPECT" -> {
                            validatePath(packet.text)
                            require(packet.spec == null || packet.spec.modelPath == packet.text) { "Inspection path/configuration mismatch" }
                            reply.send("RESULT", capabilities = if (packet.spec != null) runtime.inspectModel(packet.spec) else runtime.inspectModel(packet.text))
                        }
                        "VERIFY_RESET" -> {
                            val spec = checkNotNull(packet.spec)
                            validatePath(spec.modelPath)
                            runtime.allowVerification(spec)
                            reply.send("RESULT")
                        }
                        "LOAD" -> {
                            val spec = checkNotNull(packet.spec)
                            validatePath(spec.modelPath)
                            require(spec.litertDispatchLibDir == null) { "Dispatch libraries are selected by the qualified runtime" }
                            require(spec.accelerator in setOf("cpu", "gpu", "opencl", "auto", "npu")) { "Unknown language backend" }
                            settings = packet.settings ?: AppFeatureSettings()
                            backend = packet.preferredBackend ?: LocalRuntimeBackend.DEFAULT
                            sessionId = ""
                            // A model switch must not retain descriptors owned by the old engine.
                            // The operation lock keeps generations and inspection out of this boundary.
                            runtime.unloadEngine()
                            dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources
                                .closeLeases()
                            loadProgress = null
                            loadDeadlineGroup = null
                            val loadStarted = android.os.SystemClock.elapsedRealtime()
                            val monitor =
                                scope.launch {
                                    while (true) {
                                        delay(250)
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        val progress = loadProgress
                                        val deadline =
                                            when {
                                                now - loadStarted >= GgufLoadDeadline.ABSOLUTE_LOAD_MS -> "LOAD_OPERATION_DEADLINE_EXCEEDED"
                                                progress != null &&
                                                    GgufLoadDeadline.expired(
                                                        progress.stage,
                                                        loadStageStartedAt,
                                                        now
                                                    ) -> "LOAD_STAGE_DEADLINE_EXCEEDED"
                                                else -> null
                                            }
                                        if (deadline != null) {
                                            val retained = progress ?: GgufNativeProgress(-1, "BEFORE_NATIVE_PROGRESS")
                                            reply.send(
                                                "LOAD_PROGRESS",
                                                nativeProgress =
                                                retained.copy(
                                                    stage = deadline,
                                                    configuration = "lastStage=${retained.stage};${retained.configuration.orEmpty()}",
                                                    deadlineKind = deadline,
                                                    observedAtMs = now
                                                )
                                            )
                                            terminateWorker(deadline, packet.requestId)
                                        }
                                    }
                                }
                            runtime.setLoadProgressObserver { progress ->
                                val group = GgufLoadDeadline.group(progress.stage)
                                if (group != loadDeadlineGroup || progress.operationId != loadProgress?.operationId) {
                                    loadStageStartedAt = android.os.SystemClock.elapsedRealtime()
                                    loadDeadlineGroup = group
                                }
                                loadProgress = progress
                                reply.send(
                                    "LOAD_PROGRESS",
                                    state =
                                    runtime.state.value.copy(
                                        processIsolated = true,
                                        lifecycle = "LOADING",
                                        loadProgress = progress,
                                        allocationObservation =
                                        progress.allocationObservation ?: runtime.state.value.allocationObservation
                                    ),
                                    nativeProgress = progress
                                )
                            }
                            try {
                                runtime.loadEngine(spec)
                            } finally {
                                monitor.cancel()
                                runtime.setLoadProgressObserver(null)
                                loadProgress = null
                            }
                            reply.send("RESULT", state = runtime.state.value.copy(processIsolated = true, lifecycle = "READY"))
                        }
                        "OPEN" -> {
                            sessionId = packet.sessionId
                            val config = checkNotNull(packet.conversation)
                            runtime.createConversation(
                                config.config(
                                    if (config.tools.isEmpty()) {
                                        null
                                    } else {
                                        LocalToolExecutor { name, arguments ->
                                            val owner = checkNotNull(generatingRequest) { "No active request owns this tool callback" }
                                            val ownerReply = checkNotNull(replies[owner])
                                            val callId = UUID.randomUUID().toString()
                                            val result = CompletableDeferred<RuntimeWire>()
                                            toolResults[callId] = result
                                            try {
                                                ownerReply.send("TOOL", text = arguments, callId = callId, toolName = name)
                                                val returned = withTimeout(120_000) { result.await() }
                                                require(owner == generatingRequest) { "Tool callback outlived its request" }
                                                ownerReply.send("TOOL_RECEIVED", callId = callId)
                                                returned.errorCode?.let { throw LocalToolLimitException(it) }
                                                returned.text
                                            } finally {
                                                toolResults.remove(callId)
                                            }
                                        }
                                    }
                                )
                            )
                            reply.send(
                                "RESULT",
                                state =
                                runtime.state.value.copy(
                                    processIsolated = true,
                                    lifecycle = "READY",
                                    requestedMtp = config.speculative
                                )
                            )
                        }
                        "GENERATE" -> {
                            check(packet.sessionId.isNotBlank() && packet.sessionId == sessionId) { "Stale local session" }
                            generatingRequest = packet.requestId
                            var terminal: LocalRuntimeEvent? = null
                            val monitor =
                                scope.launch {
                                    while (jobs[packet.requestId]?.isActive == true) {
                                        delay(10_000)
                                        if (runtime.getHardwareState().thermalState >= DeviceThermalState.SEVERE) cancel(packet.requestId)
                                    }
                                }
                            try {
                                val stream =
                                    if (packet.experiment == null) {
                                        runtime.sendMessageWithBudget(packet.text, packet.images, packet.safetyTokens)
                                    } else {
                                        runtime
                                            .sendMessageWithBudget(packet.text, packet.images, packet.safetyTokens)
                                            .flowOn(LocalRuntimeTrialContext(packet.experiment))
                                    }
                                stream.collect { event ->
                                    when (event) {
                                        is LocalRuntimeEvent.TextDelta -> reply.stream("TEXT", event.text)
                                        is LocalRuntimeEvent.ThinkingDelta -> reply.stream("THINKING", event.text)
                                        is LocalRuntimeEvent.PhaseChanged -> {
                                            reply.send(
                                                "PHASE",
                                                text = event.phase.name,
                                                state = runtime.state.value.copy(processIsolated = true),
                                                nativeProgress = event.nativeProgress
                                            )
                                            if (event.nativeProgress?.stage == "TIMEOUT_CANCEL_REQUESTED") {
                                                // Same worker-death bound as explicit cancellation, without discarding the partial native receipt.
                                                val owner = jobs[packet.requestId]
                                                scope.launch {
                                                    delay(10_000)
                                                    // A cancelled coroutine can report completion while
                                                    // native prefill still owns the operation lease.
                                                    // Match the explicit cancellation watchdog.
                                                    if (activeCommandRequest == packet.requestId ||
                                                        owner?.isCompleted == false
                                                    ) {
                                                        terminateWorker("PREFILL_CANCELLATION_UNACKNOWLEDGED", packet.requestId)
                                                    }
                                                }
                                            }
                                        }
                                        is LocalRuntimeEvent.Metrics -> reply.send("METRICS", metrics = event.metrics)
                                        is LocalRuntimeEvent.Error -> terminal = event
                                        is LocalRuntimeEvent.Drain -> event.acknowledged.complete(Unit)
                                        LocalRuntimeEvent.Done -> if (terminal == null) terminal = event
                                    }
                                }
                                // Publish the terminal event only after native completion/cleanup has returned.
                                val final = terminal
                                if (final is LocalRuntimeEvent.Error) {
                                    val code = localRuntimeErrorCode(final.cause)
                                    val safeToReuse =
                                        runtime.state.value.engineSpec
                                            ?.let { runtime.isEngineLoaded(it) } == true
                                    reply.send(
                                        "ERROR",
                                        text = final.message,
                                        errorCode = code,
                                        state =
                                        runtime.state.value.copy(
                                            processIsolated = true,
                                            engineHealth = if (safeToReuse) "HEALTHY" else "RESET_REQUIRED"
                                        )
                                    )
                                } else if (final is LocalRuntimeEvent.Done) {
                                    reply.send("DONE", state = runtime.state.value.copy(processIsolated = true))
                                } else {
                                    reply.send("ERROR", text = "Local generation ended without completion")
                                }
                            } finally {
                                monitor.cancel()
                            }
                        }
                        "CLOSE" -> {
                            runtime.closeConversation()
                            sessionId = ""
                            reply.send("RESULT", state = runtime.state.value.copy(processIsolated = true, lifecycle = "READY"))
                        }
                        "UNLOAD" -> {
                            runtime.unloadEngine()
                            dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources
                                .closeLeases()
                            sessionId = ""
                            reply.send("RESULT", state = LocalRuntimeState(processIsolated = true))
                        }
                        else -> error("Unsupported local operation")
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        runCatching { runtime.closeConversation() }.onFailure {
                            if (it is NativeCancellationTimeoutException ||
                                it is NativeResourceCleanupException
                            ) {
                                terminateWorker("NATIVE_CLEANUP_FAILED", packet.requestId)
                            }
                        }
                    }
                    val safeToReuse =
                        withContext(NonCancellable) {
                            runtime.state.value.engineSpec
                                ?.let { runtime.isEngineLoaded(it) } == true
                        }
                    reply.send(
                        "CANCELLED",
                        text = "Local request cancelled after native cleanup",
                        state =
                        runtime.state.value.copy(
                            processIsolated = true,
                            engineHealth = if (safeToReuse) "HEALTHY" else "RESET_REQUIRED"
                        )
                    )
                } catch (error: Throwable) {
                    if (error is NativeCancellationTimeoutException ||
                        error is NativeResourceCleanupException
                    ) {
                        terminateWorker("NATIVE_CLEANUP_FAILED", packet.requestId)
                    }
                    val errorCode =
                        when (error) {
                            is LocalContextReplanRequired -> "AUTO_CONTEXT_REPLAN"
                            is LocalModelConfigurationException -> "MODEL_CONFIGURATION"
                            is LocalNpuPackageException -> "NPU_PACKAGE"
                            else -> null
                        }
                    reply.send(
                        "ERROR",
                        text = error.message ?: "Local worker failed",
                        errorCode = errorCode,
                        state =
                        runtime.state.value.copy(
                            processIsolated = true,
                            lifecycle = "FAILED",
                            admissionReceipt =
                            (error as? LocalMemoryAdmissionException)?.receipt ?: (error as? LocalContextReplanRequired)?.receipt
                                ?: runtime.state.value.admissionReceipt
                        ),
                        loadAttempts = (error as? LocalLoadFailure)?.attempts.orEmpty(),
                        contextReplan = error as? LocalContextReplanRequired
                    )
                } finally {
                    if (generatingRequest == packet.requestId) generatingRequest = null
                    if (activeCommandRequest == packet.requestId) activeCommandRequest = null
                    jobs.remove(packet.requestId)
                    replies.remove(packet.requestId)
                    operationLock.unlock()
                    reply.flushTerminal()
                }
            }
        jobs[packet.requestId] = job
        replies[packet.requestId] = reply
        job.start()
    }

    private fun cancel(requestId: String) {
        if (terminationGate.isTerminating()) return
        val job = jobs[requestId] ?: return
        // Signal the native abort flag immediately; cancelling a coroutine cannot
        // interrupt a blocking JNI call. Only target the command holding the lease.
        if (activeCommandRequest == requestId) runCatching { runtime.cancelActive() }
        job.cancel()
        scope.launch {
            delay(10_000)
            // Cancellation may finish the coroutine while JNI remains blocked. Do
            // not trust Job completion alone as proof that the native lease was released.
            if (activeCommandRequest == requestId || !job.isCompleted) {
                terminateWorker("CANCELLATION_UNACKNOWLEDGED", requestId)
            }
        }
    }

    private fun terminateWorker(
        reason: String,
        requestId: String
    ) {
        // Multiple watchdogs, cancellation and service destruction may race.
        // Only the first request records a termination; no further native calls are allowed.
        if (!terminationGate.beginTermination()) return
        runCatching {
            NativeWorkerTermination.record(
                File(noBackupFilesDir, "native-worker"),
                Process.myPid(),
                requestId,
                generation,
                reason,
                loadProgress ?: runtime.state.value.loadProgress
            )
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "NativeRuntime",
            "WORKER_TERMINATION_REQUESTED · pid=${Process.myPid()} · request=$requestId · generation=$generation · reason=$reason · operation=${loadProgress?.operationId ?: -1} · stage=${loadProgress?.stage.orEmpty()}",
            "W"
        )
        Process.killProcess(Process.myPid())
    }

    private fun validatePath(path: String) {
        val file = File(path).canonicalFile
        val roots = listOfNotNull(filesDir, noBackupFilesDir, getExternalFilesDir(null)).map { it.canonicalFile }
        require(
            file.isFile && roots.any { file.path.startsWith(it.path + File.separator) }
        ) { "Model must be an installed app-owned artifact" }
    }

    override fun onDestroy() {
        scope.cancel()
        thread.quitSafely()
        // Terminating this private process releases native pointers even after an unacknowledged callback.
        terminateWorker("SERVICE_DESTROYED", generatingRequest.orEmpty())
        super.onDestroy()
    }

    private inner class Reply(
        private val request: RuntimeWire,
        private val target: Messenger
    ) {
        private var sequence = 0L
        private val baselineGeneration = runtime.latestExecutionReceipt?.nativeGenerationId
        private val baselineInspectionId = runtime.latestInspectionReceipt?.inspectionId
        var deferTerminal = false
        private var terminal: RuntimeWire? = null
        private val credits = Semaphore(8)
        private val outstanding = mutableSetOf<Long>()

        @Synchronized fun acknowledge(sequence: Long) {
            if (outstanding.remove(sequence)) credits.release()
        }

        suspend fun stream(
            kind: String,
            text: String
        ) {
            credits.acquire()
            synchronized(this) {
                val nextSequence = sequence + 1
                outstanding.add(nextSequence)
                try {
                    send(kind, text)
                } catch (failure: Throwable) {
                    // A failed Binder send cannot ever be acknowledged.
                    // Return its credit so a later stream does not deadlock.
                    if (outstanding.remove(nextSequence)) credits.release()
                    throw failure
                }
            }
        }

        @Synchronized fun send(
            kind: String,
            text: String = "",
            metrics: LocalInferenceMetrics? = null,
            state: LocalRuntimeState? = null,
            capabilities: LocalModelCapabilities? = null,
            callId: String = "",
            toolName: String = "",
            errorCode: String? = null,
            loadAttempts: List<LocalLoadAttempt> = emptyList(),
            nativeProgress: GgufNativeProgress? = null,
            contextReplan: LocalContextReplanRequired? = null
        ) {
            val native =
                if (request.operation == "GENERATE") {
                    (nativeProgress?.executionReceipt ?: metrics?.runtimeExecutionReceipt ?: runtime.latestExecutionReceipt)
                        ?.takeIf { it.nativeGenerationId != null && it.nativeGenerationId != baselineGeneration }
                        ?.copy(
                            runtimeRequestId = request.requestId,
                            workerGenerationId = generation,
                            physicalAttemptId = request.physicalAttemptId
                        )
                } else {
                    null
                }
            val reportedState =
                state?.copy(
                    engineLoadReceipt =
                    (runtime.latestEngineLoadReceipt ?: state.engineLoadReceipt)?.copy(
                        workerGenerationId = generation,
                        workerPid = Process.myPid()
                    )
                )
            val packet =
                RuntimeWire(
                    operation = kind,
                    requestId = request.requestId,
                    sessionId = request.sessionId,
                    generation = generation,
                    sequence = ++sequence,
                    text = text,
                    metrics = metrics,
                    state = reportedState,
                    capabilities = capabilities,
                    callId = callId,
                    toolName = toolName,
                    errorCode = errorCode,
                    loadAttempts = loadAttempts,
                    processPssBytes =
                    if (kind in setOf("METRICS", "ERROR", "CANCELLED", "RESULT") ||
                        nativeProgress?.stage in setOf("PREFILL_BEGIN", "TIMEOUT_CANCEL_REQUESTED", "NATIVE_RETURN")
                    ) {
                        android.os.Debug.getPss() *
                            1024
                    } else {
                        null
                    },
                    nativeProgress = nativeProgress,
                    executionReceipt = native,
                    inspectionReceipt = runtime.latestInspectionReceipt?.takeIf { it.inspectionId != baselineInspectionId }?.copy(
                        runtimeRequestId = request.requestId,
                        workerGenerationId = generation
                    ),
                    attemptedContextTokens = contextReplan?.attemptedContextTokens,
                    suggestedContextTokens = contextReplan?.suggestedContextTokens
                )
            if (deferTerminal && kind in setOf("RESULT", "DONE", "ERROR", "CANCELLED")) {
                terminal = packet
            } else {
                RuntimeWireTransport.send(this@LocalInferenceService, target, packet)
            }
        }

        @Synchronized fun flushTerminal() {
            // A caller may issue its next command as soon as it receives this packet.
            terminal?.let { RuntimeWireTransport.send(this@LocalInferenceService, target, it) }
            terminal = null
        }
    }
}

internal fun isInferenceProcess(): Boolean =
    android.app.Application
        .getProcessName()
        ?.endsWith(":local_inference") == true
