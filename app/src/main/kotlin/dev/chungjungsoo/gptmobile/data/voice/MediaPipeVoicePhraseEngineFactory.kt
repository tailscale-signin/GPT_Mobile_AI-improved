package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** The selected SDK engine owns its model lease; it never opens the native Kokoro pack or lexicon. */
internal class MediaPipeVoicePhraseEngineFactory(
    private val modelStore: MediaPipeSpeechModelStore,
    private val engine: MediaPipeSpeechEngine,
    private val backend: VoiceSpeechBackend,
    private val speakingRate: Float,
    private val voiceId: String = MediaPipeSpeechCatalog.model(engine).defaultVoice,
    private val taskFactory: (MediaPipeSpeechTaskConfiguration) -> MediaPipeSpeechTask = ::AndroidMediaPipeSpeechTask
) : VoicePhraseEngineFactory {
    private val model = MediaPipeSpeechCatalog.model(engine)
    private val generation = AtomicLong()
    private val pendingLock = Any()
    private var pending: MediaPipeSpeechTask? = null

    init {
        require(speakingRate.isFinite() && speakingRate in .5f..2f)
        require(voiceId.isNotBlank())
        require(
            when (engine) {
                MediaPipeSpeechEngine.KOKORO -> backend in setOf(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.LITERT_KOKORO_GPU)
                MediaPipeSpeechEngine.PHONON -> backend == VoiceSpeechBackend.PHONON_CPU
                MediaPipeSpeechEngine.POCKET -> backend == VoiceSpeechBackend.POCKET_CPU
            }
        )
    }

    override val selectedBackend: VoiceSpeechBackend get() = backend
    override val requiresKokoroAssets: Boolean get() = false
    override fun isReady(): Boolean = modelStore.isReady(engine)
    override val identity = "${MediaPipeSpeechCatalog.RUNTIME_ID}:${model.sha256}:${backend.name}:${speakingRate.toBits()}:$voiceId"

    override fun cancelPending() {
        generation.incrementAndGet()
        synchronized(pendingLock) { pending?.cancel() }
    }

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        // directory/lexicon/threads belong to the legacy engine contract. The SDK exposes no
        // thread-count control, and its model/voice frontend is wholly inside the verified bundle.
        val ticket = generation.get()
        val lease = modelStore.acquire(engine, backend, speakingRate, voiceId)
        var task: MediaPipeSpeechTask? = null
        try {
            checkTicket(ticket)
            task = taskFactory(
                MediaPipeSpeechTaskConfiguration(
                    lease.file,
                    lease.cacheDirectory,
                    backend == VoiceSpeechBackend.LITERT_KOKORO_GPU,
                    speakingRate,
                    voiceId
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
            return MediaPipeVoicePhraseEngine(task, lease, backend, speakingRate, voiceId)
        } catch (failure: Throwable) {
            synchronized(pendingLock) { if (pending === task) pending = null }
            val closeFailure = runCatching { task?.close() }.exceptionOrNull()
            if (closeFailure == null) {
                lease.close()
            } else if (closeFailure !== failure) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun checkTicket(ticket: Long) {
        if (generation.get() != ticket || Thread.currentThread().isInterrupted) throw CancellationException("Speech preparation cancelled.")
    }
}
