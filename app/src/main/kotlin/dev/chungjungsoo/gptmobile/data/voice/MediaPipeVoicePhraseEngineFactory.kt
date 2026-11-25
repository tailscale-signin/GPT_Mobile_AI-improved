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
    private val taskFactory: (MediaPipeSpeechTaskConfiguration) -> MediaPipeSpeechTask = ::AndroidMediaPipeSpeechTask
) : VoicePhraseEngineFactory {
    private val model = MediaPipeSpeechCatalog.model(engine)
    private val generation = AtomicLong()
    private val reportedBackend = AtomicReference<VoiceSpeechBackend?>()
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
                MediaPipeSpeechEngine.PHONON -> backend == VoiceSpeechBackend.PHONON_CPU
                MediaPipeSpeechEngine.POCKET -> backend == VoiceSpeechBackend.POCKET_CPU
            }
        )
    }

    override val selectedBackend: VoiceSpeechBackend get() = backend
    override val requiresKokoroAssets: Boolean get() = false
    override val nativeCleanupPending: Boolean get() = cleanupPending.get()
    override fun isReady(): Boolean = modelStore.isReady(engine)
    val effectiveTargetBucket: Int = if (engine == MediaPipeSpeechEngine.KOKORO) kokoroBucket.sdkValue else 0
    override val residentIdentity = "${MediaPipeSpeechCatalog.RUNTIME_ID}:${model.sha256}:${backend.name}:${speakingRate.toBits()}:bucket=$effectiveTargetBucket:incremental=$incrementalInput:allowCpuFallback=${onPlaybackDelegate != null}"
    override val identity = "$residentIdentity:voice=$voiceId"

    override fun prepareForPlayback(engine: VoicePhraseEngine) {
        engine.selectVoice(voiceId)
        if (engine is MediaPipeVoicePhraseEngine) reportBackend(engine)
    }

    private fun reportBackend(engine: MediaPipeVoicePhraseEngine) {
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
        return open(backend, generation.get()).also(::reportBackend)
    }

    private fun open(request: VoiceSpeechBackend, ticket: Long): MediaPipeVoicePhraseEngine {
        checkTicket(ticket)
        val lease = modelStore.acquire(engine, request, speakingRate, model.defaultVoice, effectiveTargetBucket)
        var task: MediaPipeSpeechTask? = null
        try {
            if (request == VoiceSpeechBackend.LITERT_KOKORO_NPU) modelStore.prepareNpu()
            checkTicket(ticket)
            task = taskFactory(
                MediaPipeSpeechTaskConfiguration(
                    lease.file,
                    lease.cacheDirectory,
                    when (request) {
                        VoiceSpeechBackend.LITERT_KOKORO_NPU -> Delegate.NPU
                        VoiceSpeechBackend.LITERT_KOKORO_GPU -> Delegate.GPU
                        else -> Delegate.CPU
                    },
                    speakingRate,
                    model.defaultVoice,
                    effectiveTargetBucket
                )
            )
            synchronized(pendingLock) {
                pending = task
                checkTicket(ticket)
            }
            if (voiceId !in task.voiceIds()) throw IOException("The selected voice is unavailable in ${model.title}.")
            synchronized(pendingLock) {
                checkTicket(ticket)
                pending = null
            }
            return MediaPipeVoicePhraseEngine(task, lease, request, speakingRate, voiceId, incrementalInput)
        } catch (failure: Throwable) {
            synchronized(pendingLock) { if (pending === task) pending = null }
            val closeFailure = runCatching { task?.close() }.exceptionOrNull()
            if (closeFailure == null) {
                lease.close()
            } else {
                unretiredTask = task
                cleanupPending.set(true)
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            // Only retry failed accelerator environment setup or construction. Once a task exists, its
            // voice/stream errors belong to that task. Benchmark factories stay strict.
            if (task == null &&
                onPlaybackDelegate != null &&
                request in setOf(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_NPU) &&
                failure !is CancellationException &&
                failure !is InterruptedException &&
                (failure is Exception || failure is LinkageError)
            ) {
                checkTicket(ticket)
                return try {
                    open(VoiceSpeechBackend.LITERT_KOKORO_CPU, ticket)
                } catch (cpuFailure: Throwable) {
                    if (cpuFailure !== failure) cpuFailure.addSuppressed(failure)
                    throw cpuFailure
                }
            }
            throw failure
        }
    }

    private fun checkTicket(ticket: Long) {
        if (generation.get() != ticket || Thread.currentThread().isInterrupted) throw CancellationException("Speech preparation cancelled.")
    }
}
