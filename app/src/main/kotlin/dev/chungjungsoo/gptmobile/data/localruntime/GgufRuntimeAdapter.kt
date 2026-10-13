package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest
import dev.melo.gguf.GgufNative
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CPU-only, pinned and QAIRT-independent; the existing holder/process owns every lease. */
internal class GgufRuntimeAdapter(context: Context) : LocalRuntime {
    private val journal = NativeExitEvidence.journal(context, File(context.noBackupFilesDir, "gguf-native"))

    @Volatile private var handle = 0L
    private var loaded: LocalEngineSpec? = null
    private var tuple: String? = null
    private var configuration: LocalConversationConfig? = null
    private val history = mutableListOf<Pair<String, String>>()
    override fun loadedEngineSpec() = loaded
    override suspend fun isEngineLoaded(spec: LocalEngineSpec) = handle != 0L && loaded?.engineConfiguration() == spec.engineConfiguration()
    override fun hasOpenConversation() = configuration != null

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        val manifest = ArtifactManifestStore.read(file)
        manifest.validate(requireNotNull(file.parentFile))
        check(manifest.runtime in setOf(ArtifactRuntime.LLAMA_CPP, ArtifactRuntime.GENIEX_LLAMA_CPP)) { "This app build cannot execute QAIRT preview bundles" }
        check(!manifest.vision && manifest.projectorFile == null) { "This GGUF runtime supports self-contained text models only" }
        check("cpu" in manifest.supportedBackends) { "This artifact does not permit the packaged CPU backend" }
        LocalModelCapabilities(minOf(manifest.contextTokens, 32768), true, setOf("cpu"), false, false, false, false)
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) = withContext(Dispatchers.IO) {
        if (isEngineLoaded(spec)) return@withContext
        RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.LLAMA_CPP)
        inspectModel(spec.modelPath).validate(spec)
        check(spec.accelerator == "cpu") { "The packaged GGUF engine is CPU-only; GPU and NPU require separate qualification" }
        val file = File(spec.modelPath)
        val manifest = ArtifactManifestStore.read(file)
        val model = ModelArtifactManifest.safeFile(requireNotNull(file.parentFile), manifest.entryFile)
        if (manifest.runtime == ArtifactRuntime.LLAMA_CPP) check(manifest.runtimeVersion == GgufNative.REVISION) { "This artifact requires another GGUF engine revision" }
        val identity = "${manifest.files.first { it.path == manifest.entryFile }.sha256}|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|cpu|${spec.maxTokens}"
        unloadEngine()
        journal.before(identity, "LOAD")
        try {
            cancellableNative { GgufNative.load(model.absolutePath.toByteArray(), spec.maxTokens, spec.cpuThreads?.coerceIn(1, 64) ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 8)).also { handle = it } }
            loaded = spec
            tuple = identity
        } catch (failure: Throwable) {
            withContext(NonCancellable) { unloadEngine() }
            throw failure
        } finally {
            journal.finished()
        }
    }

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val manifest = ArtifactManifestStore.read(File(spec.modelPath))
        journal.allowVerification("${manifest.files.first { it.path == manifest.entryFile }.sha256}|${GgufNative.REVISION}|${android.os.Build.FINGERPRINT}|cpu|${spec.maxTokens}")
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        check(handle != 0L) { "GGUF model is not loaded" }
        require(config.tools.isEmpty() && !config.isConstrainedDecodingEnabled && config.thinkingEnabled != true && config.speculativeDecoding != true) { "This GGUF route supports plain text without tools, thinking or speculative decoding" }
        require(config.initialMessages.none { it.images.isNotEmpty() }) { "GGUF image input is not qualified" }
        configuration = config
        history.clear()
        config.systemPrompt?.takeIf { it.isNotBlank() }?.let { history += "system" to it }
        history += config.initialMessages.map { (if (it.role == LocalHistoryRole.USER) "user" else "assistant") to it.text }
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = channelFlow {
        require(images.isEmpty()) { "This GGUF runtime is text-only" }
        val config = checkNotNull(configuration) { "GGUF conversation is not open" }
        val active = handle
        val messages = history.toList() + ("user" to text)
        val started = SystemClock.elapsedRealtime()
        val segment = java.util.UUID.randomUUID().toString()
        val output = StringBuilder()
        val decoder = Utf8GenerationDecoder()
        var first: Long? = null
        var saturated = false
        send(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL))
        journal.before(checkNotNull(tuple), "GENERATE", segment)
        try {
            val counts = cancellableNative {
                GgufNative.generate(active, messages.map { it.first }.toTypedArray(), messages.map { it.second.toByteArray() }.toTypedArray(), config.maxOutputTokens?.coerceIn(1, 8192) ?: 512, config.sampler.topK.coerceIn(1, 1024), config.sampler.topP.coerceIn(0f, 1f), config.sampler.temperature.coerceIn(0f, 5f)) { bytes ->
                    if (!isActive) return@generate false
                    val chunk = decoder.append(bytes)
                    if (chunk.isNotEmpty()) {
                        if (first == null) {
                            first = SystemClock.elapsedRealtime() - started
                            trySend(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING))
                        }
                        if (output.length + chunk.length > 262144) {
                            saturated = true
                            return@generate false
                        }
                        output.append(chunk)
                        if (!trySend(LocalRuntimeEvent.TextDelta(chunk)).isSuccess) {
                            saturated = true
                            return@generate false
                        }
                    }
                    true
                }
            }
            check(counts.size == 5) { "Malformed GGUF native receipt" }
            val unicodeComplete = decoder.isComplete()
            val finish = when (counts[4]) {
                0L -> "eos"
                1L -> "length"
                2L -> "canceled"
                else -> "native_error"
            }
            // Control and final counters use suspending delivery, independent of callback queue pressure.
            send(
                LocalRuntimeEvent.Metrics(
                    LocalInferenceMetrics(
                        totalDurationMs = SystemClock.elapsedRealtime() - started,
                        totalCharacters = output.length,
                        native = NativeInferenceMetrics(counts[0].toInt(), counts[1].toInt(), if (counts[2] > 0) counts[0] * 1000.0 / counts[2] else 0.0, if (counts[3] > 0) counts[1] * 1000.0 / counts[3] else 0.0),
                        metricSource = "native_segment",
                        firstVisibleAnswerMs = first,
                        decodeDurationMs = counts[3],
                        segmentId = segment,
                        finishReason = finish
                    )
                )
            )
            when {
                !unicodeComplete -> send(LocalRuntimeEvent.Error("GGUF stopped inside a Unicode sequence; retained output is partial"))
                saturated -> send(LocalRuntimeEvent.Error("GGUF output queue or character limit reached; retained output is partial"))
                counts[4] == 0L -> {
                    history += "user" to text
                    history += "assistant" to output.toString()
                    send(LocalRuntimeEvent.Done)
                }
                counts[4] == 1L -> send(LocalRuntimeEvent.Error("The response reached its output limit; saved output is partial"))
                else -> send(LocalRuntimeEvent.Error("GGUF generation ended: $finish; saved output is partial"))
            }
        } finally {
            journal.finished()
        }
    }

    private suspend fun <T> cancellableNative(block: () -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                GgufNative.cancel(handle)
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            withContext(NonCancellable) { watcher.cancelAndJoin() }
        }
    }
    override fun cancelActive() {
        GgufNative.cancel(handle)
    }
    override suspend fun closeConversation() {
        configuration = null
        history.clear()
    }
    override suspend fun unloadEngine() {
        closeConversation()
        val active = handle
        handle = 0
        loaded = null
        tuple = null
        if (active != 0L) withContext(Dispatchers.IO) { GgufNative.unload(active) }
    }
}

/** Decode complete Unicode scalars only; a token boundary may split a UTF-8 sequence. */
internal class Utf8GenerationDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    private var pending = ByteArray(0)
    fun append(bytes: ByteArray): String {
        val input = java.nio.ByteBuffer.wrap(pending + bytes)
        val output = java.nio.CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false).throwIfError()
        pending = ByteArray(input.remaining()).also { input.get(it) }
        check(pending.size <= 3) { "Invalid UTF-8 generation boundary" }
        output.flip()
        return output.toString()
    }
    fun isComplete(): Boolean = pending.isEmpty()
    private fun java.nio.charset.CoderResult.throwIfError() {
        if (isError) throwException()
    }
}
