package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import dev.chungjungsoo.gptmobile.data.localruntime.QnnEnvironment
import dev.chungjungsoo.gptmobile.data.localruntime.QualcommSocSupport
import dev.melo.kokoro.StreamingAsr
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal class VoiceRecognitionRuntimeException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** One prepared model, independent capture streams, and explicit execution receipts. */
internal class SherpaNativeSpeechDecoder internal constructor(
    private val pack: VoiceRecognitionModelPack,
    private val compute: VoiceRecognitionCompute,
    private val cacheRoot: File,
    private val threads: Int,
    private val prepareLibraries: () -> String,
    private val nativeFactory: (SherpaNativeConfiguration) -> SherpaSpeechNative,
    private val evidence: (StreamingAsr.Evidence) -> Unit = {}
) : LocalSpeechDecoder {
    constructor(
        context: Context,
        pack: VoiceRecognitionModelPack,
        compute: VoiceRecognitionCompute,
        cacheRoot: File,
        threads: Int = 2,
        evidence: (StreamingAsr.Evidence) -> Unit = {}
    ) : this(
        pack,
        compute,
        cacheRoot,
        threads,
        prepareLibraries = {
            if (pack.requiredSocModel != null && QualcommSocSupport.canonicalSoc(Build.SOC_MODEL.orEmpty()) != pack.requiredSocModel) {
                throw VoiceRecognitionRuntimeException(
                    "ASR_SOC_UNSUPPORTED",
                    "This Nemotron NPU pack requires ${pack.requiredSocModel}. Select CPU or GPU, or install a pack for this device."
                )
            }
            if (compute == VoiceRecognitionCompute.NPU) {
                val prepared = QnnEnvironment.prepareForExecution(context.applicationContext)
                if (!prepared.prerequisitesReady) {
                    throw VoiceRecognitionRuntimeException("ASR_PROVIDER_LOAD", "Nemotron NPU could not prepare its Qualcomm runtime. Select CPU or check the installed runtime.")
                }
                prepared.dispatchDir
            } else {
                context.applicationInfo.nativeLibraryDir
            }
        },
        nativeFactory = ::JniSherpaSpeechNative,
        evidence = evidence
    )

    init {
        require(pack.model.engine == VoiceRecognitionEngine.SHERPA && pack.compute == compute)
        require(threads in 1..8)
    }

    @Volatile private var native: SherpaSpeechNative? = null

    @Volatile private var cancelled = false

    @Volatile private var retirementFailure: VoiceRecognitionRuntimeException? = null
    private var directory: String? = null
    private var language = "en-US"
    private var vocabularySize = 0
    private var style = VoiceListeningStyle.NATURAL
    private var events: ((LocalSpeechEvent) -> Unit)? = null
    private var streamId = 0L
    private var revision = 0L
    private var previousText = ""
    private var endpointReported = false
    private var finalReported = false
    private var lastEvidence: StreamingAsr.Evidence? = null
    private var failureCode: String? = null

    override val supportsReuse: Boolean get() = true
    override val runtimeEvidence: String?
        get() = lastEvidence?.let { "runtime=${StreamingAsr.RUNTIME_ID};model=${pack.modelIdentity};$it;cancelled=$cancelled" }

    override fun configure(style: VoiceListeningStyle) {
        check(native == null || this.style == style) { "Listening style changes require a fresh model" }
        this.style = style
    }

    override fun configureRecognition(languageTag: String, vocabulary: List<String>) {
        if (!pack.model.supportsLanguage(languageTag)) {
            throw VoiceRecognitionRuntimeException("ASR_LANGUAGE_UNSUPPORTED", "${pack.model.title} does not support the selected recognition language.")
        }
        language = if (pack.model == VoiceRecognitionModel.NEMOTRON_3_5) nemotronPromptLanguage(languageTag) else languageTag
        vocabularySize = vocabulary.size
    }

    override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) = checked {
        check(this.events == null)
        check(this.directory == null || this.directory == directory.absolutePath) { "The prepared model belongs to another installation" }
        cancelled = false
        if (native == null) {
            val work = File(cacheRoot, "recognition-native/${pack.modelIdentity}").apply { mkdirs() }
            native = nativeFactory(
                SherpaNativeConfiguration(
                    directory = directory,
                    pack = pack,
                    provider = when (compute) {
                        VoiceRecognitionCompute.CPU -> "cpu"
                        VoiceRecognitionCompute.GPU -> "qnn-gpu"
                        VoiceRecognitionCompute.NPU -> "qnn-htp-context"
                    },
                    language = language,
                    libraryDirectory = prepareLibraries(),
                    workDirectory = work,
                    threads = threads,
                    endpointSilenceSeconds = style.vadWindowSeconds
                )
            )
            this.directory = directory.absolutePath
        } else {
            checkNotNull(native).reset(language)
        }
        if (cancelled) {
            native?.cancel()
            throw VoiceRecognitionRuntimeException("ASR_CANCELLED", "Speech recognition was cancelled.")
        }
        streamId = streamIds.incrementAndGet()
        revision = 0
        previousText = ""
        endpointReported = false
        finalReported = false
        failureCode = null
        this.events = events
        observeEvidence()
    }

    override fun accept(samples: FloatArray) = checked {
        check(events != null && !finalReported)
        val result = checkNotNull(native).accept(samples)
        observeEvidence()
        if (result.text != previousText) publish(result, final = false)
        if (result.endpoint && result.text.isNotBlank() && !endpointReported) {
            endpointReported = true
            // This asks the capture owner to stop and drain its existing PCM
            // queue. Only finish() emits a terminal transcript revision.
            events?.invoke(LocalSpeechEvent.Final(result.text, transcript(result, final = false)))
        }
    }

    override fun finish() = checked {
        if (events != null && !finalReported) {
            val result = checkNotNull(native).finish()
            observeEvidence()
            publish(result, final = true)
            finalReported = true
        }
    }

    private fun publish(result: StreamingAsr.Result, final: Boolean) {
        if (result.text.isNotBlank() && previousText.isBlank()) events?.invoke(LocalSpeechEvent.Speech)
        previousText = result.text
        val timed = transcript(result, final)
        if (final) events?.invoke(LocalSpeechEvent.Final(timed.text, timed)) else events?.invoke(LocalSpeechEvent.Partial(timed.text, timed))
    }

    private fun transcript(result: StreamingAsr.Result, final: Boolean): VoiceRecognitionTranscript = VoiceRecognitionTranscript.fromTokens(
        text = result.text,
        tokens = result.tokens.toList(),
        tokenStartSeconds = result.timestampsSeconds.map(Float::toDouble),
        segmentStartSeconds = result.segmentStartSeconds.toDouble(),
        streamId = streamId,
        revision = ++revision,
        isFinal = final,
        sampleRate = StreamingAsr.SAMPLE_RATE,
        audioFrames = result.acceptedAudioFrames
    )

    private fun observeEvidence() {
        checkNotNull(native).evidence().also {
            lastEvidence = it
            evidence(it)
        }
    }

    override fun executionEvidence(): Map<String, String> {
        val observed = lastEvidence
        return buildMap {
            put("recognitionRuntime", StreamingAsr.RUNTIME_ID)
            put("recognitionModelIdentity", pack.modelIdentity)
            put("recognitionPack", pack.id)
            put("recognitionRequestedProvider", compute.name)
            put("recognitionActualProvider", observed?.actualProvider ?: "not_executed")
            put("recognitionExecutionVerified", (observed?.executionVerified == true && failureCode == null && !cancelled).toString())
            put("recognitionCancelled", cancelled.toString())
            put("recognitionCompletedChunks", (observed?.completedChunks ?: 0).toString())
            put("recognitionAcceptedAudioFrames", (observed?.acceptedAudioFrames ?: 0).toString())
            put("recognitionContextCache", if (compute == VoiceRecognitionCompute.NPU) "publisher_compiled_soc_bound" else "none")
            put("recognitionProfileScope", "first_completed_invocation_per_prepared_component")
            put("recognitionContextualBias", if (vocabularySize == 0) "not_requested" else "not_supported_by_this_pack")
            listOf("Encoder", "Decoder", "Joiner").forEachIndexed { index, component ->
                put("recognition${component}GpuKernels", (observed?.sampledAcceleratorKernels?.getOrNull(index) ?: 0).toString())
                put("recognition${component}CpuKernels", (observed?.sampledCpuKernels?.getOrNull(index) ?: 0).toString())
                put("recognition${component}Runs", (observed?.completedComponentRuns?.getOrNull(index) ?: 0).toString())
            }
            failureCode?.let { put("recognitionFailure", it) }
        }
    }

    override fun releaseStream() {
        events = null
        previousText = ""
        finalReported = false
    }

    override fun cancel() {
        cancelled = true
        if (retirementFailure == null) native?.cancel()
    }

    override fun close() {
        releaseStream()
        retirementFailure?.let { throw it }
        val retiring = native
        try {
            retiring?.close()
        } catch (failure: Throwable) {
            throw retainRetirementFailure(failure)
        }
        native = null
        directory = null
    }

    private inline fun <T> checked(action: () -> T): T = try {
        retirementFailure?.let { throw it }
        action()
    } catch (failure: StreamingAsr.Failure) {
        if (failure.code == "ASR_RETIREMENT_UNCONFIRMED") throw retainRetirementFailure(failure)
        failureCode = failure.code
        val message = when (failure.code) {
            "ASR_ACCELERATOR_NOT_EXECUTED" -> "The Nemotron encoder did not execute on the requested GPU. Select CPU or check GPU compatibility."
            "ASR_ACCELERATOR_UNAVAILABLE", "ASR_PROVIDER_LOAD" -> "The requested Nemotron accelerator is unavailable. Select CPU or check the installed Qualcomm runtime."
            "ASR_PROFILE_UNAVAILABLE" -> "Nemotron could not verify GPU execution. Select CPU or retry the accelerator check."
            "ASR_NATIVE_ADMISSION_BLOCKED" -> "Speech recognition is paused because an earlier native engine could not retire safely. Restart the app before preparing another recognizer."
            "ASR_CANCELLED" -> "Speech recognition was cancelled."
            else -> "${pack.model.title} could not complete recognition (${failure.code}). Check its installed model pack or select another recognizer."
        }
        throw VoiceRecognitionRuntimeException(failure.code, message, failure)
    }

    private fun retainRetirementFailure(cause: Throwable): VoiceRecognitionRuntimeException {
        val failure = retirementFailure ?: VoiceRecognitionRuntimeException(
            "ASR_RETIREMENT_UNCONFIRMED",
            "Speech recognition could not confirm native cleanup. Its model files remain in use. Restart the app before preparing another recognizer.",
            cause
        ).also { retirementFailure = it }
        // A failed native constructor can leave process-owned state even when
        // no Java handle was returned. close must still fail so the capture
        // owner retains this decoder AND its verified model-use lease.
        failureCode = failure.code
        return failure
    }

    companion object {
        private val streamIds = AtomicLong()
    }
}

internal data class SherpaNativeConfiguration(
    val directory: File,
    val pack: VoiceRecognitionModelPack,
    val provider: String,
    val language: String,
    val libraryDirectory: String,
    val workDirectory: File,
    val threads: Int,
    val endpointSilenceSeconds: Float
)

internal interface SherpaSpeechNative : AutoCloseable {
    fun accept(samples: FloatArray): StreamingAsr.Result
    fun finish(): StreamingAsr.Result
    fun reset(language: String)
    fun evidence(): StreamingAsr.Evidence
    fun cancel()
}

private class JniSherpaSpeechNative(config: SherpaNativeConfiguration) : SherpaSpeechNative {
    private val native = StreamingAsr(
        File(config.directory, checkNotNull(config.pack.encoderFile)).absolutePath,
        File(config.directory, checkNotNull(config.pack.decoderFile)).absolutePath,
        File(config.directory, checkNotNull(config.pack.joinerFile)).absolutePath,
        File(config.directory, checkNotNull(config.pack.tokensFile)).absolutePath,
        if (config.pack.model == VoiceRecognitionModel.NEMOTRON_3_5) "nemo_transducer" else "zipformer",
        config.provider, config.language, config.libraryDirectory, config.workDirectory.absolutePath,
        config.threads, config.pack.chunkMillis ?: 0, config.endpointSilenceSeconds
    )
    override fun accept(samples: FloatArray): StreamingAsr.Result = native.accept(samples)
    override fun finish(): StreamingAsr.Result = native.finish()
    override fun reset(language: String) = native.reset(language)
    override fun evidence(): StreamingAsr.Evidence = native.evidence()
    override fun cancel() = native.cancel()
    override fun close() = native.close()
}

/** QNN's prompt table lacks several bare language aliases, unlike its ONNX counterpart. */
internal fun nemotronPromptLanguage(tag: String): String {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    return when (locale.language) {
        "en" -> if (locale.country == "GB") "en-GB" else "en-US"
        "es" -> if (locale.country == "ES") "es-ES" else "es-US"
        "fr" -> if (locale.country == "CA") "fr-CA" else "fr-FR"
        "pt" -> if (locale.country == "BR") "pt-BR" else "pt-PT"
        "zh" -> if (locale.country in setOf("TW", "HK") || locale.script == "Hant") "zh-TW" else "zh-CN"
        else -> mapOf(
            "ar" to "ar-AR", "bg" to "bg-BG", "cs" to "cs-CZ", "da" to "da-DK", "de" to "de-DE",
            "et" to "et-EE", "fi" to "fi-FI", "hi" to "hi-IN", "hr" to "hr-HR", "hu" to "hu-HU",
            "it" to "it-IT", "ja" to "ja-JP", "ko" to "ko-KR", "nb" to "nb-NO", "nl" to "nl-NL",
            "pl" to "pl-PL", "ro" to "ro-RO", "ru" to "ru-RU", "sk" to "sk-SK", "sv" to "sv-SE",
            "tr" to "tr-TR", "uk" to "uk-UA", "vi" to "vi-VN"
        )[locale.language] ?: throw VoiceRecognitionRuntimeException("ASR_LANGUAGE_UNSUPPORTED", "The selected language has no supported Nemotron prompt.")
    }
}
