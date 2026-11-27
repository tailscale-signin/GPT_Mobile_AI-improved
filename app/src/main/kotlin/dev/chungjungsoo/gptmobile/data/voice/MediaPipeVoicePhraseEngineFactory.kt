package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** The selected SDK engine owns its model lease; it never opens the native Kokoro pack or lexicon. */
internal class MediaPipeVoicePhraseEngineFactory(
    private val modelStore: MediaPipeSpeechModelStore,
    private val engine: MediaPipeSpeechEngine,
    private val backend: VoiceSpeechBackend,
    private val speakingRate: Float,
    private val voiceId: String = MediaPipeSpeechCatalog.model(engine).defaultVoice,
    val kokoroBucket: MediaPipeKokoroBucket = MediaPipeKokoroBucket.AUTO,
    val incrementalInput: Boolean = true,
    private val onPlaybackDelegate: ((VoiceSpeechBackend) -> Unit)? = null,
    private val onValidatedSynthesis: ((String, VoiceSpeechBackend) -> Unit)? = null,
    private val taskFactory: (MediaPipeSpeechTaskConfiguration) -> MediaPipeSpeechTask = ::AndroidMediaPipeSpeechTask
) : VoicePhraseEngineFactory {
    private class UnavailableVoice(message: String) : IOException(message)

    private val model = MediaPipeSpeechCatalog.model(engine)
    private val generation = AtomicLong()
    private val reportedBackend = AtomicReference<VoiceSpeechBackend?>()

    @Volatile var playbackMeasurement: VoiceSpeechPlaybackMeasurement? = null
    private val pendingLock = Any()
    private var pending: MediaPipeSpeechTask? = null
    private val cleanupPending = AtomicBoolean()
    private var unretiredTask: MediaPipeSpeechTask? = null

    init {
        require(speakingRate.isFinite() && speakingRate in .5f..2f)
        require(voiceId.isNotBlank())
        require(
            when (engine) {
                MediaPipeSpeechEngine.KOKORO -> backend in setOf(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU)
                MediaPipeSpeechEngine.KOKORO_GPU -> backend == VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED
                MediaPipeSpeechEngine.KOKORO_SM8750 -> backend == VoiceSpeechBackend.LITERT_KOKORO_SM8750
                MediaPipeSpeechEngine.PHONON -> backend == VoiceSpeechBackend.PHONON_CPU
                MediaPipeSpeechEngine.POCKET -> backend == VoiceSpeechBackend.POCKET_CPU
                MediaPipeSpeechEngine.POCKET_MULTILINGUAL -> backend == VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU
            }
        )
    }

    override val selectedBackend: VoiceSpeechBackend get() = backend
    override val requiresKokoroAssets: Boolean get() = false
    override val nativeCleanupPending: Boolean get() = cleanupPending.get()
    private val canUseStandardPack = onPlaybackDelegate != null &&
        (engine == MediaPipeSpeechEngine.KOKORO_GPU || engine == MediaPipeSpeechEngine.KOKORO_SM8750)

    override fun isReady(): Boolean = modelStore.isReady(engine) || (canUseStandardPack && modelStore.isReady(MediaPipeSpeechEngine.KOKORO))
    override fun prefetchAssets(current: () -> Boolean) {
        if (modelStore.prefetchInstalled(engine, current) == 0 && canUseStandardPack && current()) {
            modelStore.prefetchInstalled(MediaPipeSpeechEngine.KOKORO, current)
        }
    }
    val effectiveTargetBucket: Int = if (engine.isKokoro) kokoroBucket.sdkValue else 0
    override val residentIdentity = "${MediaPipeSpeechCatalog.RUNTIME_ID}:${model.sha256}:${backend.name}:synthesis=1:tempo=${VoiceTempoProcessor.IMPLEMENTATION_ID}:bucket=$effectiveTargetBucket:incremental=$incrementalInput:allowCpuFallback=${onPlaybackDelegate != null}"
    override val identity = "$residentIdentity:rate=${speakingRate.toBits()}:voice=$voiceId"

    override fun prepareForPlayback(engine: VoicePhraseEngine) {
        if (engine is MediaPipePlaybackRecovery) {
            engine.prepare(voiceId, ::reportBackend)
        } else if (engine is MediaPipeVoicePhraseEngine) {
            engine.selectVoice(voiceId, onPlaybackDelegate != null)
            reportBackend(engine)
        } else {
            engine.selectVoice(voiceId)
        }
    }

    private fun reportBackend(engine: MediaPipeVoicePhraseEngine) {
        engine.onValidatedSynthesis = onValidatedSynthesis
        val report = onPlaybackDelegate ?: return
        if (reportedBackend.getAndSet(engine.configuredBackend) != engine.configuredBackend) {
            runCatching { report(engine.configuredBackend) }
        }
    }

    override fun cancelPending() {
        generation.incrementAndGet()
        synchronized(pendingLock) { pending?.cancel() }
    }

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        check(!nativeCleanupPending) { "Native speech cleanup is unconfirmed; restart the app before another preparation." }
        // directory/lexicon/threads belong to the legacy engine contract. The SDK exposes no
        // thread-count control, and its model/voice frontend is wholly inside the verified bundle.
        val ticket = generation.get()
        val request = if (canUseStandardPack && !modelStore.isReady(engine) && modelStore.isReady(MediaPipeSpeechEngine.KOKORO)) fallbackBackend(backend) else backend
        val prepared = open(request, ticket).also(::reportBackend)
        return if (onPlaybackDelegate != null && prepared.configuredBackend.isMediaPipeAccelerator) {
            MediaPipePlaybackRecovery(prepared, voiceId, ::reportBackend, { selected -> open(VoiceSpeechBackend.LITERT_KOKORO_CPU, generation.get(), selected) }, ::cancelPending)
        } else {
            prepared
        }
    }

    private fun open(request: VoiceSpeechBackend, ticket: Long, requestedVoice: String = voiceId): MediaPipeVoicePhraseEngine {
        checkTicket(ticket)
        val selectedEngine = when (request) {
            VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU -> MediaPipeSpeechEngine.KOKORO
            else -> engine
        }
        val observer = playbackMeasurement
        fun <T> timed(stage: String, operation: () -> T): T {
            if (observer == null) return operation()
            val start = System.nanoTime()
            try {
                return operation()
            } finally {
                observer.preparationPhase(stage, (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
            }
        }
        val selectedModel = MediaPipeSpeechCatalog.model(selectedEngine)
        var acquired: MediaPipeSpeechModelStore.ModelLease? = null
        var task: MediaPipeSpeechTask? = null
        try {
            val lease = timed("MODEL_LEASE") { modelStore.acquire(selectedEngine, request, 1f, selectedModel.defaultVoice, effectiveTargetBucket) }
            acquired = lease
            observer?.cacheObserved(lease.directoryPresentAtEntry, lease.evictionsOnAcquire)
            timed("DELEGATE_SETUP") {
                if (request == VoiceSpeechBackend.LITERT_KOKORO_SM8750) modelStore.requireSm8750()
                if (request == VoiceSpeechBackend.LITERT_KOKORO_NPU || request == VoiceSpeechBackend.LITERT_KOKORO_SM8750) modelStore.prepareNpu()
            }
            checkTicket(ticket)
            val opened = timed("SDK_TASK_OPEN") {
                taskFactory(
                    MediaPipeSpeechTaskConfiguration(
                        lease.file,
                        lease.cacheDirectory,
                        when (request) {
                            VoiceSpeechBackend.LITERT_KOKORO_NPU, VoiceSpeechBackend.LITERT_KOKORO_SM8750 -> Delegate.NPU
                            VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED -> Delegate.GPU
                            else -> Delegate.CPU
                        },
                        1f,
                        selectedModel.defaultVoice,
                        effectiveTargetBucket
                    )
                )
            }
            task = opened
            synchronized(pendingLock) {
                pending = opened
                checkTicket(ticket)
            }
            val voices = timed("VOICE_ENUMERATION") { opened.voiceIds() }
            val selectedVoice = SpeechLanguages.availableVoice(requestedVoice, voices, onPlaybackDelegate != null)
                ?: throw UnavailableVoice("No installed voice matches the selected language in ${selectedModel.title}.")
            synchronized(pendingLock) {
                checkTicket(ticket)
                pending = null
            }
            return MediaPipeVoicePhraseEngine(task, lease, request, 1f, selectedVoice, incrementalInput, voices, onPlaybackDelegate != null && request.isMediaPipeAccelerator)
        } catch (failure: Throwable) {
            synchronized(pendingLock) { if (pending === task) pending = null }
            val closeFailure = runCatching { task?.close() }.exceptionOrNull()
            if (closeFailure == null) {
                acquired?.close()
            } else {
                unretiredTask = task
                cleanupPending.set(true)
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            // Opening the model, initializing the delegate and enumerating voices can all load
            // native libraries. Retry only after retirement, before publishing a playback handle.
            val specialized = selectedEngine != MediaPipeSpeechEngine.KOKORO
            if (closeFailure == null &&
                onPlaybackDelegate != null &&
                request.isMediaPipeAccelerator &&
                (acquired != null || specialized) &&
                (failure !is UnavailableVoice || specialized) &&
                failure !is CancellationException &&
                failure !is InterruptedException &&
                (failure is Exception || failure is LinkageError)
            ) {
                checkTicket(ticket)
                return try {
                    open(fallbackBackend(request), ticket, requestedVoice)
                } catch (fallbackFailure: Throwable) {
                    if (fallbackFailure !== failure) fallbackFailure.addSuppressed(failure)
                    throw fallbackFailure
                }
            }
            throw failure
        }
    }

    private fun fallbackBackend(request: VoiceSpeechBackend): VoiceSpeechBackend = when (request) {
        VoiceSpeechBackend.LITERT_KOKORO_GPU_OPTIMIZED -> VoiceSpeechBackend.LITERT_KOKORO_GPU
        VoiceSpeechBackend.LITERT_KOKORO_SM8750 -> VoiceSpeechBackend.LITERT_KOKORO_NPU
        else -> VoiceSpeechBackend.LITERT_KOKORO_CPU
    }

    private fun checkTicket(ticket: Long) {
        if (generation.get() != ticket || Thread.currentThread().isInterrupted) throw CancellationException("Speech preparation cancelled.")
    }
}
