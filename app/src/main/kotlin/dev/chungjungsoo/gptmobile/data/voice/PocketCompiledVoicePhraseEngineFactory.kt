package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class PocketCompiledVoicePhraseEngineFactory(
    context: Context,
    private val modelStore: PocketCompiledSpeechModelStore,
    private val backend: VoiceSpeechBackend,
    private val speakingRate: Float = 1f,
    private val voiceId: String = PocketCompiledSpeechCatalog.DEFAULT_VOICE,
    private val captionModelStore: PocketCaptionModelStore = PocketCaptionModelStore(context)
) : VoicePhraseEngineFactory {
    private val application = context.applicationContext
    private val generation = AtomicLong()
    private val pending = AtomicReference<AtomicBoolean?>()
    private val cleanupFailed = AtomicBoolean()
    private var retainedAfterCleanupFailure: Any? = null

    init {
        require(backend.isCompiledPocket && speakingRate.isFinite() && speakingRate in .5f..2f)
        require(voiceId in PocketCompiledSpeechCatalog.VOICES)
    }

    override val selectedBackend get() = backend
    override val requiresKokoroAssets = false
    override val preparationTimeoutMs = if (backend == VoiceSpeechBackend.POCKET_NPU) 180_000L else 60_000L
    override val nativeCleanupPending get() = cleanupFailed.get()
    override val residentIdentity = "${PocketCompiledSpeechCatalog.RUNTIME_ID}:${PocketCompiledSpeechCatalog.MODEL_SHA256}:${backend.name}:tempo=${VoiceTempoProcessor.IMPLEMENTATION_ID}:rate=${speakingRate.toBits()}:captions=${PocketCaptionModelStore.IMPLEMENTATION_ID}:${PocketCaptionModelStore.MODEL_SHA256}"
    override val identity = "$residentIdentity:voice=$voiceId"
    override fun isReady() = modelStore.isReady()

    override fun preloadRuntime() {
        System.loadLibrary("LiteRt")
        System.loadLibrary("litert_jni")
    }

    override fun prepareForPlayback(engine: VoicePhraseEngine) = engine.selectVoice(voiceId)

    override fun cancelPending() {
        generation.incrementAndGet()
        pending.get()?.set(true)
    }

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        check(!nativeCleanupPending) { "Pocket native cleanup is unconfirmed; restart the app before another preparation." }
        val started = System.nanoTime()
        val ticket = generation.get()
        val cancelled = AtomicBoolean()
        check(pending.compareAndSet(null, cancelled)) { "Pocket preparation is already running." }
        val preparing = AtomicBoolean(true)
        val checkActive = {
            if (cancelled.get() || preparing.get() && generation.get() != ticket) throw CancellationException("Pocket preparation cancelled")
        }
        var lease: PocketCompiledSpeechModelStore.ModelLease? = null
        var runtime: PocketCompiledRuntime? = null
        var synthesizer: PocketCompiledSynthesizer? = null
        try {
            checkActive()
            val acquired = modelStore.acquire().also { lease = it }
            val opened = PocketCompiledRuntime.open(application, acquired.directory, backend, threads, checkActive).also { runtime = it }
            val synth = preparePocketCompiledGraph(backend, PocketCompiledPreparationGraph.HOST_ASSETS) {
                PocketCompiledSynthesizer(acquired.directory, opened.languageModel, opened.transformer, opened.vocoder, checkActive)
            }.also { synthesizer = it }
            checkActive()
            val session = object : PocketCompiledSession {
                override val tokenCount get() = synth.tokenCount
                override fun receipt() = opened.receipt()
                override fun resetMeasurements() {
                    synth.resetMeasurements()
                    opened.resetMeasurements()
                }
                override fun synthesize(text: String, voice: String, emit: (FloatArray) -> Unit) = synth.synthesize(text, voice, emit)
                override fun close() {
                    val failures = listOf(synth, opened).mapNotNull { runCatching { it.close() }.exceptionOrNull() }
                    if (failures.isNotEmpty()) {
                        cleanupFailed.set(true)
                        retainedAfterCleanupFailure = listOf(synth, opened, acquired)
                        throw PocketCompiledCleanupFailure(retainedAfterCleanupFailure, failures.first())
                    }
                    acquired.close()
                }
            }
            val engine = PocketCompiledVoicePhraseEngine(session, cancelled, backend, speakingRate, voiceId, (System.nanoTime() - started) / 1_000_000)
            preparing.set(false)
            return PocketCaptionVoicePhraseEngine(engine, captionModelStore) { retained ->
                cleanupFailed.set(true)
                retainedAfterCleanupFailure = retained
            }
        } catch (error: Throwable) {
            var retired = error !is PocketCompiledCleanupFailure
            listOfNotNull(synthesizer, runtime).forEach { resource ->
                if (runCatching { resource.close() }.isFailure) retired = false
            }
            if (retired) {
                lease?.close()
            } else {
                cleanupFailed.set(true)
                retainedAfterCleanupFailure = listOf(synthesizer, runtime, lease, error)
            }
            throw error
        } finally {
            pending.compareAndSet(cancelled, null)
        }
    }
}
