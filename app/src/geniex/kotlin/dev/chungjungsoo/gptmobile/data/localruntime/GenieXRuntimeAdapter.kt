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
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.geniex.sdk.jni.Llm
import com.geniex.sdk.jni.Vlm
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
    private val visionNative by lazy { Vlm() }
    private var vision = false
    private val visionHistory = mutableListOf<VlmChatMessage>()
    private val mediaFiles = mutableListOf<File>()
    private val mediaDirectory = File(context.noBackupFilesDir, "geniex/media-${android.os.Process.myPid()}")
    private fun stop(active: Long) {
        if (vision) visionNative.stopStream(active) else native.stopStream(active)
    }
    private fun destroy(active: Long) {
        if (vision) visionNative.destroy(active) else native.destroy(active)
    }
    private fun mediaMessage(role: String, text: String, images: List<ByteArray>): VlmChatMessage {
        require(images.size <= 8 && mediaFiles.size + images.size <= 32 && images.sumOf { it.size.toLong() } <= 32L * 1024 * 1024) { "Image count or byte limit exceeded" }
        val parts = mutableListOf(VlmContent("text", text))
        images.forEach { bytes ->
            require(bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024) { "Image is empty or exceeds 16 MiB" }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "Unsupported image dimensions" }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
            }
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Cannot decode image" }
            check(mediaDirectory.mkdirs() || mediaDirectory.isDirectory)
            val file = File(mediaDirectory, java.util.UUID.randomUUID().toString() + ".png")
            try {
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                mediaFiles += file
                parts += VlmContent("image", file.absolutePath)
            } finally {
                bitmap.recycle()
            }
        }
        return VlmChatMessage(role, parts)
    }
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
        LocalModelCapabilities(artifact.contextTokens, artifact.runtime == ArtifactRuntime.GENIEX_LLAMA_CPP, artifact.supportedBackends, artifact.vision, false, false, false, artifact.soc, visionBackends = if (artifact.vision) artifact.supportedBackends else emptySet())
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) = withContext(Dispatchers.IO) {
        val file = File(spec.modelPath)
        val root = checkNotNull(file.parentFile)
        val artifact = ArtifactManifestStore.read(file)
        artifact.validate(root, spec.accelerator.lowercase(), android.os.Build.SOC_MODEL)
        check(artifact.runtimeVersion == dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_VERSION) { "This preview requires a bundle qualified for GenieX 0.8.0." }
        require(!spec.isVisionEnabled || artifact.vision) { "This package does not declare image input" }
        require(spec.speculativeDecoding != true) { "Speculative decoding requires separate qualification" }
        require(spec.maxTokens in 1..artifact.contextTokens)
        val qairt = artifact.runtime == ArtifactRuntime.GENIEX_QAIRT
        require(qairt || artifact.runtime == ArtifactRuntime.GENIEX_LLAMA_CPP) { "Unsupported artifact runtime" }
        require(!qairt || spec.accelerator.equals("npu", true)) { "QAIRT is NPU-only; CPU/GPU requests are never silently normalized." }
        unloadEngine()
        vision = spec.isVisionEnabled
        guard.before(spec)
        try {
            // GenieX initialization can complete through an asynchronous callback.
            // Do not construct a native handle until the SDK explicitly reports success.
            val initialized = kotlinx.coroutines.CompletableDeferred<String?>()
            GenieXSdk.getInstance().init(
                context,
                object : GenieXSdk.InitCallback {
                    override fun onSuccess() {
                        initialized.complete(null)
                    }
                    override fun onFailure(reason: String) {
                        initialized.complete(reason)
                    }
                }
            )
            val initializationError = kotlinx.coroutines.withTimeout(30_000L) { initialized.await() }
            check(initializationError == null) { "GenieX initialization failed: $initializationError" }
            val modelConfig = ModelConfig(
                nCtx = if (qairt) 0 else spec.maxTokens,
                nGpuLayers = if (qairt || spec.accelerator.equals("cpu", true)) 0 else -1,
                nThreads = spec.cpuThreads ?: 4,
                nThreadsBatch = spec.cpuThreads ?: 4
            )
            val next = if (vision) {
                visionNative.create(
                    VlmCreateInput(
                        model_path = ModelArtifactManifest.safeFile(root, artifact.entryFile).path,
                        mmproj_path = artifact.projectorFile?.let { ModelArtifactManifest.safeFile(root, it).path },
                        config = modelConfig,
                        runtime_id = if (qairt) "qairt" else "llama_cpp",
                        compute_unit = spec.accelerator.lowercase(),
                        vit_device_id = spec.visionAccelerator.lowercase()
                    )
                )
            } else {
                native.create(
                    LlmCreateInput(
                        model_path = ModelArtifactManifest.safeFile(root, artifact.entryFile).path,
                        tokenizer_path = artifact.tokenizerFile?.let { ModelArtifactManifest.safeFile(root, it).path },
                        config = modelConfig,
                        runtime_id = if (qairt) "qairt" else "llama_cpp",
                        compute_unit = spec.accelerator.lowercase()
                    )
                )
            }
            check(next != 0L) { "GenieX failed to create a model handle" }
            if (vision && !visionNative.getCapabilities(next).supportsVision) {
                visionNative.destroy(next)
                error("The loaded native artifact rejected image input; no image was discarded")
            }
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
        require(vision || config.initialMessages.all { it.images.isEmpty() && it.imageIds.isEmpty() }) { "Choose an image-capable package; images cannot be dropped" }
        require(config.thinkingEnabled != true) { "Thinking requires separate protocol qualification" }
        require(config.maxOutputTokens != null && config.maxOutputTokens in 1..4096) { "Set a bounded output budget of 1–4096 tokens." }
        require(config.sampler.temperature > 0) { "This binding treats temperature zero as a runtime default, not greedy sampling. Choose a positive temperature." }
        closeConversation()
        configuration = config
        history.clear()
        config.systemPrompt?.takeIf { it.isNotBlank() }?.let { history += ChatMessage("system", it) }
        history += config.initialMessages.map { ChatMessage(if (it.role == LocalHistoryRole.USER) "user" else "assistant", it.text) }
        if (vision) {
            withContext(Dispatchers.IO) {
                config.systemPrompt?.takeIf { it.isNotBlank() }?.let { visionHistory += mediaMessage("system", it, emptyList()) }
                config.initialMessages.forEach {
                    require(it.imageIds.isEmpty() || it.images.size == it.imageIds.size) { "History image bytes are unavailable" }
                    visionHistory += mediaMessage(if (it.role == LocalHistoryRole.USER) "user" else "assistant", it.text, it.images)
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = callbackFlow {
        val config = checkNotNull(configuration)
        require(vision || images.isEmpty()) { "This session is text-only; images cannot be dropped" }
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
                check((if (vision) visionNative.reset(active) else native.reset(active)) == 0) { "GenieX could not reset the previous session" }
                val messages = (history + ChatMessage("user", text)).toTypedArray()
                val imageMessage = if (vision) mediaMessage("user", text, images) else null
                val imageMessages = (visionHistory + listOfNotNull(imageMessage)).toTypedArray()
                val prompt = if (vision) visionNative.applyChatTemplate(active, imageMessages, null, false).formattedText else native.applyChatTemplate(active, messages, null, false, true).formattedText
                val imagePaths = if (vision) visionNative.extractMediaPaths(imageMessages).first else emptyArray()
                // Until tokenizer occupancy is exposed, conservative admission fails closed.
                require(prompt.toByteArray().size.toLong() + imagePaths.size * 4096L + checkNotNull(config.maxOutputTokens) <= checkNotNull(loaded).maxTokens) { "This conversation exceeds conservative native context admission. Shorten it or choose a larger-context artifact." }
                offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
                val generation = GenerationConfig(
                    maxTokens = checkNotNull(config.maxOutputTokens),
                    samplerConfig = SamplerConfig(config.sampler.temperature, config.sampler.topP, config.sampler.topK),
                    imagePaths = imagePaths.takeIf { it.isNotEmpty() },
                    imageCount = imagePaths.size
                )
                val callback = object : LLMTokenCallback {
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
                                    native = NativeInferenceMetrics(metrics.promptTokens.toInt(), metrics.generatedTokens.toInt(), metrics.prefillSpeed, metrics.decodingSpeed).takeIf { it.hasValidTokenCounts },
                                    metricSource = "native_segment",
                                    firstVisibleAnswerMs = firstVisible,
                                    decodeDurationMs = metrics.decodeTimeMs.toLong(),
                                    segmentId = java.util.UUID.randomUUID().toString(),
                                    finishReason = metrics.stopReason
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
                            if (imageMessage != null) {
                                visionHistory += imageMessage
                                visionHistory += mediaMessage("assistant", output.toString(), emptyList())
                            }
                            offer(LocalRuntimeEvent.Done)
                        } else {
                            offer(LocalRuntimeEvent.Error("GenieX returned an unrecognized completion status; the response is incomplete."))
                        }
                        close()
                    }
                }
                if (vision) visionNative.generate(active, prompt, generation, callback) else native.generate(active, prompt, generation, callback)
                if (!completed && !isClosedForSend) close(IllegalStateException("GenieX ended without a completion callback"))
            }
            awaitClose { }
        } finally {
            if (enteredNative) {
                if (!completed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { stop(active) }
                guard.finished()
            }
        }
    }.buffer(64)

    override fun cancelActive() {
        if (handle != 0L) stop(handle)
    }
    override suspend fun closeConversation() {
        configuration = null
        history.clear()
        visionHistory.clear()
        withContext(Dispatchers.IO) {
            mediaFiles.forEach { it.delete() }
            mediaFiles.clear()
            mediaDirectory.deleteRecursively()
        }
    }
    override suspend fun unloadEngine() = withContext(Dispatchers.IO) {
        closeConversation()
        val previous = handle
        handle = 0L
        loaded = null
        manifest = null
        if (previous != 0L) {
            stop(previous)
            destroy(previous)
        }
        Unit
    }
}

/** App updates alone cannot clear evidence of an interrupted native operation. */
private class GenieXInterruptionGuard(context: Context) {
    private val journal = NativeOperationJournal(File(context.noBackupFilesDir, "geniex/operations"))
    fun before(spec: LocalEngineSpec) {
        val tuple = listOf(dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_VERSION, android.os.Build.FINGERPRINT, spec.accelerator, dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.sha256(File(spec.modelPath))).joinToString("|")
        journal.before(tuple)
    }
    fun finished() {
        journal.finished()
    }
}
