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
    private var settings = AppFeatureSettings()
    private var backend = LocalRuntimeBackend.DEFAULT
    private lateinit var runtime: LocalRuntime
    private lateinit var messenger: Messenger
    private val operationLock = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val replies = ConcurrentHashMap<String, Reply>()
    private val toolResults = ConcurrentHashMap<String, CompletableDeferred<RuntimeWire>>()
    private var sessionId = ""

    @Volatile private var generatingRequest: String? = null

    override fun onCreate() {
        super.onCreate()
        check(isInferenceProcess()) { "Native inference service must run in its private process" }
        runtime = LocalRuntimeRouter(
            qnnRuntime = LocalRuntimeQnnImpl(this),
            liteRtRuntime = LocalRuntimeImpl(this),
            artifactRuntime = NativeArtifactRuntimeFactory.create(this),
            settingsSnapshot = { settings },
            backendSnapshot = { backend }
        )
        thread.start()
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != Process.myUid()) return
                val target = message.replyTo ?: return
                val packet = runCatching { RuntimeWireTransport.read(message) }.getOrElse { return }
                when (packet.operation) {
                    "ACK" -> replies[packet.requestId]?.acknowledge(packet.sequence)
                    "TOOL_RESULT" -> if (packet.generation == generation && packet.requestId == generatingRequest && packet.sessionId == sessionId) toolResults[packet.callId]?.complete(packet)
                    "CANCEL" -> cancel(packet.requestId)
                    "SHUTDOWN" -> Process.killProcess(Process.myPid())
                    else -> start(packet, target)
                }
            }
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun start(packet: RuntimeWire, target: Messenger) {
        val reply = Reply(packet, target)
        if (packet.generation.isNotBlank() && packet.generation != generation) {
            reply.send("ERROR", text = "Inference worker changed. Start a new request; interrupted work is not replayed.")
            return
        }
        if (!operationLock.tryLock()) {
            reply.send("ERROR", text = "The native worker is still busy or cancelling")
            return
        }
        reply.deferTerminal = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (packet.operation != "PING") QnnEnvironment.initialize(this@LocalInferenceService)
                when (packet.operation) {
                    "PING" -> reply.send("RESULT", text = packet.text)
                    "INSPECT" -> {
                        validatePath(packet.text)
                        reply.send("RESULT", capabilities = runtime.inspectModel(packet.text))
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
                        dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.closeLeases()
                        runtime.setLoadProgressObserver { progress -> reply.send("LOAD_PROGRESS", state = runtime.state.value.copy(processIsolated = true, lifecycle = "LOADING", loadProgress = progress, allocationObservation = progress.allocationObservation ?: runtime.state.value.allocationObservation), nativeProgress = progress) }
                        try {
                            runtime.loadEngine(spec)
                        } finally {
                            runtime.setLoadProgressObserver(null)
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
                        reply.send("RESULT", state = runtime.state.value.copy(processIsolated = true, lifecycle = "READY", requestedMtp = config.speculative))
                    }
                    "GENERATE" -> {
                        check(packet.sessionId.isNotBlank() && packet.sessionId == sessionId) { "Stale local session" }
                        generatingRequest = packet.requestId
                        var terminal: LocalRuntimeEvent? = null
                        val monitor = scope.launch {
                            while (jobs[packet.requestId]?.isActive == true) {
                                delay(10_000)
                                if (runtime.getHardwareState().thermalState >= DeviceThermalState.SEVERE) cancel(packet.requestId)
                            }
                        }
                        try {
                            runtime.sendMessage(packet.text, packet.images).collect { event ->
                                when (event) {
                                    is LocalRuntimeEvent.TextDelta -> reply.stream("TEXT", event.text)
                                    is LocalRuntimeEvent.ThinkingDelta -> reply.stream("THINKING", event.text)
                                    is LocalRuntimeEvent.PhaseChanged -> {
                                        reply.send("PHASE", text = event.phase.name, state = runtime.state.value.copy(processIsolated = true), nativeProgress = event.nativeProgress)
                                        if (event.nativeProgress?.stage == "TIMEOUT_CANCEL_REQUESTED") {
                                            // Same worker-death bound as explicit cancellation, without discarding the partial native receipt.
                                            val owner = jobs[packet.requestId]
                                            scope.launch {
                                                delay(10_000)
                                                if (owner?.isCompleted == false) Process.killProcess(Process.myPid())
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
                                val code = generateSequence(final.cause) { it.cause }.filterIsInstance<LocalToolLimitException>().firstOrNull()?.code
                                val safeToReuse = runtime.state.value.engineSpec?.let { runtime.isEngineLoaded(it) } == true
                                reply.send("ERROR", text = final.message, errorCode = code, state = runtime.state.value.copy(processIsolated = true, engineHealth = if (safeToReuse) "HEALTHY" else "RESET_REQUIRED"))
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
                        reply.send("RESULT")
                    }
                    "UNLOAD" -> {
                        runtime.unloadEngine()
                        dev.chungjungsoo.gptmobile.data.localmodel.LinkedModelSources.closeLeases()
                        sessionId = ""
                        reply.send("RESULT", state = LocalRuntimeState(processIsolated = true))
                    }
                    else -> error("Unsupported local operation")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { runtime.closeConversation() }.onFailure { if (it is NativeCancellationTimeoutException || it is NativeResourceCleanupException) Process.killProcess(Process.myPid()) } }
                val safeToReuse = withContext(NonCancellable) { runtime.state.value.engineSpec?.let { runtime.isEngineLoaded(it) } == true }
                reply.send("CANCELLED", text = "Local request cancelled after native cleanup", state = runtime.state.value.copy(processIsolated = true, engineHealth = if (safeToReuse) "HEALTHY" else "RESET_REQUIRED"))
            } catch (error: Throwable) {
                if (error is NativeCancellationTimeoutException || error is NativeResourceCleanupException) Process.killProcess(Process.myPid())
                val errorCode = when (error) {
                    is LocalModelConfigurationException -> "MODEL_CONFIGURATION"
                    is LocalNpuPackageException -> "NPU_PACKAGE"
                    else -> null
                }
                reply.send("ERROR", text = error.message ?: "Local worker failed", errorCode = errorCode, state = runtime.state.value.copy(processIsolated = true, lifecycle = "FAILED", admissionReceipt = (error as? LocalMemoryAdmissionException)?.receipt ?: runtime.state.value.admissionReceipt), loadAttempts = (error as? LocalLoadFailure)?.attempts.orEmpty())
            } finally {
                if (generatingRequest == packet.requestId) generatingRequest = null
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
        val job = jobs[requestId] ?: return
        job.cancel()
        scope.launch {
            delay(10_000)
            if (!job.isCompleted) Process.killProcess(Process.myPid())
        }
    }

    private fun validatePath(path: String) {
        val file = File(path).canonicalFile
        val roots = listOfNotNull(filesDir, noBackupFilesDir, getExternalFilesDir(null)).map { it.canonicalFile }
        require(file.isFile && roots.any { file.path.startsWith(it.path + File.separator) }) { "Model must be an installed app-owned artifact" }
    }

    override fun onDestroy() {
        scope.cancel()
        thread.quitSafely()
        // Terminating this private process releases native pointers even after an unacknowledged callback.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }

    private inner class Reply(private val request: RuntimeWire, private val target: Messenger) {
        private var sequence = 0L
        var deferTerminal = false
        private var terminal: RuntimeWire? = null
        private val credits = Semaphore(8)
        private val outstanding = mutableSetOf<Long>()

        @Synchronized fun acknowledge(sequence: Long) {
            if (outstanding.remove(sequence)) credits.release()
        }

        suspend fun stream(kind: String, text: String) {
            credits.acquire()
            synchronized(this) {
                outstanding.add(sequence + 1)
                send(kind, text)
            }
        }

        @Synchronized fun send(kind: String, text: String = "", metrics: LocalInferenceMetrics? = null, state: LocalRuntimeState? = null, capabilities: LocalModelCapabilities? = null, callId: String = "", toolName: String = "", errorCode: String? = null, loadAttempts: List<LocalLoadAttempt> = emptyList(), nativeProgress: GgufNativeProgress? = null) {
            val packet = RuntimeWire(
                operation = kind, requestId = request.requestId, sessionId = request.sessionId, generation = generation, sequence = ++sequence,
                text = text, metrics = metrics, state = state, capabilities = capabilities, callId = callId, toolName = toolName, errorCode = errorCode, loadAttempts = loadAttempts,
                processPssBytes = if (kind in setOf("METRICS", "ERROR", "CANCELLED", "RESULT") || nativeProgress?.stage in setOf("PREFILL_BEGIN", "TIMEOUT_CANCEL_REQUESTED", "NATIVE_RETURN")) android.os.Debug.getPss() * 1024 else null,
                nativeProgress = nativeProgress
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

internal fun isInferenceProcess(): Boolean = android.app.Application.getProcessName()?.endsWith(":local_inference") == true
