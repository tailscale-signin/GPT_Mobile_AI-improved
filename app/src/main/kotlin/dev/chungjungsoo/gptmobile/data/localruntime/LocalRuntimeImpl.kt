package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

open class LocalRuntimeImpl(
    private val context: Context
) : LocalRuntime {

    private val activityManager: ActivityManager? by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    override val deviceRamGb: Long by lazy {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalBytes = memInfo.totalMem
        if (totalBytes > 0) {
            totalBytes / (1024L * 1024L * 1024L)
        } else {
            8L
        }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeConversationConfig: LocalConversationConfig? = null
    private var loadedSpec: LocalEngineSpec? = null
    private var loadedAccelerator: String = LocalAccelerators.CPU
    private var conversationHistoryFingerprint: String? = null

    private val runtimeMutex = Mutex()
    private val activeJob = AtomicBoolean(false)
    private val lastActiveTimestampMs = AtomicLong(System.currentTimeMillis())

    private val json = Json { ignoreUnknownKeys = true }

    override fun getHardwareState(): DeviceHardwareState =
        DeviceHardwareGovernor.getHardwareState(context)

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        DeviceHardwareGovernor.computeThrottlingPolicy(getHardwareState(), deviceRamGb >= 10L)

    private fun isLowMemoryDevice(): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        return memInfo.lowMemory || (memInfo.availMem < 512L * 1024L * 1024L)
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        withContext(Dispatchers.IO) {
            // Pre-flight model integrity verification to avoid native hard crashes (SIGSEGV)
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
                    Log.i(TAG, "Model file verified: ${validation.file.name} (${validation.sizeBytes} bytes)")
                }
            }

            // Apply memory safety guardrail: if device is under memory pressure or thermal/battery throttling, clamp maxNumTokens
            val throttling = getAdaptiveThrottlingPolicy()
            val effectiveMaxTokens = if (isLowMemoryDevice() && spec.maxTokens > 1024) {
                Log.w(TAG, "Device low memory detected; throttling maxTokens from ${spec.maxTokens} to 1024")
                1024
            } else if (throttling.maxTokensClamp != null && spec.maxTokens > throttling.maxTokensClamp) {
                Log.w(TAG, "Device hardware thermal/battery throttle active; clamping maxTokens from ${spec.maxTokens} to ${throttling.maxTokensClamp}")
                throttling.maxTokensClamp
            } else {
                spec.maxTokens
            }

            // Try loading with the requested accelerator first; if it fails (e.g. driver issue with GPU/NPU),
            // gracefully cascade fallback to CPU.
            val acceleratorsToAttempt = buildList {
                add(spec.accelerator)
                val normalized = LocalAccelerators.normalize(spec.accelerator)
                if (normalized == LocalAccelerators.NPU) {
                    add(LocalAccelerators.GPU)
                    add(LocalAccelerators.CPU)
                } else if (normalized == LocalAccelerators.GPU) {
                    add(LocalAccelerators.CPU)
                }
            }.distinct()

            var lastError: Throwable? = null
            var initializedEngine: Engine? = null
            var actualAccelerator = spec.accelerator

            for (candidateAccelerator in acceleratorsToAttempt) {
                try {
                    Log.i(TAG, "Attempting to initialize LiteRT-LM engine with accelerator: $candidateAccelerator")
                    val candidateSpec = spec.copy(accelerator = candidateAccelerator)
                    val engineConfig = EngineConfig(
                        modelPath = spec.modelPath,
                        backend = backendFor(candidateAccelerator, candidateSpec),
                        visionBackend = visionBackendFor(candidateSpec),
                        audioBackend = null,
                        maxNumTokens = effectiveMaxTokens,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    initializedEngine = Engine(engineConfig).also { it.initialize() }
                    actualAccelerator = candidateAccelerator
                    Log.i(TAG, "Successfully initialized LiteRT-LM engine on $candidateAccelerator")
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to initialize LiteRT-LM engine on $candidateAccelerator: ${t.message}. Falling back...")
                    lastError = t
                }
            }

            val finalEngine = initializedEngine
                ?: throw IllegalStateException("Could not initialize local model engine on any accelerator", lastError)

            runtimeMutex.withLock {
                runCatching { conversation?.close() }
                conversation = null
                activeConversationConfig = null
                conversationHistoryFingerprint = null

                runCatching { engine?.close() }
                engine = finalEngine
                loadedSpec = spec
                loadedAccelerator = actualAccelerator
                lastActiveTimestampMs.set(System.currentTimeMillis())
            }
        }
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        runtimeMutex.withLock {
            val eng = engine ?: error("Engine is not loaded yet.")

            // Skip re-creation if the conversation is already open with the exact same initial history
            val newFingerprint = ConversationFingerprint.compute(config.initialMessages)
            if (conversation != null &&
                activeConversationConfig == config &&
                conversationHistoryFingerprint == newFingerprint
            ) {
                Log.d(TAG, "Conversation already initialized with matching configuration & history. Reusing.")
                return@withLock
            }

            runCatching { conversation?.close() }
            conversation = null
            activeConversationConfig = null
            conversationHistoryFingerprint = null

            val mappedTools = config.tools.map { desc ->
                Tool(
                    name = desc.name,
                    description = desc.description,
                    parameters = desc.inputSchemaJson
                )
            }

            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = config.sampler.topK,
                    topP = config.sampler.topP,
                    temperature = config.sampler.temperature
                ),
                systemInstruction = config.systemPrompt?.let { Content.Text(it) },
                tools = mappedTools.ifEmpty { null }
            )

            val conv = eng.createConversation(conversationConfig)
            for (msg in config.initialMessages) {
                val mappedRole = when (msg.role) {
                    LocalHistoryRole.USER -> Message.Role.USER
                    LocalHistoryRole.MODEL -> Message.Role.ASSISTANT
                }
                conv.addMessage(Message(mappedRole, contentsOf(msg.text, msg.images)))
            }

            conversation = conv
            activeConversationConfig = config
            conversationHistoryFingerprint = newFingerprint
            lastActiveTimestampMs.set(System.currentTimeMillis())
        }
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = callbackFlow {
        lastActiveTimestampMs.set(System.currentTimeMillis())
        activeJob.set(true)

        val conv: Conversation
        val eng: Engine
        val config: LocalConversationConfig?
        synchronized(this@LocalRuntimeImpl) {
            conv = conversation ?: run {
                close(IllegalStateException("No conversation active."))
                return@callbackFlow
            }
            eng = engine ?: run {
                close(IllegalStateException("No engine loaded."))
                return@callbackFlow
            }
            config = activeConversationConfig
        }

        val startTime = System.currentTimeMillis()
        var firstTokenTime = 0L
        var totalChunks = 0
        var totalChars = 0

        try {
            send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))

            // Speculative decoding / Phase-split execution loop with cooperative yielding
            var isFirst = true
            var consecutiveYieldCount = 0

            val flowResponse = conv.sendMessage(contentsOf(text, images))
            flowResponse.collect { message ->
                if (!activeJob.get()) {
                    return@collect
                }

                if (isFirst) {
                    isFirst = false
                    firstTokenTime = System.currentTimeMillis()
                    send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING))
                }

                val chunkText = message.visibleText()
                if (chunkText.isNotEmpty()) {
                    totalChunks++
                    totalChars += chunkText.length
                    send(LocalRuntimeEvent.TextDelta(chunkText))
                }

                // Parse function calls if model emitted tool calls
                message.contents.contents.filterIsInstance<Content.FunctionCall>().forEach { call ->
                    val toolName = call.name
                    val argsJson = call.args.toString()
                    val executor = config?.toolExecutor
                    if (executor != null) {
                        send(LocalRuntimeEvent.ThinkingDelta("\nExecuting tool '$toolName'...\n"))
                        try {
                            val resultString = executor.execute(toolName, argsJson)
                            conv.addMessage(
                                Message(
                                    Message.Role.TOOL,
                                    Contents.of(Content.FunctionResponse(toolName, JsonObject(mapOf("result" to JsonPrimitive(resultString))))))
                            )
                        } catch (t: Throwable) {
                            send(LocalRuntimeEvent.ThinkingDelta("\nTool execution failed: ${t.message}\n"))
                        }
                    }
                }

                // Adaptive cooperative yielding based on device thermal governor
                consecutiveYieldCount++
                val throttling = getAdaptiveThrottlingPolicy()
                if (consecutiveYieldCount >= throttling.yieldFrequency) {
                    consecutiveYieldCount = 0
                    if (throttling.yieldSleepMs > 0) {
                        delay(throttling.yieldSleepMs)
                    } else {
                        kotlinx.coroutines.yield()
                    }
                }
            }

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            val ttft = if (firstTokenTime > 0) firstTokenTime - startTime else 0L
            val estimatedTokens = (totalChars / 4).coerceAtLeast(totalChunks)
            val genDurationSec = if (endTime > firstTokenTime && firstTokenTime > 0) {
                (endTime - firstTokenTime) / 1000.0
            } else {
                duration / 1000.0
            }
            val tokPerSec = if (genDurationSec > 0) estimatedTokens / genDurationSec else 0.0

            send(
                LocalRuntimeEvent.Metrics(
                    LocalInferenceMetrics(
                        timeToFirstTokenMs = ttft,
                        totalDurationMs = duration,
                        totalChunks = totalChunks,
                        totalCharacters = totalChars,
                        estimatedTokens = estimatedTokens,
                        tokensPerSecond = tokPerSec
                    )
                )
            )
            send(LocalRuntimeEvent.Done)
        } catch (c: CancellationException) {
            Log.d(TAG, "Local inference cancelled.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error in local model execution: ${t.message}", t)
            send(LocalRuntimeEvent.Error(t.message ?: "Unknown local execution error", t))
        } finally {
            activeJob.set(false)
            lastActiveTimestampMs.set(System.currentTimeMillis())
            channel.close()
        }

        awaitClose {
            activeJob.set(false)
        }
    }.flowOn(Dispatchers.IO)

    override fun cancelActive() {
        activeJob.set(false)
    }

    override fun hasOpenConversation(): Boolean = conversation != null

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean {
        val loaded = loadedSpec ?: return false
        return loaded.modelPath == spec.modelPath &&
            LocalAccelerators.normalize(loadedAccelerator) == LocalAccelerators.normalize(spec.accelerator)
    }

    override suspend fun closeConversation() {
        runtimeMutex.withLock {
            runCatching { conversation?.close() }
            conversation = null
            activeConversationConfig = null
            conversationHistoryFingerprint = null
        }
    }

    override suspend fun unloadEngine() {
        runtimeMutex.withLock {
            runCatching { conversation?.close() }
            conversation = null
            activeConversationConfig = null
            conversationHistoryFingerprint = null

            runCatching { engine?.close() }
            engine = null
            loadedSpec = null
            loadedAccelerator = LocalAccelerators.CPU
        }
    }

    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean {
        if (activeJob.get()) return false
        val idleDuration = System.currentTimeMillis() - lastActiveTimestampMs.get()
        if (idleDuration >= idleThresholdMs && engine != null) {
            Log.i(TAG, "Unloading engine after ${idleDuration}ms of inactivity.")
            unloadEngine()
            return true
        }
        return false
    }

    private fun backendFor(accelerator: String, spec: LocalEngineSpec? = null): Backend = when (LocalAccelerators.normalize(accelerator)) {
        LocalAccelerators.GPU -> Backend.GPU()
        LocalAccelerators.NPU -> {
            val dispatchDir = spec?.litertDispatchLibDir ?: context.applicationInfo.nativeLibraryDir
            Backend.NPU(nativeLibraryDir = dispatchDir)
        }
        else -> Backend.CPU()
    }

    private fun visionBackendFor(spec: LocalEngineSpec): Backend? {
        if (!spec.isVisionEnabled) return null
        return when (LocalAccelerators.normalize(spec.accelerator)) {
            LocalAccelerators.CPU -> Backend.CPU()
            LocalAccelerators.NPU -> {
                val dispatchDir = spec.litertDispatchLibDir ?: context.applicationInfo.nativeLibraryDir
                Backend.NPU(nativeLibraryDir = dispatchDir)
            }
            else -> Backend.GPU()
        }
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

    companion object {
        private const val TAG = "LocalRuntimeImpl"
    }
}
