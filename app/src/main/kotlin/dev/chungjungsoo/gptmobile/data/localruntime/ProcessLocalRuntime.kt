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
    private val readiness = LocalReadinessRegistry()
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
    override val isBusy get() = pending.isNotEmpty()

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

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = execute("INSPECT", text = modelPath).capabilities

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        readiness.reset()
        command(RuntimeWire("VERIFY_RESET", UUID.randomUUID().toString(), generation = generation, spec = spec))
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        if (isEngineLoaded(spec)) return
        val snapshot = settings.getFeatureSettings()
        val packet = RuntimeWire("LOAD", UUID.randomUUID().toString(), generation = generation, spec = spec, settings = snapshot, preferredBackend = settings.getLocalRuntimeBackend())
        val artifact = java.io.File(spec.modelPath)
        check(artifact.isFile || artifact.isDirectory) { "MISSING_ASSET: Download or relink the selected model before running it." }
        val identity = "${spec.engineConfiguration()}|${artifact.length()}|${artifact.lastModified()}|${dev.chungjungsoo.gptmobile.data.localmodel.LocalModelInstallationEpoch.current()}|${dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION}|${packet.preferredBackend}|${snapshot.localEngineTuning()}"
        try {
            mutableState.value = state.value.copy(lifecycle = "LOADING")
            readiness.qualify(identity) {
                if (!isEngineLoaded(spec)) command(packet, timeoutMs = 180_000)
            }
        } catch (error: Exception) {
            mutableState.value = state.value.copy(lifecycle = "FAILED", fallbackReason = error.message)
            throw error
        }
        requestedSpec = spec
        tuning = snapshot.localEngineTuning()
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
        val request = Pending(packet, LocalEventBuffer())
        pending[packet.requestId] = request
        try {
            send(packet)
            withTimeout(10 * 60_000L) {
                while (true) {
                    val event = request.events!!.take() ?: break
                    emit(event)
                }
                request.finished.await()
            }
        } finally {
            finishRequest(request)
        }
    }

    override fun cancelActive() {
        mutableState.value = state.value.copy(lifecycle = "CANCELLING")
        pending.values.forEach { request ->
            request.cancelling = true
            request.toolJobs.values.forEach { it.cancel() }
            scope.launch { runCatching { sendCancel(request.packet) } }
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
            mutableState.value = state.value.copy(lifecycle = "CANCELLING")
            runCatching { sendCancel(request.packet) }
            if (withTimeoutOrNull(12_000) {
                    runCatching { request.finished.await() }
                    true
                } != true
            ) {
                remote?.let { runCatching { RuntimeWireTransport.send(context, it, request.packet.copy(operation = "SHUTDOWN"), incoming) } }
                disconnect("Inference worker did not acknowledge cancellation. Partial output is preserved; start a new request.")
            }
        }
        pending.remove(request.packet.requestId)
    }

    private suspend fun sendCancel(packet: RuntimeWire) = withContext(Dispatchers.IO) {
        remote?.let { RuntimeWireTransport.send(context, it, packet.copy(operation = "CANCEL", text = "", images = emptyList(), spec = null, conversation = null, settings = null), incoming) }
    }

    private suspend fun send(packet: RuntimeWire) = withContext(Dispatchers.IO) {
        RuntimeWireTransport.send(context, connect(), packet, incoming)
    }

    private suspend fun connect(): Messenger = bindMutex.withLock {
        remote?.let { return@withLock it }
        val result = CompletableDeferred<Messenger>()
        binding = result
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                synchronized(this@ProcessLocalRuntime) {
                    if (connection !== this) return
                    try {
                        binder.linkToDeath({ if (connection === this) disconnect("Native inference worker stopped. Completed output is preserved; tools will not be repeated.") }, 0)
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
                    if (!accepted) sendCancel(request.packet)
                    remote?.let { RuntimeWireTransport.send(context, it, packet.copy(operation = "ACK", text = ""), incoming) }
                }
            }
            "PHASE" -> {
                mutableState.value = state.value.copy(lifecycle = if (packet.text == "PREFILL") "PREFILLING" else "DECODING")
                request.events?.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.valueOf(packet.text)))
            }
            "METRICS" -> packet.metrics?.let { request.events?.offer(LocalRuntimeEvent.Metrics(it)) }
            "TOOL" -> runTool(request, packet)
            "TOOL_RECEIVED" -> deliveryReceipts.remove(packet.callId)?.let { receipt -> scope.launch { receipt.acknowledge() } }
            "RESULT", "DONE", "ERROR", "CANCELLED" -> {
                if (packet.operation == "DONE") request.events?.offer(LocalRuntimeEvent.Done)
                if (packet.operation == "ERROR" || packet.operation == "CANCELLED") {
                    request.events?.offer(LocalRuntimeEvent.Error(packet.text, packet.errorCode?.let(::LocalToolLimitException)))
                    sessionId = ""
                }
                mutableState.value = state.value.copy(lifecycle = if (packet.operation == "ERROR") "FAILED" else "READY")
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

    private class Pending(val packet: RuntimeWire, val events: LocalEventBuffer? = null) {
        val finished = CompletableDeferred<RuntimeWire>()
        var sequence = 0L

        @Volatile var cancelling = false
        val toolJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    }
}
