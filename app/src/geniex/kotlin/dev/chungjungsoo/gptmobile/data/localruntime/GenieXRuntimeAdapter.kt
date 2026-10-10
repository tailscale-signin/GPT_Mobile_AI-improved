@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.SystemClock
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.jni.Llm
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Pinned 0.8.0 experimental binding. Direct callbacks are deliberate: the SDK's
 * convenience Flow ignores trySend failure and can silently lose tokens.
 * This preview APK owns GenieX's complete QAIRT library set, never qnn-runtime.
 */
internal class GenieXRuntimeAdapter(private val context: Context) : LocalRuntime {
    private val native by lazy { Llm() }
    private val guard = GenieXInterruptionGuard(context)

    @Volatile private var handle = 0L
    private var loaded: LocalEngineSpec? = null
    private var manifest: ModelArtifactManifest? = null
    private var configuration: LocalConversationConfig? = null
    private val history = mutableListOf<ChatMessage>()

    override fun loadedEngineSpec(): LocalEngineSpec? = loaded
    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean = handle != 0L && loaded == spec
    override fun hasOpenConversation(): Boolean = configuration != null
    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        val artifact = ArtifactManifestStore.read(file)
        artifact.validate(checkNotNull(file.parentFile))
        check(artifact.runtime in setOf(ArtifactRuntime.GENIEX_QAIRT, ArtifactRuntime.GENIEX_LLAMA_CPP)) { "This build supports GenieX QAIRT/GGUF bundles, not legacy Genie binaries." }
        LocalModelCapabilities(artifact.contextTokens, artifact.runtime == ArtifactRuntime.GENIEX_LLAMA_CPP, artifact.supportedBackends, false, false, false, false, artifact.soc)
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) = withContext(Dispatchers.IO) {
        val file = File(spec.modelPath)
        val root = checkNotNull(file.parentFile)
        val artifact = ArtifactManifestStore.read(file)
        artifact.validate(root, spec.accelerator.lowercase(), android.os.Build.SOC_MODEL)
        check(artifact.runtimeVersion == "0.8.0") { "This preview requires a bundle qualified for GenieX 0.8.0." }
        require(!spec.isVisionEnabled && spec.speculativeDecoding != true) { "Vision and speculative decoding are not qualified in this preview." }
        require(spec.maxTokens in 1..artifact.contextTokens)
        val qairt = artifact.runtime == ArtifactRuntime.GENIEX_QAIRT
        require(qairt || artifact.runtime == ArtifactRuntime.GENIEX_LLAMA_CPP) { "Unsupported artifact runtime" }
        require(!qairt || spec.accelerator.equals("npu", true)) { "QAIRT is NPU-only; CPU/GPU requests are never silently normalized." }
        unloadEngine()
        guard.before(spec)
        try {
            var initializationError: String? = null
            GenieXSdk.getInstance().init(
                context,
                object : GenieXSdk.InitCallback {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: String) {
                        initializationError = reason
                    }
                }
            )
            check(initializationError == null) { initializationError.orEmpty() }
            val next = native.create(
                LlmCreateInput(
                    model_path = ModelArtifactManifest.safeFile(root, artifact.entryFile).path,
                    tokenizer_path = artifact.tokenizerFile?.let { ModelArtifactManifest.safeFile(root, it).path },
                    config = ModelConfig(
                        nCtx = if (qairt) 0 else spec.maxTokens,
                        nGpuLayers = if (qairt || spec.accelerator.equals("cpu", true)) 0 else -1,
                        nThreads = spec.cpuThreads ?: 4,
                        nThreadsBatch = spec.cpuThreads ?: 4
                    ),
                    runtime_id = if (qairt) "qairt" else "llama_cpp",
                    compute_unit = spec.accelerator.lowercase()
                )
            )
            check(next != 0L) { "GenieX failed to create a model handle" }
            handle = next
            loaded = spec
            manifest = artifact
        } finally {
            guard.finished()
        }
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        check(handle != 0L)
        require(config.tools.isEmpty() && !config.isConstrainedDecodingEnabled) { "GenieX preview is Chat only. Tool execution and constrained JSON require separate qualification." }
        require(config.initialMessages.all { it.images.isEmpty() } && config.thinkingEnabled != true) { "This preview supports text in Quick mode only." }
        require(config.maxOutputTokens != null && config.maxOutputTokens in 1..4096) { "Set a bounded output budget of 1–4096 tokens." }
        require(config.sampler.temperature > 0) { "This binding treats temperature zero as a runtime default, not greedy sampling. Choose a positive temperature." }
        configuration = config
        history.clear()
        config.systemPrompt?.takeIf { it.isNotBlank() }?.let { history += ChatMessage("system", it) }
        history += config.initialMessages.map { ChatMessage(if (it.role == LocalHistoryRole.USER) "user" else "assistant", it.text) }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = callbackFlow {
        val config = checkNotNull(configuration)
        require(images.isEmpty()) { "GenieX preview does not accept image input." }
        val active = handle
        check(active != 0L)
        val started = SystemClock.elapsedRealtime()
        val output = StringBuilder()
        var completed = false
        var enteredNative = false
        var firstVisible: Long? = null
        fun offer(event: LocalRuntimeEvent): Boolean {
            if (trySend(event).isSuccess) return true
            close(IllegalStateException("GenieX output queue filled; generation stopped without dropping accepted text. Reduce the output budget."))
            return false
        }
        try {
            withContext(Dispatchers.IO) {
                guard.before(checkNotNull(loaded))
                enteredNative = true
                check(native.reset(active) == 0) { "GenieX could not reset the previous session" }
                val messages = (history + ChatMessage("user", text)).toTypedArray()
                val prompt = native.applyChatTemplate(active, messages, null, false, true).formattedText
                // Until tokenizer occupancy is exposed, conservative admission fails closed.
                require(prompt.toByteArray().size.toLong() + checkNotNull(config.maxOutputTokens) <= checkNotNull(loaded).maxTokens) { "This conversation exceeds conservative native context admission. Shorten it or choose a larger-context artifact." }
                offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
                native.generate(
                    active,
                    prompt,
                    GenerationConfig(
                        maxTokens = checkNotNull(config.maxOutputTokens),
                        samplerConfig = SamplerConfig(config.sampler.temperature, config.sampler.topP, config.sampler.topK)
                    ),
                    object : LLMTokenCallback {
                        override fun onToken(token: String): Boolean {
                            if (!isActive || isClosedForSend) return false
                            if (output.length + token.length > 262_144) {
                                close(IllegalStateException("GenieX reply exceeded the preview text limit"))
                                return false
                            }
                            if (firstVisible == null) {
                                firstVisible = SystemClock.elapsedRealtime() - started
                                if (!offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING))) return false
                            }
                            output.append(token)
                            return token.chunked(4096).all { offer(LocalRuntimeEvent.TextDelta(it)) }
                        }
                        override fun onComplete(result: LlmGenerateResult) {
                            completed = true
                            val metrics = result.profileData
                            offer(
                                LocalRuntimeEvent.Metrics(
                                    LocalInferenceMetrics(
                                        timeToFirstTokenMs = metrics.ttftMs.toLong(),
                                        totalDurationMs = SystemClock.elapsedRealtime() - started,
                                        totalCharacters = output.length,
                                        native = NativeInferenceMetrics(metrics.promptTokens.toInt(), metrics.generatedTokens.toInt(), metrics.prefillSpeed, metrics.decodingSpeed).takeIf { it.isValid },
                                        metricSource = "native_segment",
                                        firstVisibleAnswerMs = firstVisible,
                                        decodeDurationMs = metrics.decodeTimeMs.toLong(),
                                        segmentId = java.util.UUID.randomUUID().toString()
                                    )
                                )
                            )
                            if (metrics.stopReason in setOf("length", "context_length")) {
                                offer(LocalRuntimeEvent.Error("The response reached its output or context limit. Saved output can be continued in a new request."))
                            } else if (metrics.stopReason == "user") {
                                close(kotlinx.coroutines.CancellationException("GenieX generation cancelled"))
                                return
                            } else if (metrics.stopReason in setOf("eos", "stop_sequence")) {
                                history += ChatMessage("user", text)
                                history += ChatMessage("assistant", output.toString())
                                offer(LocalRuntimeEvent.Done)
                            } else {
                                offer(LocalRuntimeEvent.Error("GenieX returned an unrecognized completion status; the response is incomplete."))
                            }
                            close()
                        }
                    }
                )
                if (!completed && !isClosedForSend) close(IllegalStateException("GenieX ended without a completion callback"))
            }
            awaitClose { }
        } finally {
            if (enteredNative) {
                if (!completed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { native.stopStream(active) }
                guard.finished()
            }
        }
    }.buffer(64)

    override fun cancelActive() {
        if (handle != 0L) native.stopStream(handle)
    }
    override suspend fun closeConversation() {
        configuration = null
        history.clear()
    }
    override suspend fun unloadEngine() = withContext(Dispatchers.IO) {
        closeConversation()
        val previous = handle
        handle = 0L
        loaded = null
        manifest = null
        if (previous != 0L) {
            native.stopStream(previous)
            native.destroy(previous)
        }
        Unit
    }
}

/** App updates alone cannot clear evidence of an interrupted native operation. */
private class GenieXInterruptionGuard(context: Context) {
    private val journal = NativeOperationJournal(File(context.noBackupFilesDir, "geniex/operations"))
    fun before(spec: LocalEngineSpec) {
        val tuple = listOf("0.8.0", android.os.Build.FINGERPRINT, spec.accelerator, dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.sha256(File(spec.modelPath))).joinToString("|")
        journal.before(tuple)
    }
    fun finished() {
        journal.finished()
    }
}
