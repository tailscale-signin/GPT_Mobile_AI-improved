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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Exercises production queueing with independently fixed inputs, including actual same-owner warm replies. */
internal class VoiceSpeechPlaybackBenchmark(
    private val context: Context,
    private val models: LocalVoiceModelStore,
    private val configuration: VoiceSpeechBenchmarkConfiguration,
    private val thermalStatus: () -> Int?
) {
    private data class Active(val output: KokoroVoiceOutput, val measurement: VoiceSpeechPlaybackMeasurement, val completion: CompletableDeferred<VoiceSpeechPlaybackResult>)
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<Active?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val retirements = mutableListOf<VoiceNativeRetirement>()
    val pendingRetirements: List<VoiceNativeRetirement> get() = synchronized(retirements) { retirements.filter { it.state.value != VoiceNativeRetirementState.RETIRED } }

    suspend fun awaitRetired() {
        synchronized(retirements) { retirements.toList() }.forEach { it.awaitRetired() }
    }

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

    /** Compatibility entry point for a single fresh-session playback smoke test. */
    suspend fun measure(backend: VoiceSpeechBackend): VoiceSpeechPlaybackResult = measureSeries(backend).first()

    suspend fun measureSeries(backend: VoiceSpeechBackend, publish: (List<VoiceSpeechPlaybackResult>) -> Unit = {}): List<VoiceSpeechPlaybackResult> = withContext(Dispatchers.Main.immediate) {
        check(pendingRetirements.isEmpty()) { "Previous native speech session is still retiring" }
        val results = mutableListOf<VoiceSpeechPlaybackResult>()
        suspend fun publishSnapshot(duringCleanup: Boolean = false) {
            val snapshot = results.toList()
            withContext(if (duringCleanup) NonCancellable + Dispatchers.IO else Dispatchers.IO) { publish(snapshot) }
        }
        val plans = VoiceSpeechPlaybackProtocol.plans(configuration)
        for ((scenario, selected) in plans.groupBy { it.scenario }) {
            if (cancelled.get()) break
            // A cold or cancellation sample gets a fresh engine. Other scenarios reuse the exact owner.
            val sessions = if (scenario in setOf(VoiceSpeechPlaybackScenario.COLD_START, VoiceSpeechPlaybackScenario.CANCELLATION)) selected.map(::listOf) else listOf(selected)
            for (session in sessions) {
                if (cancelled.get() || pendingRetirements.isNotEmpty()) break
                val relay = VoiceSpeechPlaybackMeasurementRelay()
                val nativeOwner = AtomicReference<VoicePhraseEngine?>(null)
                val native = QualcommVoicePhraseEngineFactory(context, backend, {}, voice = configuration.voice, speechRate = configuration.speechRate)
                val strictFactory = object : VoicePhraseEngineFactory {
                    override val selectedBackend = backend
                    override val preparationTimeoutMs get() = native.preparationTimeoutMs
                    override val identity = "playback-benchmark-${native.identity}-${configuration.startedIdentity()}"
                    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                        val preparationMeasurement = relay.current
                        preparationMeasurement?.preparationStarted()
                        val created = try {
                            native.createBenchmarkEngine(directory, lexicon, threads)
                        } catch (failure: Throwable) {
                            preparationMeasurement?.nativeFailure(failure, configuration.deviceKey)
                            throw failure
                        }
                        nativeOwner.set(created)
                        return object : VoicePhraseEngine by created {
                            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                                val attemptMeasurement = relay.current
                                attemptMeasurement?.attemptStarted(text)
                                return try {
                                    created.generate(text, speaker, speed)
                                } catch (failure: Throwable) {
                                    attemptMeasurement?.nativeFailure(failure, configuration.deviceKey)
                                    throw failure
                                }
                            }
                        }
                    }
                    override fun cancelPending() = native.cancelPending()
                }
                val output = KokoroVoiceOutput(
                    context,
                    models,
                    { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
                    { configuration.speechRate },
                    measurements = relay
                )
                val startIndex = results.size
                var retirement: VoiceNativeRetirement? = null
                try {
                    output.configureEngine(strictFactory)
                    output.configureThreads(configuration.threads)
                    output.configurePacing(VoicePauseDuration.NATURAL, VoicePerformanceMode.BALANCED)
                    when (configuration.calibrationPolicy) {
                        VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO -> output.configureFixedCaptionOffset(0)
                        VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS -> output.configureSavedRouteCaptionOffset(configuration.captionOffsetMs)
                    }
                    for (plan in session) {
                        if (cancelled.get()) break
                        val result = measureSample(output, relay, backend, plan, onInterrupted = { interrupted ->
                            results += interrupted
                            publishSnapshot(duringCleanup = true)
                        }) { nativeOwner.get()?.executionProgress }
                        results += result
                        publishSnapshot()
                        if (result.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) || result.state == VoiceSpeechPlaybackState.CANCELLED && plan.cancellationTarget == null) break
                    }
                } finally {
                    relay.current = null
                    withContext(NonCancellable + Dispatchers.Main.immediate) { output.close() }
                    val closing = VoiceNativeRetirement(output::awaitClosed).also { synchronized(retirements) { retirements += it } }
                    retirement = closing
                    val cleanup = withContext(NonCancellable) { closing.awaitWithinBudget() }
                    for (index in startIndex until results.size) results[index] = results[index].copy(cleanupState = cleanup.name)
                    publishSnapshot(duringCleanup = true)
                }
                if (retirement?.state?.value != VoiceNativeRetirementState.RETIRED) return@withContext results.toList()
                if (results.lastOrNull()?.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED)) return@withContext results.toList()
            }
        }
        results.toList()
    }

    private suspend fun measureSample(output: KokoroVoiceOutput, relay: VoiceSpeechPlaybackMeasurementRelay, backend: VoiceSpeechBackend, plan: VoiceSpeechPlaybackPlan, onInterrupted: suspend (VoiceSpeechPlaybackResult) -> Unit, currentProgress: () -> VoiceSpeechProgress?): VoiceSpeechPlaybackResult = coroutineScope {
        val measurement = VoiceSpeechPlaybackMeasurement(backend, thermalStatus(), plan = plan)
        relay.current = measurement
        output.bindTimedSpokenRange { segment, _, range, timing -> measurement.caption(timing, segment, range) }
        val completion = CompletableDeferred<VoiceSpeechPlaybackResult>()
        val owned = Active(output, measurement, completion)
        check(active.compareAndSet(null, owned))
        var targetJob: kotlinx.coroutines.Job? = null
        var inputJob: kotlinx.coroutines.Job? = null
        try {
            if (cancelled.get()) {
                measurement.cancelRequested()
                return@coroutineScope measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus())
            }
            val reply = output.openReplyWithFailure(
                configuration.voice,
                complete = {
                    val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, thermalStatus())
                    val valid = result.executionVerified && result.captionManifestValid && result.firstPresentedAudioMs != null && result.totalAudioMs > 0
                    completion.complete(if (valid || plan.cancellationTarget != null) result else result.copy(state = VoiceSpeechPlaybackState.FAILED, failure = VoiceSpeechFailureCode.OUTPUT_INVALID, pipelineFailureCode = "FIXTURE_VALIDATION_FAILED"))
                },
                error = { failure, _ ->
                    val evidence = speechPlaybackFailureEvidence(failure.cause, backend, configuration.deviceKey)
                    val state = if (evidence?.classification?.state == VoiceSpeechBenchmarkBackendState.UNSUPPORTED) VoiceSpeechPlaybackState.UNSUPPORTED else VoiceSpeechPlaybackState.FAILED
                    completion.complete(measurement.result(state, thermalStatus(), evidence?.classification?.code ?: VoiceSpeechFailureCode.EXECUTION_FAILED, evidence?.classification?.stage, failure.code, evidence))
                }
            )
            if (reply == null) {
                completion.complete(measurement.result(VoiceSpeechPlaybackState.FAILED, thermalStatus(), VoiceSpeechFailureCode.EXECUTION_FAILED, pipelineFailureCode = "OUTPUT_UNAVAILABLE"))
            } else {
                inputJob = launch {
                    plan.segments.forEachIndexed { index, segment ->
                        if (index > 0 && plan.arrivalIntervalMs > 0) delay(plan.arrivalIntervalMs)
                        if (completion.isCompleted) return@launch
                        reply.enqueue(segment)
                    }
                    reply.finish()
                }
                plan.cancellationTarget?.let { target ->
                    targetJob = launch {
                        val reached = withTimeoutOrNull(VoiceSpeechPlaybackProtocol.MAX_CANCEL_TARGET_WAIT_MS) {
                            while (!completion.isCompleted) {
                                if (measurement.reached(target, currentProgress())) return@withTimeoutOrNull true
                                delay(20)
                            }
                            false
                        } == true
                        if (!completion.isCompleted) {
                            val stillReached = measurement.reached(target, currentProgress())
                            measurement.cancelRequested(reached && stillReached)
                            output.stop("speech_benchmark_cancel_probe")
                            completion.complete(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
                        }
                    }
                }
            }
            val deadline = if (plan.scenario == VoiceSpeechPlaybackScenario.SUSTAINED) 600_000L else 180_000L
            withTimeoutOrNull(deadline) { completion.await() } ?: run {
                measurement.cancelRequested()
                output.stop("speech_benchmark_deadline")
                measurement.result(VoiceSpeechPlaybackState.FAILED, thermalStatus(), VoiceSpeechFailureCode.EXECUTION_FAILED, pipelineFailureCode = "BENCHMARK_DEADLINE")
                    .copy(timeoutLimitMs = deadline, timeoutElapsedMs = deadline, timeoutScope = "BENCHMARK_SAMPLE")
            }
        } catch (cancelled: CancellationException) {
            measurement.cancelRequested()
            output.stop("speech_benchmark_cancelled")
            onInterrupted(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
            throw cancelled
        } finally {
            targetJob?.cancel()
            inputJob?.cancel()
            active.compareAndSet(owned, null)
        }
    }

    private fun VoiceSpeechBenchmarkConfiguration.startedIdentity() = "$appBuild-${voice.name}-$threads-$speechRate"
}
