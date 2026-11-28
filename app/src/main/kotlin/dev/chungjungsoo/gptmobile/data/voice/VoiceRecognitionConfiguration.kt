package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class VoiceRecognitionConfiguration(
    val model: VoiceRecognitionModel = VoiceRecognitionModel.MOONSHINE_TINY,
    val compute: VoiceRecognitionCompute = VoiceRecognitionCompute.CPU,
    val threads: Int = 0
) {
    init {
        require(threads in 0..8)
    }

    fun supported(): VoiceRecognitionConfiguration =
        if (VoiceRecognitionModelCatalog.pack(model, compute) != null) this else copy(compute = VoiceRecognitionCompute.CPU)
}

/** One store per pack shares its verified generation, install mutex and active-use leases. */
internal class RecognitionModelRegistry(directory: File, legacyTinyStore: LocalVoiceModelStore) {
    private val stores = VoiceRecognitionModel.entries.flatMap { model ->
        VoiceRecognitionModelCatalog.computes(model).map { compute ->
            (model to compute) to RecognitionModelStore(directory, model, compute, legacyTinyStore)
        }
    }.toMap()

    fun store(configuration: VoiceRecognitionConfiguration): RecognitionModelStore =
        stores.getValue(configuration.model to configuration.compute)

    val all: List<RecognitionModelStore> get() = stores.values.toList()
}

/** Keeps microphone ownership/cancellation identical across native recognizers. */
internal class StreamingVoiceRecognizer(
    context: Context,
    private val models: RecognitionModelRegistry,
    private val create: (RecognitionModelStore, VoiceRecognitionConfiguration, (Map<String, String>) -> Unit) -> MoonshineVoiceRecognizer = { store, configuration, observe ->
        MoonshineVoiceRecognizer(context, store, observe) {
            createRecognitionDecoder(context, store.pack, configuration)
        }
    },
    private val scheduleReleaseCheck: ((() -> Unit) -> Unit)? = null
) : LocalVoiceRecognitionInput,
    VoiceInterruptionInput {
    private var closed = false
    private var current: MoonshineVoiceRecognizer? = null
    private val retiring = mutableListOf<MoonshineVoiceRecognizer>()
    private var generation = 0L
    private val pendingReleases = AtomicInteger()
    private var prewarmSuppressedGeneration: Long? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var diagnosticProbe: VoiceDiagnosticTrace.Probe? = null
    private var vocabulary: List<String> = emptyList()
    private val unavailable = MutableStateFlow(VoiceInterruptionStatus.UNAVAILABLE)
    private val execution = MutableStateFlow<Map<String, String>>(emptyMap())
    val executionEvidence: StateFlow<Map<String, String>> = execution

    var configuration = VoiceRecognitionConfiguration()
        private set

    @Synchronized
    fun configure(value: VoiceRecognitionConfiguration) {
        if (closed || configuration == value) return
        require(VoiceRecognitionModelCatalog.pack(value.model, value.compute) != null) { "No verified model pack exists for the selected recognizer." }
        // close cancels capture immediately; native retirement remains on its serial
        // worker. The shared gate and microphone lease prevent overlapping replacement.
        generation++
        current?.let {
            it.close()
            retiring += it
        }
        current = null
        execution.value = emptyMap()
        configuration = value
    }

    override var languageTag: String = Locale.getDefault().toLanguageTag()
        set(value) {
            field = value
            current?.languageTag = value
        }

    override var listeningStyle: VoiceListeningStyle = VoiceListeningStyle.NATURAL
        set(value) {
            field = value
            current?.listeningStyle = value
        }

    override var captureBatchMs: Int = 40
        set(value) {
            require(value in setOf(20, 40, 80, 100))
            field = value
            current?.captureBatchMs = value
        }

    @Synchronized
    private fun input(): MoonshineVoiceRecognizer {
        current?.let { return it }
        val ticket = generation
        return create(models.store(configuration), configuration) { observed ->
            synchronized(this) { if (!closed && generation == ticket) execution.value = observed }
        }.also {
            it.languageTag = languageTag
            it.listeningStyle = listeningStyle
            it.captureBatchMs = captureBatchMs
            it.configureVocabulary(vocabulary)
            it.bindDiagnostics(diagnosticProbe)
            current = it
        }
    }

    @Synchronized
    private fun retired(listener: VoiceAudio.Listener? = null): Boolean {
        if (pendingReleases.get() > 0) {
            listener?.failure("The previous speech model is still being released. Try again shortly.", "VOICE_RECOGNIZER_RETIRING", retryMillis = 500, retryable = true)
            return false
        }
        retiring.removeAll { it.isRetired }
        if (retiring.isEmpty()) return true
        if (retiring.any { it.retirementUnconfirmed }) {
            listener?.failure("The previous speech model could not confirm native cleanup. Restart the app before loading another recognizer.", "VOICE_RECOGNIZER_RETIREMENT_UNCONFIRMED", recoveryAction = VoiceRecoveryAction.LOCAL_MODEL)
        } else {
            listener?.failure("The previous speech model is still being released. Try again shortly.", "VOICE_RECOGNIZER_RETIRING", retryMillis = 500, retryable = true)
        }
        return false
    }

    override fun listen(listener: VoiceAudio.Listener) {
        if (!closed && retired(listener)) {
            prewarmSuppressedGeneration = null
            input().listen(listener)
        }
    }

    override fun listenWithFeedback(listener: VoiceAudio.Listener, feedback: (() -> Boolean)?) {
        if (!closed && retired(listener)) {
            prewarmSuppressedGeneration = null
            input().listenWithFeedback(listener, feedback)
        }
    }

    override fun finishListening() {
        current?.finishListening()
    }
    override fun stop() {
        current?.stop()
    }
    override fun releaseWarmModel(completed: (() -> Unit)?) {
        val input = current
        if (completed == null) {
            input?.releaseWarmModel()
            return
        }
        val ticket = generation
        pendingReleases.incrementAndGet()
        // Explicit removal/benchmark release must not immediately reload the same
        // generation speculatively. A later deliberate listen or selection may.
        prewarmSuppressedGeneration = ticket
        val pending = retiring.toList()
        val released = AtomicBoolean(input == null)
        input?.releaseWarmModel { released.set(true) }
        val deadline = System.nanoTime() + 30_000_000_000L
        fun checkRetirement() {
            when {
                generation != ticket || current !== input -> {
                    pendingReleases.decrementAndGet()
                    execution.value = mapOf("recognitionFailure" to "ASR_RETIREMENT_SUPERSEDED")
                }
                VoiceRecognitionRetirement.process.blocked || input?.retirementUnconfirmed == true || pending.any { it.retirementUnconfirmed } -> {
                    pendingReleases.decrementAndGet()
                    execution.value = mapOf("recognitionFailure" to "ASR_RETIREMENT_UNCONFIRMED")
                }
                released.get() && pending.all { it.isRetired } -> {
                    pendingReleases.decrementAndGet()
                    completed()
                }
                System.nanoTime() >= deadline -> {
                    pendingReleases.decrementAndGet()
                    execution.value = mapOf("recognitionFailure" to "ASR_RETIREMENT_TIMEOUT")
                }
                else -> scheduleReleaseCheck?.invoke { checkRetirement() } ?: main.postDelayed({ checkRetirement() }, 100)
            }
        }
        checkRetirement()
    }
    override fun prewarm() {
        if (!closed && prewarmSuppressedGeneration != generation && retired() && models.store(configuration).isReady() && configuration.model.supportsLanguage(languageTag)) input().prewarm()
    }
    override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
        diagnosticProbe = probe
        current?.bindDiagnostics(probe)
    }
    override fun configureVocabulary(words: List<String>) {
        vocabulary = words.toList()
        current?.configureVocabulary(vocabulary)
    }

    override val interruptionState: StateFlow<VoiceInterruptionStatus> get() = current?.interruptionState ?: unavailable
    override fun startInterruptionMonitoring(outputLevel: () -> Float?, interrupted: () -> Unit) {
        if (!closed && retired()) input().startInterruptionMonitoring(outputLevel, interrupted)
    }
    override fun stopInterruptionMonitoring() {
        current?.stopInterruptionMonitoring()
    }
    override fun afterInterruptionStopped(released: () -> Unit) {
        if (closed) return
        val input = current
        if (input == null) released() else input.afterInterruptionStopped(released)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        generation++
        current?.let {
            it.close()
            retiring += it
        }
        current = null
    }

    /** Off-main confirmation for runtime replacement; mapped files stay pinned on uncertainty. */
    fun awaitClosed(timeoutMs: Long): Boolean {
        val pending = synchronized(this) { if (!closed) return false else retiring.toList() }
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        for (input in pending) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
            if (!input.awaitClosed(remaining)) return false
        }
        return !VoiceRecognitionRetirement.process.blocked
    }
}

internal fun createRecognitionDecoder(context: Context, pack: VoiceRecognitionModelPack, configuration: VoiceRecognitionConfiguration): LocalSpeechDecoder {
    require(pack.model == configuration.model && pack.compute == configuration.compute)
    if (VoiceRecognitionRetirement.process.blocked) {
        throw VoiceRecognitionRuntimeException("ASR_RETIREMENT_UNCONFIRMED", "Restart the app before loading another recognition model; previous native cleanup could not be confirmed.")
    }
    VoiceRecognitionMemoryPolicy.requireCapacity(context, pack)
    val threads = if (configuration.threads > 0) configuration.threads else VoiceRecognitionCpuCalibration.recommendedThreads(context, pack) ?: minOf(2, VoiceCpuSchedulingPolicy.deviceLimit())
    return when (configuration.model.engine) {
        VoiceRecognitionEngine.MOONSHINE -> MoonshineStreamDecoder.forModel(configuration.model)
        VoiceRecognitionEngine.SHERPA -> SherpaNativeSpeechDecoder(
            context.applicationContext,
            pack,
            configuration.compute,
            File(context.noBackupFilesDir, "voice-recognition-graph-cache"),
            threads
        )
    }
}
