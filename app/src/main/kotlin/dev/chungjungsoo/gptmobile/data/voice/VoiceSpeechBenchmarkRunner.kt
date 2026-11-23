package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** CPU/GPU/NPU receive identical text, voice, speed and thread settings, on a single worker. */
internal class VoiceSpeechBenchmarkRunner(
    private val create: (VoiceSpeechBackend) -> VoicePhraseEngine,
    private val cancelPreparation: () -> Unit = {},
    private val thermalStatus: () -> Int? = { null },
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val plan: (VoiceSpeechBenchmarkFixture, VoiceSpeechBenchmarkWorkload) -> List<String> = VoiceSpeechWorkload::phrases
) {
    private val cancelled = AtomicBoolean(false)
    private val currentEngine = AtomicReference<VoicePhraseEngine?>(null)
    private data class CancellationRequest(val atNanos: Long, val stage: VoiceSpeechStage?)
    private val cancellationRequest = AtomicReference<CancellationRequest?>(null)

    fun cancel() {
        val engine = currentEngine.get()
        val stage = runCatching { engine?.executionProgress?.takeIf { it.active }?.stage }.getOrNull()
            ?: VoiceSpeechStage.PREPARING.takeIf { engine == null }
        cancellationRequest.compareAndSet(null, CancellationRequest(nanoTime(), stage))
        cancelled.set(true)
        runCatching { cancelPreparation() }
        runCatching { currentEngine.get()?.cancel() }
    }

    fun run(initial: VoiceSpeechBenchmarkReport, publish: (VoiceSpeechBenchmarkReport) -> Unit): VoiceSpeechBenchmarkReport {
        var report = initial
        fun emit() = publish(report)
        fun update(index: Int, result: VoiceSpeechBenchmarkBackendResult) {
            report = report.copy(results = report.results.toMutableList().also { it[index] = result })
            emit()
        }
        emit()
        val plans = linkedMapOf<VoiceSpeechBenchmarkFixture, List<String>>()
        var invalidFixture: VoiceSpeechBenchmarkFixture? = null
        var invalidChunk: Int? = null
        var invalidBytes: Int? = null
        try {
            check(initial.configuration.currentProtocol) { "FRONTEND_INPUT_INVALID" }
            for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                invalidFixture = fixture
                invalidChunk = null
                invalidBytes = null
                val chunks = plan(fixture, report.configuration.workload)
                if (chunks.isEmpty() || chunks.size > VoiceSpeechWorkload.MAX_CHUNKS) throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_INVALID", 0)
                chunks.forEachIndexed { index, text ->
                    invalidChunk = index
                    invalidBytes = text.toByteArray(Charsets.UTF_8).size
                    VoiceSpeechWorkload.validate(text)
                }
                plans[fixture] = chunks
            }
        } catch (error: Exception) {
            val failure = speechBenchmarkFailure(error)
            val workload = error as? VoiceSpeechWorkloadFailure
            return report.copy(
                state = VoiceSpeechBenchmarkRunState.FAILED,
                issue = VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD,
                finishedAtMillis = wallTime(),
                results = report.results.mapIndexed { index, result ->
                    if (index == 0) {
                        result.copy(
                            state = VoiceSpeechBenchmarkBackendState.NOT_RUN,
                            failure = failure.code,
                            failureStage = VoiceSpeechStage.FRONTEND,
                            failedFixture = invalidFixture,
                            failedChunk = invalidChunk,
                            failedInputBytes = workload?.inputBytes ?: invalidBytes,
                            failedInputByteLimit = workload?.inputByteLimit ?: VoiceSpeechWorkload.MAX_PHRASE_BYTES
                        )
                    } else {
                        result.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN)
                    }
                }
            ).also(publish)
        }
        for (backend in report.configuration.plannedBackendOrder) {
            val index = report.results.indexOfFirst { it.requestedBackend == backend }
            if (cancelled.get()) break
            val preparationThermalBefore = thermalStatus()
            var result = report.results[index].copy(preparationThermalBefore = preparationThermalBefore)
            if ((preparationThermalBefore ?: -1) >= 2) {
                report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                update(index, result)
                break
            }
            result = result.copy(state = VoiceSpeechBenchmarkBackendState.PREPARING)
            report = report.copy(activeFixture = null, activeRepeat = null, activeChunk = null)
            update(index, result)
            var engine: VoicePhraseEngine? = null
            var attemptEvidence: VoiceSpeechExecutionEvidence? = null
            var attemptStarted: Long? = null
            var attemptedInputBytes: Int? = null
            try {
                checkActive()
                var preparedAt = 0L
                var preparationMs = 0L
                engine = VoiceNativeEngineGate.exclusive {
                    checkActive()
                    preparedAt = nanoTime()
                    attemptStarted = preparedAt
                    create(result.requestedBackend).also {
                        preparationMs = elapsed(preparedAt)
                        currentEngine.set(it)
                    }
                }
                // Preparation has completed even if cancellation wins the next checkpoint.
                result = result.copy(sessionPrepareMs = preparationMs)
                attemptStarted = null
                checkActive()
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.RUNNING)
                update(index, result)
                for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                    val phrases = plans.getValue(fixture)
                    for (iteration in 0..report.configuration.mode.warmRepeats) {
                        checkActive()
                        var prepareToFirstPcmMs: Long? = null
                        result = result.copy(incompleteFixture = fixture, incompleteRepeat = iteration, incompleteChunks = emptyList())
                        for ((chunkIndex, phrase) in phrases.withIndex()) {
                            // A throwing generate may retain an earlier native receipt. Only this
                            // returned call's observation can identify its actual execution provider.
                            attemptEvidence = null
                            attemptStarted = null
                            attemptedInputBytes = phrase.toByteArray(Charsets.UTF_8).size
                            checkActive()
                            val before = thermalStatus()
                            if ((before ?: -1) >= 2) {
                                report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                                throw CancellationException("Thermal limit")
                            }
                            report = report.copy(activeFixture = fixture, activeRepeat = iteration, activeChunk = chunkIndex)
                            emit()
                            val ready = checkNotNull(engine)
                            val chunk = VoiceNativeEngineGate.exclusive {
                                checkActive()
                                ready.resetCancellation()
                                checkActive()
                                val started = nanoTime()
                                attemptStarted = started
                                val audio = ready.generate(phrase, report.configuration.voice.speakerId, report.configuration.speechRate)
                                val generationMs = elapsed(started)
                                if (chunkIndex == 0 && result.samples.isEmpty()) prepareToFirstPcmMs = elapsed(preparedAt)
                                checkActive()
                                val evidence = ready.executionEvidence
                                attemptEvidence = evidence
                                check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() } && audio.samples.any { it != 0f }) { "OUTPUT_INVALID" }
                                try {
                                    kokoroWordFrames(audio.durations, audio.wordTokens, audio.wordTokens.size / 2, audio.samples.size)
                                } catch (_: IllegalArgumentException) {
                                    error("OUTPUT_INVALID")
                                }
                                check(evidence?.verifiedAudio == true) { "OUTPUT_INVALID" }
                                val observed = checkNotNull(evidence)
                                val audioObservation = VoiceSpeechAudioObservation.measure(phrase, audio)
                                check(audioObservation.tokenDurations.size == observed.tokenCount) { "OUTPUT_INVALID" }
                                check(observed.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(result.requestedBackend)) { "CPU_ONLY" }
                                if (result.requestedBackend != VoiceSpeechBackend.CPU) {
                                    check(observed.profileAvailable == true) { "EXECUTION_EVIDENCE_MISSING" }
                                    check(observed.acceleratorKernels > 0) { "CPU_ONLY" }
                                }
                                VoiceSpeechBenchmarkChunk(
                                    index = chunkIndex,
                                    inputBytes = attemptedInputBytes,
                                    inputSha256 = VoiceSpeechWorkload.digest(phrase),
                                    generationMs = generationMs,
                                    audioMs = audio.samples.size * 1000.0 / SAMPLE_RATE,
                                    effectiveProvider = observed.effectiveProvider,
                                    acceleratorKernels = observed.acceleratorKernels,
                                    cpuKernels = observed.cpuKernels,
                                    acceleratorMicros = observed.acceleratorMicros,
                                    cpuMicros = observed.cpuMicros,
                                    graphPrepareMs = observed.prepareMs,
                                    tokenCount = observed.tokenCount,
                                    validatedAudio = observed.verifiedAudio,
                                    thermalBefore = before,
                                    thermalAfter = thermalStatus(),
                                    hardwareType = observed.hardwareType,
                                    profileAvailable = observed.profileAvailable,
                                    profileId = observed.profileId,
                                    profileReused = observed.profileReused,
                                    cacheHit = observed.cacheHit,
                                    currentPrepareMs = observed.currentPrepareMs,
                                    graphPreparedThisCall = observed.graphPreparedThisCall,
                                    cachedShapeCount = observed.cachedShapeCount,
                                    cacheLimit = observed.cacheLimit,
                                    cacheEstimatedBytes = observed.cacheEstimatedBytes,
                                    audioRms = audioObservation.rms,
                                    audioPeak = audioObservation.peak,
                                    clippedSamples = audioObservation.clippedSamples,
                                    nonSilentSamples = audioObservation.nonSilentSamples,
                                    discoverySummary = observed.discoverySummary,
                                    frontendMs = observed.frontendMs, validationMs = observed.validationMs, cacheEvictions = observed.cacheEvictions, profileFinalizeMs = observed.profileFinalizeMs,
                                    nativeSynthesisMs = observed.synthesisMs,
                                    audioObservation = audioObservation,
                                    cacheBudgetBytes = observed.cacheBudgetBytes,
                                    activeShapeEstimatedBytes = observed.activeShapeEstimatedBytes,
                                    availableMemoryBytes = observed.availableMemoryBytes,
                                    cacheAdmission = observed.cacheAdmission
                                ).also { attemptStarted = null }
                            }
                            result = result.copy(incompleteChunks = result.incompleteChunks + chunk)
                            update(index, result)
                        }
                        val chunks = result.incompleteChunks
                        val first = chunks.first()
                        val last = chunks.last()
                        val sample = VoiceSpeechBenchmarkSample(
                            fixture, iteration, chunks.sumOf { it.generationMs }, first.generationMs, prepareToFirstPcmMs,
                            chunks.sumOf { it.audioMs }, last.effectiveProvider,
                            last.acceleratorKernels, last.cpuKernels, last.acceleratorMicros, last.cpuMicros,
                            chunks.sumOf { it.currentPrepareMs ?: 0 }, chunks.sumOf { it.tokenCount }, chunks.all { it.validatedAudio },
                            first.thermalBefore, last.thermalAfter, last.hardwareType, chunks
                        )
                        result = result.copy(samples = result.samples + sample, incompleteFixture = null, incompleteRepeat = null, incompleteChunks = emptyList())
                        update(index, result)
                    }
                }
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.COMPLETE)
            } catch (_: CancellationException) {
                val interruptedMs = attemptStarted?.let(::elapsed)
                val interruptedGeneration = interruptedMs != null && attemptedInputBytes != null
                val nativeProgress = runCatching { engine?.executionProgress }.getOrNull().takeIf { interruptedGeneration }
                // Active UI fields can still name the previous completed chunk. Only a
                // live timed call may contribute interrupted-attempt context or duration.
                result = result.copy(
                    state = VoiceSpeechBenchmarkBackendState.CANCELLED,
                    sessionPrepareMs = result.sessionPrepareMs ?: interruptedMs.takeUnless { interruptedGeneration },
                    failedFixture = report.activeFixture.takeIf { interruptedGeneration },
                    failedRepeat = report.activeRepeat.takeIf { interruptedGeneration },
                    failedChunk = report.activeChunk.takeIf { interruptedGeneration },
                    failedInputBytes = attemptedInputBytes.takeIf { interruptedGeneration },
                    failedInputByteLimit = VoiceSpeechWorkload.MAX_PHRASE_BYTES.takeIf { interruptedGeneration },
                    failedAttemptMs = interruptedMs.takeIf { interruptedGeneration },
                    cancellationStage = cancellationRequest.get()?.stage.takeIf { interruptedMs != null },
                    cancellationNativeStage = nativeProgress?.stage ?: VoiceSpeechStage.PREPARING.takeIf { interruptedMs != null && !interruptedGeneration }
                )
            } catch (error: Exception) {
                val failure = speechBenchmarkFailure(error)
                if (failure.code.isInputFailure) report = report.copy(issue = VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD)
                val native = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).filterIsInstance<dev.melo.kokoro.TimedKokoro.Failure>().firstOrNull()
                val workload = error as? VoiceSpeechWorkloadFailure
                val observed = attemptEvidence ?: speechFailureExecutionEvidence(native ?: error, result.requestedBackend, report.configuration.deviceKey)
                result = result.copy(
                    state = failure.state,
                    failure = failure.code,
                    failureStage = failure.stage ?: when {
                        report.activeFixture == null -> VoiceSpeechStage.PREPARING
                        observed != null -> VoiceSpeechStage.VALIDATING
                        else -> null
                    },
                    nativeCode = failure.nativeCode,
                    failureEffectiveProvider = observed?.effectiveProvider?.takeIf { it in setOf("cpu", "qnn-gpu", "qnn-htp") },
                    failureAcceleratorKernels = observed?.takeIf { it.profileAvailable == true }?.acceleratorKernels,
                    failureCpuKernels = observed?.takeIf { it.profileAvailable == true }?.cpuKernels,
                    failedFixture = report.activeFixture,
                    failedRepeat = report.activeRepeat,
                    failedChunk = report.activeChunk,
                    failedInputBytes = native?.inputBytes?.takeIf { it >= 0 } ?: workload?.inputBytes ?: attemptedInputBytes,
                    failedInputByteLimit = native?.inputByteLimit?.takeIf { it > 0 } ?: workload?.inputByteLimit ?: VoiceSpeechWorkload.MAX_PHRASE_BYTES.takeIf { attemptedInputBytes != null },
                    failedTokenCount = native?.tokenCount?.takeIf { it >= 0 },
                    failedTokenLimit = native?.tokenLimit?.takeIf { it > 0 },
                    failedRequestedProvider = native?.provider ?: VoiceSpeechBenchmarkBackendResult.provider(result.requestedBackend),
                    failedDiscoverySummary = native?.discoverySummary,
                    failedAttemptMs = attemptStarted?.let(::elapsed),
                    failureObservation = observed?.let(VoiceSpeechBenchmarkFailureObservation::from)
                )
            } catch (_: LinkageError) {
                result = result.copy(
                    state = VoiceSpeechBenchmarkBackendState.UNSUPPORTED,
                    failure = VoiceSpeechFailureCode.PROVIDER_LOAD,
                    failureStage = VoiceSpeechStage.PREPARING,
                    failedFixture = report.activeFixture,
                    failedRepeat = report.activeRepeat,
                    failedChunk = report.activeChunk,
                    failedInputBytes = attemptedInputBytes,
                    failedAttemptMs = attemptStarted?.let(::elapsed)
                )
            } finally {
                currentEngine.compareAndSet(engine, null)
                val retired = VoiceNativeEngineGate.exclusive { runCatching { engine?.close() }.isSuccess }
                if (result.state == VoiceSpeechBenchmarkBackendState.CANCELLED && retired) {
                    result = result.copy(cancellationNativeRetirementMs = cancellationRequest.get()?.let { (nanoTime() - it.atNanos).coerceAtLeast(0) / 1_000_000 })
                }
                update(index, result)
            }
            if (result.state == VoiceSpeechBenchmarkBackendState.CANCELLED || report.issue == VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD) break
        }
        val finalState = when {
            cancelled.get() -> VoiceSpeechBenchmarkRunState.CANCELLED
            report.issue == VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD -> VoiceSpeechBenchmarkRunState.FAILED
            report.issue != null -> VoiceSpeechBenchmarkRunState.INTERRUPTED
            // Native cancellation and an interrupted worker also stop the run,
            // even when they did not originate from this runner's cancel button.
            report.results.any { it.state == VoiceSpeechBenchmarkBackendState.CANCELLED } -> VoiceSpeechBenchmarkRunState.CANCELLED
            else -> VoiceSpeechBenchmarkRunState.COMPLETE
        }
        report = report.copy(
            state = finalState,
            finishedAtMillis = wallTime(),
            issue = if (cancelled.get()) VoiceSpeechBenchmarkIssue.CANCELLED else report.issue,
            activeFixture = null,
            activeRepeat = null,
            activeChunk = null,
            results = report.results.map { if (it.state == VoiceSpeechBenchmarkBackendState.PENDING) it.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN) else it }
        )
        emit()
        return report
    }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Speech benchmark cancelled")
    }
    private fun elapsed(started: Long): Long = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000).coerceAtLeast(1)

    companion object {
        const val SAMPLE_RATE = 24_000
    }
}

internal data class VoiceSpeechBenchmarkFailure(
    val code: VoiceSpeechFailureCode,
    val state: VoiceSpeechBenchmarkBackendState,
    val stage: VoiceSpeechStage? = null,
    val nativeCode: Int? = null
)

internal fun speechBenchmarkFailure(error: Exception): VoiceSpeechBenchmarkFailure {
    val native = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).filterIsInstance<dev.melo.kokoro.TimedKokoro.Failure>().firstOrNull()
    val code = VoiceSpeechFailureCode.entries.firstOrNull { it.name == (native?.code ?: error.message) } ?: VoiceSpeechFailureCode.EXECUTION_FAILED
    return VoiceSpeechBenchmarkFailure(
        code,
        if (code in setOf(VoiceSpeechFailureCode.DEVICE_UNAVAILABLE, VoiceSpeechFailureCode.PROVIDER_LOAD, VoiceSpeechFailureCode.CPU_ONLY)) {
            VoiceSpeechBenchmarkBackendState.UNSUPPORTED
        } else {
            VoiceSpeechBenchmarkBackendState.FAILED
        },
        native?.stage?.uppercase()?.let { stage -> VoiceSpeechStage.entries.firstOrNull { it.name == stage } }
            ?: VoiceSpeechStage.FRONTEND.takeIf { error is VoiceSpeechWorkloadFailure || code.isInputFailure },
        native?.nativeCode
    )
}
