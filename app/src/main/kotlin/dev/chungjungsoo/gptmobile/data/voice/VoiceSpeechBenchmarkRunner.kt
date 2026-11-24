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
    private val plan: (VoiceSpeechBenchmarkFixture, VoiceSpeechBenchmarkWorkload) -> List<String> = VoiceSpeechWorkload::phrases,
    private val preparationOwner: () -> VoicePhraseEngineFactory? = { null },
    private val watchdogFactory: (VoiceSpeechBenchmarkDeadlines, () -> VoiceSpeechStage?, (VoiceSpeechBenchmarkTimeout) -> Unit) -> VoiceSpeechBenchmarkWatchdog = { limits, progress, stop ->
        VoiceSpeechBenchmarkWatchdog(limits, nanoTime, progress, stop)
    }
) {
    private val cancelled = AtomicBoolean(false)
    private val currentEngine = AtomicReference<VoicePhraseEngine?>(null)
    private data class CancellationRequest(val atNanos: Long, val stage: VoiceSpeechStage?)
    private val cancellationRequest = AtomicReference<CancellationRequest?>(null)
    private val currentWatchdog = AtomicReference<VoiceSpeechBenchmarkWatchdog?>(null)
    private val cleanupUnconfirmed = AtomicBoolean(false)
    private var unretiredPreparationOwner: VoicePhraseEngineFactory? = null
    val nativeCleanupPending: Boolean get() = cleanupUnconfirmed.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val engine = currentEngine.get()
        val stage = runCatching { engine?.executionProgress?.takeIf { it.active }?.stage }.getOrNull()
            ?: VoiceSpeechStage.PREPARING.takeIf { engine == null }
        cancellationRequest.compareAndSet(null, CancellationRequest(nanoTime(), stage))
        currentWatchdog.get()?.close()
        runCatching { cancelPreparation() }
        runCatching { currentEngine.get()?.cancel() }
    }

    fun run(initial: VoiceSpeechBenchmarkReport, publish: (VoiceSpeechBenchmarkReport) -> Unit): VoiceSpeechBenchmarkReport {
        require(initial.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO && !initial.configuration.playback)
        check(!nativeCleanupPending)
        var report = initial
        val publicationLock = Any()
        val published = AtomicReference(initial)
        var activeBackend: VoiceSpeechBackend? = null
        fun emit() = synchronized(publicationLock) {
            currentWatchdog.get()?.timeout?.let { timeout ->
                report = report.copy(
                    results = report.results.map { row ->
                        if (row.requestedBackend == activeBackend) row.copy(stopReason = row.stopReason ?: VoiceSpeechBenchmarkStopReason.DEADLINE, deadline = timeout, cleanupState = row.cleanupState ?: VoiceSpeechBenchmarkCleanupState.PENDING) else row
                    }
                )
            }
            published.set(report)
            publish(report)
        }
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
            val stopLock = Any()
            var backendActive = true
            activeBackend = backend
            val watchdog = watchdogFactory(
                report.configuration.deadlines ?: VoiceSpeechBenchmarkDeadlines.forMode(report.configuration.mode),
                { runCatching { currentEngine.get()?.executionProgress?.takeIf { it.active }?.stage }.getOrNull() }
            ) { timeout ->
                synchronized(stopLock) {
                    if (backendActive && !cancelled.get()) {
                        // Publish before the synchronous owner is allowed to retire. This
                        // snapshot reports the request; it does not claim that JNI stopped.
                        try {
                            synchronized(publicationLock) {
                                val snapshot = published.get().let { previous ->
                                    previous.copy(
                                        results = previous.results.map { row ->
                                            if (row.requestedBackend == backend) row.copy(stopReason = VoiceSpeechBenchmarkStopReason.DEADLINE, deadline = timeout, cleanupState = VoiceSpeechBenchmarkCleanupState.PENDING) else row
                                        }
                                    )
                                }
                                published.set(snapshot)
                                publish(snapshot)
                            }
                        } finally {
                            runCatching { cancelPreparation() }
                            runCatching { currentEngine.get()?.cancel() }
                        }
                    }
                }
            }
            currentWatchdog.set(watchdog)
            try {
                if (!watchdog.beginBackend()) throw CancellationException("Speech benchmark stopped before preparation")
                checkActive()
                var preparedAt = 0L
                var preparationMs = 0L
                engine = VoiceNativeEngineGate.exclusive {
                    checkActive()
                    preparedAt = nanoTime()
                    attemptStarted = preparedAt
                    if (!watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)) throw CancellationException("Speech benchmark preparation cancelled")
                    checkActive()
                    create(result.requestedBackend).also {
                        preparationMs = elapsed(preparedAt)
                        currentEngine.set(it)
                    }
                }
                // Preparation has completed even if cancellation wins the next checkpoint.
                result = result.copy(sessionPrepareMs = preparationMs)
                attemptStarted = null
                watchdog.endPhase()
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
                                if (!watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)) throw CancellationException("Speech benchmark synthesis cancelled")
                                checkActive()
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
                                ).also {
                                    watchdog.endPhase()
                                    checkActive()
                                    attemptStarted = null
                                }
                            }
                            result = result.copy(incompleteChunks = result.incompleteChunks + chunk)
                            update(index, result)
                            if (!report.configuration.deepDiagnostics && backend != VoiceSpeechBackend.CPU) {
                                val reference = report.results.singleOrNull { it.requestedBackend == VoiceSpeechBackend.CPU }
                                    ?.samples?.singleOrNull { it.fixture == fixture && it.repeat == iteration }
                                    ?.chunks?.singleOrNull { it.index == chunk.index }
                                if (VoiceSpeechAudioReference.compare(reference?.audioObservation, chunk.audioObservation).status == VoiceSpeechAudioReferenceStatus.MISMATCH) {
                                    result = result.copy(stopReason = VoiceSpeechBenchmarkStopReason.AUDIO_REFERENCE_MISMATCH)
                                    error("OUTPUT_INVALID")
                                }
                            }
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
                val deadlinePublicationFailure = synchronized(stopLock) {
                    val failure = runCatching { watchdog.checkpoint() }.exceptionOrNull()
                    watchdog.close()
                    backendActive = false
                    failure
                }
                val timeout = watchdog.timeout
                var retirementPublicationFailure = deadlinePublicationFailure
                if (timeout != null) {
                    result = result.copy(
                        state = if (cancelled.get()) VoiceSpeechBenchmarkBackendState.CANCELLED else VoiceSpeechBenchmarkBackendState.FAILED,
                        stopReason = VoiceSpeechBenchmarkStopReason.DEADLINE,
                        deadline = timeout,
                        cleanupState = VoiceSpeechBenchmarkCleanupState.PENDING
                    )
                    val failure = runCatching { update(index, result) }.exceptionOrNull()
                    if (retirementPublicationFailure == null) {
                        retirementPublicationFailure = failure
                    } else if (failure != null && failure !== retirementPublicationFailure) {
                        retirementPublicationFailure.addSuppressed(failure)
                    }
                }
                val preparationFailure = preparationOwner()?.takeIf { it.nativeCleanupPending }
                if (preparationFailure != null) unretiredPreparationOwner = preparationFailure
                val engineRetired = VoiceNativeEngineGate.exclusive { runCatching { engine?.close() }.isSuccess }
                // A failed preparation never returned an engine to this runner. Its
                // factory still owns the handle and must not be reported as retired.
                val retired = engineRetired && preparationFailure == null
                result = result.copy(cleanupState = if (retired) VoiceSpeechBenchmarkCleanupState.RETIRED else VoiceSpeechBenchmarkCleanupState.FAILED)
                if (retired) {
                    currentEngine.compareAndSet(engine, null)
                    result = result.copy(deadlineNativeRetirementMs = watchdog.requestedAtNanos?.let { (nanoTime() - it).coerceAtLeast(0) / 1_000_000 })
                } else {
                    cleanupUnconfirmed.set(true)
                    report = report.copy(issue = VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING)
                }
                if (result.state == VoiceSpeechBenchmarkBackendState.CANCELLED && retired) {
                    result = result.copy(cancellationNativeRetirementMs = cancellationRequest.get()?.let { (nanoTime() - it.atNanos).coerceAtLeast(0) / 1_000_000 })
                }
                currentWatchdog.compareAndSet(watchdog, null)
                try {
                    update(index, result)
                } catch (failure: Throwable) {
                    retirementPublicationFailure?.takeUnless { it === failure }?.let(failure::addSuppressed)
                    throw failure
                }
                retirementPublicationFailure?.let { throw it }
            }
            if (result.state == VoiceSpeechBenchmarkBackendState.CANCELLED || report.issue == VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD || nativeCleanupPending) break
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
            issue = when {
                nativeCleanupPending -> VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING
                cancelled.get() -> VoiceSpeechBenchmarkIssue.CANCELLED
                else -> report.issue
            },
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
        if (currentWatchdog.get()?.checkpoint() != null) throw CancellationException("Speech benchmark deadline requested cancellation")
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
