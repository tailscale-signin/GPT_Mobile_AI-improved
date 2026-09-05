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
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Production [LocalRuntime] implementation wrapping LiteRT-LM. */
class LocalRuntimeImpl(
    private val context: Context
) : LocalRuntime {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedAccelerator: String = LocalAccelerators.CPU
    private var loadedSpec: LocalEngineSpec? = null

    private val activityManager: ActivityManager? by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    private val deviceRamGb: Long by lazy {
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

    private val isMidRamDevice: Boolean by lazy {
        deviceRamGb >= 6L
    }

    private fun getAvailableMemoryMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024L * 1024L)
    }

    private fun isLowMemoryDevice(): Boolean {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || (memoryInfo.availMem / (1024L * 1024L) < 500L)
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        withContext(Dispatchers.IO) {
            // Apply memory safety guardrail: if device is under memory pressure, throttle maxNumTokens
            val effectiveMaxTokens = if (isLowMemoryDevice() && spec.maxTokens > 1024) {
                Log.w(TAG, "Device low memory detected; throttling maxTokens from ${spec.maxTokens} to 1024")
                1024
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
                    val engineConfig = EngineConfig(
                        modelPath = spec.modelPath,
                        backend = backendFor(candidateAccelerator),
                        visionBackend = visionBackendFor(spec.copy(accelerator = candidateAccelerator)),
                        audioBackend = null,
                        maxNumTokens = effectiveMaxTokens,
                        maxNumImages = if (spec.isVisionEnabled) MAX_IMAGES_PER_MESSAGE else null
                    )
                    val nextEngine = Engine(engineConfig)
                    nextEngine.initialize()
                    initializedEngine = nextEngine
                    actualAccelerator = candidateAccelerator
                    Log.i(TAG, "Successfully initialized LiteRT-LM engine with accelerator: $candidateAccelerator")
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed initializing LiteRT-LM engine with accelerator $candidateAccelerator: ${t.message}")
                    lastError = t
                }
            }

            if (initializedEngine == null) {
                throw lastError ?: IllegalStateException("Failed to initialize LiteRT-LM engine with any accelerator")
            }

            engine = initializedEngine
            loadedAccelerator = actualAccelerator
            loadedSpec = spec.copy(accelerator = actualAccelerator, maxTokens = effectiveMaxTokens)
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun createConversation(config: LocalConversationConfig) {
        withContext(Dispatchers.IO) {
            val currentEngine = engine ?: error("LiteRT-LM engine is not loaded")
            conversation?.close()
            val toolProviders = config.tools.map { descriptor ->
                tool(BridgedOpenApiTool(descriptor, config.toolExecutor))
            }
            val previousConstrainedDecoding = ExperimentalFlags.enableConversationConstrainedDecoding
            ExperimentalFlags.enableConversationConstrainedDecoding = config.isConstrainedDecodingEnabled
            try {
                conversation = currentEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = config.systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                        initialMessages = config.initialMessages.map { message ->
                            when (message.role) {
                                LocalHistoryRole.USER -> Message.user(contentsOf(message.text, message.images))
                                LocalHistoryRole.MODEL -> Message.model(contentsOf(message.text, message.images))
                            }
                        },
                        tools = toolProviders,
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
            } finally {
                ExperimentalFlags.enableConversationConstrainedDecoding = previousConstrainedDecoding
            }
        }
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = callbackFlow {
        val activeConversation = conversation
        if (activeConversation == null) {
            trySend(LocalRuntimeEvent.Error("LiteRT-LM conversation is not ready"))
            close()
            return@callbackFlow
        }

        val startTimeMs = SystemClock.elapsedRealtime()
        val firstTokenTimeMs = AtomicLong(0L)
        val chunkCount = AtomicInteger(0)
        val totalCharacters = AtomicInteger(0)
        val hasEmittedAny = AtomicBoolean(false)

        activeConversation.sendMessageAsync(
            contentsOf(text, images),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val now = SystemClock.elapsedRealtime()
                    if (firstTokenTimeMs.compareAndSet(0L, now)) {
                        hasEmittedAny.set(true)
                    }

                    message.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotEmpty() }?.let { thought ->
                        trySend(LocalRuntimeEvent.ThinkingDelta(thought))
                    }
                    val visibleText = message.visibleText()
                    if (visibleText.isNotEmpty()) {
                        chunkCount.incrementAndGet()
                        totalCharacters.addAndGet(visibleText.length)
                        trySend(LocalRuntimeEvent.TextDelta(visibleText))
                    }
                }

                override fun onDone() {
                    val finishTimeMs = SystemClock.elapsedRealtime()
                    val totalDuration = finishTimeMs - startTimeMs
                    val ttft = if (firstTokenTimeMs.get() > 0L) firstTokenTimeMs.get() - startTimeMs else totalDuration
                    val chars = totalCharacters.get()
                    // Rough approximation: ~4 characters per token for English/general text
                    val estimatedTokens = (chars / 4).coerceAtLeast(chunkCount.get())
                    val tps = if (totalDuration > 0) (estimatedTokens.toDouble() / (totalDuration.toDouble() / 1000.0)) else 0.0

                    val metrics = LocalInferenceMetrics(
                        timeToFirstTokenMs = ttft,
                        totalDurationMs = totalDuration,
                        totalChunks = chunkCount.get(),
                        totalCharacters = chars,
                        estimatedTokens = estimatedTokens,
                        tokensPerSecond = tps
                    )
                    trySend(LocalRuntimeEvent.Metrics(metrics))
                    trySend(LocalRuntimeEvent.Done)
                    close()
                }

                override fun onError(throwable: Throwable) {
                    if (throwable is CancellationException || throwable is kotlinx.coroutines.CancellationException) {
                        trySend(LocalRuntimeEvent.Done)
                    } else {
                        trySend(
                            LocalRuntimeEvent.Error(
                                message = throwable.message ?: "Local inference failed",
                                cause = throwable
                            )
                        )
                    }
                    close()
                }
            }
        )

        awaitClose {
            runCatching { activeConversation.cancelProcess() }
        }
    }.buffer(Channel.UNLIMITED)

    override fun cancelActive() {
        runCatching { conversation?.cancelProcess() }
    }

    override fun hasOpenConversation(): Boolean = conversation != null

    override fun isEngineLoaded(spec: LocalEngineSpec): Boolean = engine != null && loadedSpec == spec

    override suspend fun closeConversation() {
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            conversation = null
        }
    }

    override suspend fun unloadEngine() {
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            conversation = null
            runCatching { engine?.close() }
            engine = null
            loadedSpec = null
            loadedAccelerator = LocalAccelerators.CPU
        }
    }

    private fun backendFor(accelerator: String): Backend = when (LocalAccelerators.normalize(accelerator)) {
        LocalAccelerators.GPU -> Backend.GPU()
        LocalAccelerators.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
    }

    private fun visionBackendFor(spec: LocalEngineSpec): Backend? {
        if (!spec.isVisionEnabled) return null
        return when (LocalAccelerators.normalize(spec.accelerator)) {
            LocalAccelerators.CPU -> Backend.CPU()
            LocalAccelerators.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
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

    private companion object {
        const val TAG = "LocalRuntimeImpl"
        const val THOUGHT_CHANNEL = "thought"
        const val MAX_IMAGES_PER_MESSAGE = 10
    }
}

private class BridgedOpenApiTool(
    private val descriptor: LocalToolDescriptor,
    private val executor: LocalToolExecutor?
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", descriptor.name)
        put("description", descriptor.description)
        put("parameters", Json.parseToJsonElement(descriptor.inputSchemaJson))
    }.toString()

    override fun execute(paramsJsonString: String): String = runBlocking {
        val current = executor ?: error("LiteRT-LM tool executor is not registered")
        try {
            withTimeout(TOOL_EXECUTE_TIMEOUT_MS) {
                current.execute(descriptor.name, paramsJsonString)
            }
        } catch (error: TimeoutCancellationException) {
            "Tool '${descriptor.name}' failed: ${error.message ?: "timed out"}"
        } catch (error: Exception) {
            "Tool '${descriptor.name}' failed: ${error.message ?: "unknown error"}"
        }
    }

    private companion object {
        const val TOOL_EXECUTE_TIMEOUT_MS = 60_000L
    }
}
