package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runs installed SDK engines on the fixed public fixtures. Native handle retirement stays owned. */
internal class VoiceSpeechEngineBenchmarkRunner(
    private val factory: (VoiceSpeechBackend) -> VoicePhraseEngineFactory,
    private val directory: File,
    private val thermalStatus: () -> Int? = { null },
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val onStopping: () -> Unit = {},
    private val scheduler: VoiceSpeechBenchmarkScheduler? = null,
    private val watchdogFactory: ((VoiceSpeechBenchmarkDeadlines, (VoiceSpeechBenchmarkTimeout) -> Unit) -> VoiceSpeechBenchmarkWatchdog)? = null
) {
    private val cancelled = AtomicBoolean()
    private val cancellationAt = AtomicLong(-1)
    private val currentFactory = AtomicReference<VoicePhraseEngineFactory?>(null)
    private val currentEngine = AtomicReference<VoicePhraseEngine?>(null)
    private val ownerLock = Any()
    private var currentOwner: Any? = null
    private var nativeCancellationRequested = false
    private var quarantinedEngine: VoicePhraseEngine? = null
    private var quarantinedFactory: VoicePhraseEngineFactory? = null

    @Volatile var nativeCleanupPending: Boolean = false
        private set

    fun cancel() {
        cancellationAt.compareAndSet(-1, nanoTime())
        cancelled.set(true)
        requestNativeCancellation()
    }

    private fun requestNativeCancellation(): Unit = synchronized(ownerLock) {
        if (currentOwner == null || nativeCancellationRequested) return@synchronized
        val engine = currentEngine.get()
        val factory = currentFactory.get()
        if (engine == null && factory == null) return@synchronized
        nativeCancellationRequested = true
        if (engine != null) runCatching { engine.cancel() } else runCatching { factory?.cancelPending() }
    }

    fun run(initial: VoiceSpeechBenchmarkReport, publish: (VoiceSpeechBenchmarkReport) -> Unit): VoiceSpeechBenchmarkReport {
        val configuration = initial.configuration
        check(!nativeCleanupPending) { "Native speech cleanup is unconfirmed; restart the app before another benchmark." }
        require(configuration.engine != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO && !configuration.playback)
        require(configuration.currentProtocol)
        var report = initial.copy(engineResults = configuration.backends.map(::VoiceSpeechEngineBenchmarkResult))
        val publicationLock = Any()
        val published = AtomicReference(report)
        val publicationFailure = AtomicReference<Throwable?>(null)
        fun publishSnapshot(snapshot: VoiceSpeechBenchmarkReport) {
            published.set(snapshot)
            try {
                publish(snapshot)
            } catch (failure: Throwable) {
                publicationFailure.compareAndSet(null, failure)
                throw failure
            }
        }
        fun update(index: Int, result: VoiceSpeechEngineBenchmarkResult) {
            synchronized(publicationLock) {
                // The timer publishes only immutable deadline evidence. It never reads the
                // owner's in-progress PCM accumulator or mutates the owner's local report.
                val last = published.get().engineResults[index]
                val merged = if (result.timeoutScope == null && last.timeoutScope != null) {
                    result.copy(
                        timeoutScope = last.timeoutScope,
                        timeoutLimitMs = last.timeoutLimitMs,
                        timeoutElapsedMs = last.timeoutElapsedMs,
                        cleanupState = if (result.cleanupState == "NOT_OBSERVED") last.cleanupState else result.cleanupState
                    )
                } else {
                    result
                }
                report = report.copy(engineResults = report.engineResults.toMutableList().also { it[index] = merged })
                publishSnapshot(report)
            }
        }
        synchronized(publicationLock) { publishSnapshot(report) }
        for (backend in configuration.plannedBackendOrder) {
            if (cancelled.get() || nativeCleanupPending) break
            val index = report.engineResults.indexOfFirst { it.requestedBackend == backend }
            val thermal = thermalStatus()
            if ((thermal ?: -1) >= 2) {
                report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                break
            }
            var result = report.engineResults[index].copy(state = VoiceSpeechBenchmarkBackendState.PREPARING, preparationThermalBefore = thermal)
            update(index, result)
            val timeout = AtomicReference<VoiceSpeechBenchmarkTimeout?>(null)
            val owner = Any()
            synchronized(ownerLock) {
                currentOwner = owner
                nativeCancellationRequested = false
            }
            var engine: VoicePhraseEngine? = null
            var preparedAt: Long? = null
            var attemptAt: Long? = null
            var measurement: VoiceSpeechEnginePcmMeasurement? = null
            val deadlines = configuration.deadlines ?: VoiceSpeechBenchmarkDeadlines.forMode(configuration.mode)
            val timeoutAction: (VoiceSpeechBenchmarkTimeout) -> Unit = { observed ->
                synchronized(publicationLock) {
                    val requested = synchronized(ownerLock) {
                        if (currentOwner === owner && !cancelled.get()) {
                            timeout.compareAndSet(null, observed)
                            true
                        } else {
                            false
                        }
                    }
                    if (requested) {
                        val snapshot = published.get().let { previous ->
                            previous.copy(
                                engineResults = previous.engineResults.map { row ->
                                    if (row.requestedBackend == backend) {
                                        row.copy(cleanupState = "PENDING", timeoutScope = observed.scope.name, timeoutLimitMs = observed.limitMs, timeoutElapsedMs = observed.elapsedMs)
                                    } else {
                                        row
                                    }
                                }
                            )
                        }
                        try {
                            runCatching { publishSnapshot(snapshot) }
                        } finally {
                            requestNativeCancellation()
                            runCatching(onStopping)
                        }
                    }
                }
            }
            val watchdog = watchdogFactory?.invoke(deadlines, timeoutAction) ?: if (scheduler == null) {
                VoiceSpeechBenchmarkWatchdog(deadlines, nanoTime, onTimeout = timeoutAction)
            } else {
                VoiceSpeechBenchmarkWatchdog(deadlines, nanoTime, onTimeout = timeoutAction, scheduler = scheduler)
            }
            fun retainTimeout(observed: VoiceSpeechBenchmarkTimeout?): VoiceSpeechBenchmarkTimeout? {
                // The watchdog records first and publishes afterward. Its callback can be
                // blocked while this owner reaches a checkpoint or starts retirement.
                if (!cancelled.get() && observed != null) timeout.compareAndSet(null, observed)
                return timeout.get()
            }
            fun checkActive() {
                if (cancelled.get()) {
                    requestNativeCancellation()
                    throw CancellationException("Speech benchmark cancelled")
                }
                retainTimeout(watchdog.checkpoint())
                if (cancelled.get() || timeout.get() != null) {
                    requestNativeCancellation()
                    throw CancellationException("Speech benchmark stopped")
                }
            }
            try {
                watchdog.beginBackend()
                checkActive()
                val selected = factory(backend)
                require(selected.selectedBackend == backend && !selected.requiresKokoroAssets) { "BENCHMARK_ENGINE_MISMATCH" }
                synchronized(ownerLock) { currentFactory.set(selected) }
                if (!selected.isReady()) throw MissingModel()
                checkActive()
                preparedAt = nanoTime()
                attemptAt = preparedAt
                watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)
                engine = VoiceNativeEngineGate.exclusive {
                    checkActive()
                    selected.create(directory, "", configuration.threads).also { created -> synchronized(ownerLock) { currentEngine.set(created) } }
                }
                result = result.copy(sessionPrepareMs = elapsed(preparedAt))
                attemptAt = null
                watchdog.endPhase()
                checkActive()
                require(engine.supportsStreaming) { "BENCHMARK_STREAMING_UNAVAILABLE" }
                val streamEngine = object : VoicePhraseEngine by checkNotNull(engine) {
                    // consumeVoiceStream also requests cancellation on rejected callbacks.
                    // Route that request through the same owner gate as UI and watchdog stops.
                    override fun cancel() = requestNativeCancellation()
                }
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.RUNNING)
                update(index, result)
                for ((fixture, repeat) in VoiceSpeechWorkload.schedule(configuration.workload, configuration.mode)) {
                    checkActive()
                    val before = thermalStatus()
                    if ((before ?: -1) >= 2) throw ThermalLimit()
                    // The fixed workload's entire spoken text is retained in both modes.
                    // The prepared factory's input mode also governs production playback.
                    val text = VoiceSpeechWorkload.phrases(fixture, configuration.workload).joinToString(" ")
                    report = report.copy(activeFixture = fixture, activeRepeat = repeat, activeChunk = null)
                    result = result.copy(failedFixture = fixture, failedRepeat = repeat, partialSample = null)
                    update(index, result)
                    attemptAt = nanoTime()
                    val sampleMeasurement = VoiceSpeechEnginePcmMeasurement(fixture, repeat, text, before, preparedAt.takeIf { result.samples.isEmpty() }, nanoTime)
                    measurement = sampleMeasurement
                    val plan = VoiceStreamingPhrasePlan(text, VoicePronunciationDictionary.EMPTY, configuration.speechRate, englishFallback = !result.requestedBackend.isLiteRtKokoro)
                    if (streamEngine.supportsIncrementalInput) {
                        sampleMeasurement.beginStream(text, incrementalInput = true)
                        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
                        VoiceNativeEngineGate.exclusive {
                            checkActive()
                            engine.resetCancellation()
                            checkActive()
                            consumeVoiceInputStream(streamEngine, {
                                checkActive()
                                plan.next(VoicePerformanceMode.BALANCED.followUpWords)?.text
                            }, configuration.voice.speakerId, configuration.speechRate, { !cancelled.get() && timeout.get() == null }) { pcm ->
                                checkActive()
                                sampleMeasurement.accept(pcm)
                                // An incremental reply has one stream but many PCM chunks.
                                // Retain the no-progress deadline and the total backend cap.
                                if (pcm.samples.isNotEmpty()) watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
                                true
                            }
                        }
                        watchdog.endPhase()
                        checkActive()
                        sampleMeasurement.finishStream()
                    }
                    var call = 0
                    while (!streamEngine.supportsIncrementalInput) {
                        val phrase = plan.next(VoicePerformanceMode.BALANCED.followUpWords) ?: break
                        checkActive()
                        require(call++ < VoiceSpeechWorkload.MAX_CHUNKS) { "BENCHMARK_STREAM_LIMIT" }
                        sampleMeasurement.beginStream(phrase.text)
                        watchdog.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK)
                        VoiceNativeEngineGate.exclusive {
                            checkActive()
                            engine.resetCancellation()
                            checkActive()
                            consumeVoiceStream(streamEngine, phrase.text, configuration.voice.speakerId, configuration.speechRate, { !cancelled.get() && timeout.get() == null }) { pcm ->
                                checkActive()
                                sampleMeasurement.accept(pcm)
                                true
                            }
                        }
                        watchdog.endPhase()
                        checkActive()
                        sampleMeasurement.finishStream()
                    }
                    val sample = sampleMeasurement.snapshot(complete = true, thermalAfter = thermalStatus())
                    result = result.copy(samples = result.samples + sample, partialSample = null, failedFixture = null, failedRepeat = null)
                    measurement = null
                    attemptAt = null
                    update(index, result)
                }
                checkActive()
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.COMPLETE)
            } catch (failure: Exception) {
                val deadline = retainTimeout(watchdog.timeout)
                val code = when {
                    cancelled.get() -> "CANCELLED"
                    deadline != null -> "DEADLINE"
                    failure is MissingModel -> "MODEL_UNAVAILABLE"
                    failure is ThermalLimit -> "THERMAL_LIMIT"
                    failure is IllegalArgumentException || failure is IllegalStateException -> "OUTPUT_INVALID"
                    else -> "EXECUTION_FAILED"
                }
                val interrupted = measurement?.snapshot(complete = false, thermalAfter = thermalStatus())
                result = result.copy(
                    state = when {
                        cancelled.get() -> VoiceSpeechBenchmarkBackendState.CANCELLED
                        failure is MissingModel -> VoiceSpeechBenchmarkBackendState.UNSUPPORTED
                        else -> VoiceSpeechBenchmarkBackendState.FAILED
                    },
                    partialSample = interrupted,
                    failureCode = code,
                    failedAttemptMs = attemptAt?.let(::elapsed),
                    cancellationMs = cancellationAt.get().takeIf { it >= 0 }?.let(::elapsed),
                    timeoutScope = deadline?.scope?.name,
                    timeoutLimitMs = deadline?.limitMs,
                    timeoutElapsedMs = deadline?.elapsedMs
                )
                if (failure is ThermalLimit) report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
            } catch (_: LinkageError) {
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.UNSUPPORTED, failureCode = "RUNTIME_UNAVAILABLE", failedAttemptMs = attemptAt?.let(::elapsed), partialSample = measurement?.snapshot(complete = false, thermalAfter = thermalStatus()))
            } finally {
                watchdog.close()
                // Closing fences new timer records. Consume the last recorded deadline
                // before clearing this owner, even when its notification is still delayed.
                val deadline = retainTimeout(watchdog.timeout)
                if (deadline != null) requestNativeCancellation()
                val retiringFactory = synchronized(publicationLock) {
                    synchronized(ownerLock) {
                        currentOwner = null
                        currentEngine.set(null)
                        currentFactory.getAndSet(null)
                    }
                }
                if (cancelled.get() || deadline != null) {
                    result = result.copy(
                        state = if (cancelled.get()) VoiceSpeechBenchmarkBackendState.CANCELLED else VoiceSpeechBenchmarkBackendState.FAILED,
                        failureCode = if (cancelled.get()) "CANCELLED" else "DEADLINE",
                        cancellationMs = cancellationAt.get().takeIf { it >= 0 }?.let(::elapsed),
                        timeoutScope = deadline?.scope?.name,
                        timeoutLimitMs = deadline?.limitMs,
                        timeoutElapsedMs = deadline?.elapsedMs
                    )
                }
                // Destruction runs only on the synchronous owner after create/stream returns.
                // A failed close retains the object and its model lease until process restart.
                if (engine != null) {
                    result = result.copy(cleanupState = "PENDING")
                    // A UI/store observer is not allowed to bypass native destruction or
                    // the failed-close quarantine. Rethrow it only after retirement.
                    runCatching { update(index, result) }
                    val retiringAt = nanoTime()
                    val retired = runCatching { VoiceNativeEngineGate.exclusive { engine.close() } }.isSuccess
                    nativeCleanupPending = !retired
                    if (!retired) quarantinedEngine = engine
                    result = result.copy(
                        cleanupState = if (retired) "RETIRED" else "FAILED",
                        nativeRetirementMs = elapsed(retiringAt),
                        state = if (retired) result.state else VoiceSpeechBenchmarkBackendState.FAILED,
                        failureCode = if (retired) result.failureCode else "NATIVE_RETIREMENT_FAILED"
                    )
                    if (!retired) report = report.copy(issue = VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING)
                } else {
                    result = result.copy(cleanupState = "RETIRED")
                }
                if (retiringFactory?.nativeCleanupPending == true) {
                    quarantinedFactory = retiringFactory
                    nativeCleanupPending = true
                    result = result.copy(cleanupState = "FAILED", state = VoiceSpeechBenchmarkBackendState.FAILED, failureCode = "NATIVE_RETIREMENT_FAILED")
                    report = report.copy(issue = VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING)
                }
                runCatching { update(index, result) }
            }
            publicationFailure.get()?.let { throw it }
            if (report.issue != null || cancelled.get()) break
        }
        report = report.copy(
            state = when {
                cancelled.get() -> VoiceSpeechBenchmarkRunState.CANCELLED
                report.issue != null -> VoiceSpeechBenchmarkRunState.INTERRUPTED
                else -> VoiceSpeechBenchmarkRunState.COMPLETE
            },
            issue = report.issue ?: VoiceSpeechBenchmarkIssue.CANCELLED.takeIf { cancelled.get() },
            engineResults = report.engineResults.map { if (it.state == VoiceSpeechBenchmarkBackendState.PENDING) it.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN) else it },
            activeFixture = null,
            activeRepeat = null,
            activeChunk = null,
            finishedAtMillis = wallTime()
        )
        synchronized(publicationLock) { publishSnapshot(report) }
        return report
    }

    private fun elapsed(start: Long) = (nanoTime() - start).coerceAtLeast(0) / 1_000_000
    private class MissingModel : IllegalStateException()
    private class ThermalLimit : IllegalStateException()
}
