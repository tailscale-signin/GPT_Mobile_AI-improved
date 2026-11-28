package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.melo.kokoro.KokoroFrontend
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
    private val thermalStatus: () -> Int?,
    private val engineFactory: ((VoiceSpeechBackend) -> VoicePhraseEngineFactory)? = null
) {
    private data class Active(val output: KokoroVoiceOutput, val measurement: VoiceSpeechPlaybackMeasurement, val completion: CompletableDeferred<VoiceSpeechPlaybackResult>)
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<Active?>(null)
    private val main = Handler(Looper.getMainLooper())
    private data class PendingCleanup(val backend: VoiceSpeechBackend, val retirement: VoiceNativeRetirement, val measurement: VoiceSpeechPlaybackMeasurement?, val engine: VoicePhraseEngine?)

    @Volatile private var pendingCleanup: PendingCleanup? = null
    private val retirements = mutableListOf<VoiceNativeRetirement>()
    private val privacyContext = ConversationPrivacyContext.capture()
    private val unretiredOwners = mutableListOf<Any>()

    @Volatile var nativeCleanupPending: Boolean = false
        private set

    @Volatile var thermalLimitReached: Boolean = false
        private set
    val pendingRetirements: List<VoiceNativeRetirement> get() = synchronized(retirements) { retirements.filter { it.state.value != VoiceNativeRetirementState.RETIRED } }

    suspend fun awaitRetired() {
        synchronized(retirements) { retirements.toList() }.forEach { it.awaitRetired() }
    }

    fun confirmedCleanup(report: VoiceSpeechBenchmarkReport): VoiceSpeechBenchmarkReport {
        val pending = pendingCleanup ?: return report
        val retiredAt = pending.retirement.retiredAtNanos ?: return report
        if (nativeCleanupPending || report.configuration != configuration) return report
        val rows = report.playbackResults
        val last = rows.indexOfLast { it.requestedBackend == pending.backend && it.cleanupState == "PENDING" }
        if (last < 0) return report
        return report.copy(
            playbackResults = rows.mapIndexed { index, row ->
                if (row.requestedBackend == pending.backend && row.cleanupState == "PENDING") {
                    row.copy(sdkCancelToReadReturnMs = pending.engine?.sdkCancelToReadReturnMs?.takeIf { index == last && row.stopOrigin != null }, cleanupState = "RETIRED", nativeRetirementMs = if (index == last) pending.measurement?.stopToRetiredMs(retiredAt) else null)
                } else {
                    row
                }
            },
            engineResults = report.engineResults.map { if (it.requestedBackend == pending.backend) it.copy(cleanupState = "RETIRED") else it }
        )
    }

    private val stopOrigin = AtomicReference<VoiceSpeechStopOrigin?>(null)
    private val warmingOutput = AtomicReference<KokoroVoiceOutput?>(null)

    fun cancel(origin: VoiceSpeechStopOrigin = VoiceSpeechStopOrigin.USER) {
        stopOrigin.compareAndSet(null, origin)
        cancelled.set(true)
        warmingOutput.get()?.let { output -> main.post { if (warmingOutput.get() === output) output.cancelPrewarm() } }
        active.get()?.let { owned ->
            owned.measurement.cancelRequested(origin = stopOrigin.get())
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

    suspend fun measureSeries(backend: VoiceSpeechBackend, plans: List<VoiceSpeechPlaybackPlan> = VoiceSpeechPlaybackProtocol.plans(configuration), publish: (List<VoiceSpeechPlaybackResult>) -> Unit = {}): List<VoiceSpeechPlaybackResult> = withContext(Dispatchers.Main.immediate) {
        check(pendingRetirements.isEmpty() && !nativeCleanupPending) { "Previous native speech session is still retiring" }
        val results = mutableListOf<VoiceSpeechPlaybackResult>()
        suspend fun publishSnapshot(duringCleanup: Boolean = false) {
            val snapshot = results.toList()
            withContext(if (duringCleanup) NonCancellable + Dispatchers.IO else Dispatchers.IO) { publish(snapshot) }
        }
        for ((scenario, selected) in plans.groupBy { it.scenario }) {
            if (cancelled.get()) break
            // A cold or cancellation sample gets a fresh engine. Other scenarios reuse the exact owner.
            val sessions = when (scenario) {
                VoiceSpeechPlaybackScenario.COLD_START, VoiceSpeechPlaybackScenario.CANCELLATION -> selected.map(::listOf)
                VoiceSpeechPlaybackScenario.RESIDENT_WARM -> listOf(selected.filter { it.prewarmMode == null }).filter { it.isNotEmpty() } + selected.filter { it.prewarmMode != null }.map(::listOf)
                else -> listOf(selected)
            }
            for (session in sessions) {
                if (cancelled.get() || pendingRetirements.isNotEmpty()) break
                val relay = VoiceSpeechPlaybackMeasurementRelay()
                val streamingRelay = VoiceSpeechEnginePlaybackRelay()
                val nativeOwner = AtomicReference<VoicePhraseEngine?>(null)
                val cleanupFailed = AtomicBoolean()
                val sdk = configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
                val selected = if (sdk) {
                    checkNotNull(engineFactory) { "An installed SDK engine factory is required" }(backend).also {
                        require(it.selectedBackend == backend && !it.requiresKokoroAssets)
                    }
                } else {
                    QualcommVoicePhraseEngineFactory(
                        context,
                        backend,
                        {},
                        voice = configuration.voice,
                        speechRate = configuration.speechRate,
                        htpPerformanceMode = configuration.htpPerformanceMode,
                        modelVariant = configuration.nativeModelVariant
                    )
                }
                fun quarantine() {
                    cleanupFailed.set(true)
                    nativeCleanupPending = true
                    synchronized(unretiredOwners) {
                        unretiredOwners += selected
                        nativeOwner.get()?.let { unretiredOwners += it }
                    }
                }
                val strictFactory = object : VoicePhraseEngineFactory {
                    override val selectedBackend = backend
                    override val requiresKokoroAssets get() = selected.requiresKokoroAssets
                    override val nativeCleanupPending get() = selected.nativeCleanupPending || cleanupFailed.get()
                    override val preparationTimeoutMs get() = selected.preparationTimeoutMs
                    override fun isReady() = selected.isReady()
                    override val identity = "playback-benchmark-${selected.identity}-${configuration.startedIdentity()}"
                    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                        val preparationMeasurement = relay.current
                        preparationMeasurement?.preparationStarted()
                        (selected as? MediaPipeVoicePhraseEngineFactory)?.playbackMeasurement = preparationMeasurement
                        val created = try {
                            if (sdk) selected.create(directory, lexicon, threads) else (selected as QualcommVoicePhraseEngineFactory).createBenchmarkEngine(directory, lexicon, threads)
                        } catch (failure: Throwable) {
                            if (selected.nativeCleanupPending) quarantine()
                            preparationMeasurement?.nativeFailure(failure, configuration.deviceKey, configuration.modelSha256)
                            throw failure
                        } finally {
                            (selected as? MediaPipeVoicePhraseEngineFactory)?.playbackMeasurement = null
                        }
                        nativeOwner.set(created)
                        if (sdk) return VoiceSpeechBenchmarkStreamingEngine(created, { relay.current }, { streamingRelay.current }, ::quarantine)
                        return object : VoicePhraseEngine by created {
                            override fun close() {
                                try {
                                    created.close()
                                } catch (failure: Throwable) {
                                    quarantine()
                                    throw failure
                                }
                            }
                            override fun preparePhrase(text: String): KokoroFrontend.EncodedPhrase? {
                                relay.current?.attemptStarted(text)
                                return created.preparePhrase(text)
                            }
                            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio =
                                generatePrepared(text, preparePhrase(text), speaker, speed)
                            override fun generatePrepared(text: String, encoded: KokoroFrontend.EncodedPhrase?, speaker: Int, speed: Float): VoicePhraseAudio {
                                val attemptMeasurement = relay.current
                                return try {
                                    created.generatePrepared(text, encoded, speaker, speed).also { audio ->
                                        attemptMeasurement?.audioGenerated(VoiceSpeechAudioObservation.measure(text, audio))
                                    }
                                } catch (failure: Throwable) {
                                    attemptMeasurement?.nativeFailure(failure, configuration.deviceKey, configuration.modelSha256)
                                    throw failure
                                }
                            }
                        }
                    }
                    override fun cancelPending() = selected.cancelPending()
                }
                val output = KokoroVoiceOutput(
                    context,
                    models,
                    { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
                    { configuration.speechRate },
                    privacy = { privacyContext },
                    measurements = relay
                )
                val startIndex = results.size
                val sustained = if (scenario == VoiceSpeechPlaybackScenario.SUSTAINED) VoiceSpeechSustainedSeries(configuration.sustainedTargetAudioMs) else null
                var retirement: VoiceNativeRetirement? = null
                var finishedSessionLoop = false
                try {
                    output.configureEngine(strictFactory)
                    output.configureThreads(configuration.threads)
                    output.configurePacing(VoicePauseDuration.NATURAL, VoicePerformanceMode.BALANCED)
                    // This diagnostic fixture measures captions independently of the display preference.
                    output.configureCaptionAlignment(true)
                    when (configuration.calibrationPolicy) {
                        VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO -> output.configureFixedCaptionOffset(0)
                        VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS -> output.configureSavedRouteCaptionOffset(configuration.captionOffsetMs)
                    }
                    val pendingPlans = session.toMutableList()
                    var planIndex = 0
                    while (planIndex < pendingPlans.size) {
                        if (cancelled.get() || sustained?.canStart() == false) break
                        val sustainedThermal = if (sustained != null) thermalStatus() else null
                        if (sustained != null && !sustained.canStart(sustainedThermal)) {
                            thermalLimitReached = (sustainedThermal ?: -1) >= 2
                            break
                        }
                        val plan = pendingPlans[planIndex++]
                        val limit = sustained?.sampleDeadlineMs() ?: 180_000L
                        val scope = if (sustained != null && sustained.remainingMs() <= VoiceSpeechSustainedSeries.MAX_REPLY_WALL_MS) "SUSTAINED_SERIES" else "BENCHMARK_SAMPLE"
                        val prewarmStart = System.nanoTime()
                        val prewarmed = plan.prewarmMode?.let { mode ->
                            val ready = CompletableDeferred<Boolean>()
                            warmingOutput.set(output)
                            try {
                                if (!cancelled.get()) output.prewarmForBenchmark(configuration.voice, mode) { ready.complete(it) } else ready.complete(false)
                                withTimeoutOrNull(strictFactory.preparationTimeoutMs + VoiceSilentWarmup.CANCELLATION_DEADLINE_MS) { ready.await() } ?: false
                            } finally {
                                if (!ready.isCompleted) output.cancelPrewarm()
                                warmingOutput.compareAndSet(output, null)
                            }
                        }
                        val prewarmMs = prewarmed?.let { (System.nanoTime() - prewarmStart).coerceAtLeast(0) / 1_000_000 }
                        val measured = measureSample(output, relay, streamingRelay, backend, plan, limit, scope, onInterrupted = { interrupted ->
                            results += (sustained?.observe(interrupted) ?: interrupted).copy(prewarmCompleted = prewarmed, prewarmElapsedMs = prewarmMs)
                            publishSnapshot(duringCleanup = true)
                        }) { nativeOwner.get()?.executionProgress }
                        val result = (sustained?.observe(measured) ?: measured).copy(prewarmCompleted = prewarmed, prewarmElapsedMs = prewarmMs)
                        results += result
                        publishSnapshot()
                        if (result.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) || result.state == VoiceSpeechPlaybackState.CANCELLED && plan.cancellationTarget == null) break
                        if (sustained?.canStart() == true) {
                            pendingPlans += plan.copy(repeat = plan.repeat + 1, expectedResident = true)
                        }
                    }
                    finishedSessionLoop = true
                } finally {
                    if (sustained != null && results.size > startIndex) {
                        results[results.lastIndex] = sustained.finishIncomplete(results.last(), cancelled.get() || !finishedSessionLoop || thermalLimitReached)
                            .let { if (thermalLimitReached) it.copy(pipelineFailureCode = "THERMAL_LIMIT") else it }
                    }
                    val lastMeasurement = relay.current
                    relay.current = null
                    streamingRelay.current = null
                    withContext(NonCancellable + Dispatchers.Main.immediate) { output.close() }
                    val closing = VoiceNativeRetirement(output::awaitClosed).also { synchronized(retirements) { retirements += it } }
                    retirement = closing
                    val cleanup = withContext(NonCancellable) { closing.awaitWithinBudget() }
                    if (cleanup == VoiceNativeRetirementState.PENDING) pendingCleanup = PendingCleanup(backend, closing, lastMeasurement, nativeOwner.get())
                    if (strictFactory.nativeCleanupPending) {
                        quarantine()
                    }
                    for (index in startIndex until results.size) {
                        results[index] = if (nativeCleanupPending) {
                            results[index].copy(cleanupState = "FAILED", state = VoiceSpeechPlaybackState.FAILED, pipelineFailureCode = "NATIVE_RETIREMENT_FAILED")
                        } else {
                            results[index].copy(sdkCancelToReadReturnMs = nativeOwner.get()?.sdkCancelToReadReturnMs?.takeIf { index == results.lastIndex && results[index].stopOrigin != null }, cleanupState = cleanup.name, nativeRetirementMs = if (cleanup == VoiceNativeRetirementState.RETIRED && index == results.lastIndex) lastMeasurement?.stopToRetiredMs(closing.retiredAtNanos) else null)
                        }
                    }
                    publishSnapshot(duringCleanup = true)
                }
                if (nativeCleanupPending || retirement?.state?.value != VoiceNativeRetirementState.RETIRED) return@withContext results.toList()
                if (thermalLimitReached) return@withContext results.toList()
                if (results.lastOrNull()?.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED)) return@withContext results.toList()
            }
        }
        results.toList()
    }

    private suspend fun measureSample(output: KokoroVoiceOutput, relay: VoiceSpeechPlaybackMeasurementRelay, streamingRelay: VoiceSpeechEnginePlaybackRelay, backend: VoiceSpeechBackend, plan: VoiceSpeechPlaybackPlan, deadline: Long, deadlineScope: String, onInterrupted: suspend (VoiceSpeechPlaybackResult) -> Unit, currentProgress: () -> VoiceSpeechProgress?): VoiceSpeechPlaybackResult = coroutineScope {
        val measurement = VoiceSpeechPlaybackMeasurement(backend, thermalStatus(), plan = plan)
        relay.current = measurement
        val streaming = VoiceSpeechEnginePlaybackObservation().takeIf { configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }
        streamingRelay.current = streaming
        fun describe(result: VoiceSpeechPlaybackResult) = if (streaming == null) {
            result
        } else {
            result.copy(
                streamedAudioValidated = streaming.validated(),
                requestedDelegate = requestedSpeechDelegate(backend),
                observedProviderStatus = "NOT_EXPOSED_BY_SDK",
                engineStreamCalls = streaming.callCount(),
                sdkPcm = streaming.signalSnapshot(),
                firstSdkPcmAfterStreamStartMs = streaming.firstPcmMs(),
                engineStreamWallMs = streaming.streamWallMs(),
                requestedThreads = null,
                effectiveThreads = null
            )
        }
        output.bindTimedSpokenRange { segment, _, range, timing -> measurement.caption(timing, segment, range) }
        val completion = CompletableDeferred<VoiceSpeechPlaybackResult>()
        val owned = Active(output, measurement, completion)
        check(active.compareAndSet(null, owned))
        var targetJob: kotlinx.coroutines.Job? = null
        var inputJob: kotlinx.coroutines.Job? = null
        try {
            if (cancelled.get()) {
                measurement.cancelRequested(origin = stopOrigin.get())
                return@coroutineScope describe(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
            }
            val reply = output.openReplyWithFailure(
                configuration.voice,
                complete = {
                    val result = describe(measurement.result(VoiceSpeechPlaybackState.COMPLETE, thermalStatus()))
                    val valid = (if (streaming == null) result.executionVerified else result.streamedAudioValidated == true && result.chunks > 0) && result.captionManifestValid && result.firstPresentedAudioMs != null && result.totalAudioMs > 0
                    completion.complete(
                        if (valid || plan.cancellationTarget != null) {
                            result
                        } else {
                            result.copy(
                                state = VoiceSpeechPlaybackState.FAILED,
                                failure = VoiceSpeechFailureCode.OUTPUT_INVALID,
                                pipelineFailureCode = if (result.streamedAudioValidated == true && !result.captionManifestValid) "CAPTION_MANIFEST_MISMATCH" else "FIXTURE_VALIDATION_FAILED"
                            )
                        }
                    )
                },
                error = { failure, _ ->
                    val evidence = speechPlaybackFailureEvidence(failure.cause, backend, configuration.deviceKey, configuration.modelSha256)
                    val state = if (evidence?.classification?.state == VoiceSpeechBenchmarkBackendState.UNSUPPORTED) VoiceSpeechPlaybackState.UNSUPPORTED else VoiceSpeechPlaybackState.FAILED
                    completion.complete(describe(measurement.result(state, thermalStatus(), evidence?.classification?.code ?: VoiceSpeechFailureCode.EXECUTION_FAILED, evidence?.classification?.stage, failure.code, evidence)))
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
                            measurement.cancelRequested(reached && stillReached, VoiceSpeechStopOrigin.PROBE)
                            output.stop("speech_benchmark_cancel_probe")
                            completion.complete(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus()))
                        }
                    }
                }
            }
            describe(
                withTimeoutOrNull(deadline) { completion.await() } ?: run {
                    measurement.cancelRequested(origin = VoiceSpeechStopOrigin.DEADLINE)
                    output.stop("speech_benchmark_deadline")
                    measurement.result(VoiceSpeechPlaybackState.FAILED, thermalStatus(), VoiceSpeechFailureCode.EXECUTION_FAILED, pipelineFailureCode = "BENCHMARK_DEADLINE")
                        .let { it.copy(timeoutLimitMs = deadline, timeoutElapsedMs = it.elapsedMs, timeoutScope = deadlineScope) }
                }
            )
        } catch (cancelled: CancellationException) {
            measurement.cancelRequested(origin = stopOrigin.get() ?: VoiceSpeechStopOrigin.COROUTINE)
            output.stop("speech_benchmark_cancelled")
            onInterrupted(describe(measurement.result(VoiceSpeechPlaybackState.CANCELLED, thermalStatus())))
            throw cancelled
        } finally {
            targetJob?.cancel()
            inputJob?.cancel()
            active.compareAndSet(owned, null)
        }
    }

    private fun VoiceSpeechBenchmarkConfiguration.startedIdentity() = "$appBuild-${voice.name}-$threads-$speechRate"
}
