package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Exercises the actual speech planner, bounded PCM queue, AudioTrack and presented-audio caption clock. */
internal class VoiceSpeechPlaybackBenchmark(
    private val context: Context,
    private val models: LocalVoiceModelStore,
    private val configuration: VoiceSpeechBenchmarkConfiguration,
    private val thermalStatus: () -> Int?
) {
    private data class Active(
        val output: KokoroVoiceOutput,
        val measurement: VoiceSpeechPlaybackMeasurement,
        val completion: CompletableDeferred<VoiceSpeechPlaybackResult>
    )

    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<Active?>(null)
    private val main = Handler(Looper.getMainLooper())

    fun cancel() {
        cancelled.set(true)
        active.get()?.let { owned ->
            owned.measurement.cancelRequested()
            main.post {
                if (active.get() === owned) {
                    owned.output.stop("speech_benchmark_cancelled")
                    owned.completion.complete(owned.measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
                }
            }
        }
    }

    suspend fun measure(backend: VoiceSpeechBackend): VoiceSpeechPlaybackResult = withContext(Dispatchers.Main.immediate) {
        val measurement = VoiceSpeechPlaybackMeasurement(backend, thermalStatus())
        val completion = CompletableDeferred<VoiceSpeechPlaybackResult>()
        val native = QualcommVoicePhraseEngineFactory(context, backend, {}, voice = configuration.voice, speechRate = configuration.speechRate)
        val strictFactory = object : VoicePhraseEngineFactory {
            override val selectedBackend = backend
            override val identity = "playback-benchmark-${native.identity}-${configuration.startedIdentity()}"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = native.createBenchmarkEngine(directory, lexicon, threads)
            override fun cancelPending() = native.cancelPending()
        }
        val output = KokoroVoiceOutput(
            context,
            models,
            { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
            { configuration.speechRate },
            measurements = measurement
        )
        val owned = Active(output, measurement, completion)
        check(active.compareAndSet(null, owned))
        try {
            output.configureEngine(strictFactory)
            output.configureThreads(configuration.threads)
            output.configurePacing(VoicePauseDuration.NATURAL, VoicePerformanceMode.BALANCED)
            output.configureCaptionOffset(0)
            output.bindTimedSpokenRange { segment, _, range, timing -> measurement.caption(timing, segment, range) }
            if (cancelled.get()) {
                measurement.cancelRequested()
                completion.complete(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
            } else {
                output.speakProjectedWithFailure(
                    VoiceSpeechBenchmarkFixture.LONG.text,
                    configuration.voice,
                    complete = {
                        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, thermalStatus())
                        val valid = result.firstPresentedAudioMs != null &&
                            result.chunks > 0 &&
                            result.validatedChunks == result.chunks &&
                            result.totalAudioMs > 0 &&
                            result.captionObservations > 0 &&
                            result.captionClockRegressions == 0 &&
                            result.captionTimingViolations == 0 &&
                            result.captionWordsExpected > 0 &&
                            result.captionWordsObserved == result.captionWordsExpected &&
                            result.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(backend) &&
                            (backend == VoiceSpeechBackend.CPU || result.acceleratorExecutionObserved)
                        completion.complete(if (valid) result else result.copy(state = VoiceSpeechPlaybackState.FAILED, failure = VoiceSpeechFailureCode.OUTPUT_INVALID))
                    },
                    error = { failure, _ ->
                        val cause = generateSequence(failure.cause) { it.cause }.take(8).filterIsInstance<dev.melo.kokoro.TimedKokoro.Failure>().firstOrNull()
                        val classified = speechBenchmarkFailure(cause ?: (failure.cause as? Exception) ?: IllegalStateException("EXECUTION_FAILED"))
                        val state = if (classified.state == VoiceSpeechBenchmarkBackendState.UNSUPPORTED) VoiceSpeechPlaybackState.UNSUPPORTED else VoiceSpeechPlaybackState.FAILED
                        completion.complete(measurement.result(state, thermalStatus(), classified.code, classified.stage))
                    }
                )
            }
            withTimeoutOrNull(180_000L) { completion.await() } ?: run {
                measurement.cancelRequested()
                output.stop("speech_benchmark_timeout")
                measurement.result(VoiceSpeechPlaybackState.FAILED, thermalStatus(), VoiceSpeechFailureCode.EXECUTION_FAILED)
            }
        } catch (cancelled: CancellationException) {
            measurement.cancelRequested()
            output.stop("speech_benchmark_cancelled")
            throw cancelled
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { output.close() }
            // Closing schedules native cleanup behind the synthesis owner. Do not release
            // benchmark ownership while that old worker can still hold a model or audio.
            withContext(NonCancellable + Dispatchers.IO) {
                while (!output.awaitClosed(250)) yield()
            }
            active.compareAndSet(owned, null)
        }
    }

    private fun VoiceSpeechBenchmarkConfiguration.startedIdentity() = "$appBuild-${voice.name}-$threads-$speechRate"
}
