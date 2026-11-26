package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

internal suspend fun voiceBenchGateway(context: Context, factory: VoicePhraseEngineFactory?, active: () -> Boolean, interrupt: AtomicReference<(() -> Unit)?>, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
    if (factory?.selectedBackend != VoiceSpeechBackend.GATEWAY_CUDA) return listOf(VoiceBenchObservation.unavailable("cuda", "GATEWAY_SPEECH_NOT_SELECTED"))
    val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
    models.refresh(false)
    if (!models.acquireUse()) return listOf(VoiceBenchObservation.unavailable("cuda", "LOCAL_TOKENIZER_PACK_MISSING"))
    val voice = KokoroVoice.HEART
    val current = AtomicReference<VoicePhraseEngine?>(null)
    interrupt.set {
        factory.cancelPending()
        current.get()?.cancel()
        Unit
    }
    try {
        if (!active()) throw CancellationException("Benchmark stopped")
        val started = System.nanoTime()
        val engine = factory.create(models.modelDirectory(), voice.lexicon, 1)
        current.set(engine)
        val preparation = elapsedVoiceBenchMs(started)
        check(engine.executionProvider == "gateway-cuda")
        val rows = mutableListOf<VoiceBenchObservation>()
        repeat(2) { repeat ->
            for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                if (!active()) throw CancellationException("Benchmark stopped")
                val signal = VoicePcmSignalMeasurement()
                val frames = VoiceStreamFrames(fixture.text)
                var first: Long? = null
                var cues = 0
                val start = System.nanoTime()
                if (engine.supportsStreaming) {
                    consumeVoiceStream(engine, fixture.text, voice.speakerId, 1f, active) { chunk ->
                        if (first == null && chunk.samples.isNotEmpty()) first = elapsedVoiceBenchMs(start)
                        signal.accept(chunk.samples)
                        cues += frames.accept(chunk).size
                        true
                    }
                    frames.finish()
                } else {
                    val output = engine.generate(fixture.text, voice.speakerId, 1f)
                    first = elapsedVoiceBenchMs(start)
                    signal.accept(output.samples)
                }
                val pcm = requireNotNull(signal.snapshot())
                val good = pcm.rms.isFinite() && pcm.nonSilent > 0 && pcm.clipped.toDouble() / pcm.samples <= .01
                rows += VoiceBenchObservation(
                    "${fixture.name}-$repeat",
                    if (good) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED,
                    if (good) null else "INVALID_SIGNAL",
                    buildMap {
                        put("prepareMs", preparation.toDouble())
                        put("elapsedMs", elapsedVoiceBenchMs(start).toDouble())
                        first?.let { put("firstPcmMs", it.toDouble()) }
                        put("frames", pcm.samples.toDouble())
                        put("wordCues", cues.toDouble())
                        put("clippedSamples", pcm.clipped.toDouble())
                    }
                )
                checkpoint(rows)
            }
        }
        return rows
    } finally {
        interrupt.set(null)
        try {
            current.getAndSet(null)?.close()
        } finally {
            models.releaseUse()
        }
    }
}
