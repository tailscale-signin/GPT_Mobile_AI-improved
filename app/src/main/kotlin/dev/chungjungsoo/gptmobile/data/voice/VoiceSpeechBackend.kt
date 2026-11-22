package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

internal enum class VoiceSpeechBackend(val label: String) {
    CPU("On-device CPU"),
    QNN_GPU("Adreno GPU · experimental"),
    QNN_HTP("Hexagon NPU · experimental"),
    GATEWAY_CUDA("Gateway CUDA · optional")
}

/** Unsupported accelerator graphs stay usable on CPU and report the actual provider. */
internal class QualcommVoicePhraseEngineFactory(
    context: Context,
    private val backend: VoiceSpeechBackend,
    private val report: (String) -> Unit
) : VoicePhraseEngineFactory {
    private val pending = AtomicReference<TimedKokoro?>(null)
    private val generation = AtomicLong()
    override fun cancelPending() {
        generation.incrementAndGet()
        runCatching { pending.get()?.cancel() }
    }

    private val libraryDirectory = context.applicationInfo.nativeLibraryDir
    private val profileDirectory = File(context.cacheDir, "voice-provider-profiles")
    override val identity = "kokoro-${backend.name}-qnn2.7-qairt2.51-ort1.28.2-${UUID.randomUUID()}"

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        val ticket = generation.get()
        require(backend == VoiceSpeechBackend.QNN_GPU || backend == VoiceSpeechBackend.QNN_HTP)
        val provider = if (backend == VoiceSpeechBackend.QNN_GPU) "qnn-gpu" else "qnn-htp"
        var native: TimedKokoro? = null
        return try {
            check(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
            check(File(libraryDirectory, "libonnxruntime_providers_qnn.so").isFile)
            check(File(libraryDirectory, if (backend == VoiceSpeechBackend.QNN_GPU) "libQnnGpu.so" else "libQnnHtp.so").isFile)
            check(profileDirectory.isDirectory || profileDirectory.mkdirs())
            report("Checking ${backend.label} with the installed speech model…")
            val engine = TimedKokoro(
                File(directory, KokoroModelPack.MODEL).absolutePath,
                File(directory, KokoroModelPack.VOICES).absolutePath,
                File(directory, KokoroModelPack.TOKENS).absolutePath,
                File(directory, lexicon).absolutePath,
                threads,
                provider,
                libraryDirectory,
                File(profileDirectory, UUID.randomUUID().toString()).absolutePath
            )
            native = engine
            pending.set(engine)
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            // A real, unplayed warmup is mandatory: provider registration alone is not evidence.
            engine.generate("hello there.", 0, 1f)
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            val nodes = engine.acceleratedNodes()
            check(nodes > 0)
            report("${backend.label}: $nodes accelerated kernel events verified. Compare latency in Performance.")
            object : VoicePhraseEngine {
                private val cancelled = AtomicBoolean(false)

                @Volatile private var fallback: VoicePhraseEngine? = null
                override val executionProvider: String get() = fallback?.executionProvider ?: provider
                override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                    if (cancelled.get()) throw CancellationException("Speech cancelled")
                    fallback?.let { return it.generate(text, speaker, speed) }
                    return try {
                        engine.generate(text, speaker, speed).let { VoicePhraseAudio(it.samples, it.durations, it.wordTokens) }
                    } catch (error: IllegalStateException) {
                        if (error is CancellationException || cancelled.get()) throw CancellationException("Speech cancelled")
                        // A different phrase shape can expose an unsupported operator. Retry
                        // only this unplayed phrase on CPU and retain the actual-provider tag.
                        runCatching { engine.close() }
                        val cpu = VoiceNativeEngineGate.exclusive {
                            if (cancelled.get()) throw CancellationException("Speech cancelled")
                            TimedKokoroPhraseEngineFactory.create(directory, lexicon, threads)
                        }
                        fallback = cpu
                        if (cancelled.get()) {
                            cpu.cancel()
                            throw CancellationException("Speech cancelled")
                        }
                        report("${backend.label} could not execute this phrase shape. Speech is running on CPU.")
                        cpu.generate(text, speaker, speed)
                    }
                }
                override fun resetCancellation() {
                    cancelled.set(false)
                    fallback?.resetCancellation() ?: engine.resetCancellation()
                }
                override fun cancel() {
                    cancelled.set(true)
                    engine.cancel()
                    fallback?.cancel()
                }
                override fun close() {
                    cancelled.set(true)
                    engine.close()
                    fallback?.close()
                }
            }
        } catch (error: CancellationException) {
            runCatching { native?.close() }
            throw error
        } catch (error: Exception) {
            runCatching { native?.close() }
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            report("${backend.label} is unavailable for this device/model. Speech is running on CPU.")
            TimedKokoroPhraseEngineFactory.create(directory, lexicon, threads)
        } catch (error: LinkageError) {
            runCatching { native?.close() }
            if (generation.get() != ticket) throw CancellationException("Speech cancelled")
            report("${backend.label} could not load. Speech is running on CPU.")
            TimedKokoroPhraseEngineFactory.create(directory, lexicon, threads)
        } finally {
            pending.compareAndSet(native, null)
        }
    }
}

internal class UnavailableVoicePhraseEngineFactory(private val reason: String) : VoicePhraseEngineFactory {
    override val identity = "voice-unavailable-$reason"
    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error(reason)
}
